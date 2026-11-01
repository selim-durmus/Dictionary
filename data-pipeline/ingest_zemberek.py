"""
Extend the ``forms`` table with Turkish inflected forms Wiktionary has no table for, using the
Zemberek morphological analyzer (Apache-2.0, Java; see zemberek/).

Wiktionary's inflection tables cover the common paradigms, but real text is full of forms they
never list: ``gerektiğini → gerekmek``, ``yaptığını → yapmak``, ``kendini → kendi``, and spoken
spellings like ``geliyom → gelmek``. Zemberek splits any word into root + suffixes, so this step
runs it over every word that occurs at least ``--min-count`` times in the full OpenSubtitles list
and links the ones the dictionary can't resolve yet to a translatable headword.

Only words with no translation of their own and no existing ``forms`` link get new rows, so
Wiktionary always wins. Each word gets one link, its best reading, stored with ``explicit`` = -1
so it sorts after Wiktionary's explicit (1) and table (0) links under the app's
``ORDER BY explicit DESC``. (The app shows only the first lemma that has translations, and every
candidate here has one, so further readings would never be shown.)

For each analysis the candidates are tried in order: derived stems longest first, as an infinitive
and as-is (``güzelleştirdik → güzelleştirmek``), then Zemberek's dictionary lemma, then the root.
Proper-noun readings and readings with more suffixes rank lower.

Run after ingest_forms (which rebuilds the table from scratch).

Usage:
    python -m ingest_zemberek
    python -m ingest_zemberek --min-count 5
"""
from __future__ import annotations

import argparse
import os
import sqlite3
import subprocess
from collections import defaultdict
from pathlib import Path

from coverage_report import WORD_RE
from sources import fetch
from sources.parse import INFLECTION
from sources.text import fold

HERE = Path(__file__).parent
REPO = HERE.parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"
WORK = HERE / "build" / "zemberek"
FULL_URL = "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/tr/tr_full.txt"

BACK_VOWELS = set("aıouâ")
FRONT_VOWELS = set("eiöüîû")


def infinitive(stem: str) -> str | None:
    """Verb stem → dictionary infinitive by vowel harmony (gel → gelmek, yap → yapmak)."""
    for ch in reversed(stem):
        if ch in BACK_VOWELS:
            return stem + "mak"
        if ch in FRONT_VOWELS:
            return stem + "mek"
    return None


def word_list(min_count: int) -> list[str]:
    path = fetch.download(FULL_URL, CACHE / "freq" / "tr_full.txt")
    words, seen = [], set()
    for line in open(path, encoding="utf-8"):
        parts = line.split(" ")
        if len(parts) < 2 or int(parts[1]) < min_count:
            continue
        w = fold(parts[0], "tr")
        if len(w) > 1 and WORD_RE["tr"].match(w) and w not in seen:
            seen.add(w)
            words.append(w)
    return words


def run_zemberek(words: list[str]) -> Path:
    WORK.mkdir(parents=True, exist_ok=True)
    src, out = WORK / "words.txt", WORK / "analyses.tsv"
    src.write_text("\n".join(words) + "\n", encoding="utf-8")
    gradlew = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
    subprocess.run([str(gradlew), "-q", "-p", str(HERE / "zemberek"), "run",
                    f"--args={src} {out}"], cwd=REPO, check=True)
    return out


def candidates(analyses: list[str], word: str) -> list[str]:
    """Lemma spellings to try for one word, best first."""
    parsed = []
    for a in analyses:
        lemma, pos, morphemes, stems = a.split("|")
        parsed.append((lemma[:1].isupper(), int(morphemes), lemma, pos, stems.split(",")))
    parsed.sort(key=lambda p: (p[0], p[1]))
    out: list[str] = []
    for _proper, _n, lemma, pos, stems in parsed:
        for stem in reversed(stems[1:]):
            if stem == word:
                continue
            out += [x for x in (infinitive(stem), stem) if x]
        out.append(lemma)
        # A verb's bare root is a different word (yaz "summer" vs yazmak "to write").
        if pos != "Verb":
            out.append(stems[0])
    return out


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--min-count", type=int, default=3,
                    help="Minimum OpenSubtitles count for a word to be analyzed.")
    args = ap.parse_args()
    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

    conn = sqlite3.connect(args.db)
    if not conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'forms'").fetchone():
        raise SystemExit("No forms table — run ingest_forms first.")

    # folded → exact headword spellings that have a real cross-language translation
    headwords: dict[str, list[str]] = defaultdict(list)
    for (word,) in conn.execute(
        "SELECT DISTINCT source_word FROM entries "
        "WHERE source_lang = 'tr' AND target_lang != 'tr' AND category != ?", (INFLECTION,)
    ):
        headwords[fold(word, "tr")].append(word)
    for spellings in headwords.values():
        spellings.sort(key=lambda w: (w != w.lower(), w))
    linked = {f for (f,) in conn.execute("SELECT DISTINCT form FROM forms WHERE lang = 'tr'")}

    words = [w for w in word_list(args.min_count) if w not in headwords and w not in linked]
    print(f"analyzing {len(words):,} Turkish words with no translation or form link…", flush=True)
    analyses = run_zemberek(words)

    rows: list[tuple] = []
    unanalyzed = unmatched = 0
    for line in open(analyses, encoding="utf-8"):
        word, *found = line.rstrip("\n").split("\t")
        if not found:
            unanalyzed += 1
            continue
        lemma = next((headwords[k][0] for k in (fold(c, "tr") for c in candidates(found, word))
                      if k != word and k in headwords), None)
        if lemma is None:
            unmatched += 1
        else:
            rows.append((word, "tr", lemma, -1))

    conn.executemany(
        "INSERT OR IGNORE INTO forms (form, lang, lemma, explicit) VALUES (?, ?, ?, ?)", rows)
    conn.commit()
    print(f"  linked {len(rows):,} words; "
          f"{unanalyzed:,} not analyzable, {unmatched:,} with no translatable lemma")
    conn.execute("VACUUM")
    conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
