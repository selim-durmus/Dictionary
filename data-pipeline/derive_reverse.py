"""
Derive extra real-dictionary pairs by reading the existing Wiktionary rows backwards.

  * en→tr translation tables, flipped, give tr→en:  book → kitap   ⇒  kitap → book
    (adds ~18K Turkish headwords that had no English translation)
  * short tr→en glosses, flipped, give en→tr:       kitap: "book"  ⇒  book → kitap
    (adds ~11K English headwords that had no Turkish translation)

Rows are tagged ``source='wiktionary-rev'`` and rank with the dictionary tier in the app, just
below direct Wiktionary rows (see EntryDao.tierExpr). Only short, clean targets are flipped —
a gloss like "A building for a family to reside in" is a definition, not an equivalent, so it is
skipped; "house, home, abode" yields three pairs. Inflection-note and proper-name senses are
never flipped.

Idempotent: deletes previous ``wiktionary-rev`` rows first. Rebuilds FTS + VACUUMs at the end.

Usage:
    python -m derive_reverse
"""
from __future__ import annotations

import argparse
import re
import sqlite3
from pathlib import Path

from sources.parse import INFLECTION
from sources.text import strip_parens
from translate_gap import ensure_source_column, finalize_db

HERE = Path(__file__).parent
DEFAULT_DB = HERE / "build" / "dictionary.db"
SOURCE = "wiktionary-rev"

INSERT = (
    "INSERT OR IGNORE INTO entries "
    "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
    " sense_order, source) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
)

MAX_WORDS = 3
# Glosses that describe rather than translate.
NOT_AN_EQUIVALENT = re.compile(
    r"\b(given name|surname|letter of the|name of the|abbreviation|initialism|acronym|"
    r"used to|used in|used as|indicates?|expresses|suffix|prefix|particle)\b",
    re.I,
)
EN_ARTICLE = re.compile(r"^(to|a|an|the) ", re.I)
LETTERS = re.compile(r"^[^\W\d_]+(?:[ '\-][^\W\d_]+)*$")


def equivalents(gloss: str) -> list[str]:
    """Short comma/semicolon-separated equivalents in a gloss; [] if it reads as a definition."""
    if NOT_AN_EQUIVALENT.search(gloss):
        return []
    out = []
    for part in re.split(r"[;,]", strip_parens(gloss)):
        p = part.strip().rstrip(".!").strip()
        if not p or len(p.split()) > MAX_WORDS or not LETTERS.match(p):
            continue
        out.append(p)
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    conn.execute("PRAGMA journal_mode = WAL")
    ensure_source_column(conn)
    conn.execute("DELETE FROM entries WHERE source = ?", (SOURCE,))

    batch: list[tuple] = []

    # en→tr tables ⇒ tr→en. The English sense description (definition) travels along.
    tr_en = 0
    for en, tr, pos, cat, definition, order in conn.execute(
        "SELECT source_word, target_word, pos, category, definition, sense_order FROM entries "
        "WHERE source = 'wiktionary' AND source_lang = 'en' AND target_lang = 'tr' "
        "AND (pos IS NULL OR pos != 'name')"
    ).fetchall():
        tr_word = strip_parens(tr)
        if not tr_word or len(tr_word.split()) > MAX_WORDS or not LETTERS.match(tr_word):
            continue
        if tr_word.lower() == en.lower():
            continue
        batch.append((tr_word, "tr", en, "en", pos, cat, definition, order, SOURCE))
        tr_en += 1

    # tr→en short glosses ⇒ en→tr. The full English gloss becomes the definition.
    en_tr = 0
    for tr, gloss, pos, cat, order in conn.execute(
        "SELECT source_word, target_word, pos, category, sense_order FROM entries "
        "WHERE source = 'wiktionary' AND source_lang = 'tr' AND target_lang = 'en' "
        "AND category != ? AND (pos IS NULL OR pos != 'name')",
        (INFLECTION,),
    ).fetchall():
        for i, eq in enumerate(equivalents(gloss)):
            en = EN_ARTICLE.sub("", eq).strip()
            if not en or en.lower() == tr.lower():
                continue
            batch.append((en, "en", tr, "tr", pos, cat, gloss if gloss != eq else None,
                          order * 10 + i, SOURCE))
            en_tr += 1

    conn.executemany(INSERT, batch)
    conn.commit()
    kept = conn.execute("SELECT source_lang, COUNT(*), COUNT(DISTINCT source_word) FROM entries "
                        "WHERE source = ? GROUP BY source_lang", (SOURCE,)).fetchall()
    print(f"candidates: {tr_en:,} tr→en, {en_tr:,} en→tr")
    for lang, n, words in kept:
        print(f"  kept {n:,} {lang}-source rows over {words:,} headwords")
    print("Rebuilding FTS + VACUUM…")
    finalize_db(conn)
    conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
