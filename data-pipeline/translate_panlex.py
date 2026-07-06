"""
Fill the gap with real (non-MT) en→tr translations from PanLex (CC0 public domain).

PanLex aggregates thousands of human dictionaries into a shared-meaning graph. The
``cointegrated/panlex-meanings`` HF mirror ships one TSV per language; two expressions that
share a ``meaning`` id are translations of each other. So we build a ``meaning → Turkish`` map
from the small ``tur.tsv`` and stream ``eng.tsv``, emitting en→tr pairs for English headwords
that exist in our dictionary. These are *real* dictionary translations — they rank above the
OPUS-MT rows (see EntryDao tierExpr), and they especially help the long-tail words OPUS echoed.

Columns (both TSVs): id, langvar, txt, txt_degr, meaning, langvar_uid.

No API key, no model. Inputs are downloaded to cache/ (gitignored):
    cache/panlex-eng.tsv  (~750 MB)   cache/panlex-tur.tsv  (~44 MB)

Usage:
    python -m translate_panlex                 # extract + insert + finalize
    python -m translate_panlex --top 3         # keep up to N Turkish translations per word
    python -m translate_panlex --residual-only # only fill words with no existing translation
"""
from __future__ import annotations

import argparse
import sqlite3
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

from tqdm import tqdm

from translate_gap import ensure_source_column, finalize_db

HF_BASE = "https://huggingface.co/datasets/cointegrated/panlex-meanings/resolve/main/data"

HERE = Path(__file__).parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"
ENG_TSV = CACHE / "panlex-eng.tsv"
TUR_TSV = CACHE / "panlex-tur.tsv"

INSERT = (
    "INSERT OR IGNORE INTO entries "
    "(source_word, source_lang, target_word, target_lang, pos, category, definition, "
    " sense_order, source) "
    "VALUES (?, 'en', ?, 'tr', NULL, 'General', NULL, ?, 'panlex')"
)


def ensure_tsvs() -> None:
    """Download the CC0 eng/tur PanLex meaning TSVs into cache/ if missing (gitignored)."""
    CACHE.mkdir(parents=True, exist_ok=True)
    for code, dest in (("eng", ENG_TSV), ("tur", TUR_TSV)):
        if dest.exists():
            continue
        url = f"{HF_BASE}/{code}.tsv"
        print(f"Downloading {url} -> {dest.name} …")
        tmp = dest.with_suffix(dest.suffix + ".part")
        urllib.request.urlretrieve(url, tmp)
        tmp.replace(dest)


def load_meaning_to_turkish(path: Path) -> dict[str, list[str]]:
    """meaning id -> distinct Turkish surface forms (skip the header)."""
    m2tr: dict[str, list[str]] = defaultdict(list)
    with open(path, encoding="utf-8") as f:
        header = f.readline()
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            txt, meaning = parts[2], parts[4]
            if txt and txt not in m2tr[meaning]:
                m2tr[meaning].append(txt)
    return m2tr


def english_headwords(conn: sqlite3.Connection) -> tuple[set[str], dict[str, str]]:
    """Set of English source words in our DB + a lowercase→original lookup, so PanLex text can
    be matched to the exact headword form the app stores."""
    originals: set[str] = set()
    lower: dict[str, str] = {}
    for (w,) in conn.execute("SELECT DISTINCT source_word FROM entries WHERE source_lang = 'en'"):
        originals.add(w)
        lower.setdefault(w.lower(), w)
    return originals, lower


def has_real_or_mt(conn: sqlite3.Connection) -> set[str]:
    """English words that already have a wiktionary/mt/llm en→tr row (i.e. not residual)."""
    return {
        w
        for (w,) in conn.execute(
            "SELECT DISTINCT source_word FROM entries "
            "WHERE source_lang = 'en' AND target_lang = 'tr'"
        )
    }


def extract_pairs(originals: set[str], lower: dict[str, str], m2tr: dict[str, list[str]]):
    """Stream eng.tsv; for each English expression that (a) maps to a headword in our DB and
    (b) shares a meaning with Turkish, tally Turkish candidates weighted by shared-meaning count."""
    pairs: dict[str, Counter] = defaultdict(Counter)
    with open(ENG_TSV, encoding="utf-8") as f:
        f.readline()  # header
        for line in tqdm(f, unit="row", desc="scan eng"):
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 5:
                continue
            txt, meaning = parts[2], parts[4]
            tr_list = m2tr.get(meaning)
            if not tr_list:
                continue
            word = txt if txt in originals else lower.get(txt.lower())
            if word is None:
                continue
            for tr in tr_list:
                if tr and tr.lower() != word.lower():
                    pairs[word][tr] += 1
    return pairs


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--top", type=int, default=3, help="Max Turkish translations kept per word.")
    ap.add_argument("--residual-only", action="store_true",
                    help="Only insert for words that have no existing en→tr translation.")
    ap.add_argument("--sample", type=int, default=20)
    args = ap.parse_args()

    ensure_tsvs()
    if not args.db.exists():
        raise SystemExit(f"{args.db} not found — build the dictionary first.")

    conn = sqlite3.connect(args.db)
    conn.execute("PRAGMA journal_mode = WAL")
    ensure_source_column(conn)

    print("Loading Turkish meaning map…")
    m2tr = load_meaning_to_turkish(TUR_TSV)
    print(f"  {len(m2tr):,} Turkish meanings")

    originals, lower = english_headwords(conn)
    print(f"Matching against {len(originals):,} English headwords…")
    pairs = extract_pairs(originals, lower, m2tr)
    print(f"  {len(pairs):,} English headwords got at least one PanLex translation")

    skip = has_real_or_mt(conn) if args.residual_only else set()
    residual = has_real_or_mt(conn)  # for coverage reporting

    inserted = words_covered = residual_covered = 0
    samples_left = args.sample
    batch = []
    for word, counter in pairs.items():
        if word in skip:
            continue
        tops = [tr for tr, _ in counter.most_common(args.top)]
        if not tops:
            continue
        words_covered += 1
        if word not in residual:
            residual_covered += 1
        if samples_left > 0:
            print(f"  · {word} -> {tops}")
            samples_left -= 1
        for i, tr in enumerate(tops):
            batch.append((word, tr, i))
        if len(batch) >= 5000:
            conn.executemany(INSERT, batch)
            conn.commit()
            inserted += len(batch)
            batch.clear()
    if batch:
        conn.executemany(INSERT, batch)
        conn.commit()
        inserted += len(batch)

    print(f"\nInserted {inserted:,} panlex rows across {words_covered:,} headwords "
          f"({residual_covered:,} of them were previously untranslated).")
    total = conn.execute("SELECT COUNT(*) FROM entries WHERE source = 'panlex'").fetchone()[0]
    print(f"Rebuilding FTS + VACUUM ({total:,} panlex rows present)…")
    finalize_db(conn)
    conn.close()
    print("Done.")


if __name__ == "__main__":
    main()
