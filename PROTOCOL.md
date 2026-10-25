# InterLink Wire Protocol v1

All communication is between:

- **Laptop** — runs a WebSocket **server** (Tauri app, listens on TCP `27341/tcp` by default)
- **Phone** — runs a WebSocket **client** (Android foreground service, auto-reconnects)

The phone always dials the laptop (laptop is on the LAN, usually plugged in).

---

## 1. Transport

- WebSocket (`ws://<laptop-ip>:27341/ws`), binary frames only.
- Discovery: the phone broadcasts UDP `ILWHOIS?` to the paired port; the laptop
  replies `ILHERE|<base64url(device name)>|<port>` (best effort — the phone uses
  the source address and the port; the name field is informational).
  The phone remembers the last successful `ip:port` after pairing and retries it
  first, falling back to discovery when it stops answering.
- **Endpoint candidates are ordered: loopback first.** The phone always tries
  `127.0.0.1:<port>` before its stored or discovered LAN host (see §4.12). With
  no `adb reverse` tunnel in place that connection is refused instantly and it
  falls through to WiFi; with one in place the link rides the USB cable and
  needs no shared network at all. Trying loopback first costs one refused
  connection and buys cable-mode transparency.
- LAN presence: every phone and laptop broadcasts
  `ILANN|<base64url(device name)>|<role>|<port>` on UDP **27342** every 3 seconds,
  where `role` is `phone` or `laptop`. Receivers key the device list by sender IP
  and advertised port and expire entries not heard for 15 seconds. This channel is
  unauthenticated and carries no secrets; it is only used to display nearby devices.
  A successfully paired phone is also registered through its encrypted link, so the
  connected device remains visible even when a firewall blocks UDP 27342.

## 2. Cryptography

### 2.1 Pairing secret

32 random bytes (`S`), base64url-encoded (**no padding**) into the QR code shown by the laptop.
QR payload — four colon-separated fields, `il1:` prefix, with an optional fifth:

```
il1:<base64url(S)>:<port>:<base64url(device name utf8)>[:<ipv4>]
```

When present, the fifth field is the laptop's LAN IPv4: the phone connects to
it directly and skips discovery. When absent (4 fields — e.g. no network route
yet), the phone falls back to its stored endpoint or UDP discovery (§1).
IPv4 only: a colon-separated payload cannot carry IPv6.

The QR code must be rendered **dark modules on a light background** — phone
scanners cannot decode inverted (light-on-dark) codes.

Stored on both sides after successful pairing. Rotating the secret unpairs both sides.

### 2.2 Keys

```
root_key   = HKDF-SHA256(ikm = S, salt = "interlink-v1-root",  info = "frame-key", len = 32)
session_key = HKDF-SHA256(ikm = S, salt = client_nonce ‖ server_nonce, info = "session-key", len = 32)
```

### 2.3 Handshake (per connection)

1. Client connects.
2. Client → Server: **frame kind `0x01`**, JSON `hello` with `nonce` (32 random bytes, b64url),
   sealed under `root_key`.
3. Server → Client: `hello_ack` with `nonce` (the server's 32 random bytes), device info
   and `caps`, sealed under `root_key`.
4. Both derive `session_key`. **Every subsequent frame** is sealed under `session_key`.
   If any frame fails to open → close connection (possible MITM / stale secret).

### 2.4 Frame format

```
frame := kind(1 byte) ‖ nonce(24 bytes) ‖ ciphertext(+16 byte Poly1305 tag)
```

| kind | plaintext (before sealing) | AAD |
|---|---|---|
| `0x01` | UTF-8 JSON envelope (§3) | `kind` byte |
| `0x02` | `tid(16 bytes) ‖ seq(u32 BE) ‖ chunk` | `kind ‖ tid ‖ seq` |

Cipher: **XChaCha20-Poly1305** (random 24-byte nonce per frame).
`tid` = transfer id (random 16 bytes) ties data chunks to a control message.
`seq` starts at 0, increments per chunk; the receiver assembles until `size`
bytes (from the control message) have arrived, then reports completion with the
`file.done`/`transfer.done` pushes (§4.4). Chunks must arrive in order — a `seq`
gap aborts the transfer (WebSocket is ordered; the check is a safety net).

## 3. JSON envelope (kind `0x01`)

```jsonc
{
  "v": 1,               // protocol version (constant)
  "t": "sms.send",       // message type
  "id": "…b64…",         // sender-generated id (8 random bytes, b64) for correlation
  "r": "…b64… | null",   // set on responses: the request id being answered
  "ok": true,            // responses only: success flag
  "e": "error text",     // responses only: present when ok=false
  "d": { }               // payload
}
```

- **Requests**: `r: null`. Peer replies with a response (`r = id`) or streams multiple
  responses (page 1..n with same `r`).
- **Pushes** (no request): response-like messages with `r: null` and their own `id`.
- Senders MAY omit fields left at their default value — `v` when 1, `r`/`ok`/`e` when
  null, `d` when empty (the phone's serializer does exactly this). Receivers must
  default them; when present, `d` is always a JSON object.
- **Liveness**: `ping` (either direction, every 15 s) → peer must reply `pong`
  (carries battery/status). No reply within 45 s ⇒ disconnect + reconnect with backoff.

Timestamps: all dates are **epoch milliseconds (UTC)** unless stated otherwise.

## 4. Message types

Legend: `P→L` phone pushes to laptop, `L→P` laptop sends, `LR` = laptop request,
`PR` = phone request. All payloads in `d`.

### 4.1 Session / device

| type | dir | payload `d` | notes |
|---|---|---|---|
| `hello` | P→L | `{nonce, device:{name, model, os, app}, caps?}` | first frame, root-key sealed |
| `hello_ack` | L→P | `{nonce, device:{name, os, app}, caps:[…]}` | root-key sealed |
| `ping` / `pong` | ↔ | `{battery?:{level,charging}, screen?:bool}` | 15 s interval |
| `unpair` | ↔ | `{}` | wipes stored secret on receiver, closes |

### 4.2 Notifications

| type | dir | payload `d` |
|---|---|---|
| `notif.post` | P→L | `{key, pkg, app, title, text, bigText?, subText?, category?, when, ongoing?, actions:[{label, token, reply?}], hasReply, avatar? (b64 jpeg ≤ 32 KB)}` |
| `notif.remove` | P→L | `{key, pkg}` |
| `notif.action` | L→P | `{key, token, reply?: string}` — invoke action or send RemoteInput reply |
| `notif.dismiss` | L→P | `{key}` — cancel notification |
| `notif.history` | LR | `{limit}` → resp `{items:[notif.post…]}` — recent dismissal history |

`key` = Android `StatusBarNotification.key`.

**Scope: every app, by default.** The phone mirrors a notification from
whichever package raised it, excluding only InterLink itself (whose own
foreground-service notification would echo straight back forever). An optional
per-package allowlist exists for users who want to narrow this, but it is
empty by default — an allowlist is the wrong default, because what it gets
wrong it gets wrong silently, with the dropped app simply never appearing.

**Actions and replies.** `token` is an index into the posting notification's
`actions`, and `reply: true` marks the one carrying Android `RemoteInput` —
the only action a typed reply can be delivered to. The laptop does not have to
know that index: a `notif.action` with `reply` text and a blank `token` is
resolved on the phone to the first RemoteInput action. Both an unusable token
and an action the phone refuses come back as a `notif.action` **error**, never
an empty success — the laptop toasts success on an empty response, so
reporting a dropped reply as sent would be worse than reporting it as failed.

### 4.3 SMS & contacts

| type | dir | payload `d` |
|---|---|---|
| `sms.threads` | LR | → `{threads:[{id, address, name?, snippet, unread, date, pinned?}]}` |
| `sms.list` | LR | `{threadId, limit?, before?}` → `{messages:[{id, threadId, box:"inbox"\|"sent"\|"draft", address, name?, body, date, read, status?}]}` |
| `sms.send` | L→P | `{address?, threadId?, body, subId?}` → `{id, date}` |
| `sms.read` | L→P | `{threadId}` — mark thread read |
| `sms.changed` | P→L | `{threadId?, kind:"new"\|"sent"\|"read"}` — laptop refetches |
| `sms.received` | P→L | `{address, body, time}` — raw new message (informational; `sms.changed` follows for refetch) |
| `contacts.query` | LR | `{q?, limit?}` → `{contacts:[{id, name, phones:[string]}]}` (q empty ⇒ recent/favorites, limit ≤ 50) |

### 4.4 Media / files

| type | dir | payload `d` |
|---|---|---|
| `media.list` | LR | `{offset, limit ≤ 200, type:"image"\|"video"\|"audio"\|"all", q?}` → `{total, items:[{id, name, mime, size, w?, h?, date, dur?, title?, artist?, album?}]}`. `total` is the **real** count of matching items (cached ~15 s), not `items.length` — the gallery paginates against it. `dur` is **milliseconds**. `"all"` is images+videos only; `"audio"` is the music library (ids `a:<id>`), ordered by title then artist rather than by date — nobody browses an album by how recently it was copied over — and `q` is a case-insensitive substring match over title/artist/album, which is what makes a 5,000-song library reachable at all |
| `media.thumb` | LR | `{id, w, tid}` → `{tid, mime}` then **one `0x02` frame** (seq 0) with JPEG ≤ 64 KB — `tid` is generated by the laptop, which pre-registers its collector. Generated for **videos** too (poster frame), so the grid has thumbnails for both. **Audio has no poster frame** and fails with `not_found: no thumbnail`; the laptop never asks for one and draws a glyph instead |
| `media.get` | LR | `{id, tid}` → `{tid, size, chunks, name?, mime?}` then N × `0x02` frames; laptop saves to its media folder. For videos **and songs** the laptop saves once, then plays the file back in-app via the Tauri asset protocol |
| `file.offer` | L→P | `{tid, name, mime, size, chunks, dest:"gallery"\|"download"\|"media"}`. `"media"` restores straight into the photo/video library (used by backup restore); the other two land in `Downloads/InterLink` |
| `file.accept` | P→L | `{tid}` / `file.reject {tid, reason}` — then laptop streams `0x02` frames |
| `file.done` | P→L | `{tid, path}` — saved location (Android display path); terminal for the tid |
| `transfer.done` | P→L | `{tid, name, path, size}` — the same completion carrying the name for the laptop UI. Sent alongside `file.done`; laptops report each transfer **once** |
| `transfer.failed` | P→L | `{tid, name, error}` — assembly failed on the phone |
| `file.offer` (P→L) | P→L | phone-initiated share: phone sends `file.offer`, laptop replies `{tid, status:"accepted"}` (or a `busy:` error when auto-accept is off), then phone streams `0x02` frames; the laptop assembles and reports `transfer.done` |
| `transfer.cancel` | ↔ | `{tid, reason?}` |

Chunk size: **64 KiB**. Flow control (`file.ack {tid, seq}` every 32 chunks) is
**reserved but not currently required**: the WebSocket is ordered and receivers
read chunks back-to-back as they arrive.

**Completion is acknowledged, not assumed.** When the laptop sends a file it
does not return from `file_send` on its last byte: it registers a waiter keyed
`filedone:<tid-b64u>` and waits for the phone's `transfer.done` / `file.done` /
`transfer.failed` / `transfer.cancel` push for that tid (up to
`60 s + size/1MiB`, capped at 600 s). Remote APK install and backup restore
depend on the file actually existing on the phone, not on it having left the
socket. Waiters are dropped on disconnect so a lost link fails the caller
instead of hanging it. `assemble_incoming` likewise has a 60 s idle timeout so
a stalled sender cannot wedge a collector forever.

### 4.5 Clipboard

| type | dir | payload `d` |
|---|---|---|
| `clip.set` | ↔ | `{text ≤ 128 KB, ts}` — receiver sets its clipboard (if enabled) |
| `clip.get` | LR | → `{text, ts}` |

### 4.6 Calls

| type | dir | payload `d` |
|---|---|---|
| `call.state` | P→L | `{state:"incoming"\|"active"\|"ended", dir:"in"\|"out", number, name?, callId, duration?}` |
| `call.incoming` | P→L | same payload as `call.state` — compatibility alias the phone pushes **alongside** `call.state`; laptops key off `call.state` |
| `call.answer` | L→P | `{callId}` |
| `call.decline` | L→P | `{callId}` |
| `call.end` | L→P | `{callId}` |
| `call.dial` | L→P | `{number}` → `{callId}` |
| `call.audio_start` | L→P | `{callId, codec:"pcm_s16le", rate:16000, ch:1, frameMs:20, uplink:true, downlink:true}` — the two booleans are independent (see below) |
| `call.audio_stop` | L→P | `{callId}` |
| `call.audio` | ↔ | kind `0x02` frames, `tid` = callId hash(16B), `seq` = frame #, payload = PCM S16LE 20 ms (6400 B). Phone → laptop = far end (only when `downlink`); laptop → phone = mic (only when `uplink`). |
| `call.audio_state` | P→L | `{callId, ok, reason?}` — `ok:false` with `reason:"mic_permission"\|"capture_blocked"\|"no_call"` ⇒ fallback: audio stays on phone |

**Both directions are independently steerable.** `uplink` (speak through the
laptop) and `downlink` (hear through the laptop) are separate flags so the user
can pick, per call, any laptop mic and any laptop output — including Bluetooth —
or leave a direction on the phone:

| uplink | downlink | meaning |
|---|---|---|
| `true` | `true` | full relay: laptop mic → phone, phone → laptop output |
| `false` | `true` | hear on the laptop, keep speaking into the phone |
| `true` | `false` | speak into the laptop, hear on the phone |
| `false` | `false` | phone-only call — **no relay is started at all** and no `0x02` frames flow either way |

The device *lists* (which laptop mic / output to use) never cross the wire: the
phone only ever learns the two booleans. Each side resolves its own device from
its own config.

Audio relay capability is **negotiated**: the phone answers the `call.audio_start`
request itself, then pushes `call.audio_state` once capture actually starts or is
blocked. Laptop UI shows phone-speaker fallback when not ok.
(Cellular call audio capture is OEM-restricted; on failure the call still works
as remote control + phone speaker.)

### 4.7 Utilities

| type | dir | payload `d` |
|---|---|---|
| `device.ring` | L→P | `{seconds ≤ 30}` — phone plays loud find-me alarm |
| `device.battery` | P→L push | `{level, charging, temp?}` (also piggybacked on `pong`). Pushed **on connect and then every 30 s**, so the laptop shows a live percentage without polling |
| `device.info` | LR | → `{name, model, os, app, ip, storage?:{total,free}}` |
| `find.phone` | L→P | alias of `device.ring` with vibrate+flash best effort |

### 4.8 Errors

`ok:false` responses carry `e` with a stable code prefix:
`perm:` (missing Android permission), `busy:`, `not_found:`, `internal:`.

### 4.9 Screen mirror

| type | dir | payload `d` |
|---|---|---|
| `mirror.start` | L→P | `{w?:720, fps?:8}` → `{status:"consent_required"}`. Phone launches the MediaProjection consent dialog; frames only flow after the user approves. |
| `mirror.stop` | L→P | `{}` → `{ok:true}` |
| `mirror.state` | P→L push | `{active:true, pending?}` when consent granted / starting; `{active:false, reason?:"denied"}` on stop or denial |
| `mirror.frame` | P→L push | `{seq, mime:"image/jpeg", data}` — STANDARD base64 JPEG, one complete frame per push. Desktop routes fixed-tid kind-`0x02` frames here. |

The live frame stream rides kind `0x02` (data) frames on a **fixed tid** =
`sha256("interlink-mirror-v1")[..16]` — identical derivation on all three
platforms (`crypto::mirror_tid()` / `Crypto.tidFromString()`). Each frame is a
complete JPEG (`seq` = frame counter, no chunking/reassembly); the desktop
intercepts this tid in its data-collector router and re-emits as the
`mirror.frame` push above. Base64 is STANDARD — `data:` URLs reject the
URL-safe alphabet.

**Delivery is latest-frame-wins, not queued, at both ends.** The phone keeps
capturing whether or not the laptop has painted the previous frame, so any
queue between them is a queue of *past* frames and end-to-end latency grows by
the queue's depth for as long as the mirror runs. Neither end is allowed to
build one:

| end | mechanism |
|---|---|
| phone | exactly one send loop; a new frame **overwrites** the not-yet-sent slot rather than launching another send |
| laptop | the frames feed a single-value watch channel, so a frame written while the previous was being emitted replaces it rather than following it |

The consequence is that a link or laptop that cannot quite keep up shows
stutter — dropped frames — rather than a smooth picture that drifts seconds
behind. That trade is deliberate: for a live screen, current-but-choppy is
strictly better than smooth-and-late, and the lag would otherwise be
unbounded. `seq` remains monotonic per session; it is a frame counter, not a
gap signal. The phone also encodes **one** JPEG per frame (no re-encode loop
to hit a size target) so the frame interval stays predictable.

Losing the link or calling `mirror.stop` drops the laptop's sink, so a stale
last frame cannot keep painting over a dead session.

### 4.10 Remote app install

| type | dir | payload `d` |
|---|---|---|
| `app.install` | L→P | `{path}` — filename of a package **already delivered to the phone** via `file.offer {dest:"download"}`. The phone stages the APK (direct path → `Downloads/InterLink` → MediaStore query → cache copy) and opens a `PackageInstaller` session |
| `install.status` | P→L push | `{status:"pending"\|"success"\|"failure"\|"blocked", name?, error?, sessionId?}` — progress of that session |

The laptop's side is strictly ordered and *waits*:

```
pick .apk  →  file.offer + 0x02 frames  →  wait for transfer.done   (§4.4)
          →  app.install {path}         →  wait for install.status push
```

**Nothing installs without the user.** `PackageInstaller` always surfaces the
normal system confirmation dialog — the phone pushes `status:"pending"` when the
session is submitted, and the user taps Install on the phone. The laptop only
learns the outcome. `status:"blocked"` means Android refused because InterLink
doesn't hold `REQUEST_INSTALL_PACKAGES` / the "Install unknown apps" special
access, which the phone app exposes as a grant row under its permissions.

The `InstallResultReceiver` that produces these pushes is registered
`exported="false"` and addressed explicitly only, so a third-party app cannot
spoof an `install.status` push.

### 4.11 Backup & restore

Backup is **folder-based, not archive-based** — a backup is a directory the user
can open, read and copy:

```
<backup dir>/
  contacts.vcf     contacts as vCard 3.0
  sms.json         messages as a JSON array
  manifest.json    {at, device, sections, contacts, sms, photos}
  media/…          every photo and video on the phone
```

| type | dir | payload `d` |
|---|---|---|
| `backup.export` | L→P | `{sections:["contacts","sms"]}` → `{contacts?: "<vCard>", sms?: "<json array>", at}` |
| `backup.import` | L→P | `{kind:"contacts"\|"sms", data}` → `{imported, skipped}` — `sms` additionally returns `{blocked:true, message}` when InterLink is not the phone's **default SMS app** (Android only lets the default app write the telephony provider) |
| `backup.result` | P→L push | `{tid?, status, detail?}` — reserved for long-running restore progress; the laptop currently drives restore itself and treats each `backup.import` response as authoritative |

Contacts and SMS travel **inline** in the request/response (they are small
text); photos and videos use the ordinary `media.get` (export) and
`file.offer {dest:"media"}` (restore) streaming paths, so a 20k-photo library
never needs a giant archive sitting in the phone's storage. Restore re-sends
media one file at a time and reports `{media, mediaFailed}`.

Contacts restore needs `WRITE_CONTACTS`; SMS restore needs the default-app
grant, and reports `blocked` rather than failing when it doesn't have it.

### 4.12 Connection mode (USB vs WiFi)

Connection mode is a **laptop-side** concern and adds no wire messages. The
phone always tries `127.0.0.1:<port>` **first** and only then its stored /
discovered LAN host, which makes the mode enforceable entirely by the laptop:

| mode | behaviour |
|---|---|
| `auto` | `adb reverse tcp:P tcp:P` whenever a USB device with debugging is plugged in, otherwise LAN |
| `usb`  | same tunnel, but surfaced as an error in the UI when no cable is present |
| `wifi` | never sets up a tunnel, and tears one down if it exists |

The phone needs no knowledge of the mode: when no tunnel exists the loopback
connection is refused instantly and it falls through to the LAN; when one
exists the connection goes straight down the cable and works with no shared
network at all. The tunnel is reconciled every 5 s (device replug, adb
restart), and the laptop's `get_status.transport` is simply `"usb"` when the
peer arrived on loopback (it can only have come through our own tunnel) and
`"wifi"` otherwise. Changing the mode applies the tunnel immediately and drops
the link so the phone re-handshakes onto the preferred route.

---

## 5. Capability negotiation

`hello_ack.caps` lists what the laptop can do; phone capabilities are derived from
the permissions it holds (reported in `hello.caps` when present): e.g.
`["sms","notif","media","calls","clipboard","battery"]`.
The laptop greys out features whose cap is absent and shows the exact Android
permission to grant (settings deep-link: `package:<pkg>`).

## 6. Versioning

`v` = 1. Unknown message types MUST be ignored (forward compatible). New fields are
additive; receivers ignore unknown fields.

## 7. Defaults

- Port: **27341** (fallback: any open port reported in QR).
- Keepalive: ping 15 s / timeout 45 s. Reconnect backoff: 1, 2, 4, 8, 15 s (loop).
- Max frame: 8 MiB (chunked transfers never exceed 64 KiB + headers).
