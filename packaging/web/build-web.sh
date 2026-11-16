#!/usr/bin/env bash
#
# InterLink - build the browser version (interlink-web)
#
# The browser version is the same laptop backend as the desktop app, with the
# UI served to your browser instead of drawn in a native window. Run this on
# the computer that will be paired with the phone; it builds a single
# executable for *this* OS and leaves it in packaging/web/out/.
#
# Prerequisites (the script names whichever is missing):
#   Node.js 20+     https://nodejs.org
#   Rust (stable)   https://rustup.rs
#   Linux only:     ALSA headers — Debian/Ubuntu: sudo apt install libasound2-dev
#                                  Fedora: sudo dnf install alsa-lib-devel
#                                  Arch:   sudo pacman -S alsa-lib
#
# No GTK, WebKit or other windowing toolkit is needed: the UI runs in whatever
# browser you already have.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
DESKTOP="$ROOT/desktop"

die() {
    printf '\n\033[31m%s\033[0m\n\n' "$1" >&2
    exit 1
}

need() {
    command -v "$1" >/dev/null 2>&1 \
        || die "Missing $1 — install $2, then run this again."
}

if [ ! -d "$DESKTOP/src-tauri" ]; then
    die "Could not find desktop/src-tauri. Keep packaging/ inside the InterLink
repository, next to desktop/ and android/."
fi

need node "Node.js 20+ (https://nodejs.org)"
need npm "npm (it comes with Node.js)"
need cargo "Rust from (https://rustup.rs)"

printf '\nInterLink — browser version\n===========================\n\n'

printf '==> frontend (React → static files)\n'
cd "$DESKTOP"
npm install --no-audit --no-fund
npm run build

printf '\n==> laptop backend + web server (release)\n'
cd "$DESKTOP/src-tauri"
cargo build --release --no-default-features --features web --bin interlink-web

mkdir -p "$HERE/out"
cp "target/release/interlink-web" "$HERE/out/interlink-web"

printf '\n\033[32mBuilt:\033[0m %s\n' "$HERE/out/interlink-web"
printf '\nRun it:\n  "%s"\n' "$HERE/out/interlink-web"
printf '\nIt prints a link (http://127.0.0.1:27350/?t=…). Open that link: the\n'
printf 'InterLink UI appears in your browser and pairs with the phone exactly\n'
printf 'like the desktop app. Only one InterLink laptop program can own the\n'
printf 'phone link (TCP/UDP 27341) at a time, so close the desktop app first.\n\n'
