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
    Ok(json!({
        "paired": inner.cfg.secret_b64.is_some(),
        "connected": inner.conn.is_some(),
        "peer": inner.conn.as_ref().map(|c| serde_json::to_value(&c.peer).unwrap_or(Value::Null)).unwrap_or(Value::Null),
        "serverRunning": inner.server_running,
        "ip": ip,
        "port": inner.cfg.port,
        "deviceName": inner.cfg.device_name,
        "micDevice": inner.cfg.mic_device,
        "clipboardEnabled": inner.cfg.clipboard_enabled,
        "autoAcceptFiles": inner.cfg.auto_accept_files,
        "downloadDir": inner.cfg.download_dir,
        "battery": inner.conn.as_ref().and_then(|c| c.peer.battery.clone()).unwrap_or(Value::Null),
    }))
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
        Ok::<Value, String>(json!({ "data": b64u(&chunk.1), "mime": mime }))
    }
    .await;
    state.inner.lock().await.data_collectors.remove(&tid);
    result
}

/// Download a photo/video from the phone into the InterLink folder.
#[tauri::command]
pub async fn media_download(
    state: State<'_, SharedState>,
    app: tauri::AppHandle,
    id: String,
    suggested: Option<String>,
) -> Result<Value, String> {
    let tid = crypto::new_transfer_id();
    let (tx, mut rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
    state.inner.lock().await.data_collectors.insert(tid, tx);

    let dl_dir = { state.inner.lock().await.cfg.download_dir.clone() };
    let result = async {
        let d = state
            .request_json("media.get", json!({ "id": id, "tid": b64u(&tid) }))
            .await?;
        let size = d["size"].as_u64().unwrap_or(0);
        let name = suggested
            .or_else(|| d["name"].as_str().map(String::from))
            .unwrap_or_else(|| format!("media_{id}"));
        let mime = d["mime"].as_str().unwrap_or("application/octet-stream").to_string();
        server::assemble_incoming(&state, tid, size, name, mime, dl_dir, &mut rx).await;
        Ok::<Value, String>(json!({ "ok": true }))
    }
    .await;
    state.inner.lock().await.data_collectors.remove(&tid);
    let _ = app;
    result
}

/// Send a local file to the phone. Streams chunks; phone replies file.done push.
#[tauri::command]
pub async fn file_send(state: State<'_, SharedState>, path: String) -> Result<Value, String> {
    use tokio::io::AsyncReadExt;
    let meta = tokio::fs::metadata(&path).await.map_err(|e| format!("internal: {e}"))?;
    if meta.is_dir() {
        return Err("internal: is a directory".into());
    }
    let size = meta.len();
    let name = std::path::Path::new(&path)
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
    let resp = match state
        .request(
            "file.offer",
            json!({ "tid": b64u(&tid), "name": name, "mime": mime, "size": size, "chunks": chunks, "dest": "download" }),
            Duration::from_secs(15),
        )
        .await
    {
        Ok(r) => r,
        Err(e) => {
            state.inner.lock().await.outgoing.remove(&tid);
            return Err(e);
        }
    };
    let _ = resp;

    let mut file = tokio::fs::File::open(&path).await.map_err(|e| format!("internal: {e}"))?;
    let mut buf = vec![0u8; 64 * 1024];
    let mut seq: u32 = 0;
    let mut sent: u64 = 0;
    loop {
        let n = file.read(&mut buf).await.map_err(|e| format!("internal: {e}"))?;
        if n == 0 {
            break;
        }
        if !state.send_data_frame(tid, seq, &buf[..n]).await {
            return Err("offline: connection lost mid-transfer".into());
        }
        seq += 1;
        sent += n as u64;
        if seq % 32 == 0 {
            state.emit("progress", json!({ "tid": b64u(&tid), "received": sent, "total": size }));
        }
    }
    state.emit("progress", json!({ "tid": b64u(&tid), "received": sent, "total": size }));
    // Completion arrives asynchronously: the phone pushes transfer.done /
    // file.done, which dispatch_envelope correlates via `outgoing`.
    Ok(json!({ "ok": true, "size": sent }))
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
pub async fn list_audio_inputs() -> Result<Value, String> {
    Ok(json!({ "devices": audio::list_inputs() }))
}

/// Start call audio relay for an active call (negotiates with the phone).
#[tauri::command]
pub async fn call_audio_start(
    state: State<'_, SharedState>,
    call_id: String,
    mic: Option<String>,
) -> Result<Value, String> {
    // Stop any previous relay. The lock guard must be released before
    // `audio::stop` runs — `audio::stop` takes the same lock (tokio Mutex
    // guards in `if let` scrutinees live until the end of the whole block).
    let old = state.inner.lock().await.call_audio.take();
    if let Some(old) = old {
        let old_id = old.call_id.clone();
        drop(old);
        audio::stop(&state, &old_id).await;
    }
    let mic = match mic {
        Some(m) => Some(m),
        None => state.inner.lock().await.cfg.mic_device.clone(),
    };
    match audio::start_negotiated(&state, &call_id, mic).await {
        Ok(handle) => {
            state.inner.lock().await.call_audio = Some(handle);
            Ok(json!({ "ok": true }))
        }
        Err(e) => Err(e),
    }
}

#[tauri::command]
pub async fn call_audio_stop(state: State<'_, SharedState>, call_id: Option<String>) -> Result<Value, String> {
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
    {
        let mut inner = state.inner.lock().await;
        if let Some(obj) = patch.as_object() {
            for (k, v) in obj {
                match k.as_str() {
                    "device_name" => inner.cfg.device_name = v.as_str().unwrap_or("Laptop").into(),
                    "mic_device" => inner.cfg.mic_device = v.as_str().map(String::from),
                    "clipboard_enabled" => inner.cfg.clipboard_enabled = v.as_bool().unwrap_or(true),
                    "auto_accept_files" => inner.cfg.auto_accept_files = v.as_bool().unwrap_or(true),
                    "download_dir" => inner.cfg.download_dir = v.as_str().map(String::from),
                    _ => {}
                }
            }
        }
    }
    state.save_config().await;
    get_settings(state).await
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
