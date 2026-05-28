#!/usr/bin/env bash
# Build the offline dictionary.db that the Android app ships in assets/.
#
# Usage:
#   ./scripts/build-dictionary.sh             # default: TR + EN headwords (recommended)
#   ./scripts/build-dictionary.sh --tr-only   # smaller build, Turkish source only (~15 MB)
#   ./scripts/build-dictionary.sh --smoke     # also run sample-lookup smoke tests
#
# What it does:
#   1. Creates data-pipeline/.venv if missing and installs tqdm.
#   2. Runs build_dictionary (downloading Kaikki dumps into data-pipeline/cache on first run).
#   3. Copies the resulting dictionary.db into app/src/main/assets/.
#
# Disk + bandwidth notes:
#   - Default mode pulls a ~2.9 GB English Wiktionary dump on first run; final db is ~480 MB.
#   - --tr-only skips the English dump (~360 MB) and produces a ~15 MB db.
#   - Both modes cache downloads under data-pipeline/cache/ (gitignored).

set -euo pipefail

script_path="${BASH_SOURCE[0]}"
repo_root="$(cd "$(dirname "$script_path")/.." && pwd)"

include_en=1
smoke=""
for arg in "$@"; do
  case "$arg" in
    --tr-only)    include_en=0 ;;
    --smoke)      smoke="--smoke" ;;
    -h|--help)
      sed -n '2,18p' "$script_path" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "Unknown argument: $arg" >&2
      echo "Run with --help for usage." >&2
      exit 2
      ;;
  esac
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
