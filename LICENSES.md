# Third-party data & model attribution

## Dictionary data — Wiktionary (via Kaikki)

English and Turkish headwords, glosses, and translations are derived from
[Wiktionary](https://www.wiktionary.org/) dumps published by
[Kaikki.org](https://kaikki.org/). Wiktionary content is dual-licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) and the
[GNU Free Documentation License](https://www.gnu.org/licenses/fdl-1.3.html).

## Turkish→Turkish definitions — TDK Güncel Türkçe Sözlük

Turkish-language definitions marked `source = 'tdk'` (Turkish headword → Turkish meaning) are
derived from the **Güncel Türkçe Sözlük** of the Turkish Language Association (Türk Dil Kurumu,
[sozluk.gov.tr](https://sozluk.gov.tr/)), via the machine-readable
[`ogun/guncel-turkce-sozluk`](https://github.com/ogun/guncel-turkce-sozluk) mirror (12th ed.,
~99k headwords), ingested by `ingest_tdk.py`. The mirror's packaging is MIT-licensed; the
dictionary content itself is © Türk Dil Kurumu and is included here for reference/educational use.
These rows are same-language, so they rank below any real Turkish→English translation and surface
the Turkish meaning when no English translation exists (they also carry TDK's `eskimiş` /
archaic-Ottoman usage labels in `pos`).

## Machine translations — OPUS-MT (Helsinki-NLP / Tatoeba)

English→Turkish entries marked `source = 'mt'` in the bundled dictionary were generated at
build time with the **OPUS-MT** model
[`Helsinki-NLP/opus-mt-tc-big-en-tr`](https://huggingface.co/Helsinki-NLP/opus-mt-tc-big-en-tr)
(round-trip confidence filtering uses `Helsinki-NLP/opus-mt-tc-big-tr-en`). These models are
part of the [OPUS-MT / Tatoeba Translation Challenge](https://github.com/Helsinki-NLP/Tatoeba-Challenge)
and are licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/).

> Tiedemann, J. (2020). *The Tatoeba Translation Challenge – Realistic Data Sets for Low
> Resource and Multilingual MT.* Proceedings of the Fifth Conference on Machine Translation.

Inference is run via [CTranslate2](https://github.com/OpenNMT/CTranslate2) (MIT License).

## Dictionary translations — PanLex (CC0)

English→Turkish entries marked `source = 'panlex'` are real dictionary translations derived from
the [PanLex](https://panlex.org/) database (the [`cointegrated/panlex-meanings`](https://huggingface.co/datasets/cointegrated/panlex-meanings)
mirror), joined on shared meaning ids by `translate_panlex.py`. PanLex data is released under
[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (public domain — no attribution
required; credited here as good practice). These rows only fill words that have no Wiktionary or
MT translation and are ranked below them.

## Machine translations — Claude (Anthropic)

English→Turkish entries marked `source = 'llm'` are per-sense translations generated at build time
with **Claude Haiku** (Anthropic) via the Message Batches API (`translate_llm.py`), using each
sense's English gloss for disambiguation. These outrank the OPUS-MT rows in the app and the OPUS
rows remain as a fallback tier. Per the [Anthropic usage terms](https://www.anthropic.com/legal/commercial-terms),
model outputs belong to the user; this entry is disclosure of machine-translation provenance, not
a required attribution.

The translations are produced offline during the data-pipeline build (`translate_gap.py`,
`translate_llm.py`); the shipped app performs no network calls and runs no model at runtime.
