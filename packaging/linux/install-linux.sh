#!/usr/bin/env bash
#
# Put InterLink in this user's application menu (and on the desktop).
#
#   ./packaging/linux/install-linux.sh                  # newest built AppImage
#   ./packaging/linux/install-linux.sh InterLink.AppImage
#   ./packaging/linux/install-linux.sh --uninstall
#
# No root needed: the app and its launcher land under ~/.local. An AppImage
# does not register itself with the desktop, which is why closing the window
# makes the app seem to vanish — this creates the icon you can click to open
# it again.
#
# The .deb is still the system-wide option (it installs a menu entry by
# itself). Use this script when you run the AppImage, or on a distro without
# dpkg.

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

BIN_DIR="${XDG_BIN_HOME:-$HOME/.local/bin}"
DATA_DIR="${XDG_DATA_HOME:-$HOME/.local/share}"
APPS_DIR="$DATA_DIR/applications"
ICON_ROOT="$DATA_DIR/icons/hicolor"
TARGET="$BIN_DIR/InterLink.AppImage"
MENU_FILE="$APPS_DIR/interlink.desktop"
SHORTCUT="$HOME/Desktop/InterLink.desktop"
ICON_SIZES=(32 128 256 512)

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
die() { printf '\n\033[31m%s\033[0m\n\n' "$*" >&2; exit 1; }

UNINSTALL=0
NO_SHORTCUT=0
APPIMAGE=""
for arg in "$@"; do
    case "$arg" in
        --uninstall) UNINSTALL=1 ;;
        --no-desktop-shortcut) NO_SHORTCUT=1 ;;
        -*) die "Unknown option: $arg" ;;
        *) APPIMAGE="$arg" ;;
    esac
done

refresh_caches() {
    command -v update-desktop-database >/dev/null 2>&1 \
        && update-desktop-database "$APPS_DIR" 2>/dev/null || true
    # Optional: user icon directories work without a cache; this only helps
    # desktops that have one.
    command -v gtk-update-icon-cache >/dev/null 2>&1 \
        && gtk-update-icon-cache -f -t "$ICON_ROOT" 2>/dev/null || true
}

if [ "$UNINSTALL" = "1" ]; then
    rm -f "$TARGET" "$MENU_FILE" "$SHORTCUT"
    for size in "${ICON_SIZES[@]}"; do
        rm -f "$ICON_ROOT/${size}x${size}/apps/interlink.png"
    done
    refresh_caches
    say "InterLink removed from this account's launcher."
    printf '  (Your pairing secret in ~/.config/com.interlink.desktop was left alone.)\n'
    exit 0
fi

if [ -z "$APPIMAGE" ]; then
    shopt -s nullglob
    candidates=("$HERE/out/"*.AppImage)
    [ "${#candidates[@]}" -gt 0 ] \
        || die "No AppImage found in packaging/linux/out/.

Build one first:
    ./packaging/linux/build-linux.sh

or point at the file directly:
    $0 /path/to/InterLink.AppImage"
    APPIMAGE="${candidates[0]}"
    for f in "${candidates[@]}"; do
        [ "$f" -nt "$APPIMAGE" ] && APPIMAGE="$f"
    done
fi
[ -f "$APPIMAGE" ] || die "Not a file: $APPIMAGE"

say "Installing $(basename "$APPIMAGE") for this account"
mkdir -p "$BIN_DIR" "$APPS_DIR" "$ICON_ROOT"
install -m 0755 "$APPIMAGE" "$TARGET"

# Icons come from the repo so the menu entry looks right even if the AppImage
# carries only one size. 512px is the dock/app-menu size Plasma asks for.
declare -A SRC=(
    [32]="$ROOT/desktop/src-tauri/icons/32x32.png"
    [128]="$ROOT/desktop/src-tauri/icons/128x128.png"
    [256]="$ROOT/desktop/src-tauri/icons/128x128@2x.png"
    [512]="$ROOT/desktop/src-tauri/icons/icon.png"
)
for size in "${ICON_SIZES[@]}"; do
    [ -f "${SRC[$size]}" ] || continue
    mkdir -p "$ICON_ROOT/${size}x${size}/apps"
    install -m 0644 "${SRC[$size]}" "$ICON_ROOT/${size}x${size}/apps/interlink.png"
done

cat > "$MENU_FILE" <<EOF
[Desktop Entry]
Type=Application
Name=InterLink
GenericName=Phone link
Comment=Link an Android phone to this laptop: notifications, calls, files, screen
Exec="$TARGET" %U
TryExec=$TARGET
Icon=interlink
Terminal=false
Categories=Utility;
Keywords=android;phone;notifications;files;calls;screen;mirror;clipboard;
StartupWMClass=Interlink
EOF
chmod 0644 "$MENU_FILE"

# Most people look on the desktop for an icon, so drop one there too when the
# folder exists. Plasma needs it marked executable/trusted to launch in place.
if [ -d "$HOME/Desktop" ] && [ "$NO_SHORTCUT" = "0" ]; then
    cp "$MENU_FILE" "$SHORTCUT"
    chmod +x "$SHORTCUT"
    gio set "$SHORTCUT" metadata::trusted true 2>/dev/null || true
fi

refresh_caches

say "Done — InterLink is in your application menu."
printf '  menu entry: %s\n  app:        %s\n' "$MENU_FILE" "$TARGET"
[ -f "$SHORTCUT" ] && printf '  desktop:    %s\n' "$SHORTCUT"
printf '\nClick it once in the menu, then pin it to the taskbar if your desktop\n'
printf 'supports that. To remove the launcher again:\n  %s --uninstall\n' "$0"
