# Linux setup & build

Everything Linux-specific is grouped in this folder. **One double-click of
[`build-linux.sh`](build-linux.sh) produces a `.deb` and an `.AppImage`.**

```bash
# from your file manager: right-click build-linux.sh → Run / Allow launching
# from a terminal:
./packaging/linux/build-linux.sh
```

The script checks the WebKitGTK toolchain, runs `npm install`, runs
`npm run tauri build -- --bundles deb,appimage`, and drops both installers in
`packaging/linux/out/`, opening that folder when it finishes.

| Installer | Use it on |
|---|---|
| `InterLink_*.deb` | Debian / Ubuntu — double-click, your package manager takes over |
| `InterLink_*.AppImage` | any distro — `chmod +x` once, then it just runs (`libfuse2` needed to launch) |

## Prerequisites (one-time)

Toolchain: rustup (`~/.cargo`) + Node 20+ (`~/.local/node` or your distro's).

WebKitGTK dev packages, once:

```bash
# Debian / Ubuntu
sudo apt install libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev \
  librsvg2-dev libssl-dev libasound2-dev build-essential curl wget file libxdo-dev

# Fedora
sudo dnf install webkit2gtk4.1-devel gtk3-devel libayatana-appindicator-gtk3-devel \
  librsvg2-devel openssl-devel alsa-lib-devel gcc make curl wget file

# Arch / CachyOS
sudo pacman -S webkitgtk-4.1 gtk3 libayatana-appindicator librsvg openssl \
  alsa-lib base-devel curl wget file
```

`build-linux.sh` checks `webkit2gtk-4.1` before it starts and prints these
one-liners if it is missing, rather than letting `cargo` fail with a linking
error halfway through.

## Known quirk

WebKitGTK on some GPU/driver combos crashes at window creation
(`Error 71 (Protocol error)`); `desktop/src-tauri/src/lib.rs` already sets
`WEBKIT_DISABLE_DMABUF_RENDERER=1` as the official workaround.

## Firewall

The app listens on TCP **27341**, UDP discovery on **27341**, and LAN presence
beacons on UDP **27342**. The phone must be able to reach them. If your distro
firewall is on, open the ports (the beacon port is optional if you only need
the paired connection, but is required to discover other unpaired devices):

```bash
sudo ufw allow 27341/tcp            # encrypted phone ↔ laptop link
sudo ufw allow 27341/udp            # pairing endpoint discovery
sudo ufw allow 27342/udp            # nearby-device list
sudo firewall-cmd --add-port=27341/tcp --add-port=27341/udp --add-port=27342/udp --permanent
sudo firewall-cmd --reload
```

Full build reference (dev loop, Android half, troubleshooting) lives in
[BUILD.md](../../BUILD.md).
