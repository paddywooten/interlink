# macOS setup & build

Everything macOS-specific is grouped in this folder. **One double-click of
[`build-dmg.sh`](build-dmg.sh) produces a `.dmg`.**

```bash
# from Finder: right-click build-dmg.sh → Open (macOS asks once)
# from a terminal:
./packaging/macos/build-dmg.sh
```

The script checks the toolchain, runs `npm install`, runs
`npm run tauri build -- --bundles dmg`, and drops `InterLink_*.dmg` in
`packaging/macos/out/`, opening that folder when it finishes.

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
- Notifications on macOS are not bridged yet (Linux/Windows only in the MVP).

### Signing / notarization

`build-dmg.sh` produces an ad-hoc-signed app — fine for personal use; Gatekeeper
warns on first open → right-click → Open. For distribution, set
`bundle.macOS.signingIdentity` in `desktop/src-tauri/tauri.conf.json` and
notarize with `xcrun notarytool`.

Full build reference, including the Android half and the
[troubleshooting checklist](../../BUILD.md), lives in
[BUILD.md](../../BUILD.md).
