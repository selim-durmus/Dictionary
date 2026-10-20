# Dictionary — Handoff & Status

Living document. Update at the end of each meaningful work session so a fresh Claude (or you) can pick up cold.

## TL;DR — Current state

Offline EN↔TR dictionary Android app. Compose UI, Room for user state, SQLite + FTS4 for the bundled dictionary. ~1.7M entries shipped in `app/src/main/assets/dictionary.db` (≈ 480 MB; **gitignored**, regenerate via `scripts/build-dictionary.sh`).

MVP is functional and feature-complete: 3-tab pager (Search/Recents/Top 50), entry-detail bottom sheet, fuzzy search, "Did you mean" suggestion banner, EN↔TR direction filter, auto-record recents, swipe-to-delete recents, clear-with-undo on Recents and Top 50, home-screen quick-search widget with translucent popup activity, custom monochrome launcher icon.

**The open problem: missing translations.** Measure it with `cd data-pipeline && python -m coverage_report --words` (OpenSubtitles top-50K words per language in rank bands + the hand-picked list in `coverage_words.py`). See "Coverage work (2026-10-19)" below for where it stands; the OPUS-MT sections further down are history.

## Coverage work (2026-10-19)

Measured on a fresh build (Kaikki dumps of 2026-10-03). "Dictionary" = hand-written dictionary data (Wiktionary EN + TR editions, reverse pairs); "any" also counts PanLex/MT.

| | before (Wiktionary + TDK only) | after free sources (dictionary / any) |
|---|---|---|
| EN→TR, top 1K words | 77% | 92% / 98.5% |
| EN→TR, 1K–5K | 57% | 81% / 93.5% |
| EN→TR, 5K–20K | 33% | 66% / 82.5% |
| TR→EN, top 1K | 55% | 95% |
| TR→EN, 1K–5K | 33% | 85% |
| TR→EN, 5K–20K | 19% | 78% |

What changed (all $0, offline at runtime):
- **`forms` table + lemma fallback** (`ingest_forms.py`, `EntryDao.lemmaFallback`). Turkish is agglutinative; `gidiyorum`, `evlerimizden`, `kitabı` now resolve to `gitmek`, `ev`, `kitap` with a "Form of X" banner. English too (`went → go`, `children → child`). Grammar-note senses get category `Inflection` in `parse.py` and rank below real translations. Biggest single TR→EN win.
- **Turkish Wiktionary edition** (`ingest_trwiktionary.py`, `source='trwiktionary'`): English words defined in Turkish (propagate, lease, commute…) + Turkish words' English translation tables.
- **Reverse pairs** (`derive_reverse.py`, `source='wiktionary-rev'`): en→tr tables flipped into tr→en and short tr→en glosses flipped into en→tr.
- **PanLex on by default**, matched to lowercase headwords first (it used to attach "Goldfish → Kılıçbalığı" to a proper-noun headword).
- **OPUS-MT demoted**: runs last, only for single words with no Turkish from any source, no `--prune` (the English gloss stays), ranked below PanLex. Pilot showed idioms rendered literally (piece of cake → kek parçası), collapsed polysemy (match → maç only) and confident errors (hassle → leylek).
- **App**: `Entry.source` + a visible label for machine translation / AI / PanLex rows; tier order dictionary > reverse/trwiktionary > llm > panlex > mt > same-language/inflection notes. `versionCode` 5 so installs re-copy the DB.

After OPUS-MT (residual 528K words → 258,772 kept) and the first 12,000 Haiku senses (top-50K words, chunks en_001–en_024 + pilot), 2026-10-20:

| | dictionary-grade | any translation |
|---|---|---|
| EN→TR top 1K / 1K–5K / 5K–20K / 20K–50K | 96.9 / 90.2 / 73.2 / 45.8% | 99.1 / 96.5 / 88.4 / 68.6% |
| TR→EN same bands | 94.6 / 84.6 / 78.2 / 70.7% | 94.8 / 85.0 / 78.7 / 71.3% |

DB 749 MB (debug APK 283 MB). Inflected words whose own translations are only guesses (MT/AI/PanLex) show their lemma's dictionary entries first ("went" → go → gitmek before MT "gitti").

**Installing on the Pixel:** the phone has a debug build signed on the Mac; a debug build from the Windows PC has a different key, so `adb install -r` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. Either build on the Mac, copy the Mac's `~/.android/debug.keystore` to the PC, or uninstall first (wipes Recents/Top 50).

Still open (in priority order):
1. **Turkish forms Wiktionary has no table for** (`gerektiğini`, `yaptığını`, `kendini`): ~14–28% of TR words in the 1K–50K bands. Use Zemberek (Apache-2.0) at build time over a large Turkish word list to extend `forms`.
2. **Per-sense quality via Claude** — no API key (Pro plan), so it runs as Haiku subagents inside Claude Code with `llm_agent_batches.py` (`export` → one Haiku agent per 500-sense chunk using the prompt from `llm_agent_batches.py prompt <chunk>` → `ingest`). Rows are `source='llm'`, labelled "AI translation" in the app.
   - Done 2026-10-19: top-20K English words whose best translation was PanLex/MT/definition-only → 4,571 words, 5,786 senses (first 2 each), 12 parallel agents, ~7 min. Cost: 5-hour meter +15%, weekly +2%. Also ingested the 2,000-sense pilot (`build/llm_pilot/`, incl. 500 TDK tr→en senses).
   - 2026-10-20 00:47–01:20: all English top-50K gap chunks (en_001–en_043, ~21K senses) and TDK chunks tdk_001–tdk_018 (18K of 80.7K senses, 1,000 per chunk) done → 94,573 llm rows. Cost of that window: 5-hour 0→90%, weekly 22→34%. EN→TR "any" for ranks 20K–50K 68.6→73.3%; Turkish words with only a Turkish definition 1.6→0.1%.
   - 2026-10-20 05:47–06:15: tdk_019–tdk_040 done (22 chunks) → 150,718 llm rows total. Window cost: 5-hour 0→87%, weekly 34→46%. Frequency-band coverage barely moves now: the remaining TDK words are rare and mostly outside the OpenSubtitles top 50K.
   - 2026-10-20 10:47–11:17: tdk_041–tdk_061 done (21 chunks) → 201,931 llm rows total. Window cost: 5-hour 0→94%, weekly 46→58%.
   - Remaining: tdk_062–tdk_081 (20 chunks), scheduled for the 15:47 window (~+11% weekly → ~69%). A session-only cron runs them after each 5-hour reset (~25 chunks per window, stops at 85% / weekly 70%), then ingests and rebuilds. To resume by hand: pending = `build/llm_agent/*.jsonl` without a full `.out.jsonl`; launch Haiku agents with `python -m llm_agent_batches prompt <chunk>`; then `ingest`, `ingest_forms`, `coverage_report`.
   - Agents share a scratch folder; prompts now tell them to name part files after their chunk. Validate outputs (same `i` set as input, right key) before `ingest`.
3. DB size: 684 MB before MT (the Turkish `forms` links are ~2.3M rows). Frequency-filtering forms or opening the asset in place (requery/sqlite-android) would cut the on-device footprint.

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

### Implementation — DONE + RUN COMPLETE ✅

All code landed and verified; deps installed; both models converted; pilots reshaped the approach (below); **full run complete**. **Status: 876,968 MT rows in the DB → 879,148 distinct English words now have a Turkish translation (~65% headword coverage, up from ~3%). DB 480 MB → 661 MB (VACUUMed). Copied into `app/src/main/assets/dictionary.db`. Remaining: rebuild app + spot-check in-app, then commit.** Full run took ~1h49m at ~185 word/s (8-core CPU, int8, threaded).

1. **`data-pipeline/sources/translate.py`** — `OpusTranslator` (CTranslate2 + HF tokenizer, lazy heavy deps) and `ensure_ct2_model()` (one-time HF→CT2 int8 convert). Defaults: `beam_size=1`, **`inter_threads=4`, `intra_threads=2`** (the threading is the main CPU lever — the original run used the default `inter=1` and left 7/8 cores idle, hence the early 33→11 row/s slowdown).
2. **`data-pipeline/translate_gap.py`** (`python -m translate_gap`) — **translates distinct en→en headwords, headword-only** (not per-sense, not carrier — see pilot findings). Inserts one en→tr row per word tagged **`source='mt'`** alongside the en→en rows. Echo-drop is the only filter (output == source → model couldn't translate it). **Resumable**: `NOT EXISTS` skips words that already have an MT row; FTS index is **rebuilt** at the end of each run (so let a resume run to completion). Flags: `--limit`, `--sample N`, `--beam`, `--back-translate` (off), `--db`.
3. **Schema/ranking** — `db.py` adds `source TEXT NOT NULL DEFAULT 'wiktionary'`. `translate_gap` ALTERs it onto a pre-existing DB (no multi-GB reparse needed). `EntryDao.tierExpr()` ranks **real Wiktionary (0) > MT (1) > en→en gloss (2)**; degrades to a two-tier split if the column is absent.
4. **`DictionaryDb.VERSION` 2 → 3** so installs re-copy the MT-augmented DB.
5. **`scripts/build-dictionary.sh`** — MT step on by default; `--no-translate-gap` / `--mt-limit N`. Installs MT deps only when the step runs.
6. **`LICENSES.md`** — CC-BY-4.0 attribution (OPUS-MT/Tatoeba) + Wiktionary/Kaikki + CTranslate2.

**Pilot findings that reshaped the approach** (don't re-litigate these):
- **Carrier sentences gave no benefit.** The model translates a headword context-independently, so `"word: gloss"` carriers added no keep-rate, often *worse* quality (singularised plurals, left rarer words in English), and were ~10× slower (sequence length dominates CPU decode). → headword-only.
- **Per-sense was redundant.** All senses of a word produce the same Turkish word → translate distinct words once (1.67M → 1.36M).
- **Back-translation chrF filter is noise** on isolated words (chrF≈0 for correct translations); it dropped ~24/27 *good* outputs and doubled runtime. The model's own sequence score doesn't separate good from echoed either. → echo-drop only, back-translation off by default.
- **Keep-rate ~54–61%** on representative real words; the rest echo (kept as English-gloss fallback). Quality spot-checked good (`imperceptible→algılanamaz`, `ubiquitous→yaygın`, `amniocentesis→amniyosentez`).

### Next step → Rebuild app + spot-check + commit

The run is complete and the 661 MB DB is already copied into `app/src/main/assets/dictionary.db` (VERSION=3 so it re-copies on launch). Remaining:

1. **Rebuild the app** in Android Studio (or `./gradlew :app:assembleDebug`) and **spot-check in-app**: search `imperceptible` (→ algılanamaz, MT), `propagate` (→ çoğaltma, MT), `book` (→ kitap, real Wiktionary wins), `serendipity` (English gloss fallback — echoed). Confirm MT rows render and rank below Wiktionary rows in EntryDetail.
2. **Commit** the still-uncommitted pipeline + app changes once in-app behavior is confirmed.

To re-run from scratch later (e.g. after a fresh DB build): `./scripts/build-dictionary.sh` does parse → MT → copy. Models are cached under `data-pipeline/models/` (gitignored); deps are in the venv.

**Note:** code changes (headword-only, distinct-word, threading, resume-safe FTS + VACUUM, schema/ranking, VERSION bump, LICENSES.md) are currently **uncommitted** per the repo owner's call.

### Remaining risks / things to verify

- **Quality on technical/medical/legal jargon is weaker** (opus-mt is general-domain) — but MT rows rank below real translations and the en→en gloss remains, so it degrades gracefully.
- **Storage delta**: spot-check the final DB size after the run; expect ~+20–40 MB (one row per kept word). APK ~510 → ~530–540 MB.
- **Spot-check in the app**, not just the DB — confirm MT rows render and rank below Wiktionary rows in EntryDetail.

## Open questions / decisions deferred

- ~~**Replace or augment en→en rows with MT translations?**~~ **Decided: augment** — MT rows (`source='mt'`) sit above the en→en gloss rows, which stay as a final fallback.
- ~~**Confidence filter / threshold**~~ **Decided: echo-drop only** — back-translation chrF was piloted and rejected (noise on isolated words).
- **PanLex augmentation** as a follow-up after the MT pass — adds dictionary-quality entries on top of MT output. Worth doing if MT quality alone isn't enough.
- **Disk footprint** — 540 MB total install is heavy. Long-term, opening SQLite directly from the APK asset (via [requery/sqlite-android](https://github.com/requery/sqlite-android) + `ParcelFileDescriptor`) would halve the on-device footprint by removing the duplicate copy. Currently deferred.

## Standing rules

- **Commit + push after every meaningful change.** Stop hook enforces the push half.
- **Never commit `app/src/main/assets/dictionary.db`** — too big, regenerate via the script.
- **Class names stayed `TranslateApp` / `TranslateTheme` / etc.** even though the app is now "Dictionary". Internal-only, low value to rename.
- **Use `./gradlew :app:assembleDebug` directly** (no `export JAVA_HOME` prefix) — the global setting handles it.
