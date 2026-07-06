#!/usr/bin/env bash
# Build the offline dictionary.db that the Android app ships in assets/.
#
# Usage:
#   ./scripts/build-dictionary.sh                  # default: TR + EN headwords, then MT gap-fill
#   ./scripts/build-dictionary.sh --tr-only        # smaller build, Turkish source only (~15 MB)
#   ./scripts/build-dictionary.sh --no-translate-gap   # skip the OPUS-MT step (fast)
#   ./scripts/build-dictionary.sh --mt-limit 10000     # pilot the MT step on N rows only
#   ./scripts/build-dictionary.sh --translate-llm      # ALSO run the paid Claude per-sense pass
#   ./scripts/build-dictionary.sh --panlex             # ALSO fill residual gaps with PanLex (free, CC0)
#   ./scripts/build-dictionary.sh --no-tdk             # skip the Turkish→Turkish TDK layer (on by default)
#   ./scripts/build-dictionary.sh --smoke          # also run sample-lookup smoke tests
#
# What it does:
#   1. Creates data-pipeline/.venv if missing and installs tqdm.
#   2. Runs build_dictionary (downloading Kaikki dumps into data-pipeline/cache on first run).
#   3. Unless --no-translate-gap: fills the en→en gloss gap with OPUS-MT (Helsinki-NLP) en→tr
#      translations via translate_gap (installs ctranslate2/transformers/torch into the venv,
#      converts the model to CTranslate2 int8 on first run). This is the long step — hours on
#      CPU for the full ~1.67M rows; it is resumable, so re-running continues where it stopped.
#   4. With --translate-llm (OFF by default — it costs money): runs translate_llm, a per-sense
#      Claude/Haiku pass over the English gloss set via the Anthropic Message Batches API. Needs
#      ANTHROPIC_API_KEY (with billing) in the environment. Pilot it standalone first:
#      cd data-pipeline && python -m translate_llm --limit 300 --sample 40
#   5. Unless --no-tdk: adds the Turkish→Turkish layer (ingest_tdk) from the TDK Güncel Türkçe
#      Sözlük (~99k headwords, incl. archaic/Ottoman "eskimiş" words). Downloads a ~12 MB dump into
#      cache/ on first run; augments the DB in place, so it works in any mode without a reparse.
#   6. Copies the resulting dictionary.db into app/src/main/assets/.
#
# Disk + bandwidth notes:
#   - Default mode pulls a ~2.9 GB English Wiktionary dump on first run; final db is ~480 MB
#     (~+30-50 MB after the MT pass).
#   - --tr-only skips the English dump (~360 MB) and produces a ~15 MB db (MT step is a no-op).
#   - Both modes cache downloads under data-pipeline/cache/ (gitignored).

set -euo pipefail

script_path="${BASH_SOURCE[0]}"
repo_root="$(cd "$(dirname "$script_path")/.." && pwd)"

include_en=1
translate_gap=1
translate_llm=0
panlex=0
tdk=1
mt_limit=""
smoke=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tr-only)          include_en=0 ;;
    --no-translate-gap) translate_gap=0 ;;
    --translate-llm)    translate_llm=1 ;;
    --panlex)           panlex=1 ;;
    --no-tdk)           tdk=0 ;;
    --mt-limit)         shift; mt_limit="${1:?--mt-limit needs a value}" ;;
    --smoke)            smoke="--smoke" ;;
    -h|--help)
      sed -n '2,30p' "$script_path" | sed 's/^# \{0,1\}//'
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

if [[ ! -x .venv/bin/python3 ]]; then
  echo "==> Creating data-pipeline/.venv"
  python3 -m venv .venv
  .venv/bin/pip install --quiet --upgrade pip
  .venv/bin/pip install --quiet tqdm
fi

flags=()
[[ $include_en -eq 1 ]] && flags+=(--include-en)
[[ -n $smoke ]] && flags+=($smoke)

echo "==> Building dictionary.db ${flags[*]:-(turkish only)}"
.venv/bin/python3 -m build_dictionary "${flags[@]}"

if [[ $translate_gap -eq 1 && $include_en -eq 1 ]]; then
  echo "==> Ensuring MT dependencies (ctranslate2, transformers, sentencepiece, sacrebleu, torch)"
  .venv/bin/pip install --quiet ctranslate2 transformers sentencepiece sacrebleu torch
  mt_flags=(--prune)  # drop en→en rows superseded by an MT translation (#12), shrinks the DB
  [[ -n $mt_limit ]] && mt_flags+=(--limit "$mt_limit")
  echo "==> Filling en→en gap with OPUS-MT + prune ${mt_flags[*]}"
  .venv/bin/python3 -m translate_gap "${mt_flags[@]}"
elif [[ $translate_gap -eq 0 ]]; then
  echo "==> Skipping OPUS-MT gap-fill (--no-translate-gap)"
fi

if [[ $translate_llm -eq 1 && $include_en -eq 1 ]]; then
  if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
    echo "ERROR: --translate-llm needs ANTHROPIC_API_KEY (with billing) in the environment." >&2
    exit 2
  fi
  echo "==> Ensuring Claude dependency (anthropic)"
  .venv/bin/pip install --quiet anthropic
  echo "==> Per-sense Claude translation + prune (Message Batches API; costs tokens)"
  .venv/bin/python3 -m translate_llm --prune
fi

if [[ $panlex -eq 1 && $include_en -eq 1 ]]; then
  echo "==> Filling residual gaps with PanLex (CC0; downloads ~800 MB of eng/tur TSVs into cache/)"
  .venv/bin/python3 -m translate_panlex --residual-only
fi

if [[ $tdk -eq 1 ]]; then
  echo "==> Adding Turkish→Turkish definitions from TDK Güncel Türkçe Sözlük (ingest_tdk)"
  .venv/bin/python3 -m ingest_tdk
else
  echo "==> Skipping TDK tr→tr layer (--no-tdk)"
fi

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
