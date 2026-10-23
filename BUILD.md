# Building InterLink

InterLink has two halves:

| Part | Tech | Runs on |
|---|---|---|
| Laptop app (`desktop/`) | Tauri 2 (Rust + React/TS) | Linux, **Windows**, **macOS** |
| Phone app (`android/`) | Kotlin + Jetpack Compose | Android 8.0+ (minSdk 26) |

The wire protocol is identical on every OS — see `PROTOCOL.md`.

## Linux (reference platform)

```fish
# toolchain: rustup + Node 20+ (any distro package manager)
cd desktop
npm install
npm run tauri dev      # develop
npm run tauri build    # .deb/.rpm/AppImage in src-tauri/target/release/bundle/
```

Known quirk: WebKitGTK on some GPU/driver combos crashes at window creation
(`Error 71 (Protocol error)`); `src/lib.rs` already sets
`WEBKIT_DISABLE_DMABUF_RENDERER=1` as the official workaround.

Firewall: the app listens on TCP **27341**, UDP discovery on **27341**, and LAN
presence beacons on UDP **27342**. The phone must be able to reach them. If your
distro firewall is on, open the ports (the beacon port is optional if you only
need the paired connection, but is required to discover other unpaired devices):

```bash
sudo ufw allow 27341/tcp            # encrypted phone ↔ laptop link
sudo ufw allow 27341/udp            # pairing endpoint discovery
sudo ufw allow 27342/udp            # nearby-device list
sudo firewall-cmd --add-port=27341/tcp --add-port=27341/udp --add-port=27342/udp --permanent
sudo firewall-cmd --reload
```

## Windows 10/11

Prerequisites (one-time):

1. **Rust**: [rustup.rs](https://rustup.rs) → install with the default
   `x86_64-pc-windows-msvc` toolchain (this also pulls the MSVC build tools —
   accept the VS Build Tools workload with "Desktop development with C++" if
   prompted).
2. **Node.js** 20+ (LTS installer is fine).
3. **WebView2 Runtime**: preinstalled on Windows 11 and most Windows 10;
   otherwise the [Evergreen Bootstrapper](https://developer.microsoft.com/microsoft-edge/webview2/).
4. **Tauri prerequisites**: no extra SDKs needed — Tauri's Windows target only
   needs Rust + WebView2.

Build and run:

```powershell
cd desktop
npm install
npm run tauri dev          # develop
npm run tauri build        # MSI installer in src\tauri\target\release\bundle\msi\
```

First firewall prompt: when the app starts listening it asks once —
**allow it on Private networks**. This is the #1 reason the phone will
otherwise "connect and retry" forever.

If the phone still can't reach the laptop, allow the port manually:

```powershell
netsh advfirewall firewall add rule name="InterLink Link" dir=in action=allow protocol=TCP localport=27341
netsh advfirewall firewall add rule name="InterLink Discovery" dir=in action=allow protocol=UDP localport=27341
netsh advfirewall firewall add rule name="InterLink Nearby" dir=in action=allow protocol=UDP localport=27342
```

Notes:
- Unsigned apps trigger SmartScreen ("Windows protected your PC") →
  "More info" → "Run anyway". Signing with an Authenticode cert removes this.
- Windows is fine with the phone on the hotspot/laptop on Wi-Fi as long as
  routes exist; the phone's manual-IP pairing tab covers odd networks.

## macOS (13 Ventura or newer; Apple Silicon or Intel)

Prerequisites (one-time):

```bash
xcode-select --install      # command line tools (clang, etc.)
brew install rustup node    # or install rustup.rs + nodejs.org manually
rustup default stable
```

Build and run:

```bash
cd desktop
npm install
npm run tauri dev           # develop
npm run tauri build         # .app/.dmg in src-tauri/target/release/bundle/dmg/
```

macOS permissions the app will prompt for (all on first use):
- **Microphone** — laptop-side call audio (and the mic picker in the Calls tab).
- **Local Network** — macOS 15+ gates all LAN traffic (including our WebSocket
  server + UDP discovery) behind this prompt. **Deny → phone connects and
  retries forever.** Grant it (System Settings → Privacy & Security →
  Local Network → InterLink).
- Notifications on macOS are not bridged yet (Linux/Windows only in the MVP).

Signing/notarization: `tauri build` produces an ad-hoc-signed app — fine for
personal use; Gatekeeper will warn on first open → right-click → Open.
For distribution, set `bundle.macOS.signingIdentity` in
`desktop/src-tauri/tauri.conf.json` and notarize with `xcrun notarytool`.

## Android app

```bash
# needs JDK 17 + Android SDK (ANDROID_HOME pointing at it)
cd android
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease     # needs a signing config for store/self-install
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or use Android Studio: open `android/` and press Run.

The phone pairs by QR (laptop app → Pair dialog). The QR embeds the laptop's
IP; if the network is unusual (VPN, AP isolation), use the phone's
**Pair → Enter code** tab to paste the code and type the laptop IP manually.

## Phone ↔ laptop checklist when they won't talk

1. Same network (or phone hotspot with the laptop connected to it).
2. Laptop firewall allows TCP 27341 (see per-OS sections above).
3. macOS: Local Network permission granted to InterLink.
4. Re-open the laptop's Pair dialog after moving networks — the QR carries
   the laptop's current IP. (Or use the manual-code tab.)
