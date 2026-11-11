#!/bin/bash
set -euo pipefail

notch_source_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
notch_app_dir="${1:?Usage: bash build.sh /absolute/output/AuralisNotch.app}"
notch_sdk="$(xcrun --sdk macosx --show-sdk-path)"
notch_icon="$notch_source_dir/Auralis.icns"
if [[ ! -f "$notch_icon" ]]; then notch_icon="$notch_source_dir/../icons/auralis.icns"; fi
mkdir -p "$notch_app_dir/Contents/MacOS" "$notch_app_dir/Contents/Resources"
xcrun swiftc -O -swift-version 5 -parse-as-library -target arm64-apple-macos14.0 -sdk "$notch_sdk" \
    -framework AppKit -framework SwiftUI \
    "$notch_source_dir"/*.swift "$notch_source_dir"/Upstream/*.swift \
    -o "$notch_app_dir/Contents/MacOS/AuralisNotch"
cp "$notch_source_dir/Info.plist" "$notch_app_dir/Contents/Info.plist"
cp "$notch_icon" "$notch_app_dir/Contents/Resources/Auralis.icns"
# Ship corresponding native source, build instructions, provenance and licenses with the binary.
notch_source_copy="$notch_app_dir/Contents/Resources/Source"
mkdir -p "$notch_source_copy/Upstream"
cp "$notch_source_dir"/*.swift "$notch_source_dir"/*.md "$notch_source_dir"/Info.plist \
    "$notch_source_dir"/build.sh "$notch_source_dir"/LICENSE "$notch_source_dir"/THIRD_PARTY_LICENSES "$notch_source_copy/"
cp "$notch_source_dir"/Upstream/*.swift "$notch_source_copy/Upstream/"
cp "$notch_icon" "$notch_source_copy/Auralis.icns"
codesign --force --sign - "$notch_app_dir"
