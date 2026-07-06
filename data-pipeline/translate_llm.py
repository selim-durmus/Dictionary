"""
Per-sense English→Turkish translation via Claude (Anthropic Message Batches API).

The OPUS-MT pass (translate_gap.py) gave every translatable headword *one* Turkish word,
context-free — so homographs collapse (``bank → banka`` only) and parts of speech drift. Claude
reads each sense's English gloss, so it can translate **per sense** (``bank → banka`` *and*
``kıyı``) at dictionary quality. This step translates every English sense that lacks a real
Wiktionary translation and writes en→tr rows tagged ``source='llm'`` that outrank the OPUS
``mt`` rows in the app (see EntryDao tierExpr). OPUS rows stay as a lower fallback tier.

Input is the **full** en→en sense set streamed from the English Kaikki dump (``parse_english``),
not the DB — the OPUS run was pruned, so the per-sense glosses for OPUS-covered words only exist
in the dump.

Cost: Message Batches API is 50% off. Haiku 4.5 is $1/$5 per MTok → ~$0.50/$2.50 batched.
Run the pilot first to confirm the per-sense token cost before the full run.

Prerequisite: ``ANTHROPIC_API_KEY`` set in the environment with billing enabled. (Claude Code's
own auth does not flow into this standalone script.)

Usage:
    export ANTHROPIC_API_KEY=sk-ant-...
    python -m translate_llm --limit 300 --sample 40    # pilot: quality + cost
    python -m translate_llm --prune                    # full run (resumable)
    python -m translate_llm --prune-only               # just drop superseded en→en glosses

    --senses-per-request N   senses batched into one API request (default 25)
    --max-senses N           cap senses translated per headword (default: all)
    --model ID               default claude-haiku-4-5
    --db PATH                dictionary.db to augment (default build/dictionary.db)

A run persists its batch id(s) + per-request manifest to build/llm_batch_state.json so polling
and result collection resume across restarts (a batch can take up to 24h; most finish < 1h).
Delete that file to start a fresh submission.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import time
from pathlib import Path

from tqdm import tqdm

from sources import fetch, parse
from translate_gap import ensure_source_column, finalize_db

HERE = Path(__file__).parent
CACHE = HERE / "cache"
BUILD = HERE / "build"
DEFAULT_DB = BUILD / "dictionary.db"
STATE_FILE = BUILD / "llm_batch_state.json"

MODEL = "claude-haiku-4-5"

SYSTEM_PROMPT = (
    "You are a bilingual English→Turkish lexicographer building a dictionary. "
    "For each English dictionary sense you are given — a headword, its part of speech, and its "
    "English definition — return the single best Turkish translation of the HEADWORD *in that "
    "specific sense*, in dictionary/lemma form (e.g. infinitive for verbs, nominative singular "
    "for nouns). Use the definition and part of speech to disambiguate homographs: 'bank' as a "
    "financial institution is 'banka', but 'bank' as the side of a river is 'kıyı'. "
    "Return the translation only — no article, no explanation, no English. "
    "If a sense genuinely has no Turkish equivalent (a proper noun, a foreign term used as-is, or "
    "an untranslatable grammatical particle), return null for its \"tr\". "
    "Return exactly one item per input, echoing each input's integer \"i\"."
)

# Structured-output schema: forces a JSON object the collector can parse deterministically.
OUTPUT_SCHEMA = {
    "type": "json_schema",
    "schema": {
        "type": "object",
        "properties": {
            "items": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "i": {"type": "integer"},
                        "tr": {"type": ["string", "null"]},
                    },
                    "required": ["i", "tr"],
                    "additionalProperties": False,
                },
            }
        },
        "required": ["items"],
        "additionalProperties": False,
    },
}

INSERT = (
    "INSERT OR IGNORE INTO entries "
    "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
    " sense_order, source) "
    "VALUES (?, 'en', ?, 'tr', ?, ?, ?, ?, 'llm')"
)


def done_senses(conn: sqlite3.Connection) -> set[tuple[str, int]]:
    """(source_word, sense_order) pairs that already have an llm row, so reruns resume."""
    return {
        (w, o)
        for w, o in conn.execute(
            "SELECT source_word, sense_order FROM entries WHERE source = 'llm' AND source_lang = 'en'"
        )
    }


def iter_target_senses(en_path: Path, done: set[tuple[str, int]], max_senses: int | None):
    """Stream untranslated English senses: parse_english emits an en→en row for every sense that
    lacks a real Turkish translation. Yields dicts with the gloss + metadata each needs."""
    per_word: dict[str, int] = {}
    for row in parse.parse_english(en_path):
        if row.target_lang != "en":  # skip senses that already have a real Wiktionary en→tr
            continue
        if (row.source_word, row.sense_order) in done:
            continue
        if max_senses is not None:
            n = per_word.get(row.source_word, 0)
            if n >= max_senses:
                continue
            per_word[row.source_word] = n + 1
        yield {
            "word": row.source_word,
            "pos": row.pos,
            "category": row.category,
            "sense_order": row.sense_order,
            "gloss": row.definition or row.target_word,
        }


def build_request(custom_id: str, items: list[dict], model: str) -> dict:
    lines = []
    for i, it in enumerate(items):
        pos = f"[{it['pos']}] " if it["pos"] else ""
        lines.append(f"{i}. {pos}{it['word']} — {it['gloss']}")
    user = "Translate each sense to Turkish.\n\nItems:\n" + "\n".join(lines)
    return {
        "custom_id": custom_id,
        "params": {
            "model": model,
            "max_tokens": 2048,
            "system": [
                {"type": "text", "text": SYSTEM_PROMPT, "cache_control": {"type": "ephemeral"}}
            ],
            "output_config": {"format": OUTPUT_SCHEMA},
            "messages": [{"role": "user", "content": user}],
        },
    }


def chunked(seq, n):
    for i in range(0, len(seq), n):
        yield seq[i:i + n]


def submit(client, targets: list[dict], per_request: int, model: str) -> dict:
    """Build + submit batch requests; write a manifest mapping custom_id → items. One batch holds
    up to 100K requests, so this is almost always a single batch."""
    manifest: dict[str, list[dict]] = {}
    requests = []
    for ci, items in enumerate(chunked(targets, per_request)):
        cid = f"c{ci}"
        manifest[cid] = items
        requests.append(build_request(cid, items, model))

    print(f"Submitting {len(requests):,} requests ({len(targets):,} senses) to the Batches API…")
    # The API caps a batch at 100K requests; split if we ever exceed that.
    batch_ids = []
    for group in chunked(requests, 100_000):
        batch = client.messages.batches.create(requests=group)
        batch_ids.append(batch.id)
        print(f"  batch {batch.id} ({len(group):,} requests, status {batch.processing_status})")

    state = {"model": model, "batch_ids": batch_ids, "manifest": manifest}
    STATE_FILE.write_text(json.dumps(state))
    return state


def poll(client, batch_ids: list[str], interval: int = 30) -> None:
    pending = set(batch_ids)
    while pending:
        for bid in list(pending):
            b = client.messages.batches.retrieve(bid)
            if b.processing_status == "ended":
                pending.discard(bid)
                print(f"  batch {bid} ended: {b.request_counts}")
        if pending:
            time.sleep(interval)


def collect(client, conn: sqlite3.Connection, state: dict, sample: int) -> tuple[int, int]:
    manifest: dict[str, list[dict]] = state["manifest"]
    kept = dropped = 0
    samples_left = sample
    for bid in state["batch_ids"]:
        for result in client.messages.batches.results(bid):
            if result.result.type != "succeeded":
                continue
            items = manifest.get(result.custom_id)
            if not items:
                continue
            msg = result.result.message
            text = next((b.text for b in msg.content if b.type == "text"), "")
            try:
                parsed = json.loads(text).get("items", [])
            except (json.JSONDecodeError, AttributeError):
                continue
            rows = []
            for entry in parsed:
                idx = entry.get("i")
                tr = entry.get("tr")
                if not isinstance(idx, int) or not (0 <= idx < len(items)):
                    continue
                it = items[idx]
                if samples_left > 0:
                    print(f"  · [{it['pos'] or '-'}] {it['word']!r} — "
                          f"{it['gloss'][:45]!r} ⇒ {tr!r}")
                    samples_left -= 1
                if not tr or not str(tr).strip() or str(tr).strip().lower() == it["word"].lower():
                    dropped += 1
                    continue
                rows.append((it["word"], str(tr).strip(), it["pos"], it["category"],
                             it["gloss"], it["sense_order"]))
            if rows:
                conn.executemany(INSERT, rows)
                conn.commit()
                kept += len(rows)
    return kept, dropped


def prune_superseded(conn: sqlite3.Connection) -> int:
    """Delete en→en gloss rows whose word now has an llm (en→tr) row — they're redundant for
    display and the llm row already carries the English gloss in its definition."""
    cur = conn.execute(
        """
        DELETE FROM entries
        WHERE source_lang = 'en' AND target_lang = 'en' AND source = 'wiktionary'
          AND EXISTS (
              SELECT 1 FROM entries l
              WHERE l.source = 'llm' AND l.source_lang = 'en' AND l.source_word = entries.source_word
          )
        """
    )
    conn.commit()
    return cur.rowcount


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--limit", type=int, default=None, help="Cap senses processed (pilot).")
    ap.add_argument("--senses-per-request", type=int, default=25)
    ap.add_argument("--max-senses", type=int, default=None, help="Cap senses per headword.")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--sample", type=int, default=0, help="Print this many (word → tr) examples.")
    ap.add_argument("--poll-interval", type=int, default=30)
    ap.add_argument("--prune", action="store_true",
                    help="After collecting, drop en→en rows superseded by an llm row.")
    ap.add_argument("--prune-only", action="store_true")
    args = ap.parse_args()

    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

    conn = sqlite3.connect(args.db)
    conn.execute("PRAGMA journal_mode = WAL")
    ensure_source_column(conn)

    if args.prune_only:
        deleted = prune_superseded(conn)
        print(f"Pruned {deleted:,} redundant en→en rows. Rebuilding FTS + VACUUM…")
        finalize_db(conn)
        conn.close()
        return

    import anthropic  # lazy: only needed for an actual run, and surfaces a clear error if missing

    client = anthropic.Anthropic()

    # Resume an in-flight submission if one exists; otherwise build the target set and submit.
    if STATE_FILE.exists():
        state = json.loads(STATE_FILE.read_text())
        print(f"Resuming {len(state['batch_ids'])} batch(es) from {STATE_FILE.name}.")
    else:
        en_path = fetch.fetch_english(CACHE)
        done = done_senses(conn)
        print(f"Scanning English dump for untranslated senses ({len(done):,} already done)…")
        targets = []
        for sense in tqdm(iter_target_senses(en_path, done, args.max_senses), unit="sense"):
            targets.append(sense)
            if args.limit is not None and len(targets) >= args.limit:
                break
        if not targets:
            print("Nothing to translate — all senses already have llm rows.")
            if args.prune:
                prune_superseded(conn)
                finalize_db(conn)
            conn.close()
            return
        state = submit(client, targets, args.senses_per_request, args.model)

    print("Polling for batch completion…")
    poll(client, state["batch_ids"], args.poll_interval)

    print("Collecting results…")
    kept, dropped = collect(client, conn, state, args.sample)
    print(f"Inserted {kept:,} llm rows, dropped {dropped:,} (null / empty / echoed).")

    if args.prune:
        deleted = prune_superseded(conn)
        print(f"Pruned {deleted:,} redundant en→en rows.")

    total_llm = conn.execute("SELECT COUNT(*) FROM entries WHERE source = 'llm'").fetchone()[0]
    print(f"Rebuilding FTS index + VACUUM ({total_llm:,} llm rows present)…")
    finalize_db(conn)
    conn.close()

    # Completed cleanly — drop the resume state so the next run starts fresh.
    STATE_FILE.unlink(missing_ok=True)
    print("Done.")


if __name__ == "__main__":
    main()
