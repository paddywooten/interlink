#!/usr/bin/env bash
#
# One-click InterLink build for Linux → .deb + .AppImage
#
# Double-click this file in your file manager (or run it from a terminal):
#
#     ./packaging/linux/build-linux.sh
#
# It checks the WebKitGTK toolchain, installs the frontend dependencies,
# builds the app and leaves the installers in packaging/linux/out/.
#
# One-time prerequisites: Rust (rustup), Node 20+, and the WebKitGTK dev
# packages — see packaging/linux/README.md for the per-distro one-liners.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$HERE/out"
START=$(date +%s)

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
die()  { printf '\n\033[31m%s\033[0m\n\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = "Linux" ] \
    || die "This file builds the Linux installers — run it on Linux.
macOS:   packaging/macos/build-dmg.sh
Windows: packaging\\windows\\build-msi.bat"

command -v node >/dev/null 2>&1 \
    || die "Node.js 20+ is not installed.
Install the LTS from https://nodejs.org (or your distro's package manager), then run this again."

export PATH="$HOME/.cargo/bin:$PATH"
command -v cargo >/dev/null 2>&1 \
    || die "Rust is not installed.
Install it from https://rustup.rs, accept the default toolchain, then run this again."

command -v pkg-config >/dev/null 2>&1 && pkg-config --exists webkit2gtk-4.1 \
    || die "The WebKitGTK development files are missing (webkit2gtk-4.1) — this is
the usual reason a Linux Tauri build fails. Install them once with:

  Debian/Ubuntu:
    sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev \\
      librsvg2-dev libssl-dev libasound2-dev build-essential curl wget file libxdo-dev
  Fedora:
    sudo dnf install webkit2gtk4.1-devel gtk3-devel libayatana-appindicator-gtk3-devel \\
      librsvg2-devel openssl-devel alsa-lib-devel gcc make curl wget file
  Arch:
    sudo pacman -S webkitgtk-4.1 gtk3 libayatana-appindicator librsvg openssl \\
      alsa-lib base-devel curl wget file

then run this file again."

say "[1/4] Repository: $ROOT"
cd "$ROOT/desktop"

say "[2/4] Installing frontend dependencies"
npm install --no-audit --no-fund || die "npm install failed — see the output above."

say "[3/4] Building the app, the .deb and the .AppImage
(first run compiles Rust in release mode; that takes a few minutes)"
npm run tauri build -- --bundles deb,appimage \
    || die "The build failed — see the output above."

say "[4/4] Collecting the installers"
mkdir -p "$OUT"
shopt -s nullglob
bundles=("$ROOT"/desktop/src-tauri/target/release/bundle/deb/*.deb
         "$ROOT"/desktop/src-tauri/target/release/bundle/appimage/*.AppImage)
[ "${#bundles[@]}" -gt 0 ] \
    || die "The build reported success but nothing appeared under
desktop/src-tauri/target/release/bundle/{deb,appimage}/"
rm -f "$OUT"/*.deb "$OUT"/*.AppImage
cp "${bundles[@]}" "$OUT"/

say "Done in $(( $(date +%s) - START ))s — your installers:"
ls -lh "$OUT"/* | sed 's/^/  /'
printf '\n  %s\n' "$OUT"
xdg-open "$OUT" 2>/dev/null || true

cat <<'NOTE'

Install:  double-click the .deb (Debian/Ubuntu, opens in your package manager)
          or the .AppImage (any distro — make it executable first:
          chmod +x InterLink*.AppImage, and install libfuse2 to run it).

The firewall still has to allow TCP/UDP 27341 and UDP 27342 or the phone will
never reach the laptop — see packaging/linux/README.md.
NOTE
