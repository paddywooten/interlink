# InterLink setup — Linux

**Double-click [`build-linux.sh`](build-linux.sh)** (or run it in a terminal).
That is the whole build step: it checks the toolchain, installs the frontend
dependencies, builds the laptop app and leaves the installers here:

```
packaging/linux/out/InterLink_*.deb        Debian / Ubuntu
packaging/linux/out/InterLink_*.AppImage   any distro
```

Then pick one:

| You have | Do this |
|---|---|
| Ubuntu / Debian / Mint | Double-click the `.deb` — your package manager installs it with a menu entry |
| Anything else (Arch, Fedora, openSUSE…) | Run [`install-launcher.sh`](install-launcher.sh) once, then launch **InterLink** from the menu |

`install-launcher.sh` needs no root password. It copies the AppImage to
`~/.local/bin/InterLink.AppImage` and adds the menu entry, desktop icon and
icons, so the app does not seem to disappear when you close it. Undo with
`./install-launcher.sh --uninstall`.

## Before the first build (once per PC)

Rust ([rustup.rs](https://rustup.rs)) and Node.js 20+, then the WebKitGTK
packages for your distro. The one-liners are in
[`packaging/linux/README.md`](../packaging/linux/README.md#prerequisites-one-time);
`build-linux.sh` also tells you exactly what is missing if you skip this.

## If the phone cannot reach the laptop

Allow TCP/UDP `27341` and UDP `27342` through the firewall — full commands per
distro are in [`packaging/linux/README.md`](../packaging/linux/README.md#firewall).

## Where the real scripts live

This folder is the obvious one-click entry point. The build and launcher logic
stay in [`packaging/linux/`](../packaging/linux/), which also has the detailed
notes. Keep the folders together.
