"""
Fill the en→en gloss gap with OPUS-MT machine translations.

Wiktionary ships a Turkish translation for only ~3% of English senses; the other ~1.67M
senses land in the DB as en→en "gloss" fallback rows (target_lang == source_lang). This step
translates the distinct English headwords behind those rows through OPUS-MT (Helsinki-NLP) and
writes en→tr rows tagged ``source='mt'`` *alongside* the originals. Wiktionary rows keep
outranking MT rows in the app (see EntryDao ranking); the en→en glosses remain as a final
fallback under the MT rows.

Design, after piloting several approaches:
  * **Headword-only input.** A "carrier" sentence (``"<word>: <gloss>"``) was piloted on the
    theory that bare lemmas hallucinate — but the model translates the headword essentially
    context-independently, so the carrier gave *no* keep-rate gain, often worse quality
    (singularised plurals, left rarer words in English), and was far slower (sequence length
    dominates CPU decode time). So we feed the bare headword.
  * **Distinct words, not per sense.** Since the gloss is irrelevant to the output, all senses
    of a word yield the same Turkish word; we translate each distinct headword once.
  * **Echo-drop only.** The dominant failure is echoed English (``serendipity → Serendipity``)
    when the model can't translate a word; dropping outputs equal to the source catches it. A
    tr→en back-translation chrF filter was piloted and **rejected** — on isolated headwords it
    is noise (chrF ≈ 0 for correct translations), drops most good output, and doubles runtime;
    the model's own sequence score doesn't separate good from echoed either. Off by default
    (``--back-translate`` forces it on). MT rows rank below real Wiktionary rows with the en→en
    gloss as final fallback, so a wrong MT row degrades gracefully.
  * **Threads.** CTranslate2 ``inter_threads`` (parallel batches) is the main CPU lever; the
    default of 1 left most cores idle. See OpusTranslator.

Greedy (beam 1) + headword-only + threading runs ~50-80 words/s int8 on an 8-core CPU.

Usage:
    python -m translate_gap --limit 2000 --sample 40       # quick pilot, print samples
    python -m translate_gap                                # full run (resumable)

    --beam N              beam size (default 1 / greedy)
    --back-translate      enable the (unreliable) tr→en chrF round-trip filter
    --db PATH             dictionary.db to read+augment (default: build/dictionary.db)

The forward (en→tr) and, with --back-translate, reverse (tr→en) models are converted to
CTranslate2 int8 on first run under ``models/`` (one-time, ~230 MB each, needs torch).
"""
from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

from tqdm import tqdm

from sources import translate

HERE = Path(__file__).parent
DEFAULT_DB = HERE / "build" / "dictionary.db"
MODELS = HERE / "models"


def column_exists(conn: sqlite3.Connection, table: str, column: str) -> bool:
    return any(row[1] == column for row in conn.execute(f"PRAGMA table_info({table})"))


def ensure_source_column(conn: sqlite3.Connection) -> None:
    """The ``source`` tier column ships in fresh builds (db.py). Add it to a pre-existing DB
    so this step can run against the current asset without a full multi-GB reparse."""
    if not column_exists(conn, "entries", "source"):
        conn.execute(
            "ALTER TABLE entries ADD COLUMN source TEXT NOT NULL DEFAULT 'wiktionary'"
        )
        conn.commit()


def clean_output(translated: str) -> str:
    """Tidy a translated headword: first clause only, surrounding punctuation/quotes stripped.

    Inputs are bare headwords (no carrier — see module docstring), so the model usually returns
    a bare Turkish word; this just trims the occasional trailing clause or punctuation."""
    head = translated.split(";", 1)[0].split(",", 1)[0]
    return head.strip().strip(".\"'“”():").strip()


def fetch_words(conn: sqlite3.Connection, limit: int | None) -> list[tuple]:
    """Distinct en→en headwords lacking any MT row yet (so reruns resume).

    One row per *word*, not per sense: the model translates a headword independently of its
    gloss, so all senses yield the same Turkish word (which the UNIQUE constraint would collapse
    anyway). Translating distinct words once cuts ~1.67M sense-rows to ~1.36M words. We keep a
    representative pos/category/gloss per word for the stored row's metadata."""
    sql = """
        SELECT e.source_word, e.pos, e.category, e.definition, e.target_word
        FROM entries e
        WHERE e.source_lang = 'en' AND e.target_lang = 'en' AND e.source = 'wiktionary'
          -- Skip headwords with no Latin letter (bare punctuation / numerals like '%', '0', '@').
          AND e.source_word GLOB '*[A-Za-z]*'
          AND NOT EXISTS (
              SELECT 1 FROM entries m
              WHERE m.source = 'mt' AND m.source_lang = 'en' AND m.source_word = e.source_word
          )
        GROUP BY e.source_word
        ORDER BY LENGTH(e.source_word)
    """
    if limit is not None:
        sql += f" LIMIT {int(limit)}"
    return conn.execute(sql).fetchall()


def chrf_scorer():
    from sacrebleu.metrics import CHRF

    metric = CHRF()
    return lambda hyp, ref: metric.sentence_score(hyp, [ref]).score / 100.0


def prune_redundant_glosses(conn: sqlite3.Connection) -> int:
    """Delete en→en gloss rows for headwords that now have an MT (en→tr) row.

    Those words gained a Turkish translation, and each MT row already carries an English gloss in
    its ``definition``, so the separate en→en sense-rows are redundant for display. Removing them
    is the bulk of the DB-size win. Words the model couldn't translate keep all their en→en rows.
    Caller is responsible for the FTS rebuild + VACUUM afterwards.
    """
    cur = conn.execute(
        """
        DELETE FROM entries
        WHERE source_lang = 'en' AND target_lang = 'en' AND source = 'wiktionary'
          AND EXISTS (
              SELECT 1 FROM entries m
              WHERE m.source = 'mt' AND m.source_lang = 'en' AND m.source_word = entries.source_word
          )
        """
    )
    conn.commit()
    return cur.rowcount


def finalize_db(conn: sqlite3.Connection) -> None:
    """Rebuild the FTS index from the content table and compact the file. Resume-safe + idempotent."""
    conn.execute("INSERT INTO entries_fts(entries_fts) VALUES('rebuild')")
    conn.execute("INSERT INTO entries_fts(entries_fts) VALUES('optimize')")
    conn.commit()
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.execute("PRAGMA journal_mode = DELETE")
    conn.commit()
    conn.execute("VACUUM")


def run_prune_only(db_path: Path) -> None:
    conn = sqlite3.connect(db_path)
    try:
        ensure_source_column(conn)
        before = conn.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
        deleted = prune_redundant_glosses(conn)
        print(f"Pruned {deleted:,} redundant en→en rows. Rebuilding FTS + VACUUM…")
        finalize_db(conn)
        after = conn.execute("SELECT COUNT(*) FROM entries").fetchone()[0]
        print(f"Rows {before:,} → {after:,}.")
    finally:
        conn.close()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--limit", type=int, default=None, help="Cap rows processed (pilot).")
    ap.add_argument("--batch-size", type=int, default=64)
    ap.add_argument("--beam", type=int, default=1,
                    help="Beam size. Default 1 (greedy) — ~2-3x faster than beam 4 with only a "
                         "minor quality cost on this best-effort MT tier. Raise for more quality.")
    ap.add_argument("--back-translate", dest="back_translate", action="store_true",
                    help="Enable the tr→en chrF round-trip filter. Off by default — piloting "
                         "showed it is noise on isolated headwords and drops good rows.")
    ap.add_argument("--threshold", type=float, default=0.4,
                    help="chrF round-trip cutoff in [0,1] (only with --back-translate).")
    ap.add_argument("--sample", type=int, default=0,
                    help="Print this many (word → translation) examples and continue.")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--compute-type", default="int8")
    ap.add_argument("--prune", action="store_true",
                    help="After translating, drop en→en rows whose word now has an MT row (#12).")
    ap.add_argument("--prune-only", action="store_true",
                    help="Skip translation; just prune redundant en→en rows, rebuild FTS, VACUUM.")
    args = ap.parse_args()

    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

    if args.prune_only:
        run_prune_only(args.db)
        return

    fwd_dir = translate.ensure_ct2_model(translate.EN_TR_MODEL, MODELS / "opus-en-tr-ct2",
                                         quantization=args.compute_type)
    forward = translate.OpusTranslator(fwd_dir, translate.EN_TR_MODEL,
                                       device=args.device, compute_type=args.compute_type,
                                       beam_size=args.beam)
    reverse = None
    score = None
    if args.back_translate:
        rev_dir = translate.ensure_ct2_model(translate.TR_EN_MODEL, MODELS / "opus-tr-en-ct2",
                                             quantization=args.compute_type)
        reverse = translate.OpusTranslator(rev_dir, translate.TR_EN_MODEL,
                                           device=args.device, compute_type=args.compute_type,
                                           beam_size=args.beam)
        score = chrf_scorer()

    read_conn = sqlite3.connect(args.db)
    # The `source` tier column ships in fresh builds but is absent from a pre-MT DB; add it
    # before the first read so fetch_words (which filters on it) can run.
    ensure_source_column(read_conn)
    words = fetch_words(read_conn, args.limit)
    read_conn.close()
    if not words:
        print("Nothing to translate — all en→en headwords already have MT rows.")
        if args.prune:
            run_prune_only(args.db)
        return
    print(f"Translating {len(words):,} distinct en→en headwords "
          f"(beam {args.beam}, back-translation {'ON' if reverse else 'OFF'}).")

    write_conn = sqlite3.connect(args.db)
    write_conn.execute("PRAGMA journal_mode = WAL")
    ensure_source_column(write_conn)

    insert = (
        "INSERT OR IGNORE INTO entries "
        "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
        " sense_order, source) "
        "VALUES (?, 'en', ?, 'tr', ?, ?, ?, 0, 'mt')"
    )

    kept = dropped = 0
    samples_left = args.sample

    with tqdm(total=len(words), unit="word", unit_scale=True, desc="translate") as bar:
        for i in range(0, len(words), args.batch_size):
            chunk = words[i:i + args.batch_size]
            # Headword-only — the carrier gloss gave no keep-rate gain and hurt speed/quality.
            outputs = forward.translate([w[0] for w in chunk], max_batch_size=args.batch_size)
            heads = [clean_output(o) for o in outputs]

            confidences: list[float | None] = [None] * len(chunk)
            if reverse is not None:
                idxs = [j for j, h in enumerate(heads) if h]
                if idxs:
                    back = reverse.translate([heads[j] for j in idxs],
                                             max_batch_size=args.batch_size)
                    for j, en_again in zip(idxs, back):
                        confidences[j] = score(en_again, chunk[j][0])

            batch_inserts = []
            for j, (word, pos, category, definition, gloss) in enumerate(chunk):
                tr_head = heads[j]
                conf = confidences[j]
                if samples_left > 0:
                    print(f"  · {word!r} → {tr_head!r}"
                          + (f"  (chrf {conf:.2f})" if conf is not None else ""))
                    samples_left -= 1
                # Drop the echoed-English case (model returned the word ~unchanged).
                if not tr_head or tr_head.lower() == word.lower():
                    dropped += 1
                    continue
                if reverse is not None and (conf is None or conf < args.threshold):
                    dropped += 1
                    continue
                batch_inserts.append((word, tr_head, pos, category, definition or gloss))

            if batch_inserts:
                write_conn.executemany(insert, batch_inserts)
                write_conn.commit()
                kept += len(batch_inserts)
            bar.update(len(chunk))

    if args.prune:
        deleted = prune_redundant_glosses(write_conn)
        print(f"Pruned {deleted:,} redundant en→en rows (#12).")

    # Rebuild the whole FTS index from the content table (resume-safe — indexes every MT row
    # regardless of which run inserted it), checkpoint, and VACUUM to reclaim free pages.
    total_mt = write_conn.execute(
        "SELECT COUNT(*) FROM entries WHERE source = 'mt'"
    ).fetchone()[0]
    print(f"Rebuilding FTS index + VACUUM ({total_mt:,} MT rows present)…")
    finalize_db(write_conn)
    write_conn.close()
    print(f"\nDone. kept {kept:,} MT rows, dropped {dropped:,} "
          f"(low confidence / empty / unchanged).")


if __name__ == "__main__":
    main()
