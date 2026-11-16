# InterLink in a browser (`interlink-web`)

Same InterLink, without a native window: the laptop backend runs as a local
program and serves the UI to your browser. It works on **any OS with a
browser** — Linux, Windows, macOS — and needs no GTK/WebKit/WebView2 install.

```bash
./build-web.sh          # Linux / macOS
build-web.bat           # Windows (double-click)
```

The build leaves the executable in `out/`:

| OS | File |
|---|---|
| Linux / macOS | `out/interlink-web` |
| Windows | `out/interlink-web.exe` |

Run it:

```bash
out/interlink-web
```

It prints a link like `http://127.0.0.1:27350/?t=…`; open that (it opens your
browser by itself unless you pass `--no-open`). The UI is the same React app as
the desktop build — Dashboard, Messages, Notifications, Gallery, Music,
Contacts, Calls, Mirror, Settings.

## Why an executable, not a hosted website?

The phone is the one that dials out: it connects to this computer on
**TCP/UDP 27341** (and UDP 27342 for discovery). A web page cannot accept an
incoming socket, so a purely hosted site would have nothing for the phone to
talk to. `interlink-web` is that endpoint — the same Rust backend as the
desktop app — plus a local HTTP server for the browser. Nothing goes to a third
party; the page is served from `127.0.0.1`.

## Options

```
--port <N>          Web port (default 27350; the next free one is used if busy)
--listen <ADDR>     Address to listen on (default 127.0.0.1 = this computer only)
                    0.0.0.0 lets other devices on the network open the page over
                    plain HTTP — only on a network you trust
--no-open           Don't open a browser; just print the link
--config-dir <DIR>  Where pairing/settings live (default: the same folder the
                    desktop app uses, so one pairing serves both)
-h, --help          Usage
```

The phone still connects to this computer on TCP/UDP 27341 and UDP 27342, so
**only one InterLink laptop program can run at a time** — close the desktop app
before starting `interlink-web`, and stop `interlink-web` before launching the
desktop app again.

## Pairing

Identical to the desktop app: Settings → *Pair a phone*, scan the QR with the
phone app (or approve a pairing request) and confirm the 6-digit code on both
screens. Because pairing is stored in the shared config folder by default, a
laptop already paired through the desktop app is paired in the browser too.

## Security

The page holds the keys to the phone's messages, calls and files, so the local
server treats other websites as hostile:

* every request needs a secret token, delivered once in the printed link and
  then kept in an `HttpOnly; SameSite=Strict` cookie;
* the `Host` header must be the loopback name it bound (defeats DNS rebinding),
  and state-changing requests must be same-origin;
* it listens on `127.0.0.1` unless you pass `--listen`.

With `--listen 0.0.0.0` the token travels over plain HTTP, so anyone sniffing
the network can take over the session. Use it only on a network you trust.

## Differences from the desktop app

| Feature | Browser |
|---|---|
| Send files to phone | Browser file picker; the bytes are uploaded to the machine running `interlink-web` first |
| Save from phone | Normal browser download (Chromium/Firefox save it wherever your browser saves files) |
| Drag out of the Gallery | Use *Save to laptop*; dragging a tile to the desktop is a native-window feature |
| Notifications | Browser notifications while the tab is in the background (allow them when asked) |
| Show in folder | Not available; the file is wherever your browser put it |
| Call audio / mic / speaker | Unchanged — audio runs in `interlink-web`, not the browser |
| Screen mirroring, messages, calls, clipboard, backup | Unchanged |
