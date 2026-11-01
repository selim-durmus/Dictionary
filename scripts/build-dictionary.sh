#!/usr/bin/env bash
# Build the offline dictionary.db that the Android app ships in assets/.
#
# Usage:
#   ./scripts/build-dictionary.sh                  # default: TR + EN, all free sources, then MT
#   ./scripts/build-dictionary.sh --tr-only        # smaller build, Turkish source only (~15 MB)
#   ./scripts/build-dictionary.sh --no-translate-gap   # skip the OPUS-MT step (fast)
#   ./scripts/build-dictionary.sh --mt-limit 10000     # pilot the MT step on N rows only
#   ./scripts/build-dictionary.sh --translate-llm      # ALSO run the paid Claude per-sense pass
#   ./scripts/build-dictionary.sh --no-panlex          # skip PanLex (on by default; ~800 MB download)
#   ./scripts/build-dictionary.sh --no-tdk             # skip the Turkish→Turkish TDK layer (on by default)
#   ./scripts/build-dictionary.sh --smoke          # also run sample-lookup smoke tests
#
# What it does (each step augments build/dictionary.db in place):
#   1. Creates data-pipeline/.venv if missing and installs tqdm.
#   2. build_dictionary: parses the Kaikki English-edition dumps (downloaded into cache/ on first
#      run). Grammar-note senses ("simple past of go") get category 'Inflection'.
#   3. ingest_tdk (unless --no-tdk): Turkish→Turkish definitions from the TDK Güncel Türkçe Sözlük.
#   4. ingest_trwiktionary: the Turkish Wiktionary edition — English words defined in Turkish and
#      Turkish words' English translation tables (~45 MB download).
#   5. derive_reverse: existing Wiktionary pairs read backwards in both directions.
#   6. translate_panlex (unless --no-panlex): PanLex CC0 pairs for words still without Turkish.
#   7. translate_gap (unless --no-translate-gap): OPUS-MT for single words that STILL have no
#      Turkish from any source — the last-resort tier. Hours on CPU; resumable.
#   8. translate_llm (only with --translate-llm; costs money, needs ANTHROPIC_API_KEY).
#   9. ingest_forms: the inflected-form → lemma table the app uses for "kitabı → kitap".
#  10. Copies the resulting dictionary.db into app/src/main/assets/.
#
# Measure the result with:  cd data-pipeline && python -m coverage_report --words
#
# Disk + bandwidth notes:
#   - Default mode pulls a ~3.3 GB English Wiktionary dump on first run.
#   - --tr-only skips the English dump and produces a ~15 MB db (EN-only steps are skipped).
#   - All downloads are cached under data-pipeline/cache/ (gitignored).

set -euo pipefail

script_path="${BASH_SOURCE[0]}"
repo_root="$(cd "$(dirname "$script_path")/.." && pwd)"

include_en=1
translate_gap=1
translate_llm=0
panlex=1
tdk=1
mt_limit=""
smoke=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tr-only)          include_en=0 ;;
    --no-translate-gap) translate_gap=0 ;;
    --translate-llm)    translate_llm=1 ;;
    --panlex)           panlex=1 ;;  # kept for old invocations; now the default
    --no-panlex)        panlex=0 ;;
    --no-tdk)           tdk=0 ;;
    --mt-limit)         shift; mt_limit="${1:?--mt-limit needs a value}" ;;
    --smoke)            smoke="--smoke" ;;
    -h|--help)
      sed -n '2,35p' "$script_path" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Run with --help for usage." >&2
      exit 2
      ;;
  esac
  shift
done

cd "$repo_root/data-pipeline"

# venv layout differs: bin/python3 on macOS/Linux, Scripts/python.exe on Windows (Git Bash).
venv_python() {
  if [[ -x .venv/bin/python3 ]]; then echo .venv/bin/python3
  elif [[ -x .venv/Scripts/python.exe ]]; then echo .venv/Scripts/python.exe
  fi
}
if [[ -z "$(venv_python)" ]]; then
  echo "==> Creating data-pipeline/.venv"
  python3 -m venv .venv || python -m venv .venv
  "$(venv_python)" -m pip install --quiet --upgrade pip
  "$(venv_python)" -m pip install --quiet tqdm
fi
py=("$(venv_python)" -X utf8)

flags=()
[[ $include_en -eq 1 ]] && flags+=(--include-en)
[[ -n $smoke ]] && flags+=($smoke)

echo "==> Building dictionary.db ${flags[*]:-(turkish only)}"
"${py[@]}" -m build_dictionary "${flags[@]}"

if [[ $tdk -eq 1 ]]; then
  echo "==> Adding Turkish→Turkish definitions from TDK Güncel Türkçe Sözlük (ingest_tdk)"
  "${py[@]}" -m ingest_tdk --sample 0
else
  echo "==> Skipping TDK tr→tr layer (--no-tdk)"
fi

if [[ $include_en -eq 1 ]]; then
  echo "==> Adding the Turkish Wiktionary edition (ingest_trwiktionary)"
  "${py[@]}" -m ingest_trwiktionary
  echo "==> Deriving reverse pairs from Wiktionary (derive_reverse)"
  "${py[@]}" -m derive_reverse
fi

if [[ $panlex -eq 1 && $include_en -eq 1 ]]; then
  echo "==> Filling residual gaps with PanLex (CC0; downloads ~800 MB of eng/tur TSVs into cache/)"
  "${py[@]}" -m translate_panlex --residual-only
fi

if [[ $translate_gap -eq 1 && $include_en -eq 1 ]]; then
  echo "==> Ensuring MT dependencies (ctranslate2, transformers, sentencepiece, sacrebleu, torch)"
  "${py[@]}" -m pip install --quiet ctranslate2 transformers sentencepiece sacrebleu torch
  mt_flags=()
  [[ -n $mt_limit ]] && mt_flags+=(--limit "$mt_limit")
  # No --prune: an MT row is a guess, so the word keeps its English gloss next to it.
  echo "==> OPUS-MT for words still without any Turkish ${mt_flags[*]:-}"
  "${py[@]}" -m translate_gap "${mt_flags[@]}"
elif [[ $translate_gap -eq 0 ]]; then
  echo "==> Skipping OPUS-MT gap-fill (--no-translate-gap)"
fi

if [[ $translate_llm -eq 1 && $include_en -eq 1 ]]; then
  if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
    echo "ERROR: --translate-llm needs ANTHROPIC_API_KEY (with billing) in the environment." >&2
    exit 2
  fi
  echo "==> Ensuring Claude dependency (anthropic)"
  "${py[@]}" -m pip install --quiet anthropic
  echo "==> Per-sense Claude translation (Message Batches API; costs tokens)"
  "${py[@]}" -m translate_llm
fi

echo "==> Building the inflected-form → lemma table (ingest_forms)"
form_flags=()
[[ $include_en -eq 0 ]] && form_flags+=(--no-english)
"${py[@]}" -m ingest_forms "${form_flags[@]}"

echo "==> Adding Turkish forms Wiktionary lacks (ingest_zemberek; needs Java 17)"
"${py[@]}" -m ingest_zemberek

echo "==> Linking common Turkish misspellings (ingest_spelling)"
"${py[@]}" -m ingest_spelling

src="$repo_root/data-pipeline/build/dictionary.db"
dest="$repo_root/app/src/main/assets/dictionary.db"
if [[ ! -f $src ]]; then
  echo "Build did not produce $src" >&2
  exit 1
fi

mkdir -p "$(dirname "$dest")"
cp "$src" "$dest"
size=$(du -h "$dest" | cut -f1)
echo "==> Installed $dest ($size)"
echo "Rebuild the Android app in Android Studio to pick up the new asset."
