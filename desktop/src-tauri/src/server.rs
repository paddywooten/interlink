//! WebSocket server: pairing handshake, session crypto, dispatch, discovery.

use crate::crypto::{self, FrameKey};
use crate::protocol::Envelope;
use crate::state::{Conn, PeerInfo, SharedState};
use base64::Engine;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::net::{TcpListener, UdpSocket};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::Message;

const PING_INTERVAL: Duration = Duration::from_secs(15);
const IDLE_TIMEOUT: Duration = Duration::from_secs(45);

/// Start the TCP listener + UDP discovery responder. Returns after binding.
pub async fn start(state: SharedState, app: tauri::AppHandle) -> Result<(), String> {
    let port = { state.inner.lock().await.cfg.port };
    let listener = TcpListener::bind(("0.0.0.0", port))
        .await
        .map_err(|e| format!("cannot bind port {port}: {e}"))?;

    {
        let mut inner = state.inner.lock().await;
        inner.server_running = true;
    }

    tokio::spawn(discovery_responder(state.clone(), port));
    tokio::spawn(beacon_service(state.clone(), port));
    tokio::spawn(async move {
        loop {
            match listener.accept().await {
                Ok((stream, peer_addr)) => {
                    log::info!("incoming connection from {peer_addr}");
                    let st = state.clone();
                    let app = app.clone();
                    tokio::spawn(async move {
                        let ws = tokio_tungstenite::accept_async(stream).await;
                        match ws {
                            Ok(ws) => handle_connection(ws, st, app, peer_addr).await,
                            Err(e) => log::warn!("websocket handshake failed: {e}"),
                        }
                    });
                }
                Err(e) => {
                    log::error!("accept error: {e}");
                    tokio::time::sleep(Duration::from_secs(1)).await;
                }
            }
        }
    });
    Ok(())
}

/// Respond to UDP "ILWHOIS?" broadcasts from the phone (LAN discovery).
async fn discovery_responder(state: SharedState, port: u16) {
    let sock = match UdpSocket::bind(("0.0.0.0", port)).await {
        Ok(s) => s,
        Err(e) => {
            log::warn!("udp discovery disabled: {e}");
            return;
        }
    };
    let mut buf = [0u8; 512];
    loop {
        if let Ok((n, from)) = sock.recv_from(&mut buf).await {
            let msg = &buf[..n];
            if msg == b"ILWHOIS?" {
                let name = state.inner.lock().await.cfg.device_name.clone();
                let reply = format!("ILHERE|{}|{}", base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(name), port);
                let _ = sock.send_to(reply.as_bytes(), from).await;
            }
        }
    }
}

/// Periodically announce this laptop on the LAN and record peers that do the
/// same, so the UI can show every InterLink device on the current network.
/// Runs on its own UDP port (BEACON_PORT) so it never disturbs the connect
/// handshake on the main link port.
async fn beacon_service(state: SharedState, link_port: u16) {
    let sock = match UdpSocket::bind(("0.0.0.0", crate::state::BEACON_PORT)).await {
        Ok(s) => s,
        Err(e) => {
            log::warn!("beacon discovery disabled: {e}");
            return;
        }
    };
    let _ = sock.set_broadcast(true);
    let mut buf = [0u8; 512];
    let mut tick = tokio::time::interval(Duration::from_secs(3));
    loop {
        tokio::select! {
            _ = tick.tick() => {
                let name = state.inner.lock().await.cfg.device_name.clone();
                let msg = format!(
                    "ILANN|{}|laptop|{}",
                    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(name),
                    link_port
                );
                let _ = sock.send_to(msg.as_bytes(), ("255.255.255.255", crate::state::BEACON_PORT)).await;
                // Drop devices we haven't heard from in ~15s.
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                let mut inner = state.inner.lock().await;
                let connected_ip = inner.conn.as_ref().map(|c| c.ip.clone());
                inner.devices.retain(|_, d| {
                    connected_ip.as_deref() == Some(d.ip.as_str())
                        || now.saturating_sub(d.last_seen) < 15_000
                });
            }
            res = sock.recv_from(&mut buf) => {
                let Ok((n, from)) = res else { continue };
                let Ok(text) = std::str::from_utf8(&buf[..n]) else { continue };
                // ILANN|<b64 name>|<role>|<port>
                let mut parts = text.split('|');
                if parts.next() != Some("ILANN") { continue }
                let Some(name_b64) = parts.next() else { continue };
                let Some(role) = parts.next() else { continue };
                let Some(port) = parts.next().and_then(|p| p.parse::<u16>().ok()) else { continue };
                let name = base64::engine::general_purpose::URL_SAFE_NO_PAD
                    .decode(name_b64)
                    .map(|b| String::from_utf8_lossy(&b).to_string())
                    .unwrap_or_else(|_| "unknown".into());
                let ip = from.ip().to_string();
                // Ignore our own beacons (loopback of the broadcast).
                if role == "laptop" && ip == crate::state::local_ip().unwrap_or_default() { continue }
                let now = std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .map(|d| d.as_millis() as u64)
                    .unwrap_or(0);
                let key = format!("{ip}:{port}");
                let mut inner = state.inner.lock().await;
                let is_new = !inner.devices.contains_key(&key);
                if is_new {
                    log::info!("discovered {role} {name} at {ip}:{port}");
                }
                inner.devices.insert(key, crate::state::SeenDevice {
                    name, role: role.into(), ip, port, last_seen: now,
                });
                drop(inner);
                // Notify the frontend only when the set of devices changes.
                if is_new {
                    state.emit("devices", json!({ "changed": true }));
                }
            }
        }
    }
}

async fn handle_connection(
    ws: tokio_tungstenite::WebSocketStream<tokio::net::TcpStream>,
    state: SharedState,
    app: tauri::AppHandle,
    peer_addr: std::net::SocketAddr,
) {
    let (mut ws_tx, mut ws_rx) = ws.split();
    let (out_tx, mut out_rx) = mpsc::channel::<Vec<u8>>(128);

    // ——— Handshake: first frame must be root-key-sealed hello ———
    let secret = match state.config_secret().await {
        Some(s) => s,
        None => {
            log::warn!("unpaired peer rejected from {peer_addr}");
            state.emit(
                "conn",
                json!({ "connected": false, "error": "unpaired",
                        "detail": "A phone connected but this laptop has no pairing secret. Open the Pair dialog and re-scan." }),
            );
            return;
        }
    };
    let root = crypto::frame_key(crypto::derive_root_key(&secret));

    let first = match tokio::time::timeout(IDLE_TIMEOUT, ws_rx.next()).await {
        Ok(Some(Ok(Message::Binary(b)))) => b,
        _ => {
            log::warn!("no hello frame from {peer_addr}");
            return;
        }
    };
    let hello_pt = match root.open_msg(first.as_ref()) {
        Ok(p) => p,
        Err(_) => {
            log::warn!("hello failed to decrypt from {peer_addr} — wrong pairing secret?");
            state.emit(
                "conn",
                json!({ "connected": false, "error": "pairing_mismatch",
                        "detail": "A phone tried to connect with an old pairing code. Rotate the QR code and re-scan from the phone." }),
            );
            return;
        }
    };
    let hello: Envelope = match serde_json::from_slice::<Envelope>(&hello_pt) {
        Ok(e) if e.t == "hello" => e,
        _ => {
            log::warn!("expected hello from {peer_addr}");
            return;
        }
    };
    let client_nonce_b64 = hello.d["nonce"].as_str().unwrap_or_default().to_string();
    let client_nonce = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(&client_nonce_b64)
        .unwrap_or_default();
    if client_nonce.len() != 32 {
        log::warn!("bad client nonce from {peer_addr}");
        return;
    }

    let server_nonce = crypto::random_nonce32();
    let ack = Envelope {
        v: 1,
        t: "hello_ack".into(),
        id: crate::protocol::new_id(),
        r: None,
        ok: None,
        e: None,
        d: json!({
            "nonce": base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&server_nonce),
            "device": { "name": state.inner.lock().await.cfg.device_name.clone(), "os": "linux", "app": "InterLink Laptop" },
            "caps": ["notifications", "clipboard", "files", "calls", "ui"],
        }),
    };
    let ack_bytes = root.seal_msg(&serde_json::to_vec(&ack).expect("ack serializes"));
    if ws_tx.send(Message::Binary(ack_bytes.into())).await.is_err() {
        return;
    }

    let session_key = crypto::frame_key(crypto::derive_session_key(&secret, &client_nonce, &server_nonce));

    // ——— Register connection (replaces any previous one) ———
    let peer = PeerInfo {
        name: hello.d["device"]["name"].as_str().unwrap_or("Phone").into(),
        model: hello.d["device"]["model"].as_str().unwrap_or_default().into(),
        os: hello.d["device"]["os"].as_str().unwrap_or("android").into(),
        app: hello.d["device"]["app"].as_str().unwrap_or_default().into(),
        caps: hello.d["caps"]
            .as_array()
            .map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect())
            .unwrap_or_default(),
        battery: None,
    };

    {
        let mut inner = state.inner.lock().await;
        // Fail requests tied to a previous connection.
        for (_, tx) in inner.pending.drain() {
            let _ = tx.send(Envelope::error_response("?", "internal", "offline: reconnected"));
        }
        inner.data_collectors.clear();
        inner.outgoing.clear();
        let peer_ip = peer_addr.ip().to_string();
        let device_port = inner.cfg.port;
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis() as u64)
            .unwrap_or(0);
        // The encrypted link is also a presence signal. This keeps the phone
        // visible even if a laptop firewall blocks the UDP beacon port.
        let device_key = format!("{peer_ip}:{device_port}");
        let is_new = !inner.devices.contains_key(&device_key);
        if is_new {
            log::info!("discovered phone {} at {peer_ip}:{device_port} (link)", peer.name);
        }
        inner.devices.insert(
            device_key,
            crate::state::SeenDevice {
                name: peer.name.clone(),
                role: "phone".into(),
                ip: peer_ip.clone(),
                port: device_port,
                last_seen: now,
            },
        );
        inner.conn = Some(Conn {
            tx: out_tx.clone(),
            peer: peer.clone(),
            session_key: session_key.clone(),
            ip: peer_ip,
        });
    }
    state.emit("devices", json!({ "changed": true }));
    state.emit("conn", json!({ "connected": true, "peer": peer }));
    log::info!("phone connected: {} ({})", peer.name, peer.model);

    // ——— Writer task ———
    let writer = tokio::spawn(async move {
        while let Some(frame) = out_rx.recv().await {
            if ws_tx.send(Message::Binary(frame.into())).await.is_err() {
                break;
            }
        }
    });

    // ——— Pinger ———
    let st_ping = state.clone();
    let pinger = tokio::spawn(async move {
        loop {
            tokio::time::sleep(PING_INTERVAL).await;
            let env = Envelope::push("ping", json!({}));
            if !st_ping.send_envelope(&env).await {
                break;
            }
        }
    });

    // ——— Reader ———
    loop {
        let frame: Option<Vec<u8>> = tokio::time::timeout(IDLE_TIMEOUT, ws_rx.next())
            .await
            .ok()
            .flatten()
            .and_then(|m| match m {
                Ok(Message::Binary(b)) => Some(b.to_vec()),
                Ok(Message::Close(_)) | Err(_) => None,
                Ok(_) => Some(Vec::new()), // ping/pong/text → ignore marker
            });
        let frame = match frame {
            Some(f) => f,
            None => break, // disconnect or idle timeout
        };
        if frame.is_empty() {
            continue;
        }

        match FrameKey::kind_of(&frame) {
            Some(crate::crypto::KIND_MSG) => {
                let pt = match session_key.open_msg(&frame) {
                    Ok(p) => p,
                    Err(_) => {
                        log::warn!("frame decrypt failed — closing connection");
                        break;
                    }
                };
                let env: Envelope = match serde_json::from_slice(&pt) {
                    Ok(e) => e,
                    Err(_) => continue,
                };
                dispatch_envelope(&state, &app, env).await;
            }
            Some(crate::crypto::KIND_DATA) => {
                match session_key.open_data(&frame) {
                    Ok((tid, seq, chunk)) => {
                        // Screen-mirror frames stream on a fixed tid with no
                        // collector: each chunk is one complete JPEG (seq is a
                        // frame counter) and is forwarded straight to the webview.
                        if tid == crate::crypto::mirror_tid() {
                            state.emit(
                                "push",
                                json!({
                                    "t": "mirror.frame",
                                    "d": {
                                        "seq": seq,
                                        "mime": "image/jpeg",
                                        "data": base64::engine::general_purpose::STANDARD
                                            .encode(&chunk),
                                    }
                                }),
                            );
                        } else {
                            let collector =
                                { state.inner.lock().await.data_collectors.get(&tid).cloned() };
                            if let Some(tx) = collector {
                                if tx.send((seq, chunk)).await.is_err() {
                                    state.inner.lock().await.data_collectors.remove(&tid);
                                }
                            }
                        }
                    }
                    Err(_) => {
                        log::warn!("data frame decrypt failed — closing connection");
                        break;
                    }
                }
            }
            _ => break,
        }
    }

    // ——— Teardown ———
    pinger.abort();
    writer.abort();
    if let Some(peer) = state.clear_connection().await {
        log::info!("phone disconnected: {}", peer.name);
    }
    state.emit("conn", json!({ "connected": false }));
}

/// Handle an inbound envelope from the phone: a response to one of our
/// requests, a phone request needing a reply, or a push for the webview.
async fn dispatch_envelope(state: &SharedState, app: &tauri::AppHandle, env: Envelope) {
    // 1. Response to one of our requests? (`r` correlates it.)
    if env.is_response() {
        let rid = env.r.clone().unwrap_or_default();
        let waiter = state.inner.lock().await.pending.remove(rid.as_str());
        match waiter {
            Some(tx) => {
                let _ = tx.send(env);
            }
            None => {
                // e.g. the pong for our keepalive ping — push ids are never
                // registered as pending, but status piggybacked on it still
                // deserves to land (PROTOCOL.md §4.1: pong carries battery).
                if env.t == "pong" {
                    if let Some(battery) = env.d.get("battery") {
                        {
                            let mut inner = state.inner.lock().await;
                            if let Some(conn) = inner.conn.as_mut() {
                                conn.peer.battery = Some(battery.clone());
                            }
                        }
                        state.emit("push", json!({ "t": "device.battery", "d": battery }));
                    }
                }
            }
        }
        return;
    }

    // 2. Requests from the phone that need a reply.
    match env.t.as_str() {
        "file.offer" => {
            handle_inbound_file_offer(state, app, env).await;
            return;
        }
        "ping" => {
            let mut pong = Envelope::response(&env.id, "ping", json!({}));
            pong.t = "pong".into();
            state.send_envelope(&pong).await;
            return;
        }
        _ => {}
    }

    // 3. Pushes: side effects first, then forward {t, d} to the webview.
    //    Anything not matched below is relayed verbatim, so new phone-side
    //    push types reach the UI without a server change.
    let (t, d) = match env.t.as_str() {
        "unpair" => {
            state.clear_connection().await;
            state.emit("conn", json!({ "connected": false }));
            state.emit("push", json!({ "t": "unpair", "d": env.d }));
            return;
        }
        "device.battery" => {
            {
                let mut inner = state.inner.lock().await;
                if let Some(conn) = inner.conn.as_mut() {
                    conn.peer.battery = Some(env.d.clone());
                }
            }
            ("device.battery", env.d)
        }
        // Terminal event for a laptop-initiated transfer. Synthesize the
        // {name, path} shape the webview expects unless `transfer.done`
        // (which carries the name) already reported it.
        "file.done" => {
            let path = env.d["path"].as_str().unwrap_or("").to_string();
            let d = match finish_outgoing(state, &env.d).await {
                Some((_, true)) => return, // already reported
                Some((name, false)) => json!({ "name": name, "path": path, "tid": env.d["tid"] }),
                None => json!({ "name": basename(&path), "path": path, "tid": env.d["tid"] }),
            };
            state.emit("push", json!({ "t": "transfer.done", "d": d }));
            return;
        }
        // The phone pushes `transfer.done`/`transfer.failed` (with name) and
        // the canonical `file.done` for the same tid — report each once.
        "transfer.done" | "transfer.failed" => {
            if let Some((_, true)) = finish_outgoing(state, &env.d).await {
                return; // companion push already reported it
            }
            (env.t.as_str(), env.d)
        }
        "transfer.cancel" => {
            let reason = env.d["reason"].as_str().unwrap_or("cancelled").to_string();
            let name = match finish_outgoing(state, &env.d).await {
                Some((_, true)) => return,
                Some((name, false)) => name,
                None => env.d["name"].as_str().unwrap_or("transfer").to_string(),
            };
            (
                "transfer.failed",
                json!({ "name": name, "error": reason, "tid": env.d["tid"] }),
            )
        }
        // Legacy phone builds pushed the un-prefixed alias.
        "audio_state" => ("call.audio_state", env.d),
        other => (other, env.d),
    };
    state.emit("push", json!({ "t": t, "d": d }));
}

/// Mark a laptop-initiated transfer finished. Returns the tracked name and
/// whether an earlier push already reported it; `None` = tid not tracked.
async fn finish_outgoing(state: &SharedState, d: &Value) -> Option<(String, bool)> {
    let raw = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(d["tid"].as_str()?)
        .ok()?;
    let tid: [u8; 16] = raw.try_into().ok()?;
    let mut inner = state.inner.lock().await;
    let rec = inner.outgoing.get_mut(&tid)?;
    let already = rec.done;
    rec.done = true;
    Some((rec.name.clone(), already))
}

/// Best-effort file name from a phone-side path.
fn basename(path: &str) -> String {
    path.rsplit('/')
        .next()
        .filter(|s| !s.is_empty())
        .unwrap_or("transfer")
        .to_string()
}

async fn handle_inbound_file_offer(state: &SharedState, _app: &tauri::AppHandle, env: Envelope) {
    let tid_b64 = env.d["tid"].as_str().unwrap_or("").to_string();
    use base64::Engine;
    let tid_raw = base64::engine::general_purpose::URL_SAFE_NO_PAD
        .decode(&tid_b64)
        .unwrap_or_default();
    let mut tid = [0u8; 16];
    if tid_raw.len() != 16 {
        state
            .send_envelope(&Envelope::error_response(&env.id, "file.offer", "internal: bad tid"))
            .await;
        return;
    }
    tid.copy_from_slice(&tid_raw);

    let size = env.d["size"].as_u64().unwrap_or(0);
    let name = env.d["name"].as_str().unwrap_or("transfer.bin").to_string();
    let mime = env.d["mime"].as_str().unwrap_or("application/octet-stream").to_string();

    let auto = { state.inner.lock().await.cfg.auto_accept_files };
    if !auto {
        state.emit("push", json!({ "t": "file.offer", "d": env.d }));
        state
            .send_envelope(&Envelope::error_response(&env.id, "file.offer", "busy: waiting for user approval"))
            .await;
        return;
    }

    // Accept: register collector BEFORE responding (chunks may follow immediately).
    let (tx, mut rx) = mpsc::channel::<(u32, Vec<u8>)>(256);
    state.inner.lock().await.data_collectors.insert(tid, tx);

    let resp = Envelope::response(&env.id, "file.offer", json!({ "tid": tid_b64, "status": "accepted" }));
    if !state.send_envelope(&resp).await {
        state.inner.lock().await.data_collectors.remove(&tid);
        return;
    }

    let st = state.clone();
    let dl_dir = {
        let cfg = &state.inner.lock().await.cfg;
        cfg.download_dir.clone()
    };
    tokio::spawn(async move {
        assemble_incoming(&st, tid, size, name, mime, dl_dir, &mut rx).await;
        st.inner.lock().await.data_collectors.remove(&tid);
    });
}

/// Collect chunks for an incoming transfer and write to disk.
pub async fn assemble_incoming(
    state: &SharedState,
    tid: [u8; 16],
    size: u64,
    name: String,
    _mime: String,
    download_dir: Option<String>,
    rx: &mut mpsc::Receiver<(u32, Vec<u8>)>,
) {
    let base = download_dir
        .map(std::path::PathBuf::from)
        .unwrap_or_else(default_download_dir);
    let _ = tokio::fs::create_dir_all(&base).await;
    let dest = unique_path(&base, &name);

    let mut file = match tokio::fs::File::create(&dest).await {
        Ok(f) => f,
        Err(e) => {
            state.emit("push", json!({ "t": "transfer.failed", "d": { "name": name, "error": e.to_string() } }));
            // drain until done so the sender can finish
            while rx.recv().await.is_some() {}
            return;
        }
    };

    use tokio::io::AsyncWriteExt;
    let mut received: u64 = 0;
    let mut next_seq: u32 = 0;
    let mut completed = false;
    while let Some((seq, chunk)) = rx.recv().await {
        if seq != next_seq {
            state.emit("push", json!({ "t": "transfer.failed", "d": { "name": name, "error": "sequence gap" } }));
            break;
        }
        next_seq += 1;
        received += chunk.len() as u64;
        if file.write_all(&chunk).await.is_err() {
            state.emit("push", json!({ "t": "transfer.failed", "d": { "name": name, "error": "disk write failed" } }));
            break;
        }
        if size > 0 && received % (1024 * 1024) < 65536 {
            state.emit("progress", json!({ "tid": hex_tid(&tid), "received": received, "total": size }));
        }
        if size > 0 && received >= size {
            completed = true;
            break;
        }
    }
    let _ = file.flush().await;

    if completed || (size == 0 && received > 0) {
        state.emit("progress", json!({ "tid": hex_tid(&tid), "received": received, "total": size.max(received) }));
        state.emit(
            "push",
            json!({ "t": "transfer.done", "d": { "name": name, "path": dest.display().to_string(), "size": received } }),
        );
    }
}

pub fn default_download_dir() -> std::path::PathBuf {
    let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
    std::path::PathBuf::from(home).join("InterLink")
}

pub fn unique_path(dir: &std::path::Path, name: &str) -> std::path::PathBuf {
    let p = dir.join(name);
    if !p.exists() {
        return p;
    }
    let stem = std::path::Path::new(name)
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".into());
    let ext = std::path::Path::new(name)
        .extension()
        .map(|s| format!(".{}", s.to_string_lossy()))
        .unwrap_or_default();
    for i in 1..10_000 {
        let cand = dir.join(format!("{stem}_{i}{ext}"));
        if !cand.exists() {
            return cand;
        }
    }
    p
}

pub fn hex_tid(tid: &[u8; 16]) -> String {
    tid.iter().map(|b| format!("{b:02x}")).collect()
}

/// Base64url helper shared with commands.
pub fn b64u(bytes: &[u8]) -> String {
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(bytes)
}
