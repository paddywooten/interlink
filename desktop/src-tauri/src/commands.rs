//! Tauri commands: the frontend's API surface.

use crate::audio;
use crate::crypto;
use crate::protocol::{self, make_qr_payload};
use crate::server::{self, b64u};
use crate::state::{Outgoing, SharedState};
use base64::Engine;
use serde_json::{json, Value};
use std::time::Duration;
use tauri::State;

#[tauri::command]
pub async fn get_status(state: State<'_, SharedState>) -> Result<Value, String> {
    let inner = state.inner.lock().await;
    let ip = crate::state::local_ip();
    let transport = inner
        .conn
        .as_ref()
        .map(|c| crate::state::transport_of(&c.ip));
    Ok(json!({
        "paired": inner.cfg.secret_b64.is_some(),
        "connected": inner.conn.is_some(),
        "peer": inner.conn.as_ref().map(|c| serde_json::to_value(&c.peer).unwrap_or(Value::Null)).unwrap_or(Value::Null),
        "serverRunning": inner.server_running,
        "ip": ip,
        "port": inner.cfg.port,
        "deviceName": inner.cfg.device_name,
        "micDevice": inner.cfg.mic_device,
        "speakerDevice": inner.cfg.speaker_device,
        "connection": inner.cfg.connection_mode(),
        "transport": transport,
        "adbDevice": inner.adb_device,
        "clipboardEnabled": inner.cfg.clipboard_enabled,
        "notifPopup": inner.cfg.notif_popup,
        "autoAcceptFiles": inner.cfg.auto_accept_files,
        "downloadDir": inner.cfg.download_dir,
        "battery": inner.conn.as_ref().and_then(|c| c.peer.battery.clone()).unwrap_or(Value::Null),
    }))
}

/// Devices discovered on the LAN via UDP beacons.
#[tauri::command]
pub async fn get_devices(state: State<'_, SharedState>) -> Result<Value, String> {
    let inner = state.inner.lock().await;
    let mut list: Vec<crate::state::SeenDevice> =
        inner.devices.values().cloned().collect();
    drop(inner);
    // Deterministic order (laptops first, then name) for a stable UI.
    list.sort_by(|a, b| a.role.cmp(&b.role).then(a.name.cmp(&b.name)));
    Ok(json!({ "devices": list }))
}

/// Create (or rotate) the pairing QR code.
#[tauri::command]
pub async fn get_pairing_qr(state: State<'_, SharedState>, rotate: Option<bool>) -> Result<Value, String> {
    // Embed the laptop's LAN IP so the phone can connect without UDP discovery.
    let ip = crate::state::local_ip();
    let (qr, rotated) = {
        let mut inner = state.inner.lock().await;
        let need_new = rotate.unwrap_or(false) || inner.cfg.secret_b64.is_none();
        if need_new {
            let secret = crypto::random_bytes32();
            inner.cfg.secret_b64 = Some(base64::engine::general_purpose::STANDARD.encode(secret));
            let payload =
                make_qr_payload(&secret, inner.cfg.port, &inner.cfg.device_name, ip.as_deref());
            (payload, true)
        } else {
            let secret = base64::engine::general_purpose::STANDARD
                .decode(inner.cfg.secret_b64.as_ref().unwrap())
                .map_err(|_| "internal: corrupt stored secret".to_string())?;
            if secret.len() != 32 {
                return Err("internal: corrupt stored secret".into());
            }
            let mut s32 = [0u8; 32];
            s32.copy_from_slice(&secret);
            (make_qr_payload(&s32, inner.cfg.port, &inner.cfg.device_name, ip.as_deref()), false)
        }
    };
    // The phone parses this exact layout — fail fast if make/parse disagree.
    protocol::parse_qr_payload(&qr).map_err(|e| format!("internal: pairing payload corrupt: {e}"))?;
    state.save_config().await;
    if rotated {
        // A new secret invalidates any current session.
        state.clear_connection().await;
        state.emit("conn", json!({ "connected": false }));
    }
    Ok(json!({ "qr": qr, "rotated": rotated }))
}

#[tauri::command]
pub async fn unpair(state: State<'_, SharedState>) -> Result<Value, String> {
    {
        let mut inner = state.inner.lock().await;
        inner.cfg.secret_b64 = None;
        inner.cfg.phone_hint = None;
    }
    state
        .send_envelope(&protocol::Envelope::push("unpair", json!({})))
        .await;
    state.clear_connection().await;
    state.save_config().await;
    state.emit("conn", json!({ "connected": false }));
    Ok(json!({ "ok": true }))
}

/// Generic request → the phone. Frontend uses this for most protocol messages.
#[tauri::command]
pub async fn send_request(
    state: State<'_, SharedState>,
    t: String,
    d: Value,
    timeout_ms: Option<u64>,
) -> Result<Value, String> {
    let timeout = Duration::from_millis(timeout_ms.unwrap_or(20_000));
    let resp = state.request(&t, d, timeout).await?;
    Ok(json!({ "d": resp.d }))
}

/// Fetch a media thumbnail as base64 JPEG. Laptop pre-registers the tid collector.
#[tauri::command]
pub async fn media_thumb(state: State<'_, SharedState>, id: String, w: Option<u32>) -> Result<Value, String> {
    let tid = crypto::new_transfer_id();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(8);
    state.inner.lock().await.data_collectors.insert(tid, tx);

    let result = async {
        let resp = state
            .request(
                "media.thumb",
                json!({ "id": id, "w": w.unwrap_or(320), "tid": b64u(&tid) }),
                Duration::from_secs(15),
            )
            .await?;
        let mime = resp.d["mime"].as_str().unwrap_or("image/jpeg").to_string();
        let chunk = tokio::time::timeout(Duration::from_secs(15), rx.recv())
            .await
            .map_err(|_| "busy: thumbnail timeout".to_string())?
            .ok_or_else(|| "internal: stream closed".to_string())?;
        Ok::<Value, String>(json!({
            // Standard base64 — `data:` URLs reject the URL-safe alphabet
            // (b64u's `-`/`_`), which silently breaks preview rendering.
            "data": base64::engine::general_purpose::STANDARD.encode(&chunk.1),
            "mime": mime
        }))
    }
    .await;
    state.inner.lock().await.data_collectors.remove(&tid);
    result
}

/// Download a photo/video from the phone into the InterLink folder.
/// Returns the on-disk path so the UI can play videos through the asset URL.
#[tauri::command]
pub async fn media_download(
    state: State<'_, SharedState>,
    app: tauri::AppHandle,
    id: String,
    suggested: Option<String>,
    subdir: Option<String>,
) -> Result<Value, String> {
    let tid = crypto::new_transfer_id();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
    state.inner.lock().await.data_collectors.insert(tid, tx);

    let dl_dir = {
        let base = state.inner.lock().await.cfg.download_dir.clone();
        // `subdir` is internal (backup folders); keep it under the base dir.
        match subdir {
            Some(s) if !s.is_empty() => {
                let root = base
                    .map(std::path::PathBuf::from)
                    .unwrap_or_else(server::default_download_dir);
                Some(root.join(s).display().to_string())
            }
            _ => base,
        }
    };
    let tid_label = b64u(&tid);
    let result = async {
        let d = state
            .request_json("media.get", json!({ "id": id, "tid": tid_label }))
            .await?;
        let size = d["size"].as_u64().unwrap_or(0);
        let name = suggested
            .or_else(|| d["name"].as_str().map(String::from))
            .unwrap_or_else(|| format!("media_{id}"));
        let mime = d["mime"].as_str().unwrap_or("application/octet-stream").to_string();
        let path =
            server::assemble_incoming(&state, tid, size, name, mime, dl_dir, &mut rx).await;
        let path = path.display().to_string();
        Ok::<Value, String>(json!({ "ok": true, "path": path, "tid": tid_label, "size": size }))
    }
    .await;
    state.inner.lock().await.data_collectors.remove(&tid);
    let _ = app;
    result
}

/// A URL the webview can put straight into `<audio>` / `<video>` and play.
///
/// Playback used to mean "pull the whole file over, write it to disk, then
/// play the copy" — `media_download` plus a `convertFileSrc` URL. Two things
/// were wrong with that. WebKitGTK rejects Tauri's `asset://` scheme on media
/// elements outright (`MEDIA_ERR_SRC_NOT_SUPPORTED`), so nothing played at
/// all; and even had it worked, silence would last until a 7 MB file had
/// crossed the link.
///
/// The phone serves the bytes itself now, with HTTP range support, so the
/// webview decodes from the first block and seeking is one request instead of
/// a re-download. Nothing is written to the laptop.
///
/// This only assembles the URL: the port is a fixed constant and the token
/// arrives over the already-encrypted link, so a stale or spoofed URL simply
/// gets a 401 from the phone.
#[tauri::command]
pub async fn media_stream_url(state: State<'_, SharedState>, id: String) -> Result<String, String> {
    // Prefer the tunnel whenever one is configured — the cable reaches the
    // phone even when the two sit on networks that cannot see each other,
    // which is the usual USB case. Otherwise the phone's address is already
    // known from the connection that just answered us.
    let host = {
        let inner = state.inner.lock().await;
        if inner.adb_device.is_some() && inner.cfg.connection_mode() != "wifi" {
            Some("127.0.0.1".to_string())
        } else {
            inner.conn.as_ref().map(|c| c.ip.clone())
        }
    };
    let host = host.ok_or_else(|| "Phone is not reachable".to_string())?;
    // An IPv6 literal needs brackets before it can carry a port.
    let host = if host.contains(':') && !host.starts_with('[') {
        format!("[{host}]")
    } else {
        host
    };

    let info = state.request_json("media.stream", json!({})).await?;
    let port = info["port"].as_u64().unwrap_or(crate::connection::MEDIA_PORT as u64);
    let token = info["token"]
        .as_str()
        .ok_or_else(|| "internal: media stream token missing".to_string())?;

    Ok(format!("http://{host}:{port}/m/{}?k={}", pct(&id), token))
}

/// Percent-encode for a URL path segment.
///
/// Ids look like `a:1000106750`, and a raw `:` in the first segment of a
/// relative reference is the one character RFC 3986 treats specially — so
/// encode everything outside the unreserved set rather than guess which
/// characters the phone will still read back correctly.
fn pct(s: &str) -> String {
    const HEX: &[u8; 16] = b"0123456789ABCDEF";
    let mut out = String::with_capacity(s.len() * 3);
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                out.push(b as char)
            }
            _ => {
                out.push('%');
                out.push(HEX[(b >> 4) as usize] as char);
                out.push(HEX[(b & 0x0f) as usize] as char);
            }
        }
    }
    out
}

/// Start screen mirroring. The phone launches its MediaProjection consent
/// dialog; on approval it streams JPEG frames as DATA frames on the fixed
/// mirror tid, which server.rs forwards to the webview as `mirror.frame`
/// pushes. Live state arrives via `mirror.state` pushes from the phone.
#[tauri::command]
pub async fn mirror_start(
    state: State<'_, SharedState>,
    w: Option<u32>,
    fps: Option<u32>,
    audio: Option<bool>,
) -> Result<Value, String> {
    let d = state
        .request_json(
            "mirror.start",
            json!({
                "w": w.unwrap_or(720), "fps": fps.unwrap_or(8),
                // The phone's own speaker keeps playing either way; this only
                // decides whether what it plays is also relayed here.
                "audio": audio.unwrap_or(true),
            }),
        )
        .await?;
    Ok(d)
}

/// Turn the phone's mirrored audio on or off while mirroring. The phone is
/// the source of truth: it answers, then reports the real outcome (its
/// capture rate, or why it can't) with a `mirror.audio` push that the server
/// reacts to — see `audio::on_mirror_push`.
#[tauri::command]
pub async fn mirror_audio(
    state: State<'_, SharedState>,
    on: bool,
) -> Result<Value, String> {
    let d = state.request_json("mirror.audio", json!({ "on": on })).await?;
    if !on {
        // Don't wait for a push the phone may not bother sending: nothing is
        // capturing now, so nothing may keep feeding the sink.
        audio::stop_mirror(&state).await;
    }
    Ok(d)
}

/// Stop screen mirroring (phone tears down the MediaProjection service).
#[tauri::command]
pub async fn mirror_stop(state: State<'_, SharedState>) -> Result<Value, String> {
    let res = state.request_json("mirror.stop", json!({})).await;
    // Stop painting regardless of what the phone replied: no frames are
    // coming, and a leftover sink would only hold a stale frame on screen.
    state.inner.lock().await.mirror_tx = None;
    // Audio goes with the video — same reasoning, and the phone stops its
    // capture on the same request whether or not it got to reply.
    audio::stop_mirror(&state).await;
    res
}

/// Send a local file to the phone. Streams chunks, then waits for the phone
/// to confirm it finished writing — callers (remote APK install, restore)
/// rely on the file actually existing on the phone, not just having left the
/// socket. `dest:"media"` restores straight into the photo/video library.
#[tauri::command]
pub async fn file_send(
    state: State<'_, SharedState>,
    path: String,
    dest: Option<String>,
) -> Result<Value, String> {
    send_file(&state, &path, dest.as_deref().unwrap_or("download")).await
}

/// Reusable core (also drives backup restore, which replays whole folders).
pub async fn send_file(state: &SharedState, path: &str, dest: &str) -> Result<Value, String> {
    use tokio::io::AsyncReadExt;
    let meta = tokio::fs::metadata(path).await.map_err(|e| format!("internal: {e}"))?;
    if meta.is_dir() {
        return Err("internal: is a directory".into());
    }
    let size = meta.len();
    let name = std::path::Path::new(path)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "file.bin".into());
    let mime = guess_mime(&name);
    let tid = crypto::new_transfer_id();
    let chunks = size.div_ceil(64 * 1024);

    // Track by tid so the phone's completion pushes (file.done / transfer.done
    // / transfer.failed) can be named and reported exactly once.
    state
        .inner
        .lock()
        .await
        .outgoing
        .insert(tid, Outgoing { name: name.clone(), done: false });
    // Reserve the completion wake-up before offering: the phone may push
    // `transfer.done` the instant we start streaming.
    let waiter_key = crate::state::waiter_key("filedone", &b64u(&tid));
    let waiter = state.add_waiter(waiter_key.clone()).await;
    let resp = match state
        .request(
            "file.offer",
            json!({ "tid": b64u(&tid), "name": name, "mime": mime, "size": size, "chunks": chunks, "dest": dest }),
            Duration::from_secs(15),
        )
        .await
    {
        Ok(r) => r,
        Err(e) => {
            let mut inner = state.inner.lock().await;
            inner.outgoing.remove(&tid);
            inner.waiters.remove(&waiter_key);
            return Err(e);
        }
    };
    let _ = resp;

    let mut file = tokio::fs::File::open(path).await.map_err(|e| format!("internal: {e}"))?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut seq: u32 = 0;
    let mut sent: u64 = 0;
    loop {
        let n = file.read(&mut buf).await.map_err(|e| format!("internal: {e}"))?;
        if n == 0 {
            break;
        }
        if !state.send_data_frame(tid, seq, &buf[..n]).await {
            let mut inner = state.inner.lock().await;
            inner.outgoing.remove(&tid);
            inner.waiters.remove(&waiter_key);
            return Err("offline: connection lost mid-transfer".into());
        }
        seq += 1;
        sent += n as u64;
        if seq % 32 == 0 {
            state.emit("progress", json!({ "tid": b64u(&tid), "received": sent, "total": size }));
        }
    }
    state.emit("progress", json!({ "tid": b64u(&tid), "received": sent, "total": size }));

    // The phone pushes transfer.done / file.done once it has flushed to disk.
    let wait_secs = (60 + size / (1024 * 1024)).min(600);
    let completed = match tokio::time::timeout(Duration::from_secs(wait_secs), waiter).await {
        Ok(Ok(v)) => v["ok"].as_bool().unwrap_or(false),
        Ok(Err(_)) => false,
        Err(_) => {
            state.inner.lock().await.waiters.remove(&waiter_key);
            false
        }
    };
    Ok(json!({ "ok": true, "size": sent, "completed": completed, "tid": b64u(&tid), "name": name }))
}

fn guess_mime(name: &str) -> String {
    let ext = std::path::Path::new(name)
        .extension()
        .map(|e| e.to_string_lossy().to_lowercase())
        .unwrap_or_default();
    match ext.as_str() {
        "jpg" | "jpeg" => "image/jpeg",
        "png" => "image/png",
        "gif" => "image/gif",
        "webp" => "image/webp",
        "heic" => "image/heic",
        "mp4" | "m4v" => "video/mp4",
        "mkv" => "video/x-matroska",
        "webm" => "video/webm",
        "mp3" => "audio/mpeg",
        "wav" => "audio/wav",
        "flac" => "audio/flac",
        "ogg" => "audio/ogg",
        "pdf" => "application/pdf",
        "txt" => "text/plain",
        "zip" => "application/zip",
        "apk" => "application/vnd.android.package-archive",
        "vcf" => "text/vcard",
        _ => "application/octet-stream",
    }
    .to_string()
}

#[tauri::command]
pub async fn list_audio_inputs(state: State<'_, SharedState>) -> Result<Value, String> {
    let saved = state.inner.lock().await.cfg.mic_device.clone();
    Ok(json!({ "devices": with_saved(audio::list_inputs(), saved) }))
}

/// Output devices for the far end of a call (laptop speakers, Bluetooth
/// speakers, HDMI, …) — the "Hear through" picker.
#[tauri::command]
pub async fn list_audio_outputs(state: State<'_, SharedState>) -> Result<Value, String> {
    let saved = state.inner.lock().await.cfg.speaker_device.clone();
    Ok(json!({ "devices": with_saved(audio::list_outputs(), saved) }))
}

/// A saved device can predate the PipeWire picker (raw ALSA names were all
/// cpal could offer). Keep it selectable rather than leaving the `<select>`
/// pointing at an option that no longer exists.
fn with_saved(mut devices: Vec<Value>, saved: Option<String>) -> Vec<Value> {
    if let Some(saved) = saved.filter(|s| !s.is_empty()) {
        if !devices.iter().any(|d| d["name"] == saved.as_str()) {
            devices.insert(0, json!({ "name": saved, "label": saved, "default": false }));
        }
    }
    devices
}

/// Start call audio relay for an active call (negotiates with the phone).
///
/// `uplink` (speak through the laptop) and `downlink` (hear through the
/// laptop) are independent; both false keeps the call entirely on the phone.
#[tauri::command]
pub async fn call_audio_start(
    state: State<'_, SharedState>,
    call_id: String,
    mic: Option<String>,
    speaker: Option<String>,
    uplink: Option<bool>,
    downlink: Option<bool>,
) -> Result<Value, String> {
    // Serialize with any concurrent start/stop so two quick changes can't both
    // find `call_audio` empty and both start a relay (the loser's handle would
    // be overwritten, leaving its mic stream open with nothing draining it).
    let _gate = state.call_audio_gate.lock().await;
    // Stop any previous relay. The lock guard must be released before
    // `audio::stop` runs — `audio::stop` takes the same lock (tokio Mutex
    // guards in `if let` scrutinees live until the end of the whole block).
    let old = state.inner.lock().await.call_audio.take();
    if let Some(old) = old {
        let old_id = old.call_id.clone();
        drop(old);
        audio::stop(&state, &old_id).await;
    }
    let (mic, speaker, uplink, downlink) = {
        let inner = state.inner.lock().await;
        (
            mic.or_else(|| inner.cfg.mic_device.clone()),
            speaker.or_else(|| inner.cfg.speaker_device.clone()),
            uplink.unwrap_or(true),
            downlink.unwrap_or(true),
        )
    };
    if !uplink && !downlink {
        // Nothing to relay: the phone handles both directions itself.
        return Ok(json!({ "ok": true, "mode": "phone" }));
    }
    // Keep for routing after the streams are up (start consumes the values).
    let (mic_route, spk_route) = (mic.clone(), speaker.clone());
    match audio::start_negotiated(&state, &call_id, mic, speaker, uplink, downlink).await {
        Ok(handle) => {
            state.inner.lock().await.call_audio = Some(handle);
            // Honour a *picked* PipeWire node (Bluetooth, HDMI, …). cpal can
            // only address ALSA devices, so the stream opens on the system
            // default and is relinked here — stream-scoped, so the default
            // and every other app stay where they are.
            if downlink {
                audio::route_async(audio::Track::Playback, spk_route.as_deref());
            }
            if uplink {
                audio::route_async(audio::Track::Capture, mic_route.as_deref());
            }
            Ok(json!({
                "ok": true,
                "mode": if uplink && downlink { "relay" } else if downlink { "hear_laptop" } else { "speak_laptop" },
            }))
        }
        Err(e) => Err(e),
    }
}

#[tauri::command]
pub async fn call_audio_stop(state: State<'_, SharedState>, call_id: Option<String>) -> Result<Value, String> {
    // Same gate as `call_audio_start`: a stop must not slip in between another
    // start's "take old" and "store new" steps.
    let _gate = state.call_audio_gate.lock().await;
    let handle = state.inner.lock().await.call_audio.take();
    let call_id = call_id.or_else(|| handle.as_ref().map(|h| h.call_id.clone()));
    drop(handle);
    if let Some(id) = call_id {
        if !id.is_empty() {
            audio::stop(&state, &id).await;
        }
    }
    Ok(json!({ "ok": true }))
}

#[tauri::command]
pub async fn call_mute(state: State<'_, SharedState>, muted: bool) -> Result<Value, String> {
    let inner = state.inner.lock().await;
    match inner.call_audio.as_ref() {
        Some(h) => {
            h.muted.store(muted, std::sync::atomic::Ordering::Relaxed);
            Ok(json!({ "ok": true, "muted": muted }))
        }
        None => Err("internal: no active call audio".into()),
    }
}

#[tauri::command]
pub async fn get_settings(state: State<'_, SharedState>) -> Result<Value, String> {
    let inner = state.inner.lock().await;
    serde_json::to_value(&inner.cfg).map_err(|e| format!("internal: {e}"))
}

#[tauri::command]
pub async fn set_settings(state: State<'_, SharedState>, patch: Value) -> Result<Value, String> {
    let mut connection_changed = false;
    {
        let mut inner = state.inner.lock().await;
        if let Some(obj) = patch.as_object() {
            for (k, v) in obj {
                match k.as_str() {
                    "device_name" => inner.cfg.device_name = v.as_str().unwrap_or("Laptop").into(),
                    "mic_device" => inner.cfg.mic_device = v.as_str().map(String::from),
                    "speaker_device" => inner.cfg.speaker_device = v.as_str().map(String::from),
                    "connection" => {
                        let want = match v.as_str().unwrap_or("auto") {
                            "usb" => "usb",
                            "wifi" => "wifi",
                            _ => "auto",
                        };
                        if inner.cfg.connection != want {
                            inner.cfg.connection = want.into();
                            connection_changed = true;
                        }
                    }
                    "clipboard_enabled" => inner.cfg.clipboard_enabled = v.as_bool().unwrap_or(true),
                    "auto_accept_files" => inner.cfg.auto_accept_files = v.as_bool().unwrap_or(true),
                    "notif_popup" => inner.cfg.notif_popup = v.as_bool().unwrap_or(true),
                    "download_dir" => inner.cfg.download_dir = v.as_str().map(String::from),
                    _ => {}
                }
            }
        }
    }
    state.save_config().await;
    if connection_changed {
        // Apply the tunnel now (don't wait for the ticker) and drop the link
        // so the phone re-handshakes onto the preferred route — it only tries
        // 127.0.0.1 while disconnected.
        crate::connection::tick(&state).await;
        state.clear_connection().await;
        state.emit("conn", json!({ "connected": false }));
    }
    get_settings(state).await
}

/// Re-scan for a USB device and re-apply the reverse tunnel on demand.
#[tauri::command]
pub async fn connection_refresh(state: State<'_, SharedState>) -> Result<Value, String> {
    crate::connection::tick(&state).await;
    let inner = state.inner.lock().await;
    let transport = inner
        .conn
        .as_ref()
        .map(|c| crate::state::transport_of(&c.ip));
    Ok(json!({
        "connection": inner.cfg.connection_mode(),
        "adbDevice": inner.adb_device,
        "transport": transport,
    }))
}

/// Pick an .apk to push to the phone (remote app install).
#[tauri::command]
pub async fn pick_apk() -> Result<Value, String> {
    let file = rfd::AsyncFileDialog::new()
        .set_title("Choose an Android package")
        .add_filter("Android package", &["apk"])
        .pick_file()
        .await;
    Ok(match file {
        Some(f) => json!({ "path": f.path().display().to_string() }),
        None => json!({ "path": null }),
    })
}

/// Create a backup folder on this laptop: contacts (vCard), SMS (JSON) and
/// every photo/video the phone will hand over.
#[tauri::command]
pub async fn backup_start(
    state: State<'_, SharedState>,
    sections: Option<Vec<String>>,
    out_dir: Option<String>,
) -> Result<Value, String> {
    let sections =
        sections.unwrap_or_else(|| vec!["contacts".into(), "sms".into(), "photos".into()]);
    crate::backup::start(&state, sections, out_dir).await
}

/// Replay a backup folder onto the phone.
#[tauri::command]
pub async fn backup_restore(state: State<'_, SharedState>, dir: String) -> Result<Value, String> {
    crate::backup::restore(&state, dir).await
}

/// Describe a backup folder so the UI can show what a restore will restore.
#[tauri::command]
pub async fn backup_list(dir: String) -> Result<Value, String> {
    crate::backup::list(dir).await
}

/// Pick a folder to back up into / restore from.
#[tauri::command]
pub async fn pick_backup_dir() -> Result<Value, String> {
    let dir = rfd::AsyncFileDialog::new()
        .set_title("Choose a backup folder")
        .pick_folder()
        .await;
    Ok(match dir {
        Some(d) => json!({ "path": d.path().display().to_string() }),
        None => json!({ "path": null }),
    })
}

/// Pick a directory with a native dialog.
#[tauri::command]
pub async fn pick_directory() -> Result<Value, String> {
    let dir = rfd::AsyncFileDialog::new()
        .set_title("Choose download folder")
        .pick_folder()
        .await;
    Ok(match dir {
        Some(d) => json!({ "path": d.path().display().to_string() }),
        None => json!({ "path": null }),
    })
}

/// Pick files to send to the phone. Returns absolute paths.
#[tauri::command]
pub async fn pick_files() -> Result<Value, String> {
    let files = rfd::AsyncFileDialog::new()
        .set_title("Send files to phone")
        .pick_files()
        .await;
    let paths: Vec<String> = files
        .map(|fs| fs.iter().map(|f| f.path().display().to_string()).collect())
        .unwrap_or_default();
    Ok(json!({ "paths": paths }))
}

/// Reveal a file in the system file manager.
#[tauri::command]
pub async fn reveal_in_folder(path: String) -> Result<(), String> {
    let p = std::path::PathBuf::from(&path);
    if let Some(parent) = p.parent() {
        if parent.exists() {
            Opener::open_path(parent.to_string_lossy().to_string());
            return Ok(());
        }
    }
    Err("internal: path not found".into())
}

/// Open a URL/path with the system handler (bundled opener plugin fallback).
struct Opener;
impl Opener {
    fn open_path(p: String) {
        let _ = std::process::Command::new("xdg-open").arg(&p).spawn();
    }
}

/// Base64 of a mirrored notification's attachment, for the in-app thumbnail.
#[tauri::command]
pub async fn notif_image(state: State<'_, SharedState>, key: String) -> Result<Value, String> {
    let bytes = state
        .get_notif_image(&key)
        .await
        .ok_or_else(|| "that attachment is no longer held".to_string())?;
    Ok(json!({
        "mime": "image/jpeg",
        "data": base64::engine::general_purpose::STANDARD.encode(bytes),
    }))
}

/// Save a mirrored notification's attachment into the download folder.
///
/// This is the whole point of mirroring a picture in the first place: a photo
/// that arrives in a notification is usually one the user wants to keep, and
/// on the phone it is already on its way out — apps cancel those notifications
/// almost as soon as they show them.
#[tauri::command]
pub async fn notif_save_image(
    state: State<'_, SharedState>,
    key: String,
    name: Option<String>,
) -> Result<String, String> {
    let bytes = state
        .get_notif_image(&key)
        .await
        .ok_or_else(|| "that attachment is no longer held".to_string())?;
    let configured = {
        let inner = state.inner.lock().await;
        inner.cfg.download_dir.clone()
    };
    let base = configured
        .map(std::path::PathBuf::from)
        .unwrap_or_else(server::default_download_dir);
    let _ = tokio::fs::create_dir_all(&base).await;
    let dest = server::unique_path(&base, &safe_filename(name.as_deref().unwrap_or("attachment")));
    tokio::fs::write(&dest, &bytes)
        .await
        .map_err(|e| format!("could not write the file: {e}"))?;
    log::info!("saved notification attachment to {}", dest.display());
    Ok(dest.display().to_string())
}

/// Turn a notification's app name and title into a safe file name.
///
/// The name is arbitrary text from arbitrary apps, so it has to survive the
/// trip into a folder: separators would climb out of the download directory,
/// leading dots would hide the file, and a name made entirely of junk must
/// still produce something writable.
pub fn safe_filename(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '-',
            c if c.is_control() => ' ',
            c => c,
        })
        .collect();

    // Truncate by characters, not bytes: `String::truncate` panics on a
    // multi-byte boundary, and app titles are full of them.
    let mut name: String = cleaned
        .chars()
        .take(120)
        .collect::<String>()
        .trim()
        .trim_matches('.')
        .trim()
        .to_string();
    if name.is_empty() {
        name = "attachment".into();
    }
    if !name.to_lowercase().ends_with(".jpg") {
        name.push_str(".jpg");
    }
    name
}

#[cfg(test)]
mod tests {
    use super::{pct, safe_filename};

    /// An app name and a title arrive as free text from arbitrary apps; the
    /// result still has to be a file inside the download folder.
    #[test]
    fn attachment_names_stay_inside_the_download_folder() {
        assert_eq!(safe_filename("WhatsApp"), "WhatsApp.jpg");
        // Separators become dashes and leading dots go, so nothing climbs out.
        assert_eq!(safe_filename("../../etc/passwd"), "-..-etc-passwd.jpg");
        assert_eq!(safe_filename(".."), "attachment.jpg");
        assert_eq!(safe_filename("   "), "attachment.jpg");
        assert_eq!(safe_filename(""), "attachment.jpg");
        assert_eq!(safe_filename("IMG:2026/10/28"), "IMG-2026-10-28.jpg");
        // An extension that is already there is not doubled up.
        assert_eq!(safe_filename("already.jpg"), "already.jpg");
        assert_eq!(safe_filename("photo.JPG"), "photo.JPG");
    }

    #[test]
    fn control_characters_and_very_long_titles_are_tamed() {
        assert_eq!(safe_filename("a\u{0}b\nc"), "a b c.jpg");
        // A 500-character emoji title must not panic on a byte boundary.
        let long = "📸".repeat(250);
        let out = safe_filename(&long);
        assert!(out.chars().count() <= 124, "got {} chars", out.chars().count());
        assert!(out.ends_with(".jpg"));
    }

    /// The phone splits the path on `:` to work out which collection an id
    /// names, so the separator has to survive the trip unchanged — and a raw
    /// `:` is the one character RFC 3986 treats specially in a path segment.
    #[test]
    fn media_ids_encode_their_separator() {
        assert_eq!(pct("a:1000106750"), "a%3A1000106750");
        assert_eq!(pct("v:42"), "v%3A42");
        assert_eq!(pct("i:0"), "i%3A0");
    }

    #[test]
    fn unreserved_bytes_pass_through_and_everything_else_escapes() {
        assert_eq!(pct("abcXYZ019-._~"), "abcXYZ019-._~");
        // Query delimiters, the fragment marker, the space, and the `%` that
        // would otherwise open a bogus escape of its own.
        assert_eq!(pct("a&b=c d#e%f/g"), "a%26b%3Dc%20d%23e%25f%2Fg");
    }

    /// The phone decodes with a `%xx` → byte → UTF-8 reader; this mirrors it
    /// so the two halves are checked against each other rather than each
    /// against its own idea of the format.
    #[test]
    fn ids_round_trip_through_the_phones_decoder() {
        for id in ["a:1", "v:9999999999", "i:0", "x:folder/name?&"] {
            assert_eq!(decode(&pct(id)), id, "round trip of {id}");
        }
    }

    fn decode(s: &str) -> String {
        let mut out: Vec<u8> = Vec::with_capacity(s.len());
        let b = s.as_bytes();
        let mut i = 0;
        while i < b.len() {
            if b[i] == b'%' && i + 2 < b.len() {
                if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                    out.push(v);
                    i += 3;
                    continue;
                }
            }
            out.push(b[i]);
            i += 1;
        }
        String::from_utf8(out).expect("decoded id is UTF-8")
    }
}
