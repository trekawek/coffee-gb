#!/usr/bin/env bash
set -euo pipefail

if (( $# > 1 )); then
  echo "Usage: packaging/apple-translation/test.sh [CoffeeGBTranslation.app]" >&2
  exit 2
fi
if [[ $(uname -s) != Darwin ]]; then
  echo "Apple translation native tests require macOS 15 or newer." >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
temporary=
cleanup() {
  if [[ -n "$temporary" ]]; then rm -rf -- "$temporary"; fi
}
trap cleanup EXIT
if (( $# == 1 )); then
  app=$1
else
  temporary=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-translation-test.XXXXXX")
  app="$temporary/CoffeeGBTranslation.app"
  "$script_dir/build.sh" "$app"
fi

codesign --verify --strict "$app"
python3 - "$app/Contents/MacOS/CoffeeGBTranslation" <<'PY'
import base64
import json
import struct
import subprocess
import sys
import zlib

executable = sys.argv[1]
result = subprocess.run([executable, "--self-test"], capture_output=True, text=True, timeout=60, check=True)
assert json.loads(result.stdout) == {"event": "self_test", "ok": True}, result.stdout

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff)

png = b"\x89PNG\r\n\x1a\n"
png += chunk(b"IHDR", struct.pack(">IIBBBBB", 160, 144, 8, 2, 0, 0, 0))
png += chunk(b"IDAT", zlib.compress((b"\x00" + b"\xff\xff\xff" * 160) * 144))
png += chunk(b"IEND", b"")

def request(value):
    result = subprocess.run([executable], input=json.dumps(value) + "\n", capture_output=True,
                            text=True, timeout=30, check=True)
    return [json.loads(line) for line in result.stdout.splitlines()]

assert request({"version": 1, "image": base64.b64encode(png).decode(), "width": 160, "height": 144}) == [
    {"event": "result", "regions": []}
]
assert request({"version": 2, "image": "invalid", "width": 160, "height": 144}) == [
    {"event": "error", "code": "invalid_request"}
]
print("Apple translation protocol, geometry, language selection, blank OCR and process smoke tests passed.")
PY
