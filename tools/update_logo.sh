#!/usr/bin/env bash
# Install a new logo (file path or https URL) and regenerate all derived assets.
#   tools/update_logo.sh https://jain.firstdj.com/image/logo.png
#   tools/update_logo.sh ~/Downloads/logo.png
set -euo pipefail

SRC="${1:?usage: tools/update_logo.sh <file-or-https-url>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$(mktemp)"
trap 'rm -f "$TMP"' EXIT

if [[ "$SRC" =~ ^https:// ]]; then
  curl -fsSL --retry 3 --max-time 30 -o "$TMP" "$SRC"
elif [[ "$SRC" =~ ^http:// ]]; then
  echo "Refusing plain-http download; use https." >&2; exit 1
else
  cp "$SRC" "$TMP"
fi

python3 "$ROOT/tools/generate_assets.py" "$TMP"
