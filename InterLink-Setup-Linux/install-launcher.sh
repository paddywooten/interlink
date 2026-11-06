#!/usr/bin/env bash
#
# InterLink - one-click Linux launcher install (AppImage only, no root)
#
# After build-linux.sh has produced an AppImage, double-click this file (or run
# it) once. It copies the AppImage into ~/.local/bin and adds the InterLink
# entry to your application menu, the desktop shortcut and the icons — so the
# app is still there after you close the window.
#
# The .deb installs its own menu entry; you do not need this for the .deb.
# Remove the launcher again with:
#
#     ./InterLink-Setup-Linux/install-launcher.sh --uninstall

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SCRIPT="$ROOT/packaging/linux/install-linux.sh"

if [ ! -x "$SCRIPT" ]; then
    printf '\n\033[31mCould not find the launcher installer:\033[0m\n  %s\n\n' "$SCRIPT" >&2
    printf 'Keep this folder inside the InterLink repository, next to\n'
    printf '"packaging" and "desktop".\n\n'
    exit 1
fi

exec "$SCRIPT" "$@"
