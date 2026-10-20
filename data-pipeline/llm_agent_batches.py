"""
Per-sense Claude translations without an API key: export chunks for Claude Code subagents
(Haiku) to translate, then load their output into the DB as ``source='llm'``.

translate_llm.py does the same job through the paid Message Batches API. This variant runs on a
Claude subscription instead: Claude Code reads ``build/llm_agent/<name>.jsonl`` chunks, spawns one
Haiku subagent per chunk with PROMPT_EN / PROMPT_TR (below), and each agent writes
``<name>.out.jsonl``. ``ingest`` then loads every finished pair.

Targeting (the random-sense pilot showed most untranslated senses of common words are archaic):
only frequent words whose best translation in the app is PanLex, OPUS-MT or nothing but an English
definition (per coverage_report), first ``--senses`` senses each. Inflected forms are replaced by
their lemma. Pilot cost on a Pro plan: 2,000 senses ≈ +7% of the 5-hour limit, ≈ +1% weekly.

Usage:
    python -m llm_agent_batches export --top 20000          # writes build/llm_agent/en_NNN.jsonl
    python -m llm_agent_batches export-tdk --limit 2000     # TDK tr→en chunks (tdk_NNN.jsonl)
    python -m llm_agent_batches ingest                      # loads all *.out.jsonl, rebuilds FTS
    python -m llm_agent_batches prompt en_001               # print the agent prompt for a chunk
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path

HERE = Path(__file__).parent
DEFAULT_DB = HERE / "build" / "dictionary.db"
WORK = HERE / "build" / "llm_agent"
CHUNK = 500  # senses per chunk file; override with --chunk
GAP_STATUSES = {"panlex", "mt", "definition_only", "lemma:panlex", "lemma:mt"}

PROMPT_EN = """You are an English→Turkish lexicographer filling gaps in a personal offline dictionary.

Input file (JSON Lines): {inp}
Each line: {{"i": <int>, "word": <English headword>, "pos": <part of speech>, "gloss": <English definition of ONE sense>}}

Task: for every line, give the best Turkish translation of the HEADWORD in THAT sense (use the gloss and pos to pick the meaning — e.g. "bank" as a river side is "kıyı", as a financial institution "banka"). Use dictionary/lemma form (infinitive -mak/-mek for verbs, nominative singular for nouns). Idioms and phrasal verbs get their idiomatic Turkish equivalent, never a word-for-word rendering. Add up to 3 alternative Turkish equivalents when they genuinely exist (synonyms a Turkish dictionary like Tureng would list), else an empty list. If the sense truly has no Turkish equivalent (proper noun used as-is, pure grammatical particle, untranslatable abbreviation), use null for "tr".

Output: write {out} — exactly one JSON object per input line, same order, UTF-8, real Turkish characters (ç ğ ı ö ş ü İ), no markdown:
{{"i": <same i>, "tr": "<turkish or null>", "alts": ["...", "..."]}}

Work through the whole file; do not skip lines and do not write any script — translate them yourself. Read the input in pieces if needed and write the output with the Write tool (you may write it in a few appended parts if it is long, but the final file must contain every input line). Other agents work in parallel, so name any temporary part files after this chunk (e.g. {name}_part1.jsonl), keep them in your own scratchpad folder (never next to the input file), and delete them when done. When done, reply only with the number of lines written."""

PROMPT_TR = """You are a Turkish→English lexicographer filling gaps in a personal offline dictionary.

Input file (JSON Lines): {inp}
Each line: {{"i": <int>, "word": <Turkish headword>, "pos": <Turkish part of speech>, "tanim": <Turkish definition of ONE sense, from the TDK dictionary>}}

Task: for every line, give the best English translation of the HEADWORD in THAT sense (use the Turkish definition and pos to pick the meaning). Use English dictionary form ("to ..." for verbs). Set phrases and idioms get their natural English equivalent, never a word-for-word rendering. Add up to 3 alternative English equivalents when they genuinely exist, else an empty list. If there is truly no English equivalent, give a short English explanation in "en" instead.

Output: write {out} — exactly one JSON object per input line, same order, UTF-8, no markdown:
{{"i": <same i>, "en": "<english>", "alts": ["...", "..."]}}

Work through the whole file; do not skip lines and do not write any script — translate them yourself. Read the input in pieces if needed and write the output with the Write tool (you may write it in a few appended parts if it is long, but the final file must contain every input line). Other agents work in parallel, so name any temporary part files after this chunk (e.g. {name}_part1.jsonl), keep them in your own scratchpad folder (never next to the input file), and delete them when done. When done, reply only with the number of lines written."""

INSERT = (
    "INSERT OR IGNORE INTO entries "
    "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
    " sense_order, source) VALUES (?, ?, ?, ?, ?, 'General', ?, ?, 'llm')"
)


def write_chunks(prefix: str, rows: list[dict]) -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    start = len(list(WORK.glob(f"{prefix}_*.jsonl"))) - len(list(WORK.glob(f"{prefix}_*.out.jsonl")))
    for n in range(0, len(rows), CHUNK):
        path = WORK / f"{prefix}_{start + n // CHUNK + 1:03d}.jsonl"
        with open(path, "w", encoding="utf-8") as f:
            for r in rows[n:n + CHUNK]:
                f.write(json.dumps(r, ensure_ascii=False) + "\n")
        print(f"  {path.name}: {len(rows[n:n + CHUNK])} senses")


def done_keys(conn: sqlite3.Connection) -> set[tuple]:
    """Senses already translated (llm rows in the DB) or already exported to a chunk file,
    so repeated exports only pick up new work."""
    done = {(w, l, o) for w, l, o in conn.execute(
        "SELECT source_word, source_lang, sense_order FROM entries WHERE source = 'llm'")}
    for path in WORK.glob("*.jsonl"):
        if path.name.endswith(".out.jsonl") or "_part" in path.name:
            continue
        lang = "tr" if path.name.startswith("tdk") else "en"
        for line in open(path, encoding="utf-8"):
            r = json.loads(line)
            done.add((r["word"], lang, int(r.get("o", 0)) * 10))
    return done


def export_en(conn: sqlite3.Connection, top: int, senses: int) -> None:
    import coverage_report as cr

    lk = cr.Lookup(conn)
    words = cr.freq_words("en")[:top]
    targets: list[str] = []
    for w in words:
        r = lk.resolve(w, "en")
        if r["status"] in GAP_STATUSES:
            targets.append(r["via"] or w)
    targets = list(dict.fromkeys(targets))
    done = done_keys(conn)
    rows, i = [], 0
    for w in targets:
        variants = list(dict.fromkeys([w, w.capitalize(), w.upper()]))
        q = ("SELECT source_word, pos, COALESCE(definition, target_word), sense_order FROM entries "
             f"WHERE source_word IN ({','.join('?' * len(variants))}) AND source_lang = 'en' "
             "AND target_lang = 'en' AND category != 'Inflection' "
             "AND (pos IS NULL OR pos != 'name') ORDER BY source_word = ? DESC, sense_order")
        picked = 0
        for sw, pos, gloss, order in conn.execute(q, (*variants, w)):
            if picked >= senses:
                break
            if (sw, "en", order * 10) in done:
                continue
            rows.append({"i": i, "word": sw, "pos": pos, "gloss": gloss[:300], "o": order})
            i += 1
            picked += 1
    print(f"{len(targets):,} gap words in the top {top:,} → {len(rows):,} senses")
    write_chunks("en", rows)


def export_tdk(conn: sqlite3.Connection, limit: int) -> None:
    import coverage_report as cr

    rank = {w: i for i, w in enumerate(cr.freq_words("tr"))}
    rows = conn.execute(
        "SELECT source_word, pos, COALESCE(definition, target_word), sense_order FROM entries t "
        "WHERE source = 'tdk' AND (pos IS NULL OR pos NOT LIKE '%eskimiş%') "
        "AND NOT EXISTS (SELECT 1 FROM entries x WHERE x.source_word = t.source_word "
        "AND x.source_lang = 'tr' AND x.target_lang = 'en' AND x.category != 'Inflection')"
    ).fetchall()
    done = done_keys(conn)
    rows = [r for r in rows if (r[0], "tr", r[3] * 10) not in done]
    rows.sort(key=lambda r: (rank.get(r[0], 10**9), r[0], r[3]))
    out = [{"i": i, "word": w, "pos": p, "tanim": d[:300], "o": o}
           for i, (w, p, d, o) in enumerate(rows[:limit])]
    print(f"{len(rows):,} TDK senses without English → exporting {len(out):,}")
    write_chunks("tdk", out)


def ingest(conn: sqlite3.Connection) -> None:
    from translate_gap import ensure_source_column, finalize_db

    ensure_source_column(conn)
    total = 0
    for out_path in sorted(p for p in WORK.glob("*.out.jsonl") if "_part" not in p.name) + sorted(
        (HERE / "build" / "llm_pilot").glob("*.out.jsonl")
    ):
        inp_path = out_path.with_name(out_path.name.replace(".out.jsonl", ".jsonl"))
        if not inp_path.exists():
            continue
        src = {}
        for line in open(inp_path, encoding="utf-8"):
            r = json.loads(line)
            src[r["i"]] = r
        to_en = "tanim" in next(iter(src.values()))
        batch = []
        bad = 0
        for line in open(out_path, encoding="utf-8"):
            try:
                o = json.loads(line)
            except json.JSONDecodeError:
                bad += 1
                continue
            r = src.get(o.get("i"))
            if r is None:
                bad += 1
                continue
            main = o.get("en" if to_en else "tr")
            alts = [a for a in (o.get("alts") or []) if isinstance(a, str)]
            cands = [c.strip() for c in ([main] if isinstance(main, str) else []) + alts]
            order = int(r.get("o", 0)) * 10
            gloss = r.get("tanim") if to_en else r.get("gloss")
            for k, t in enumerate(dict.fromkeys(c for c in cands if c)):
                # Skip echoes and agent placeholders ("[unknown plant]", "?", "N/A").
                if t.lower() == r["word"].lower() or t.startswith(("[", "(?", "?")) \
                        or t.lower() in {"n/a", "null", "none", "unknown"} \
                        or "unverified" in t.lower():
                    continue
                batch.append((r["word"], "tr" if to_en else "en", t, "en" if to_en else "tr",
                              r.get("pos"), gloss, order + k))
        conn.executemany(INSERT, batch)
        conn.commit()
        total += len(batch)
        print(f"  {out_path.parent.name}/{out_path.name}: {len(batch)} rows"
              + (f" ({bad} unparseable lines)" if bad else ""))
    print(f"inserted {total:,} llm rows; rebuilding FTS + VACUUM…")
    finalize_db(conn)


def main() -> None:
    global CHUNK
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["export", "export-tdk", "ingest", "prompt"])
    ap.add_argument("name", nargs="?")
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--top", type=int, default=20000)
    ap.add_argument("--senses", type=int, default=2)
    ap.add_argument("--limit", type=int, default=2000)
    ap.add_argument("--chunk", type=int, default=CHUNK, help="Senses per chunk file.")
    args = ap.parse_args()

    if args.cmd == "prompt":
        inp = WORK / f"{args.name}.jsonl"
        tmpl = PROMPT_TR if args.name.startswith("tdk") else PROMPT_EN
        print(tmpl.format(inp=inp.resolve(), out=inp.with_name(f"{args.name}.out.jsonl").resolve(),
                          name=args.name))
        return
    CHUNK = args.chunk
    conn = sqlite3.connect(args.db)
    if args.cmd == "export":
        export_en(conn, args.top, args.senses)
    elif args.cmd == "export-tdk":
        export_tdk(conn, args.limit)
    else:
        ingest(conn)
    conn.close()


if __name__ == "__main__":
    sys.exit(main())
