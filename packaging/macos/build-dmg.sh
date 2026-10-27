#!/usr/bin/env bash
#
# One-click InterLink build for macOS → InterLink_*.dmg
#
# Double-click this file in Finder (macOS asks you to right-click → Open the
# first time), or run it from a terminal:
#
#     ./packaging/macos/build-dmg.sh
#
# It checks the toolchain, installs the frontend dependencies, builds the app
# and leaves the installer in packaging/macos/out/.
#
# One-time prerequisites: xcode-select --install, Node 20+, rustup — see
# packaging/macos/README.md.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out"
START=$(date +%s)

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
die()  { printf '\n\033[31m%s\033[0m\n\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Darwin" ] \
    || die "This file builds the macOS .dmg — run it on a Mac.
Linux:  packaging/linux/build-linux.sh
Windows: packaging\\windows\\build-msi.bat"

command -v xcode-select >/dev/null 2>&1 \
    || die "Xcode Command Line Tools are not installed.
Run this once, then double-click this file again:
    xcode-select --install"
xcode-select -p >/dev/null 2>&1 \
    || die "The Command Line Tools are installed but not selected.
Run this once, then double-click this file again:
    sudo xcode-select --switch /Library/Developer/CommandLineTools"

command -v node >/dev/null 2>&1 \
    || die "Node.js 20+ is not installed.
Install the LTS from https://nodejs.org (or: brew install node), then run this again."

export PATH="$HOME/.cargo/bin:$PATH"
command -v cargo >/dev/null 2>&1 \
    || die "Rust is not installed.
Install it from https://rustup.rs, accept the default toolchain, then run this again."

say "[1/4] Repository: $ROOT"
cd "$ROOT/desktop"

say "[2/4] Installing frontend dependencies"
npm install --no-audit --no-fund || die "npm install failed — see the output above."

say "[3/4] Building the app and the .dmg
(first run compiles Rust in release mode; that takes a few minutes)"
npm run tauri build -- --bundles dmg || die "The build failed — see the output above."

say "[4/4] Collecting the installer"
mkdir -p "$OUT"
shopt -s nullglob
dmgs=("$ROOT"/desktop/src-tauri/target/release/bundle/dmg/*.dmg)
[ "${#dmgs[@]}" -gt 0 ] \
    || die "The build reported success but no .dmg appeared in
desktop/src-tauri/target/release/bundle/dmg/"
rm -f "$OUT"/*.dmg
cp "${dmgs[@]}" "$OUT"/

say "Done in $(( $(date +%s) - START ))s — your installer:"
ls -lh "$OUT"/*.dmg | sed 's/^/  /'
printf '\n  %s\n' "$OUT"
open "$OUT" 2>/dev/null || true

cat <<'NOTE'

Install: open the .dmg and drag InterLink into Applications.

First launch: right-click → Open (the build is ad-hoc signed, not notarised),
then allow the two permissions macOS asks for — Microphone for call audio, and
Local Network, without which the phone can never connect.
NOTE
