# InterLink setup — Windows

**Double-click [`BUILD-WINDOWS.bat`](BUILD-WINDOWS.bat).** That is the whole
build step: it checks the toolchain, installs the frontend dependencies, builds
the laptop app and leaves the installer here:

```
packaging\windows\out\InterLink_*.msi
```

Double-click that `.msi` to install InterLink. It adds the Start-menu entry and
the app icon, so you can open it again after closing the window.

## Before the first build (once per PC)

1. **Rust** — install from [rustup.rs](https://rustup.rs) with the default
   `x86_64-pc-windows-msvc` toolchain. Accept the Visual Studio Build Tools
   ("Desktop development with C++") prompt if it appears.
2. **Node.js 20+** — the LTS installer from [nodejs.org](https://nodejs.org).
3. **WebView2** — already on Windows 11 and most Windows 10; otherwise get the
   [Evergreen Bootstrapper](https://developer.microsoft.com/microsoft-edge/webview2/).

The first build downloads the WiX toolset by itself; no extra install.

## If the phone cannot reach the laptop

When Windows asks, allow InterLink on **Private networks**. The full firewall
note and manual `New-NetFirewallRule` commands are in
[`packaging/windows/README.md`](../packaging/windows/README.md).

## Where the real build script lives

This folder is the obvious one-click entry point. The build itself is
[`packaging/windows/build-msi.bat`](../packaging/windows/build-msi.bat), which
also has the most up-to-date notes. Keep the two folders together.
