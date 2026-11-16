#!/usr/bin/env bash
#
# InterLink - one-click browser version build
#
# Double-click this file in your file manager (right-click → Run / Allow
# launching), or run it from a terminal:
#
#     ./InterLink-Setup-Web/build-web.sh
#
# It runs the browser build script in packaging/web/ and leaves the program in
# packaging/web/out/interlink-web. Run that program, open the link it prints,
# and the InterLink UI appears in your browser. No window toolkit is needed.
#
# Prerequisites are listed in README.md here and in packaging/web/README.md.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
SCRIPT="$ROOT/packaging/web/build-web.sh"

if [ ! -x "$SCRIPT" ]; then
    printf '\n\033[31mCould not find the build script:\033[0m\n  %s\n\n' "$SCRIPT" >&2
    printf 'Keep this folder inside the InterLink repository, next to\n'
    printf '"packaging" and "desktop".\n\n'
    exit 1
fi

exec "$SCRIPT" "$@"
