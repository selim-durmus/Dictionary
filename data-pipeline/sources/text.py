from __future__ import annotations

import re

# Turkish dotted/dotless I: "I"→"ı", "İ"→"i". Must match the app's lowercase(Locale("tr")).
_TR_LOWER = str.maketrans({"I": "ı", "İ": "i"})

_PAREN = re.compile(r"\([^)]*\)|\[[^\]]*\]")


def fold(word: str, lang: str) -> str:
    """Lookup key for a headword: case-folded the way the app folds the typed query."""
    w = word.strip()
    return (w.translate(_TR_LOWER) if lang == "tr" else w).lower()


def strip_parens(text: str) -> str:
    """'(birini) kullanmak' → 'kullanmak'; collapses the leftover whitespace."""
    return " ".join(_PAREN.sub(" ", text).split())
