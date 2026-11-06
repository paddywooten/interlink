#!/usr/bin/env bash
#
# InterLink - one-click Linux build
#
# Double-click this file in your file manager (right-click → Run / Allow
# launching), or run it from a terminal:
#
#     ./InterLink-Setup-Linux/build-linux.sh
#
# It runs the Linux build script in packaging/linux/ and leaves the installers
# in packaging/linux/out/:
#
#     InterLink_*.deb        Debian / Ubuntu (menu entry included)
#     InterLink_*.AppImage   any distro (see install-launcher.sh below)
#
# Prerequisites are listed in README.md in this folder and in
# packaging/linux/README.md.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SCRIPT="$ROOT/packaging/linux/build-linux.sh"

if [ ! -x "$SCRIPT" ]; then
    printf '\n\033[31mCould not find the build script:\033[0m\n  %s\n\n' "$SCRIPT" >&2
    printf 'Keep this folder inside the InterLink repository, next to\n'
    printf '"packaging" and "desktop".\n\n'
    exit 1
fi

exec "$SCRIPT" "$@"
