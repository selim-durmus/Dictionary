"""
Fill the en→en gloss gap with OPUS-MT machine translations.

Wiktionary ships a Turkish translation for only ~3% of English senses; the other ~1.67M
senses land in the DB as en→en "gloss" fallback rows (target_lang == source_lang). This
step reads those rows, translates each **per sense** through OPUS-MT (Helsinki-NLP), and
writes en→tr rows tagged ``source='mt'`` *alongside* the originals. Wiktionary rows keep
outranking MT rows in the app (see EntryDao ranking); the en→en glosses remain as a final
fallback under the MT rows.

Per-sense translation (rather than once per headword) keeps homographs like ``bank`` /
``spring`` distinct. Each input is a **carrier string** ``"<headword>: <gloss>"`` — bare
lemmas are the documented worst case for NMT hallucination, and a short carrier sentence
recovers most of the quality.

Usage:
    python -m translate_gap --limit 200 --sample 30        # quick pilot, print samples
    python -m translate_gap --limit 10000                  # 10K pilot for a wall-clock estimate
    python -m translate_gap                                # full run (hours; resumable)

    --no-back-translate   skip the round-trip confidence filter (faster, keeps everything)
    --threshold 0.4       chrF round-trip cutoff (0..1); rows below are dropped
    --db PATH             dictionary.db to read+augment (default: build/dictionary.db)

The forward (en→tr) and, unless disabled, reverse (tr→en) models are converted to
CTranslate2 int8 on first run under ``models/`` (one-time, ~250 MB each, needs torch).
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


def carrier(headword: str, gloss: str) -> str:
    """Carrier string fed to the translator: headword in context of its (short) gloss."""
    gloss = (gloss or "").strip().rstrip("…").strip()
    return f"{headword}: {gloss}" if gloss else headword


def extract_headword(translated: str) -> str:
    """Pull the translated headword back out of a translated carrier ``"<head>: <gloss>"``."""
    head = translated.split(":", 1)[0] if ":" in translated else translated
    # First clause only, stripped of surrounding punctuation/quotes.
    head = head.split(";", 1)[0].split(",", 1)[0]
    return head.strip().strip(".\"'“”() ").strip()


def fetch_rows(conn: sqlite3.Connection, limit: int | None) -> list[tuple]:
    """en→en gloss rows that don't yet have an MT counterpart (so reruns resume)."""
    sql = """
        SELECT e.id, e.source_word, e.target_word, e.pos, e.category, e.definition, e.sense_order
        FROM entries e
        WHERE e.source_lang = 'en' AND e.target_lang = 'en' AND e.source = 'wiktionary'
          AND NOT EXISTS (
              SELECT 1 FROM entries m
              WHERE m.source = 'mt' AND m.source_lang = 'en'
                AND m.source_word = e.source_word AND m.sense_order = e.sense_order
          )
        ORDER BY LENGTH(e.source_word), e.source_word, e.sense_order
    """
    if limit is not None:
        sql += f" LIMIT {int(limit)}"
    return conn.execute(sql).fetchall()


def chrf_scorer():
    from sacrebleu.metrics import CHRF

    metric = CHRF()
    return lambda hyp, ref: metric.sentence_score(hyp, [ref]).score / 100.0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--limit", type=int, default=None, help="Cap rows processed (pilot).")
    ap.add_argument("--batch-size", type=int, default=64)
    ap.add_argument("--beam", type=int, default=4)
    ap.add_argument("--no-back-translate", dest="back_translate", action="store_false")
    ap.add_argument("--threshold", type=float, default=0.4,
                    help="chrF round-trip cutoff in [0,1]; rows below are dropped.")
    ap.add_argument("--sample", type=int, default=0,
                    help="Print this many (carrier → output → headword) examples and continue.")
    ap.add_argument("--device", default="cpu")
    ap.add_argument("--compute-type", default="int8")
    args = ap.parse_args()

    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

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
    rows = fetch_rows(read_conn, args.limit)
    read_conn.close()
    if not rows:
        print("Nothing to translate — all en→en rows already have MT counterparts.")
        return
    print(f"Translating {len(rows):,} en→en gloss rows "
          f"(back-translation {'ON' if reverse else 'OFF'}, threshold {args.threshold}).")

    write_conn = sqlite3.connect(args.db)
    write_conn.execute("PRAGMA journal_mode = WAL")
    ensure_source_column(write_conn)
    start_id = write_conn.execute("SELECT COALESCE(MAX(id), 0) FROM entries").fetchone()[0]

    insert = (
        "INSERT OR IGNORE INTO entries "
        "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
        " sense_order, source) "
        "VALUES (?, 'en', ?, 'tr', ?, ?, ?, ?, 'mt')"
    )

    kept = dropped = samples_left = 0
    samples_left = args.sample

    with tqdm(total=len(rows), unit="row", unit_scale=True, desc="translate") as bar:
        for i in range(0, len(rows), args.batch_size):
            chunk = rows[i:i + args.batch_size]
            carriers = [carrier(r[1], r[2]) for r in chunk]
            outputs = forward.translate(carriers, max_batch_size=args.batch_size)
            heads = [extract_headword(o) for o in outputs]

            confidences: list[float | None] = [None] * len(chunk)
            if reverse is not None:
                # Round-trip only the non-empty headwords; map results back by index.
                idxs = [j for j, h in enumerate(heads) if h]
                if idxs:
                    back = reverse.translate([heads[j] for j in idxs],
                                             max_batch_size=args.batch_size)
                    for j, en_again in zip(idxs, back):
                        confidences[j] = score(en_again, chunk[j][1])

            batch_inserts = []
            for j, row in enumerate(chunk):
                _id, head_en, gloss, pos, category, definition, sense_order = row
                tr_head = heads[j]
                conf = confidences[j]
                if samples_left > 0:
                    print(f"  · {head_en!r} → {outputs[j]!r}  ⇒ {tr_head!r}"
                          + (f"  (chrf {conf:.2f})" if conf is not None else ""))
                    samples_left -= 1
                if not tr_head or tr_head.lower() == head_en.lower():
                    dropped += 1
                    continue
                if reverse is not None and (conf is None or conf < args.threshold):
                    dropped += 1
                    continue
                batch_inserts.append(
                    (head_en, tr_head, pos, category, definition or gloss, sense_order)
                )

            if batch_inserts:
                write_conn.executemany(insert, batch_inserts)
                write_conn.commit()
                kept += len(batch_inserts)
            bar.update(len(chunk))

    new_count = write_conn.execute(
        "SELECT COUNT(*) FROM entries WHERE id > ? AND source = 'mt'", (start_id,)
    ).fetchone()[0]
    if new_count:
        print(f"Indexing {new_count:,} new MT rows into FTS…")
        write_conn.execute(
            "INSERT INTO entries_fts(rowid, source_word) "
            "SELECT id, source_word FROM entries WHERE id > ? AND source = 'mt'",
            (start_id,),
        )
        write_conn.execute("INSERT INTO entries_fts(entries_fts) VALUES('optimize')")
        write_conn.commit()

    write_conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    write_conn.execute("PRAGMA journal_mode = DELETE")
    write_conn.commit()
    write_conn.close()
    print(f"\nDone. kept {kept:,} MT rows, dropped {dropped:,} "
          f"(low confidence / empty / unchanged).")


if __name__ == "__main__":
    main()
