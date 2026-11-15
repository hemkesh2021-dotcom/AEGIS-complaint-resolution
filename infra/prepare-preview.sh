#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/.local/embedding/model.onnx"
URL="https://media.githubusercontent.com/media/spring-projects/spring-ai/v1.0.9/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx"
# The Git LFS digest packaged in spring-ai-transformers 1.0.9.
EXPECTED="e3dde332c13808c718680e7bf74a574e7e5d06f55bd6e1527e51509dcb8206f3"

digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

if [[ -f "$DEST" ]] && [[ "$(digest "$DEST")" == "$EXPECTED" ]]; then
  printf 'Local embedding model is ready (checksum verified).\n'
  exit 0
fi
mkdir -p "$(dirname "$DEST")"
TEMP="$(mktemp "$DEST.XXXXXX")"
trap 'rm -f "$TEMP"' EXIT
curl --fail --location --retry 2 --connect-timeout 15 --max-time 240 --output "$TEMP" "$URL"
if [[ "$(digest "$TEMP")" != "$EXPECTED" ]]; then
  printf 'Embedding model checksum mismatch; refusing to install.\n' >&2
  exit 1
fi
chmod 644 "$TEMP"
mv "$TEMP" "$DEST"
printf 'Local embedding model installed (checksum verified).\n'
