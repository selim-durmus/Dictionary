# Third-party data & model attribution

## Dictionary data — Wiktionary (via Kaikki)

English and Turkish headwords, glosses, and translations are derived from
[Wiktionary](https://www.wiktionary.org/) dumps published by
[Kaikki.org](https://kaikki.org/). Wiktionary content is dual-licensed under
[CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) and the
[GNU Free Documentation License](https://www.gnu.org/licenses/fdl-1.3.html).

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

The translations are produced offline during the data-pipeline build (`translate_gap.py`); the
shipped app performs no network calls and runs no model at runtime.
