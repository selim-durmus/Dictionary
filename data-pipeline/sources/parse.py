"""
Stream-parse Kaikki JSONL dumps into flat (source, target, lang, ...) rows.

Kaikki entry shape (relevant fields only):
    {
        "word": "kitap",
        "lang_code": "tr",
        "pos": "noun",
        "senses": [
            {
                "glosses": ["a written work"],
                "tags": ["countable"],
                "raw_tags": [...]
            }
        ],
        "translations": [
            {"code": "tr", "lang_code": "tr", "word": "kitap", "tags": [...]}
        ]
    }
"""
from __future__ import annotations

import json
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path

from tqdm import tqdm

from .categorize import categorize


@dataclass(slots=True)
class Row:
    source_word: str
    source_lang: str
    target_word: str
    target_lang: str
    pos: str | None
    category: str
    definition: str | None
    sense_order: int


def _iter_jsonl(path: Path, desc: str) -> Iterator[dict]:
    total_bytes = path.stat().st_size
    with open(path, "rb") as f, tqdm(
        total=total_bytes, unit="B", unit_scale=True, unit_divisor=1024, desc=desc
    ) as bar:
        for line in f:
            bar.update(len(line))
            line = line.strip()
            if not line:
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError:
                continue


def _collect_tags(sense: dict) -> list[str]:
    tags: list[str] = []
    for key in ("tags", "raw_tags", "topics", "categories"):
        val = sense.get(key)
        if isinstance(val, list):
            for item in val:
                if isinstance(item, str):
                    tags.append(item)
                elif isinstance(item, dict) and isinstance(item.get("name"), str):
                    tags.append(item["name"])
    return tags


def parse_turkish(jsonl_path: Path) -> Iterator[Row]:
    """Turkish headwords with English glosses (tr→en rows)."""
    for entry in _iter_jsonl(jsonl_path, desc="parse tr"):
        word = entry.get("word")
        if not isinstance(word, str) or not word.strip():
            continue
        pos = entry.get("pos")
        senses = entry.get("senses") or []
        for order, sense in enumerate(senses):
            glosses = sense.get("glosses") or []
            tags = _collect_tags(sense)
            category = categorize(tags)
            for gloss in glosses:
                if not isinstance(gloss, str) or not gloss.strip():
                    continue
                yield Row(
                    source_word=word.strip(),
                    source_lang="tr",
                    target_word=gloss.strip(),
                    target_lang="en",
                    pos=pos if isinstance(pos, str) else None,
                    category=category,
                    definition=None,
                    sense_order=order,
                )


def parse_english(jsonl_path: Path) -> Iterator[Row]:
    """English headwords with Turkish translations (en→tr rows).

    Translations live on ``senses[].translations`` (per-sense) in the English Kaikki dump.
    The top-level ``translations`` array is often present but doesn't always reflect all senses,
    so we walk both and let the row-level dedupe in db.build collapse overlaps.
    """
    for entry in _iter_jsonl(jsonl_path, desc="parse en"):
        word = entry.get("word")
        if not isinstance(word, str) or not word.strip():
            continue
        pos = entry.get("pos")
        word = word.strip()
        pos_str = pos if isinstance(pos, str) else None

        senses = entry.get("senses") or []
        for sense_idx, sense in enumerate(senses):
            sense_tags = _collect_tags(sense)
            sense_category = categorize(sense_tags)
            for tr in sense.get("translations") or []:
                row = _en_translation_row(word, pos_str, sense_category, sense_idx, tr)
                if row is not None:
                    yield row

        # Top-level translations as a safety net for entries that put them only there.
        for tr in entry.get("translations") or []:
            row = _en_translation_row(word, pos_str, "General", 0, tr)
            if row is not None:
                yield row


def _en_translation_row(
    word: str, pos: str | None, fallback_category: str, sense_idx: int, tr: object
) -> Row | None:
    if not isinstance(tr, dict):
        return None
    code = tr.get("code") or tr.get("lang_code")
    if code != "tr":
        return None
    target = tr.get("word")
    if not isinstance(target, str) or not target.strip():
        return None

    tags = tr.get("tags") if isinstance(tr.get("tags"), list) else []
    category = categorize(tags) if tags else fallback_category
    definition = tr.get("sense") if isinstance(tr.get("sense"), str) else None

    return Row(
        source_word=word,
        source_lang="en",
        target_word=target.strip(),
        target_lang="tr",
        pos=pos,
        category=category,
        definition=definition,
        sense_order=sense_idx,
    )
