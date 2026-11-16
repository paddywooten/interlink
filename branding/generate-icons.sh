#!/usr/bin/env bash
# Regenerate every desktop icon (PNG sizes, icon.ico, icon.icns, Windows tiles)
# from branding/icon.svg. The phone's launcher icon is the same artwork, kept as
# the vector in android/app/src/main/res/drawable/ic_launcher_fg.xml.
#
# Needs: rsvg-convert (librsvg) and the desktop app's npm dependencies.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/branding/icon.svg"
PNG="$ROOT/branding/icon-1024.png"
OUT="$ROOT/desktop/src-tauri/icons"

command -v rsvg-convert >/dev/null || { echo "rsvg-convert not found (install librsvg)"; exit 1; }

rsvg-convert -w 1024 -h 1024 "$SRC" -o "$PNG"

cd "$ROOT/desktop"
[ -d node_modules ] || npm install
npx tauri icon "$PNG" --output "$OUT"

# `tauri icon` also writes mobile sets the desktop build never uses; the Android
# app has its own launcher icon, so don't keep a second copy in the tree.
rm -rf "$OUT/android" "$OUT/ios"

# Browser/webview favicon and the sidebar logo use the vector directly.
cp "$SRC" "$ROOT/desktop/public/icon.svg"
echo "icons regenerated from $SRC"
