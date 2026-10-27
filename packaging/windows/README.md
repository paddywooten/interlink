# Windows setup & build

Everything Windows-specific is grouped in this folder. **One double-click of
[`build-msi.bat`](build-msi.bat) produces an MSI installer.**

```
# from Explorer: double-click build-msi.bat
# from a terminal:
packaging\windows\build-msi.bat
```

The script checks the toolchain, runs `npm install`, runs
`npm run tauri build -- --bundles msi`, and drops `InterLink_*.msi` in
`packaging\windows\out\`, opening that folder when it finishes. The window
stays open at the end (and on error) so nothing scrolls away.

## Prerequisites (one-time)

1. **Rust**: [rustup.rs](https://rustup.rs) → install with the default
   `x86_64-pc-windows-msvc` toolchain (this also pulls the MSVC build tools —
   accept the VS Build Tools workload with "Desktop development with C++" if
   prompted).
2. **Node.js** 20+ (LTS installer is fine).
3. **WebView2 Runtime**: preinstalled on Windows 11 and most Windows 10;
   otherwise the [Evergreen Bootstrapper](https://developer.microsoft.com/microsoft-edge/webview2/).
4. **Tauri prerequisites**: no extra SDKs needed — Tauri's Windows target only
   needs Rust + WebView2.

The first MSI build downloads the WiX toolset automatically; nothing to install
by hand.

## Firewall

First firewall prompt: when the app starts listening it asks once —
**allow it on Private networks**. This is the #1 reason the phone will
otherwise "connect and retry" forever.

If the phone still can't reach the laptop, allow the port manually:

```powershell
netsh advfirewall firewall add rule name="InterLink Link" dir=in action=allow protocol=TCP localport=27341
netsh advfirewall firewall add rule name="InterLink Discovery" dir=in action=allow protocol=UDP localport=27341
netsh advfirewall firewall add rule name="InterLink Nearby" dir=in action=allow protocol=UDP localport=27342
```

## Notes

- Unsigned apps trigger SmartScreen ("Windows protected your PC") →
  "More info" → "Run anyway". Signing with an Authenticode cert removes this.
- Windows is fine with the phone on the hotspot/laptop on Wi-Fi as long as
  routes exist; the phone's manual-IP pairing tab covers odd networks.

Full build reference, including the Android half and the
[troubleshooting checklist](../../BUILD.md), lives in
[BUILD.md](../../BUILD.md).
