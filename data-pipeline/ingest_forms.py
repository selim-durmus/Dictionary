"""
Build the inflected-form → lemma table the app falls back to when a typed word has no meaning
of its own.

Turkish is agglutinative: people type ``gidiyorum``, ``evlerimizden``, ``kitabı`` — forms that are
either missing from the DB or present only as a grammar note ("definite accusative singular of
kitap"). Wiktionary already knows how these map to their lemma; this step stores that mapping so
the app can show ``kitabı → kitap → book``. English inflections (``went → go``, ``mice → mouse``)
get the same treatment.

Sources (all already downloaded for the main build):
  * Kaikki English-edition dump, Turkish entries — inflection tables (``forms``) + form-of senses
  * Kaikki English-edition dump, English entries — form-of senses + ``forms`` (went, gone, goes)
  * Kaikki Turkish-edition dump (tr.wiktionary) — Turkish inflection tables + form-of senses

Table (WITHOUT ROWID, so the primary key is the only storage):

    forms(form TEXT, lang TEXT, lemma TEXT, explicit INTEGER, PRIMARY KEY(form, lang, lemma))

``form`` is folded with sources.text.fold (Turkish-aware lowercase) so the app can look it up with
the lowercased query. ``lemma`` is the exact headword spelling used in ``entries``. ``explicit`` is 1
when a Wiktionary sense says "X is a form of Y" and 0 when the link only comes from Y's inflection
table — the app prefers explicit links (``bana`` is the dative of ``ben``, even though ``ban``'s
table also produces ``bana``).

Only lemmas that have a cross-language translation are kept (the app ignores the rest). Run
after every other step, since later sources add translatable lemmas; augments in place.

Usage:
    python -m ingest_forms
    python -m ingest_forms --no-english     # Turkish only (skips the 3 GB English scan)
"""
from __future__ import annotations

import argparse
import gzip
import json
import sqlite3
from collections.abc import Iterator
from pathlib import Path

from tqdm import tqdm

from sources import fetch
from sources.parse import INFLECTION
from sources.text import fold

HERE = Path(__file__).parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"

SCHEMA = """
DROP TABLE IF EXISTS forms;
CREATE TABLE forms (
    form     TEXT    NOT NULL,
    lang     TEXT    NOT NULL,
    lemma    TEXT    NOT NULL,
    explicit INTEGER NOT NULL,
    PRIMARY KEY (form, lang, lemma)
) WITHOUT ROWID;
"""

UPSERT = (
    "INSERT INTO forms (form, lang, lemma, explicit) VALUES (?, ?, ?, ?) "
    "ON CONFLICT(form, lang, lemma) DO UPDATE SET explicit = MAX(explicit, excluded.explicit)"
)

# Inflection-table rows that are headers/metadata rather than word forms.
SKIP_FORM_TAGS = {"table-tags", "inflection-template", "class", "romanization", "canonical",
                  "auxiliary", "error-unknown-tag"}


def iter_jsonl(path: Path, desc: str) -> Iterator[dict]:
    opener = gzip.open if path.suffix == ".gz" else open
    total = path.stat().st_size
    with opener(path, "rb") as f, tqdm(total=total, unit="B", unit_scale=True,
                                       unit_divisor=1024, desc=desc) as bar:
        last = 0
        for line in f:
            if opener is open:
                bar.update(len(line))
            else:
                pos = f.fileobj.tell()  # compressed offset
                bar.update(pos - last)
                last = pos
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def links(entry: dict) -> Iterator[tuple[str, str, int]]:
    """(form, lemma, explicit) pairs contributed by one dump entry."""
    word = entry.get("word")
    if not isinstance(word, str) or not word.strip():
        return
    word = word.strip()
    for sense in entry.get("senses") or []:
        for ref in (sense.get("form_of") or []) + (sense.get("alt_of") or []):
            lemma = ref.get("word") if isinstance(ref, dict) else None
            if isinstance(lemma, str) and lemma.strip():
                yield word, lemma.strip(), 1
    for f in entry.get("forms") or []:
        form = f.get("form") if isinstance(f, dict) else None
        if not isinstance(form, str):
            continue
        form = form.strip()
        if not form or " " in form or form == "-" or SKIP_FORM_TAGS & set(f.get("tags") or []):
            continue
        yield form, word, 0


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--no-english", action="store_true", help="Skip English inflections.")
    args = ap.parse_args()
    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

    conn = sqlite3.connect(args.db)
    conn.execute("PRAGMA journal_mode = WAL")
    conn.executescript(SCHEMA)

    # Only lemmas with a real cross-language translation: the app's lemma fallback skips any
    # lemma without one, so links to the rest would be dead weight (they're most of the table).
    headwords: dict[str, set[str]] = {"tr": set(), "en": set()}
    for word, lang in conn.execute(
        "SELECT DISTINCT source_word, source_lang FROM entries "
        "WHERE target_lang != source_lang AND category != ?", (INFLECTION,)
    ):
        if lang in headwords:
            headwords[lang].add(word)

    sources: list[tuple[Path, str, str | None]] = [
        (fetch.fetch_turkish(CACHE), "tr", None),
        (fetch.fetch_trwiktionary(CACHE), "tr", "tr"),
    ]
    if not args.no_english:
        sources.append((fetch.fetch_english(CACHE), "en", None))

    kept = 0
    batch: list[tuple] = []
    for path, lang, only_lang_code in sources:
        for entry in iter_jsonl(path, desc=f"forms {path.name}"):
            if only_lang_code and entry.get("lang_code") != only_lang_code:
                continue
            for form, lemma, explicit in links(entry):
                if lemma not in headwords[lang]:
                    continue
                key = fold(form, lang)
                if key == fold(lemma, lang):
                    continue
                batch.append((key, lang, lemma, explicit))
                if len(batch) >= 20000:
                    conn.executemany(UPSERT, batch)
                    conn.commit()
                    kept += len(batch)
                    batch.clear()
    if batch:
        conn.executemany(UPSERT, batch)
        conn.commit()
        kept += len(batch)

    for lang, n, forms in conn.execute(
        "SELECT lang, COUNT(*), COUNT(DISTINCT form) FROM forms GROUP BY lang"
    ):
        print(f"  {lang}: {n:,} form→lemma links over {forms:,} distinct forms")
    conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
    conn.execute("PRAGMA journal_mode = DELETE")
    conn.commit()
    conn.execute("VACUUM")
    conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
