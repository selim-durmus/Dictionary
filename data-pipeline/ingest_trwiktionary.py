"""
Add translations from the Turkish-language Wiktionary edition (tr.wiktionary.org, via Kaikki).

The main build reads only the English edition. The Turkish edition is a different, independent
dictionary written by Turkish editors, and two of its parts fill our gaps directly:

  * **English words defined in Turkish** (~22K lemmas): ``propagate: "üremek; yavrulamak;
    çoğalmak; yaymak; iletmek"``, ``lease: "kira; kira kontratı"``, ``commute: "iş yolculuğu"``.
    → en→tr rows. A ``;``-separated list of short equivalents becomes one row each; a longer
    definitional gloss becomes one row whose target is its first clause (full text in
    ``definition``).
  * **Turkish words' English translation tables** (~15K words): ``ev → house, home``.
    → tr→en rows.

Rows are tagged ``source='trwiktionary'`` and rank with the dictionary tier in the app (see
EntryDao.tierExpr). Same license as the rest of the Wiktionary data (CC BY-SA 4.0 / GFDL).
Proper names, inflection pointers ("çoğul çekimi: tabulate") and abbreviations are skipped.

Idempotent: deletes previous ``trwiktionary`` rows first. Rebuilds FTS + VACUUMs at the end.

Usage:
    python -m ingest_trwiktionary
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import sqlite3
from pathlib import Path

from tqdm import tqdm

from sources import fetch
from sources.categorize import categorize
from sources.parse import _collect_tags, _first_sentence, is_form_of
from translate_gap import ensure_source_column, finalize_db

HERE = Path(__file__).parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"
SOURCE = "trwiktionary"

INSERT = (
    "INSERT OR IGNORE INTO entries "
    "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
    " sense_order, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
)

SKIP_POS = {"name", "abbrev", "character", "symbol", "letter"}
# Glosses that point at another word or describe a name rather than translate.
NOT_A_MEANING = re.compile(
    r"(çekimi|çoğulu|\bsoyadı\b|\berkek adı\b|\bkadın adı\b|\bkız adı\b|yazımı|kısaltması|"
    r"sözcüğünün|fiilinin|\bkipi\b|ortacı|bakınız|^bkz\b)",
    re.I,
)
SHORT_EQ_WORDS = 4


def en_rows(entry: dict):
    word = entry["word"].strip()
    pos = entry.get("pos")
    for order, sense in enumerate(entry.get("senses") or []):
        if is_form_of(sense) or "no-gloss" in (sense.get("tags") or []):
            continue
        category = categorize(_collect_tags(sense))
        for gloss in sense.get("glosses") or []:
            g = (gloss or "").strip()
            if not g or NOT_A_MEANING.search(g):
                continue
            parts = [p.strip().rstrip(".").strip() for p in g.split(";")]
            parts = [p for p in parts if p]
            if len(parts) > 1 and all(len(p.split()) <= SHORT_EQ_WORDS for p in parts):
                for i, p in enumerate(parts):
                    yield (word, "en", p, "tr", pos, category, None, order * 10 + i, SOURCE)
            else:
                head = _first_sentence(parts[0] if parts else g)
                yield (word, "en", head, "tr", pos, category, g if g != head else None,
                       order * 10, SOURCE)


def tr_rows(entry: dict):
    word = entry["word"].strip()
    pos = entry.get("pos")
    trs = list(entry.get("translations") or [])
    for sense in entry.get("senses") or []:
        trs += sense.get("translations") or []
    seen = set()
    for i, t in enumerate(trs):
        if (t.get("lang_code") or t.get("code")) != "en":
            continue
        en = (t.get("word") or "").strip()
        if not en or en.lower() == word.lower() or en in seen:
            continue
        seen.add(en)
        sense = t.get("sense")
        yield (word, "tr", en, "en", pos, "General",
               sense if isinstance(sense, str) and sense != word else None, i, SOURCE)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    args = ap.parse_args()

    path = fetch.fetch_trwiktionary(CACHE)
    conn = sqlite3.connect(args.db)
    conn.execute("PRAGMA journal_mode = WAL")
    ensure_source_column(conn)
    conn.execute("DELETE FROM entries WHERE source = ?", (SOURCE,))

    batch: list[tuple] = []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in tqdm(f, unit=" entries", desc="trwiktionary"):
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            word, lc = entry.get("word"), entry.get("lang_code")
            if not isinstance(word, str) or not word.strip() or entry.get("pos") in SKIP_POS:
                continue
            if lc == "en":
                batch.extend(en_rows(entry))
            elif lc == "tr":
                batch.extend(tr_rows(entry))
            if len(batch) >= 20000:
                conn.executemany(INSERT, batch)
                conn.commit()
                batch.clear()
    if batch:
        conn.executemany(INSERT, batch)
        conn.commit()

    for lang, n, words in conn.execute(
        "SELECT source_lang, COUNT(*), COUNT(DISTINCT source_word) FROM entries "
        "WHERE source = ? GROUP BY source_lang", (SOURCE,)
    ):
        print(f"  {lang}→: {n:,} rows over {words:,} headwords")
    print("Rebuilding FTS + VACUUM…")
    finalize_db(conn)
    conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
