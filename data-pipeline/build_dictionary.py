"""
Build the offline dictionary.db shipped inside the Translate Android app.

Usage:
    python -m build_dictionary                 # Turkish dump only (fast, smaller coverage)
    python -m build_dictionary --include-en    # also pull the multi-GB English dump
    python -m build_dictionary --smoke         # build, then run sample lookups

Output:
    build/dictionary.db    — copy this to ../android/app/src/main/assets/dictionary.db
"""
from __future__ import annotations

import argparse
import itertools
from collections.abc import Iterator
from pathlib import Path

from sources import db, fetch, parse


HERE = Path(__file__).parent
CACHE = HERE / "cache"
BUILD = HERE / "build"
OUT = BUILD / "dictionary.db"

SMOKE_QUERIES = ["kitap", "book", "göz", "goz", "gözlük", "kafa", "run", "good"]


def all_rows(*, include_en: bool, insecure: bool) -> Iterator[parse.Row]:
    tr_path = fetch.fetch_turkish(CACHE, insecure=insecure)
    streams = [parse.parse_turkish(tr_path)]
    if include_en:
        en_path = fetch.fetch_english(CACHE, insecure=insecure)
        streams.append(parse.parse_english(en_path))
    return itertools.chain.from_iterable(streams)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--include-en", action="store_true",
                    help="Also download and parse the English Wiktionary dump (multi-GB).")
    ap.add_argument("--smoke", action="store_true",
                    help="Run a few sample FTS lookups after building.")
    ap.add_argument("--insecure", action="store_true",
                    help="Skip TLS verification when downloading. Use only if you've "
                         "confirmed the host certificate has expired but the source is trusted.")
    args = ap.parse_args()

    CACHE.mkdir(parents=True, exist_ok=True)
    BUILD.mkdir(parents=True, exist_ok=True)

    inserted = db.build(all_rows(include_en=args.include_en, insecure=args.insecure), OUT)
    size_mb = OUT.stat().st_size / (1024 * 1024)
    print(f"\nbuilt {OUT}  ({inserted:,} unique rows, {size_mb:.1f} MB)")

    if args.smoke:
        db.smoke_test(OUT, SMOKE_QUERIES)


if __name__ == "__main__":
    main()
