# Building InterLink

InterLink has two halves:

| Part | Tech | Runs on |
|---|---|---|
| Laptop app (`desktop/`) | Tauri 2 (Rust + React/TS) | Linux, **Windows**, **macOS** |
| Phone app (`android/`) | Kotlin + Jetpack Compose | Android 8.0+ (minSdk 26) |

The wire protocol is identical on every OS — see `PROTOCOL.md`.

## One-click installers

Each desktop OS is grouped into its own folder holding the build script and
every note that belongs to that platform:

| OS | Folder | Double-click | Produces |
|---|---|---|---|
| macOS | [`packaging/macos/`](packaging/macos/) | `build-dmg.sh` | `InterLink_*.dmg` |
| Windows | [`packaging/windows/`](packaging/windows/) | `build-msi.bat` | `InterLink_*.msi` |
| Linux | [`packaging/linux/`](packaging/linux/) | `build-linux.sh` | `.deb` + `.AppImage` |

Each script checks the toolchain first — and names the missing piece with the
command to install it — then runs `npm install`, builds only that OS's bundles,
and leaves the installer in `packaging/<os>/out/`, opening the folder when it
finishes. Prerequisites, firewall rules, signing and OS permission notes live in
the README beside each script.

Running it by hand, from `desktop/`:

```bash
npm install
npm run tauri build -- --bundles dmg            # macOS
npm run tauri build -- --bundles msi            # Windows
npm run tauri build -- --bundles deb,appimage   # Linux
# (no --bundles flag = every bundle for the current OS)
```

CI (`.github/workflows/build.yml`) builds installers for all three desktop OSes
plus the Android APK on every push and on `v*` tags.

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

## Phone ↔ laptop checklist when they won't talk

1. Same network (or phone hotspot with the laptop connected to it).
2. Laptop firewall allows TCP 27341 — per-OS rules:
   [Linux](packaging/linux/README.md#firewall) ·
   [Windows](packaging/windows/README.md#firewall) ·
   [macOS](packaging/macos/README.md) (Local Network permission instead).
3. macOS: Local Network permission granted to InterLink.
4. Re-open the laptop's Pair dialog after moving networks — the QR carries the
   laptop's current IP. (Or use the manual-code tab.)
