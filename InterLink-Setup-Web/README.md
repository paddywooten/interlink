# InterLink — browser version (any OS)

InterLink normally opens as a desktop window. This folder builds the same app
as a **local web program**: the laptop backend runs, and the UI opens in your
browser. It works on any PC with a browser — Linux, Windows or macOS — and
needs no GTK, WebKit or WebView2.

| OS | Double-click |
|---|---|
| Linux / macOS | `build-web.sh` |
| Windows | `build-web.bat` |

You need **Node.js 20+** (<https://nodejs.org>) and **Rust**
(<https://rustup.rs>). On Linux also install ALSA headers
(`sudo pacman -S alsa-lib` / `sudo apt install libasound2-dev`), which the
build script will tell you if missing.

The build leaves the program in `../packaging/web/out/`. Run it:

```bash
../packaging/web/out/interlink-web
```

It prints a link such as `http://127.0.0.1:27350/?t=…` and opens your browser.
Pair the phone exactly as in the desktop app (Settings → *Pair a phone*).

The phone connects to **this computer** on TCP/UDP 27341, so only one InterLink
laptop program can run at a time — close the desktop app first. Full details,
options and the security model are in
[`packaging/web/README.md`](../packaging/web/README.md).
