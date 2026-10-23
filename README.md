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
- 🖼️ Gallery browsing, photo download, file transfer both ways
- 📞 Phone calls — dial pad, answer/decline, audio relay with selectable laptop microphone
- 📋 Clipboard sharing (both directions)
- 🔊 Ring my phone, battery status, device info

## How it works

1. **Pair:** the laptop shows a QR code (LAN address + 32-byte secret); the phone scans it once.
2. **Connect:** the phone (foreground service) keeps an encrypted WebSocket connection to the laptop over the local network. Traffic is end-to-end encrypted (XChaCha20-Poly1305, key derived from the pairing secret — see [PROTOCOL.md](PROTOCOL.md)).
3. **Use:** open the laptop app — messages, notifications, gallery and calls are live.

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

## Security model

- Pairing secret: 32 random bytes, transferred only via QR code (in-person).
- Per-connection session key via handshake; every frame sealed with XChaCha20-Poly1305.
- No cloud, no accounts — local network only.
- Unpairing from either side wipes the stored secret.
