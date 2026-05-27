from __future__ import annotations

from typing import Iterable


_LABEL_TO_CATEGORY = {
    # Idiom-like
    "idiomatic": "Idiom",
    "idiom": "Idiom",
    "proverb": "Idiom",
    "set phrase": "Phrase",
    "phrase": "Phrase",
    "phrasal verb": "Phrasal Verb",
    "phrasal_verb": "Phrasal Verb",
    "saying": "Phrase",
    # Register
    "slang": "Slang",
    "vulgar": "Slang",
    "offensive": "Slang",
    "colloquial": "Slang",
    "informal": "Slang",
    # Technical / domain
    "computing": "Computing",
    "internet": "Computing",
    "software": "Computing",
    "programming": "Computing",
    "mathematics": "Math",
    "math": "Math",
    "geometry": "Math",
    "physics": "Technical",
    "chemistry": "Technical",
    "biology": "Technical",
    "medicine": "Medical",
    "anatomy": "Medical",
    "pharmacology": "Medical",
    "law": "Legal",
    "legal": "Legal",
    "finance": "Trade",
    "economics": "Trade",
    "business": "Trade",
    "accounting": "Trade",
    "engineering": "Technical",
    "electronics": "Technical",
    "mechanics": "Technical",
    "linguistics": "Technical",
    "grammar": "Technical",
    "astronomy": "Technical",
    "geology": "Technical",
    "geography": "Technical",
    "history": "Technical",
    "music": "Technical",
    "religion": "Technical",
    "military": "Technical",
    "sports": "Technical",
    "cooking": "Technical",
    "botany": "Technical",
}


def categorize(tags: Iterable[str] | None) -> str:
    if not tags:
        return "General"
    for tag in tags:
        key = tag.lower().strip()
        if key in _LABEL_TO_CATEGORY:
            return _LABEL_TO_CATEGORY[key]
    return "General"
