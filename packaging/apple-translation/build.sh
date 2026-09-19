#!/usr/bin/env bash
set -euo pipefail

if (( $# < 1 || $# > 2 )) || [[ "$1" != *.app ]]; then
  echo "Usage: packaging/apple-translation/build.sh OUTPUT.app [arm64|x86_64]" >&2
  exit 2
fi
if [[ $(uname -s) != Darwin ]]; then
  echo "Building Apple translation requires macOS with Xcode 16 or newer." >&2
  exit 2
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output=$1
architecture=${2:-$(uname -m)}
case "$architecture" in
  arm64|x86_64) ;;
  *) echo "Unsupported Apple translation architecture: $architecture" >&2; exit 2 ;;
esac

sdk=$(xcrun --sdk macosx --show-sdk-path)
sdk_version=$(xcrun --sdk macosx --show-sdk-version)
if (( ${sdk_version%%.*} < 15 )); then
  echo "Apple translation requires the macOS 15 SDK or newer (Xcode 16+)." >&2
  exit 2
fi

mkdir -p "$output/Contents/MacOS"
cp "$script_dir/Info.plist" "$output/Contents/Info.plist"
xcrun --sdk macosx swiftc -swift-version 5 -parse-as-library -O \
  -target "$architecture-apple-macosx15.0" -sdk "$sdk" \
  -framework AppKit -framework SwiftUI -framework Vision -framework NaturalLanguage \
  -framework Translation -framework ImageIO \
  "$script_dir/Sources/TranslationCore.swift" \
  "$script_dir/Sources/TranslationHelper.swift" \
  "$script_dir/Sources/SelfTest.swift" \
  -o "$output/Contents/MacOS/CoffeeGBTranslation"
chmod 755 "$output/Contents/MacOS/CoffeeGBTranslation"
/usr/libexec/PlistBuddy -c 'Print :LSMinimumSystemVersion' "$output/Contents/Info.plist" >/dev/null
codesign --force --sign - "$output"
codesign --verify --strict "$output"
echo "Built $output ($architecture, macOS 15+)"
