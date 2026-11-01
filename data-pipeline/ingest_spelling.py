"""
Link common Turkish misspellings to the word they mean, through the ``forms`` table.

Subtitles (and people typing on an English keyboard) are full of spellings the dictionary can't
resolve:
  * Turkish letters typed as ASCII: ``nasil → nasıl``, ``degil → değil``, ``oldugunu → olduğunu``
  * OCR mistaking l for i: ``nasıi → nasıl``
  * words written together: ``birşeyler → bir şeyler``, ``hoşçakal → hoşça kal``, ``herzaman``

Every translatable headword and known form is indexed by its ASCII skeleton (ç→c, ğ→g, ı→i, ö→o,
ş→s, ü→u, and l→i for OCR). For every word with at least ``--min-count`` hits in the full
OpenSubtitles list that still has no translation and no ``forms`` link, the targets sharing its
skeleton are candidates: fewest changed letters wins, then the spelling that's more common in
subtitles (``cok → çok``, not ``çök``). Failing that, a single inserted space is tried. A form
target links to that form's lemma. A word that is also an English headword (tom, cole — mostly
names) is linked only when the Turkish spelling is more common in subtitles (eger → eğer).

Links are stored with ``explicit`` = -2, below Wiktionary (1, 0) and Zemberek (-1); the app shows
them as "Showing results for X" rather than "X is a form of Y".

Run after ingest_zemberek, so Zemberek's forms count as targets.

Usage:
    python -m ingest_spelling
"""
from __future__ import annotations

import argparse
import sqlite3
from pathlib import Path

from ingest_zemberek import FULL_URL
from coverage_report import WORD_RE
from sources import fetch
from sources.parse import INFLECTION
from sources.text import fold

HERE = Path(__file__).parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"
SPELLING_LINK = -2
MAX_CHANGES = 6

SKELETON = str.maketrans("çğıöşül", "cgiosui")


def skeleton(word: str) -> str:
    return word.translate(SKELETON)


def changes(a: str, b: str) -> int:
    return sum(x != y for x, y in zip(a, b))


def split_variants(word: str) -> list[str]:
    return [word[:i] + " " + word[i:] for i in range(2, len(word) - 1)]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--min-count", type=int, default=3)
    args = ap.parse_args()
    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")
    conn = sqlite3.connect(args.db)

    # folded → exact headword with a real cross-language translation
    headword: dict[str, str] = {}
    english: set[str] = set()
    for word, lang in conn.execute(
        "SELECT DISTINCT source_word, source_lang FROM entries "
        "WHERE target_lang != source_lang AND category != ?", (INFLECTION,)
    ):
        if lang == "en":
            english.add(word.lower())
        else:
            key = fold(word, "tr")
            if key not in headword or word == word.lower():
                headword[key] = word
    # known form → its best lemma
    form_lemma: dict[str, str] = {}
    for form, lemma in conn.execute(
        "SELECT form, lemma FROM forms WHERE lang = 'tr' ORDER BY explicit ASC"
    ):
        form_lemma[form] = lemma  # ascending, so the highest-ranked link is written last

    def target(variant: str) -> str | None:
        return headword.get(variant) or form_lemma.get(variant)

    by_skeleton: dict[str, list[str]] = {}
    for known in headword.keys() | form_lemma.keys():
        if " " not in known:
            by_skeleton.setdefault(skeleton(known), []).append(known)

    path = fetch.download(FULL_URL, CACHE / "freq" / "tr_full.txt")
    counts: dict[str, int] = {}
    for line in open(path, encoding="utf-8"):
        parts = line.split(" ")
        if len(parts) == 2:
            w = fold(parts[0], "tr")
            counts[w] = counts.get(w, 0) + int(parts[1])

    rows: list[tuple] = []
    for word, n in counts.items():
        if (n < args.min_count or len(word) < 3 or not WORD_RE["tr"].match(word)
                or word in headword or word in form_lemma):
            continue
        best = None
        hits = [(changes(word, v), -counts.get(v, 0), v) for v in by_skeleton.get(skeleton(word), [])]
        if hits and min(hits)[0] <= MAX_CHANGES:
            best = min(hits)[2]
        if best is None:
            hits = [v for v in split_variants(word) if target(v)]
            if hits:
                best = max(hits, key=lambda v: counts.get(v.replace(" ", ""), 0))
        if best is not None and word in english and counts.get(best.replace(" ", ""), 0) <= n:
            best = None
        if best is not None:
            rows.append((word, "tr", target(best), SPELLING_LINK))

    conn.executemany(
        "INSERT OR IGNORE INTO forms (form, lang, lemma, explicit) VALUES (?, ?, ?, ?)", rows)
    conn.commit()
    print(f"  linked {len(rows):,} misspelled words")
    conn.execute("VACUUM")
    conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
