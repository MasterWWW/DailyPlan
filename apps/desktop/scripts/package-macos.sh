#!/bin/sh
set -eu

if [ "$(uname -s)" != "Darwin" ]; then
  echo "package-macos.sh only runs on macOS" >&2
  exit 1
fi

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$root"

pnpm tauri build --bundles app

app="$root/src-tauri/target/release/bundle/macos/DailyPlan.app"
dmg="$root/src-tauri/target/release/bundle/dmg/DailyPlan_0.1.0_aarch64.dmg"
if [ ! -d "$app" ]; then
  echo "missing app bundle: $app" >&2
  exit 1
fi

mkdir -p "$(dirname "$dmg")"
hdiutil create \
  -volname DailyPlan \
  -srcfolder "$app" \
  -ov \
  -format UDZO \
  "$dmg"
echo "app: $app"
echo "dmg: $dmg"
