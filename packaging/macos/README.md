# macOS setup & build

Everything macOS-specific is grouped in this folder. **One double-click of
[`build-dmg.command`](build-dmg.command) produces a `.dmg`.**

```bash
# from Finder: double-click build-dmg.command
# from a terminal (either file):
./packaging/macos/build-dmg.command
./packaging/macos/build-dmg.sh
```

It checks the toolchain, runs `npm install`, runs
`npm run tauri build -- --bundles dmg`, and drops `InterLink_*.dmg` in
`packaging/macos/out/`, opening that folder when it finishes.

Finder can't run a `.sh` — double-clicking one just opens it in an editor — so
`build-dmg.command` is a two-line shim that hands over to `build-dmg.sh`, the
real script. Double-click the `.command`; use the `.sh` from a terminal or when
you want to read what runs.

If macOS refuses to open it ("unidentified developer"), right-click →
**Open** once, or clear the quarantine flag:

```bash
xattr -d com.apple.quarantine packaging/macos/build-dmg.command
```

## Prerequisites (one-time)

```bash
xcode-select --install      # command line tools (clang, etc.)
brew install rustup node    # or install rustup.rs + nodejs.org manually
rustup default stable
```

The script refuses to continue and tells you exactly which of these is missing,
so you can run it blind and read the error if anything is absent.

## macOS 13 Ventura or newer; Apple Silicon or Intel

### Install

Open the `.dmg` and drag **InterLink** into `Applications`.

### Permissions the app prompts for (all on first use)

- **Microphone** — laptop-side call audio (and the mic picker in the Calls tab).
- **Local Network** — macOS 15+ gates all LAN traffic (including our WebSocket
  server + UDP discovery) behind this prompt. **Deny → phone connects and
  retries forever.** Grant it: System Settings → Privacy & Security →
  Local Network → InterLink.
- Notifications are mirrored as native macOS popups, like the other two desktop
  OSes — the first one asks macOS for permission. If you dismissed that prompt,
  enable InterLink under System Settings → Notifications, or nothing will pop.

### Signing / notarization

One arch note: CI's prebuilt `.dmg` is **Apple Silicon (arm64)** because the
GitHub macOS runners are arm64. On an Intel Mac, run `build-dmg.command`
yourself — it builds for whatever architecture the Mac is.

`build-dmg.sh` produces an ad-hoc-signed app — fine for personal use; Gatekeeper
warns on first open → right-click → Open. For distribution, set
`bundle.macOS.signingIdentity` in `desktop/src-tauri/tauri.conf.json` and
notarize with `xcrun notarytool`.

Full build reference, including the Android half and the
[troubleshooting checklist](../../BUILD.md), lives in
[BUILD.md](../../BUILD.md).
