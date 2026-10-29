#!/bin/bash
#
# Double-click this file in Finder to build InterLink's macOS installer.
#
# Finder cannot run a .sh file — it opens it in an editor — so this .command
# shim is the double-clickable entry point. Terminal opens it for you and runs
# build-dmg.sh beside it, which does the actual work: check the toolchain,
# npm install, `tauri build --bundles dmg`, and leave InterLink_*.dmg in
# packaging/macos/out/.
#
# From a terminal, either file works:
#
#     ./packaging/macos/build-dmg.command

exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build-dmg.sh" "$@"
