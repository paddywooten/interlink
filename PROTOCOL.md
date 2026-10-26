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
| `media.list` | LR | `{offset, limit ≤ 200, type:"image"\|"video"\|"audio"\|"all", q?, sort?, dir?}` → `{total, items:[{id, name, mime, size, w?, h?, date, dur?, title?, artist?, album?, genre?}]}`. `total` is the **real** count of matching items (cached ~15 s), not `items.length` — the gallery paginates against it. `dur` is **milliseconds**. `"all"` is images+videos only; `"audio"` is the music library (ids `a:<id>`), ordered by title then artist rather than by date — nobody browses an album by how recently it was copied over — and `q` is a case-insensitive substring match over title/artist/album/genre, which is what makes a 5,000-song library reachable at all. `genre` is present only on `"audio"` rows and only when the file carries a tag (~70% do); it is a real column on the audio table, so ordering by it needs no join against `Genres.Members`. `sort` is one of `title`\|`artist`\|`album`\|`genre`\|`name`\|`duration`\|`date`\|`size` and `dir` is `asc`\|`desc`; both are **whitelisted, never interpolated** — the value lands in the `ORDER BY`, so an unrecognised name falls back to title/asc instead of reaching the SQL. See §4.13 for why the ordering is built the way it is |
| `media.thumb` | LR | `{id, w, tid}` → `{tid, mime}` then **one `0x02` frame** (seq 0) with JPEG ≤ 64 KB — `tid` is generated by the laptop, which pre-registers its collector. Generated for **videos** too (poster frame), so the grid has thumbnails for both. **Audio has no poster frame** and fails with `not_found: no thumbnail`; the laptop never asks for one and draws a glyph instead |
| `media.get` | LR | `{id, tid}` → `{tid, size, chunks, name?, mime?}` then N × `0x02` frames; laptop saves to its media folder. Used for **saving and transferring** — saving a photo, sending files, backup restore. **Not** for playback: that is `media.stream` + HTTP (§4.13) |
| `media.stream` | LR | `{}` → `{port, token}`. The laptop's key to the phone's media server; it composes `http://<host>:<port>/m/<id>?k=<token>` from it. Token is 256 bits of `SecureRandom`, regenerated each app start, **unrelated to the pairing secret** so leaking one says nothing about the other |
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
| `call.state` | P→L | `{state:"incoming"\|"dialing"\|"active"\|"ended", dir:"in"\|"out", number, name?, callId, duration?}` |
| `call.incoming` | P→L | same payload as `call.state` — compatibility alias the phone pushes **alongside** `call.state`; laptops key off `call.state` |
| `call.answer` | L→P | `{callId}` |
| `call.decline` | L→P | `{callId}` |
| `call.end` | L→P | `{callId}` |
| `call.dial` | L→P | `{number}` → `{callId, number, name}` (the number as dialled, `name` resolved from the address book or `""`) |
| `call.audio_start` | L→P | `{callId, codec:"pcm_s16le", rate:16000, ch:1, frameMs:20, uplink:true, downlink:true}` — the two booleans are independent (see below) |
| `call.audio_stop` | L→P | `{callId}` |
| `call.audio` | ↔ | kind `0x02` frames, `tid` = callId hash(16B), `seq` = frame #, payload = PCM S16LE 20 ms (6400 B). Phone → laptop = far end (only when `downlink`); laptop → phone = mic (only when `uplink`). |
| `call.audio_state` | P→L | `{callId, ok, reason?}` — `ok:false` with `reason:"mic_permission"\|"capture_blocked"\|"no_call"` ⇒ fallback: audio stays on phone |
| `calllog.list` | LR | `{limit? ≤ 500}` → `{calls:[{id, number, name, kind, date, duration, new}]}` — the phone's history, **newest first**. `date` is epoch **ms**, `duration` whole **seconds** (what the provider stores), `kind` ∈ `in`\|`out`\|`missed`\|`voicemail`\|`rejected`\|`blocked`\|`answered`\|`unknown` (Android's `CallLog` types, mapped on the phone so the wire never carries an OEM's extra constant). Names are resolved on the phone from the address book, falling back to the dialer's `CACHED_NAME`, so the laptop never has to join numbers against its own copy |

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

**A call is on screen before Telecom reports anything.** `call.dial`'s response
is what puts it there: the laptop renders the call (and with it the end-call
control) the moment the phone accepts the request, rather than waiting for the
first `call.state`. The phone then pushes `dialing` for an outgoing call — from
`onCallAdded` as well as `STATE_DIALING`, so a call that never reaches
`active` is never invisible — then `incoming`/`active`, and `ended` on
disconnect. Nothing in the lifecycle depends on the laptop polling.

Audio relay capability is **negotiated**: the phone answers the `call.audio_start`
request itself, then pushes `call.audio_state` once capture actually starts or is
blocked. Laptop UI shows phone-speaker fallback when not ok.
(Cellular call audio capture is OEM-restricted; on failure the call still works
as remote control + phone speaker.)

Reading the history (`calllog.list`) needs **its own** runtime permission:
`CALL_PHONE` allows placing a call and `ANSWER_PHONE_CALLS` allows answering
one, and neither says anything about being allowed to read who called whom.
The phone therefore answers with a `perm:` error rather than an empty list —
"nobody called" and "you haven't allowed this" must never look the same, or
the laptop's call page silently becomes useless and nobody knows why.

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
| `mirror.start` | L→P | `{w?:720, fps?:8, audio?:true}` → `{status:"consent_required"}`. Phone launches the MediaProjection consent dialog; frames only flow after the user approves. `audio` asks for the phone's playback sound to be relayed too. |
| `mirror.stop` | L→P | `{}` → `{ok:true}` |
| `mirror.audio` | L→P | `{on:true\|false}` → `{ok:true, started, armed?}` — turn the mirrored audio on or off while mirroring. `started` means the capture loop is running now; `armed` means there is no projection yet, so the request is recorded and honoured the moment consent produces one. A handset that can't do it answers `ok:false` with a `perm:`/`internal:` reason instead. |
| `mirror.audio` | P→L push | `{ok:true, rate, ch:1, frameMs:20}` once capture is really recording — `rate` is what the handset's mix accepted (48 kHz first, falling back to 44.1 then 16 kHz) — or `{ok:false, reason}` when it stops or never started. |
| `mirror.state` | P→L push | `{active:true, pending?}` when consent granted / starting; `{active:false, reason?:"denied"}` on stop or denial |
| `mirror.frame` | P→L push | `{seq, mime:"image/jpeg", data}` — STANDARD base64 JPEG, one complete frame per push. Desktop routes fixed-tid kind-`0x02` frames here. |

The live frame stream rides kind `0x02` (data) frames on a **fixed tid** =
`sha256("interlink-mirror-v1")[..16]` — identical derivation on all three
platforms (`crypto::mirror_tid()` / `Crypto.tidFromString()`). Each frame is a
complete JPEG (`seq` = frame counter, no chunking/reassembly); the desktop
intercepts this tid in its data-collector router and re-emits as the
`mirror.frame` push above. Base64 is STANDARD — `data:` URLs reject the
URL-safe alphabet.

**Audio is a second stream on a second tid**: `sha256("interlink-mirror-audio-v1")[..16]`
(`crypto::mirror_audio_tid()` / `ScreenMirrorService.MIRROR_AUDIO_TID`), PCM
S16LE mono, one 20 ms frame per chunk — the same wire shape as call audio
(§4.6), but fixed-rate and strictly phone → laptop. The capture is
`AudioPlaybackCaptureConfiguration` (Android 10+, needs `RECORD_AUDIO`, never
the microphone): the phone records **what other apps are playing**, not what
the room hears, and only the usages the projection admits — media, games, and
untagged audio; assistants and calls stay private by Android's own rule.
Those frames are *not* intercepted: they land in the ordinary data-collector
router under a collector the laptop's relay registered, so they die with it.

Ordering is what makes the rate trustworthy. The phone pushes
`mirror.audio {ok:true, rate}` **before** its first frame, and push and data
share one ordered link, so the laptop has its resampler built for that rate
by the time PCM arrives — no frame is ever played at the wrong pitch. The
rate itself is negotiated with the handset's mixer rather than assumed (48
kHz, then 44.1, then 16), because a capture rate the mix doesn't run at
simply fails to initialise.

On the laptop the PCM is converted to f32 and fed to a ring that the output
callback drains at the device's own rate — the same linear-resample-on-pull
path call audio uses. The phone's own speaker is **not** muted: the handset
keeps playing what it plays, which is what leaves it usable as a phone while
mirrored. Anything that ends the relay — `mirror.audio {on:false}`,
`mirror.stop`, `mirror.state {active:false}`, or the link dropping — tears
the laptop's stream down with it, because a collector with no producer is
just an open audio device playing silence.

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

### 4.13 Media streaming (HTTP)

Playback does not travel over the link at all. Alongside the WebSocket the
phone runs a small HTTP server on **port `27342`** (bound `0.0.0.0`) that
serves MediaStore content with byte ranges, and the webview puts its URL
straight into `<audio>` / `<video>`:

| | |
|---|---|
| methods | `GET`, `HEAD`, `OPTIONS` (preflight answers `204`) |
| path | `/m/<percent-encoded id>` — e.g. `/m/a%3A1000106750` |
| auth | `?k=<token>` from `media.stream`; missing, wrong or stale ⇒ **`401`** |
| no `Range` | **`200`** with `Content-Length` = the whole file |
| `Range` present | **`206`** with `Content-Range` — `bytes=a-b`, `bytes=a-` and `bytes=-n` are all supported |
| unsatisfiable | **`416`** with `Content-Range: bytes */<size>` |
| empty row (a half-copied file) | **`404 empty file`** — better than a URL the webview would reject as an unsupported source |
| other | `404`, `405`, `403` (media permission missing), `503` (not up yet) |

**Reaching it.** Over USB the laptop adds `adb forward tcp:27342 tcp:27342`
— the *opposite* direction to the link's `adb reverse`, because here the
laptop is the one dialing — and talks to `http://127.0.0.1:27342`. Over WiFi
it uses `http://<phone address>:27342` directly. `media_stream_url` on the
laptop picks between the two; nothing else has to know.

**Why not keep downloading first.** The original arrangement used
`media.get` → disk → the Tauri asset protocol, and WebKitGTK rejects
`asset://` on media elements outright: every attempt ended in
`MEDIA_ERR_SRC_NOT_SUPPORTED` and no sound at all. Even had the scheme been
accepted, the first note would have waited behind a 7 MB transfer. HTTP
starts decoding at the first block, makes a seek one request rather than a
re-download, and writes nothing to the laptop — which is also why gallery
video uses it.

**On the token.** It is 256 bits from `SecureRandom`, issued only over the
encrypted link, regenerated on every app start (so yesterday's copy dies with
the process), compared constant-time, and deliberately *not* derived from the
pairing secret — a captured stream URL grants read access to media for as
long as that process lives, and nothing more. Concurrent sockets are capped
at 16 so an unattended port cannot be talked into spawning threads without
limit.

**Ordering, while here.** `media.list`'s `ORDER BY` is assembled from a
whitelist, appends `_id` as a tiebreaker (otherwise SQLite may return equal
keys in a different order each pass, which lets the pager's
`LIMIT offset,n` window skip or repeat rows), and applies `COLLATE NOCASE` to
text columns only — collating an `INTEGER` compares its text form, which
sorts `10` before `9`.

`genre` gets one extra leading term. SQLite sorts `NULL` before every other
value, and 1,501 of the 4,974 rows on the test phone carry no genre tag at
all, so a plain `genre ASC` opens on three screens of blank cells. The order
becomes `CASE WHEN genre IS NULL THEN 1 ELSE 0 END ASC` followed by the
requested direction — a standalone flag that deliberately does *not* take
`dir`, so untagged rows sit at the bottom in both directions. It is the only
column with an empty population: `title`, `artist` and `album` are `NULL` on
zero rows (a missing tag there is the literal string `<unknown>`, normalised
to empty on the way out).

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
- Media stream port: **27342** — `adb forward tcp:27342 tcp:27342` over USB,
  the phone's own address over WiFi. Fixed, so a playback URL needs no
  discovery round trip.
- Keepalive: ping 15 s / timeout 45 s. Reconnect backoff: 1, 2, 4, 8, 15 s (loop).
- Max frame: 8 MiB (chunked transfers never exceed 64 KiB + headers).
