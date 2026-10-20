"""
Measure what the app can show for real-world lookups — run it after every pipeline change.

Query sets:
  * OpenSubtitles frequency lists (hermitdave/FrequencyWords, top 50K per language) in rank bands
  * the hand-picked words in coverage_words.py

Each query is resolved the way EntryDao does it:
  1. exact headword (case-folded) with a cross-language row that isn't an inflection note
  2. otherwise the lemma from the ``forms`` table (``kitabı → kitap``), explicit links first
  3. otherwise a same-language definition (English gloss / TDK), else nothing

and labelled with the best provenance tier it reached:
  dictionary (wiktionary, wiktionary-rev, trwiktionary) > llm > panlex > mt.

Usage:
    python -m coverage_report                 # prints the table, writes build/coverage/*.json
    python -m coverage_report --words         # also print every hand-picked word's result
"""
from __future__ import annotations

import argparse
import json
import re
import sqlite3
from collections import Counter, defaultdict
from pathlib import Path

import coverage_words
from sources import fetch
from sources.parse import INFLECTION
from sources.text import fold

HERE = Path(__file__).parent
CACHE = HERE / "cache"
DEFAULT_DB = HERE / "build" / "dictionary.db"
OUT = HERE / "build" / "coverage"

BANDS = [(1, 1000), (1001, 5000), (5001, 20000), (20001, 50000)]
DICTIONARY = {"wiktionary", "wiktionary-rev", "trwiktionary"}
TIER_ORDER = ["dictionary", "llm", "panlex", "mt"]
WORD_RE = {"en": re.compile(r"^[a-z]+(?:['-][a-z]+)?$"),
           "tr": re.compile(r"^[a-zçğıöşüâîû]+$")}


def tier_of(source: str) -> str:
    return "dictionary" if source in DICTIONARY else source


def freq_words(lang: str) -> list[str]:
    path = fetch.fetch_freq(CACHE, lang)
    words = []
    for line in open(path, encoding="utf-8"):
        w = line.split(" ")[0].strip()
        if WORD_RE[lang].match(w) and len(w) > 1:
            words.append(w)
    return words


class Lookup:
    def __init__(self, conn: sqlite3.Connection):
        cols = {r[1] for r in conn.execute("PRAGMA table_info(entries)")}
        src = "source" if "source" in cols else "'wiktionary'"
        # key -> tier -> [targets]; key -> {"same", "names"} flags
        self.cross: dict[tuple, dict[str, list]] = defaultdict(lambda: defaultdict(list))
        self.same: set[tuple] = set()
        self.pos: dict[tuple, set] = defaultdict(set)
        self.inflected: set[tuple] = set()
        for sw, sl, tw, tl, pos, cat, s in conn.execute(
            f"SELECT source_word, source_lang, target_word, target_lang, pos, category, {src} "
            "FROM entries ORDER BY sense_order"
        ):
            key = (fold(sw, sl), sl)
            self.pos[key].add(pos)
            if tl != sl and cat != INFLECTION:
                self.cross[key][tier_of(s)].append(tw)
            else:
                self.same.add(key)
            if cat == INFLECTION:
                self.inflected.add(key)
        self.lemmas: dict[tuple, list[str]] = defaultdict(list)
        has_forms = conn.execute(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'forms'").fetchone()
        if has_forms:
            for form, lang, lemma, _ in conn.execute(
                "SELECT form, lang, lemma, explicit FROM forms ORDER BY explicit DESC"
            ):
                self.lemmas[(form, lang)].append(lemma)

    def best(self, key: tuple) -> tuple[str, list[str]] | None:
        tiers = self.cross.get(key)
        if not tiers:
            return None
        for t in TIER_ORDER:
            if tiers.get(t):
                return t, tiers[t]
        return None

    def resolve(self, word: str, lang: str) -> dict:
        key = (fold(word, lang), lang)
        hit = self.best(key)
        if hit and hit[0] != "dictionary" and key in self.inflected:
            # Mirrors EntryDao.lemmaFallback: an inflected form whose own translations are only
            # guesses shows its lemma's entries first.
            for lemma in self.lemmas.get(key, [])[:1]:
                lhit = self.best((fold(lemma, lang), lang))
                if lhit and lhit[0] == "dictionary":
                    return {"word": word, "status": "lemma:dictionary", "via": lemma,
                            "targets": lhit[1][:5]}
        if hit:
            return {"word": word, "status": hit[0], "via": None, "targets": hit[1][:5]}
        for lemma in self.lemmas.get(key, []):
            lhit = self.best((fold(lemma, lang), lang))
            if lhit:
                return {"word": word, "status": "lemma:" + lhit[0], "via": lemma,
                        "targets": lhit[1][:5]}
        if key in self.same:
            status = "name_only" if self.pos.get(key) == {"name"} else "definition_only"
        else:
            status = "absent"
        return {"word": word, "status": status, "via": None, "targets": []}


def band_summary(rows: list[dict]) -> dict:
    c = Counter(r["status"] for r in rows)
    n = len(rows)
    direct = sum(c[t] for t in TIER_ORDER)
    lemma = sum(v for k, v in c.items() if k.startswith("lemma:"))
    dict_any = c["dictionary"] + c["lemma:dictionary"]
    return {
        "n": n,
        "dictionary_pct": round(100 * dict_any / n, 1),
        "any_translation_pct": round(100 * (direct + lemma) / n, 1),
        "via_lemma_pct": round(100 * lemma / n, 1),
        "definition_only_pct": round(100 * c["definition_only"] / n, 1),
        "names_pct": round(100 * c["name_only"] / n, 1),
        "absent_pct": round(100 * c["absent"] / n, 1),
        "status": dict(c),
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("--words", action="store_true", help="Print every hand-picked word.")
    args = ap.parse_args()

    conn = sqlite3.connect(args.db)
    print("loading DB…", flush=True)
    lk = Lookup(conn)
    report: dict = {}
    for lang, curated in (("en", coverage_words.EN), ("tr", coverage_words.TR)):
        words = freq_words(lang)
        report[lang] = {"bands": {}, "curated": [lk.resolve(w, lang) for w in curated]}
        print(f"\n{lang.upper()} → {'TR' if lang == 'en' else 'EN'}   "
              "(dictionary = Wiktionary/reverse/tr.wiktionary; any = incl. PanLex/MT/LLM)")
        print(f"  {'rank band':<13}{'dictionary':>11}{'any':>8}{'via lemma':>11}"
              f"{'defn only':>11}{'names':>8}{'absent':>8}")
        for lo, hi in BANDS:
            s = band_summary([lk.resolve(w, lang) for w in words[lo - 1:hi]])
            report[lang]["bands"][f"{lo}-{hi}"] = s
            print(f"  {f'{lo}-{hi}':<13}{s['dictionary_pct']:>10}%{s['any_translation_pct']:>7}%"
                  f"{s['via_lemma_pct']:>10}%{s['definition_only_pct']:>10}%"
                  f"{s['names_pct']:>7}%{s['absent_pct']:>7}%")
        cur = report[lang]["curated"]
        found = sum(not r["status"] in ("definition_only", "absent", "name_only") for r in cur)
        print(f"  hand-picked: {found}/{len(cur)} have a translation")
        if args.words:
            for r in cur:
                via = f" (via {r['via']})" if r["via"] else ""
                print(f"    {r['word']:<18} {r['status']:<18} {', '.join(r['targets'][:4])}{via}")

    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "coverage.json").write_text(json.dumps(report, ensure_ascii=False, indent=1),
                                       encoding="utf-8")
    print(f"\nwrote {OUT / 'coverage.json'}")


if __name__ == "__main__":
    main()
