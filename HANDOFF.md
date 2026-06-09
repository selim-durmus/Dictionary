# Dictionary — Handoff & Status

Living document. Update at the end of each meaningful work session so a fresh Claude (or you) can pick up cold.

## TL;DR — Current state

Offline EN↔TR dictionary Android app. Compose UI, Room for user state, SQLite + FTS4 for the bundled dictionary. ~1.7M entries shipped in `app/src/main/assets/dictionary.db` (≈ 480 MB; **gitignored**, regenerate via `scripts/build-dictionary.sh`).

MVP is functional and feature-complete: 3-tab pager (Search/Recents/Top 50), entry-detail bottom sheet, fuzzy search, "Did you mean" suggestion banner, EN↔TR direction filter, auto-record recents, swipe-to-delete recents, clear-with-undo on Recents and Top 50, home-screen quick-search widget with translucent popup activity, custom monochrome launcher icon.

**The open problem: only ~45K of ~1.36M English headwords have Turkish translations.** ~98% of English entries fall back to English glosses (1.67M en→en rows) because Wiktionary didn't ship a Turkish translation. The OPUS-MT gap-fill **pipeline is now built and verified** (see "Next step" below) — what remains is *running* it: a 10K-row pilot to sample quality + estimate wall-clock, then the full overnight run. No MT rows are in the DB yet.

## How to resume in a new chat

Tell Claude:
> Read `HANDOFF.md` at the repo root. The OPUS-MT pipeline is built; pick up at "Next step → Running it" — run the 10K pilot, sample quality, tune the threshold, then the full run.

The standing rule is **commit + push to `origin/main` after every meaningful change**. A Stop hook in `~/.claude/settings.json` enforces this automatically.

## Repo layout (essentials)

```
Translate/
├── app/                                    # Android app (com.selimdurmus.dictionary)
│   └── src/main/
│       ├── assets/dictionary.db            # ★ gitignored, ~480 MB, regenerate
│       ├── java/com/selimdurmus/dictionary/
│       │   ├── MainActivity.kt             # singleTask, handles intent extras from widget
│       │   ├── TranslateApp.kt             # Application class, holds AppContainer
│       │   ├── AppContainer.kt             # DI root: repository + dao construction
│       │   ├── data/
│       │   │   ├── DictionaryDb.kt         # bundled SQLite open + first-run copy + VERSION
│       │   │   ├── EntryDao.kt             # search() with FTS + fuzzy + redirect handling
│       │   │   ├── UserDb.kt               # Room DB for recents + stats
│       │   │   ├── LangFilter.kt           # ALL / EN_TR / TR_EN enum
│       │   │   └── DictionaryRepository.kt
│       │   ├── ui/                         # Compose screens + ViewModels
│       │   │   ├── HomePager.kt            # 3-tab pager, entry-sheet host
│       │   │   ├── SearchScreen.kt + SearchViewModel.kt
│       │   │   ├── RecentsScreen.kt + RecentsViewModel.kt
│       │   │   ├── TopWordsScreen.kt + TopWordsViewModel.kt
│       │   │   ├── EntryDetailSheet.kt + EntryDetailViewModel.kt
│       │   │   └── UndoClearBanner.kt
│       │   └── widget/
│       │       ├── QuickSearchWidgetProvider.kt
│       │       └── QuickSearchActivity.kt  # translucent dialog Activity
│       └── res/                            # widget XML, drawable, themes, strings
├── data-pipeline/                          # Python — builds dictionary.db
│   ├── build_dictionary.py
│   ├── sources/
│   │   ├── fetch.py                        # downloads Kaikki dumps to cache/
│   │   ├── parse.py                        # parse_turkish + parse_english
│   │   ├── categorize.py                   # tag → category mapping
│   │   └── db.py                           # SQLite writer with FTS index
│   └── cache/, build/                      # gitignored
├── scripts/
│   └── build-dictionary.sh                 # one-command wrapper: venv + build + install
└── HANDOFF.md                              # this file
```

## What's been built (chronological highlights)

- **Initial scaffold** — Compose 3-tab pager, theme tokens (black + gold), data layer with `EntryDao` + `RecentDao` + `StatsDao` + `DictionaryRepository`, asset-copy `DictionaryDb` opening read-only SQLite.
- **UI fill-out (post-scaffold)** — Per-screen ViewModels, debounced search (200 ms), modal bottom sheet for entry detail, swipe-to-delete on recents, auto-focus search field on tab activation, keyboard hide on tab leave, cursor-to-end on field refocus via `TextFieldValue`.
- **Search ranking** — FTS prefix match with ORDER BY:
  1. exact headword match
  2. cross-language rows (real translations) above same-language gloss rows
  3. shorter source words
  4. stable order by lang/category/sense
- **Fuzzy fallback** — Damerau-Levenshtein over candidates filtered by first-letter + length range. Triggers when FTS returns <5 hits on a ≥4-char query. Edit distance ≤2, ≤8 candidate words. Surfaces a *"Showing results for X"* banner when FTS returned zero.
- **Redirect-follow** — Wiktionary "Misspelling of X" / "Alternative spelling of X" / etc. entries are detected via a prefix list; when ALL senses for the typed headword are redirects, follow to X's entries and show the suggestion banner.
- **Data pipeline expansion** — `parse_english` now emits an `en→en` fallback row using the first gloss as `target_word` when a sense has no Turkish translation. DB schema switched to `UNIQUE(source_word, source_lang, target_word, target_lang)` + `INSERT OR IGNORE` to avoid a multi-GB Python dedup set on ~1.7M rows.
- **Language filter** — `LangFilter` enum (ALL/EN_TR/TR_EN), three pill chips on SearchScreen below the input. Filter SQL fragment applied to FTS and fuzzy candidate scans.
- **Auto-record recents** — `combine(query, results).debounce(1s)` records the top result as a recent when the query settles.
- **Quick-search widget** — Resizable 1×1 to 4×1 home-screen widget. Pure black tile with search icon. Size-aware text via `onAppWidgetOptionsChanged`: 1×1 icon-only, 1×2 "Search", 1×3+ "Search Dictionary". Tap → translucent dialog `QuickSearchActivity` with live search, top result, "Open app" button. Tapping the top result launches `MainActivity` with extras → opens entry detail.
- **Clear-with-undo** — Tap "Clear" on Recents or Top 50 → list goes empty + undo banner with 5 s shrinking progress line. Undo cancels; timer expiry commits the wipe. Animation is wall-clock-driven from a timestamp so it survives pager-tab swipes.
- **Package rename** — `com.example.translate` → `com.selimdurmus.dictionary`. GH repo renamed to `Dictionary`. Display name "Dictionary".
- **Monochrome launcher icon** — Page-line cutouts via `evenOdd` fill so themed icons show the lines on Android 13+.
- **Tooling** — Project Stop hook + global `~/.claude/settings.json` for auto-push on every Claude turn (no auto-commit; pushes existing commits to current branch). `env.JAVA_HOME` set globally + `Bash(./gradlew *)` allowlisted so gradle builds don't prompt.
- **scripts/build-dictionary.sh** — One-command pipeline: creates venv, downloads dumps if needed, builds DB, installs into assets.

## Problems we hit and how we fixed them

| Problem | Resolution |
|---|---|
| **Search burying exact match.** Typing `selam` showed English `selam*` prefix matches above the Turkish `selam` exact match. | ORDER BY now leads with `(LOWER(source_word) = LOWER(?)) DESC` and `LENGTH(source_word) ASC`. |
| **`propogate` returned the misspelling entry, not `propagate`.** Wiktionary ships `propogate → "Misspelling of propagate"` as its own row; FTS found it as the exact match. | `followRedirect()` in `EntryDao`: when **all** FTS hits for the typed headword start with a known redirect prefix ("Misspelling of", "Alternative spelling of", "Obsolete spelling of", etc.), extract X and return X's entries with `suggestion = X`. |
| **`subtle` showed English glosses ahead of `güç algılanan`.** Multiple senses; en→en rows had lower `sense_order` than en→tr rows. | Added `(target_lang != source_lang) DESC` as second ORDER BY clause so real translations rank above same-language gloss rows. |
| **`imperceptible` (and 1.34M others) have no Turkish translation.** Wiktionary's English dump doesn't ship one. | **Pipeline built (OPUS-MT gap-fill), not yet run.** See "Next step → Running it". |
| **Cursor sat at position 0 on field refocus.** Plain `String` overload of `TextField` always places caret at start. | Switched SearchScreen to the `TextFieldValue` overload; `LaunchedEffect(isActive)` sets selection to `TextRange(text.length)` before requesting focus. |
| **Widget couldn't resize below 2 cells, above 3 cells.** `minResizeWidth` equaled `minWidth` (Android requires strictly less for below-default resize); `maxResizeWidth` rounded down to 3 on Pixel launcher cell widths. | `minWidth=140dp`, `minResizeWidth=40dp`, `maxResizeWidth=560dp`. |
| **Widget popup showed MainActivity behind the translucent layer.** Default `taskAffinity` made `FLAG_ACTIVITY_NEW_TASK` resume the app's existing task. | `QuickSearchActivity` got `taskAffinity=""` + `launchMode="singleInstance"` so it lives in its own task; home screen stays visible underneath. |
| **480 MB dictionary.db too big for GitHub.** GitHub hard-rejects files over 100 MB. | Untracked via `.gitignore`; regenerate locally via `scripts/build-dictionary.sh`. The old 14.8 MB Turkish-only `.db` remains in git history. |
| **DB build OOM'd on the 1.7M-row Python dedup set.** Holding ~5M tuples in a Python `set` would have used multiple GB. | Schema added `UNIQUE(source_word, source_lang, target_word, target_lang)`, build switched to `INSERT OR IGNORE` — dedup happens in SQLite, Python uses ~0 memory for it. |
| **Constant permission prompts for `./gradlew` builds.** Every build needed approval. | Global `~/.claude/settings.json` adds `env.JAVA_HOME` (so bare `./gradlew` works without the export prefix) + `Bash(./gradlew *)` to `permissions.allow`. |
| **Search tab kept stealing focus on every pager swipe back.** | `LaunchedEffect(isActive)` only fires on the false→true edge, so focus is requested exactly when the tab becomes active. |

## Next step — translate the ~1.34M-word gap with OPUS-MT

### Decision

Use **`Helsinki-NLP/opus-mt-tc-big-en-tr`** (Marian-NMT, ~230M params, CC-BY-4.0) via **CTranslate2** with int8 quantization on the M-series Mac. Runs once at build time; runtime stays fully offline.

### Why this and not the alternatives

| Option | Cost | Quality | License | Verdict |
|---|---|---|---|---|
| **OPUS-MT (this)** | **$0** | FLORES BLEU 31.4 / chrF 0.628 | **CC-BY-4.0** | ✅ Picked — clean license, $0, runs locally |
| Commercial MT batch (Claude Haiku / GPT-4.1 mini) | $12–$70 | Comparable or slightly better | Output is yours | Rejected — user prefers $0 path; Shopify enterprise plan doesn't apply to personal projects |
| NLLB-200 distilled | $0 | Slightly above OPUS-MT | **CC-BY-NC-4.0** | ✗ NON-commercial license; Meta hasn't clarified output restriction |
| MADLAD-400-3B | ~$1–3 (needs GPU) | Best in class | Apache-2.0 | Possible upgrade later if quality is insufficient |
| PanLex CC0 dump | $0 | Tiered (B-grade) | CC0 | Nice augmentation later; only covers ~50–150K extra |
| Reverse-lookup from existing tr→en rows | $0 | 90% on 6K words, but 0/3 on user's test words (imperceptible, propagate, serendipity) | Inherited from Wiktionary | ✗ Doesn't cover the long tail at all |
| FreeDict eng-tur | $0 | High but stale (~2005) | **GPL** | Skip for now — GPL copyleft is a flag for the Android APK |

### Implementation — DONE (built + verified, not yet run)

All code landed and verified (Python compiles + import-clean, SQL flow tested end-to-end on a synthetic DB, Android app compiles):

1. **`data-pipeline/sources/translate.py`** — `OpusTranslator` wrapping CTranslate2 + HF tokenizer, and `ensure_ct2_model()` which converts a HF Marian model to CT2 int8 once. Heavy deps (`ctranslate2`/`transformers`/`torch`) are lazy-imported so the rest of the pipeline runs without them.
2. **`data-pipeline/translate_gap.py`** (`python -m translate_gap`) — reads en→en gloss rows **per sense** (homographs stay distinct), builds carrier strings `"<headword>: <gloss>"`, batch-translates, extracts the Turkish headword by splitting on the first `:`. Round-trip confidence filter (tr→en, chrF vs original via sacrebleu, default threshold **0.4**, `--no-back-translate` to skip). Inserts en→tr rows tagged **`source='mt'`** *alongside* the en→en rows (augment, not replace), then re-indexes the new rowids into FTS + optimize. **Resumable** — a `NOT EXISTS` guard skips senses that already have an MT row, so a killed run continues. Flags: `--limit`, `--sample N`, `--threshold`, `--batch-size`, `--beam`, `--db`.
3. **Schema/ranking** — `db.py` adds a `source TEXT NOT NULL DEFAULT 'wiktionary'` column. `translate_gap` ALTERs it onto a pre-existing DB so the MT step runs against the current 480 MB asset **without** a multi-GB reparse. `EntryDao.tierExpr()` ranks **real Wiktionary translations (0) > MT (1) > en→en gloss (2)**; it falls back to the old two-tier split if the `source` column is absent (won't crash on an old DB).
4. **`DictionaryDb.VERSION` 2 → 3** so installs re-copy the MT-augmented DB.
5. **`scripts/build-dictionary.sh`** — MT step runs by default after the parse phase; `--no-translate-gap` opts out, `--mt-limit N` pilots. Installs the MT deps into the venv only when the step runs.
6. **`LICENSES.md`** — CC-BY-4.0 attribution for OPUS-MT (Helsinki-NLP/Tatoeba) + Wiktionary/Kaikki + CTranslate2. (No About screen exists yet; revisit if one is added.)

### Next step → Running it

The pipeline has **never been run** — no model has been downloaded/converted and no MT rows exist in the DB. Running it is a heavy, deliberate operation (installs `torch` ~2 GB, downloads + converts two Marian models, then hours of CPU inference), so it was left for an explicit, supervised run. Do this:

```bash
cd data-pipeline
.venv/bin/pip install ctranslate2 transformers sentencepiece sacrebleu torch

# 200-row pilot, print samples to eyeball quality + the colon-split extraction
.venv/bin/python3 -m translate_gap --limit 200 --sample 40

# 10K-row pilot to extrapolate full-run wall-clock (the handoff estimate is 4–8 h on M2 CPU)
.venv/bin/python3 -m translate_gap --limit 10000

# tune --threshold from the sampled chrF distribution, then the full run (resumable)
.venv/bin/python3 -m translate_gap
```

Then `cp build/dictionary.db ../app/src/main/assets/` (or just `./scripts/build-dictionary.sh`, which now does the MT step + copy), rebuild the app, and spot-check `imperceptible`, `propagate`, `serendipity` in search.

### Risks & things to verify before committing the overnight run

- **Single-word inputs hallucinate.** Use carrier sentences. Sample 200 random outputs after a 10K-row pilot before running the full 1.34M.
- **Homographs** (`bank`, `right`, `spring`) collapse to one Turkish word per call. Translate **per sense** (each en→en row separately) rather than once per headword.
- **Apple Silicon throughput has no public benchmark.** Run a 10K-row sample first to extrapolate wall-clock. Estimate is 4–8 h overnight on M2 CPU at int8, but could be 2× off.
- **Quality on technical/medical/legal jargon will be weaker** — opus-mt model card warns it's general-domain. The confidence filter should catch most of these.
- **Storage delta**: ~30–50 MB on top of the existing 480 MB DB (depending on whether we replace or add). APK ships at ~510 → ~540 MB.
- **CC-BY-4.0 attribution** is easy to forget. Add a credit line at the same time as the translations land.

## Open questions / decisions deferred

- ~~**Replace or augment en→en rows with MT translations?**~~ **Decided: augment** — MT rows (`source='mt'`) sit above the en→en gloss rows, which stay as a final fallback.
- **Quality threshold for the confidence filter** — defaults to chrF 0.4; needs empirical tuning after the pilot sample.
- **PanLex augmentation** as a follow-up after the MT pass — adds dictionary-quality entries on top of MT output. Worth doing if MT quality alone isn't enough.
- **Disk footprint** — 540 MB total install is heavy. Long-term, opening SQLite directly from the APK asset (via [requery/sqlite-android](https://github.com/requery/sqlite-android) + `ParcelFileDescriptor`) would halve the on-device footprint by removing the duplicate copy. Currently deferred.

## Standing rules

- **Commit + push after every meaningful change.** Stop hook enforces the push half.
- **Never commit `app/src/main/assets/dictionary.db`** — too big, regenerate via the script.
- **Class names stayed `TranslateApp` / `TranslateTheme` / etc.** even though the app is now "Dictionary". Internal-only, low value to rename.
- **Use `./gradlew :app:assembleDebug` directly** (no `export JAVA_HOME` prefix) — the global setting handles it.
