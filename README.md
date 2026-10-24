# InterLink

Android ↔ Laptop interconnectivity suite — like iPhone + Mac Continuity.
Two components, one encrypted protocol:

| Component | Path | Stack |
|---|---|---|
| 🖥️ Laptop app (Windows / Linux) | `desktop/` | Tauri 2 (Rust) + React + TypeScript |
| 📱 Phone app (Android) | `android/` | Kotlin + Jetpack Compose |

## Features

- 🔔 Notification mirroring with inline reply / dismiss
- 💬 SMS threads — read & send texts from the laptop
- 👥 Contacts — the whole address book on the laptop, searchable, tap to call
- 🖼️ Gallery — **every** photo and video on the phone, with real totals, video
  thumbnails, and in-app video playback (download once, then play from disk)
- 📞 Phone calls — dial pad, answer/decline, and per-call audio routing: speak
  through any laptop mic (or the phone) and hear through any laptop output
  (laptop speakers, a Bluetooth speaker, HDMI — or the phone)
- 📱 Screen mirroring — live phone screen on the laptop (MediaProjection, user-approved)
- 📋 Clipboard sharing (both directions)
- 🔌 USB or WiFi — plug the phone in and the link rides the cable through an
  `adb reverse` tunnel; unplug and it falls back to WiFi. No shared network needed.
- 💤 Stays connected with the phone screen off — a partial wakelock and a
  high-performance WiFi lock keep the link alive through doze, so the phone
  doesn't go dark the moment you lock it (the app asks for a battery-optimisation
  exemption, which some OEM skins bury in their own settings)
- 🔔 Native popups — every mirrored notification and incoming call also raises
  an OS notification, so they still reach you with this window minimised to the
  tray (toggle in Settings)
- 📦 Remote app install — push an `.apk` from the laptop; the phone shows its
  normal install confirmation and you tap Install on the phone
- 💾 Backup & restore — contacts, SMS and every photo/video into a plain folder
  you can open and read, and back onto any phone
- 🔊 Ring my phone, live battery percentage, device info
- 🌐 Nearby devices — see InterLink phones and laptops on the same Wi-Fi automatically

## How it works

1. **Pair:** the laptop shows a QR code (LAN address + 32-byte secret); the phone scans it once.
2. **Connect:** the phone (foreground service) keeps an encrypted WebSocket connection to the laptop over the local network. Traffic is end-to-end encrypted (XChaCha20-Poly1305, key derived from the pairing secret — see [PROTOCOL.md](PROTOCOL.md)).
3. **Use:** open the laptop app — messages, notifications, gallery and calls are live.

### USB or WiFi

The phone always tries `127.0.0.1` first and only then the LAN, which makes the
transport a laptop-side setting with no extra wire protocol at all:

- **Auto** (default) — use the USB cable whenever the phone is plugged in with
  USB debugging on, otherwise WiFi.
- **USB** — cable only; surfaced as an error in the UI if no cable is present.
- **WiFi** — LAN only; any existing tunnel is torn down.

Setting it to USB means the link works with the two devices sharing no network
at all — just the cable. The laptop shows which transport is actually in use
(🔌 USB / 📶 WiFi) in the sidebar and on the dashboard.

## Repository layout

```
interlink/
├── PROTOCOL.md      # wire protocol both sides implement
├── desktop/         # Tauri app (Rust backend + React frontend)
│   ├── src/         # React/TS UI
│   └── src-tauri/   # Rust: server, encryption, audio, tray
└── android/         # Kotlin Android app
```

## Development setup

> **Windows / macOS**: see [BUILD.md](BUILD.md) for per-OS prerequisites,
> build steps, signing notes, and firewall/permission gotchas. CI
> (`.github/workflows/build.yml`) builds installers for all three desktop OSes
> plus the Android APK on every push and on `v*` tags.

Tooling is installed user-locally (no root required):

- **Rust** via rustup → `~/.cargo`
- **Node.js LTS** → `~/.local/node`
- **JDK 17 (Temurin)** → `~/.local/jdk17`
- **Android SDK** → `~/Android/sdk`

Add to `~/.bashrc` (or open a new shell — installer scripts append these):

```bash
export PATH="$HOME/.cargo/bin:$HOME/.local/node/bin:$HOME/.local/jdk17/bin:$HOME/Android/sdk/platform-tools:$PATH"
export JAVA_HOME="$HOME/.local/jdk17"
export ANDROID_HOME="$HOME/Android/sdk"
```

### Run laptop app

```bash
cd desktop
npm install
npm run tauri dev
```

### Build Android app

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

> **Calls on Android 15+**: the app declares `MANAGE_ONGOING_CALLS`
> (`signature|appop`) so Telecom binds its InCallService as NON_UI without
> replacing your default dialer. It's not runtime-grantable — after installing,
> grant it once via adb:
>
> ```bash
> adb shell appops set com.interlink.app MANAGE_ONGOING_CALLS allow
> ```
>
> Without this, `call.state` pushes never fire (Telecom skips the binding) and
> call audio relay won't work. Uninstalling the APK clears the grant.

## Security model

- Pairing secret: 32 random bytes, transferred only via QR code (in-person).
- Per-connection session key via handshake; every frame sealed with XChaCha20-Poly1305.
- No cloud, no accounts — local network only.
- Unpairing from either side wipes the stored secret.
