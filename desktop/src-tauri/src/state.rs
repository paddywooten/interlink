//! Persistent config + live connection state shared across the app.

use crate::crypto::FrameKey;
use crate::protocol::Envelope;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::Arc;
use tokio::sync::{mpsc, oneshot, Mutex};

pub const DEFAULT_PORT: u16 = 27341;
/// UDP port for LAN device-presence beacons (kept separate from the connect
/// path on DEFAULT_PORT so discovery never disturbs an active pairing).
pub const BEACON_PORT: u16 = 27342;

/// A device seen on the LAN via periodic `ILANN` beacons.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SeenDevice {
    pub name: String,
    /// "laptop" or "phone".
    pub role: String,
    pub ip: String,
    /// TCP port the device's link server/client listens on.
    pub port: u16,
    /// Unix millis when this device was last heard.
    pub last_seen: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Config {
    /// Pairing secret (32 bytes, base64) — present once paired.
    #[serde(default)]
    pub secret_b64: Option<String>,
    pub port: u16,
    pub device_name: String,
    /// Last known phone address "ip:port" for reconnection hints.
    #[serde(default)]
    pub phone_hint: Option<String>,
    #[serde(default)]
    pub mic_device: Option<String>,
    #[serde(default = "default_true")]
    pub clipboard_enabled: bool,
    #[serde(default = "default_true")]
    pub auto_accept_files: bool,
    #[serde(default)]
    pub download_dir: Option<String>,
}

fn default_true() -> bool {
    true
}

impl Default for Config {
    fn default() -> Self {
        Config {
            secret_b64: None,
            port: DEFAULT_PORT,
            device_name: default_device_name(),
            phone_hint: None,
            mic_device: None,
            clipboard_enabled: true,
            auto_accept_files: true,
            download_dir: None,
        }
    }
}

fn default_device_name() -> String {
    hostname::get()
        .ok()
        .and_then(|h| h.into_string().ok())
        .unwrap_or_else(|| "Laptop".into())
}

// Cross-platform hostname without a crate dependency:
// Windows → COMPUTERNAME env, Linux → /etc/hostname, macOS → scutil.
mod hostname {
    pub fn get() -> std::io::Result<std::ffi::OsString> {
        if let Some(h) = std::env::var_os("COMPUTERNAME") {
            if !h.is_empty() {
                return Ok(h);
            }
        }
        if let Ok(s) = std::fs::read_to_string("/etc/hostname") {
            return Ok(std::ffi::OsString::from(s.trim().to_string()));
        }
        let out = std::process::Command::new("scutil")
            .args(["--get", "ComputerName"])
            .output()?;
        if out.status.success() {
            let name = String::from_utf8_lossy(&out.stdout).trim().to_string();
            if !name.is_empty() {
                return Ok(std::ffi::OsString::from(name));
            }
        }
        Err(std::io::Error::new(std::io::ErrorKind::NotFound, "no hostname source"))
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PeerInfo {
    pub name: String,
    pub model: String,
    pub os: String,
    pub app: String,
    #[serde(default)]
    pub caps: Vec<String>,
    #[serde(default)]
    pub battery: Option<Value>,
}

pub struct Conn {
    pub tx: mpsc::Sender<Vec<u8>>,
    pub peer: PeerInfo,
    pub session_key: FrameKey,
    /// Remote address, also used to keep the connected phone in the LAN device list.
    pub ip: String,
}

/// A laptop-initiated transfer (tid → name) so the phone's completion pushes
/// (`file.done` / `transfer.done` / `transfer.failed`) can be named and
/// deduplicated when they arrive.
pub struct Outgoing {
    pub name: String,
    pub done: bool,
}

pub struct Inner {
    pub cfg: Config,
    pub cfg_path: PathBuf,
    pub conn: Option<Conn>,
    /// request id → resolver
    pub pending: HashMap<String, oneshot::Sender<Envelope>>,
    /// transfer id (16 bytes) → chunk collector (kind-0x02 frames)
    pub data_collectors: HashMap<[u8; 16], mpsc::Sender<(u32, Vec<u8>)>>,
    /// transfer id (16 bytes) → outbound transfer bookkeeping
    pub outgoing: HashMap<[u8; 16], Outgoing>,
    /// True while the server task is running.
    pub server_running: bool,
    /// Active call audio relay (if any).
    pub call_audio: Option<crate::audio::CallAudioHandle>,
    /// Devices seen on the LAN via beacons, keyed by ip:port.
    pub devices: HashMap<String, crate::state::SeenDevice>,
}

pub struct AppState {
    pub inner: Mutex<Inner>,
    /// Broadcast channel for events destined to the webview.
    pub events: tokio::sync::broadcast::Sender<Value>,
}

impl AppState {
    pub fn new(cfg_path: PathBuf) -> Arc<Self> {
        let cfg = load_config(&cfg_path);
        let (events, _) = tokio::sync::broadcast::channel(256);
        Arc::new(AppState {
            inner: Mutex::new(Inner {
                cfg,
                cfg_path,
                conn: None,
                pending: HashMap::new(),
                data_collectors: HashMap::new(),
                outgoing: HashMap::new(),
                server_running: false,
                call_audio: None,
                devices: HashMap::new(),
            }),
            events,
        })
    }

    pub async fn save_config(&self) {
        let inner = self.inner.lock().await;
        let json = serde_json::to_string_pretty(&inner.cfg).unwrap_or_default();
        let path = inner.cfg_path.clone();
        drop(inner);
        // Atomic write: a truncate+write can be interrupted (crash, kill -9,
        // full disk) and leave a half-written file, which on the next start
        // would silently discard the pairing secret. Write to a temp file,
        // keep the previous good copy as .bak, then rename into place.
        let tmp = path.with_extension("json.tmp");
        let bak = path.with_extension("json.bak");
        if tokio::fs::write(&tmp, &json).await.is_err() {
            log::error!("config save failed (temp write): {}", tmp.display());
            return;
        }
        if path.exists() {
            let _ = tokio::fs::copy(&path, &bak).await;
        }
        if let Err(e) = tokio::fs::rename(&tmp, &path).await {
            log::error!("config save failed (rename): {e}");
        }
    }

    pub async fn config_secret(&self) -> Option<Vec<u8>> {
        use base64::Engine;
        let inner = self.inner.lock().await;
        inner.cfg.secret_b64.as_ref().and_then(|s| {
            base64::engine::general_purpose::STANDARD
                .decode(s)
                .ok()
                .filter(|v| v.len() == 32)
        })
    }

    /// Send a raw frame to the connected phone. Returns false when offline.
    pub async fn send_frame(&self, frame: Vec<u8>) -> bool {
        let tx = {
            let inner = self.inner.lock().await;
            inner.conn.as_ref().map(|c| c.tx.clone())
        };
        match tx {
            Some(tx) => tx.send(frame).await.is_ok(),
            None => false,
        }
    }

    /// Send a JSON envelope (sealing happens in caller or here with current session key).
    pub async fn send_envelope(&self, env: &Envelope) -> bool {
        let frame = {
            let inner = self.inner.lock().await;
            match inner.conn.as_ref() {
                Some(c) => {
                    let json = serde_json::to_vec(env).expect("envelope serializes");
                    c.session_key.seal_msg(&json)
                }
                None => return false,
            }
        };
        self.send_frame(frame).await
    }

    /// Seal + send a kind-0x02 data frame (call audio, transfer chunks).
    pub async fn send_data_frame(&self, tid: [u8; 16], seq: u32, chunk: &[u8]) -> bool {
        let frame = {
            let inner = self.inner.lock().await;
            match inner.conn.as_ref() {
                Some(c) => c.session_key.seal_data(&tid, seq, chunk),
                None => return false,
            }
        };
        self.send_frame(frame).await
    }

    /// Issue a request and await the correlated response envelope.
    pub async fn request(&self, msg_type: &str, payload: Value, timeout: std::time::Duration) -> Result<Envelope, String> {
        let (id, env) = Envelope::request(msg_type, payload);
        let (tx, rx) = oneshot::channel();
        {
            let mut inner = self.inner.lock().await;
            if inner.conn.is_none() {
                return Err("offline: phone is not connected".into());
            }
            inner.pending.insert(id.clone(), tx);
        }
        if !self.send_envelope(&env).await {
            let mut inner = self.inner.lock().await;
            inner.pending.remove(&id);
            return Err("offline: connection lost".into());
        }
        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(resp)) => {
                if resp.ok == Some(false) {
                    Err(resp.e.unwrap_or_else(|| "request failed".into()))
                } else {
                    Ok(resp)
                }
            }
            Ok(Err(_)) => Err("internal: response channel closed".into()),
            Err(_) => {
                let mut inner = self.inner.lock().await;
                inner.pending.remove(&id);
                Err(format!("busy: timeout waiting for {msg_type}"))
            }
        }
    }

    pub async fn request_json(&self, msg_type: &str, payload: Value) -> Result<Value, String> {
        let resp = self.request(msg_type, payload, std::time::Duration::from_secs(20)).await?;
        Ok(resp.d)
    }

    pub fn emit(&self, event: &str, payload: Value) {
        let _ = self.events.send(serde_json::json!({ "kind": event, "data": payload }));
    }

    /// Drop the current connection, failing all pending requests.
    pub async fn clear_connection(&self) -> Option<PeerInfo> {
        let mut inner = self.inner.lock().await;
        for (_, tx) in inner.pending.drain() {
            let _ = tx.send(Envelope::error_response("?", "internal", "offline: connection lost"));
        }
        inner.data_collectors.clear();
        inner.outgoing.clear();
        inner.conn.take().map(|c| c.peer)
    }
}

fn load_config(path: &PathBuf) -> Config {
    match std::fs::read_to_string(path) {
        Ok(s) => match serde_json::from_str::<Config>(&s) {
            Ok(c) => c,
            Err(e) => {
                // Never silently discard a corrupt config — that loses the
                // pairing secret and forces a re-pair with no explanation.
                log::error!(
                    "config.json is corrupt ({e}) — attempting backup restore: {}",
                    path.display()
                );
                let bak = path.with_extension("json.bak");
                if let Ok(b) = std::fs::read_to_string(&bak) {
                    if let Ok(c) = serde_json::from_str::<Config>(&b) {
                        log::warn!("restored config from backup: {}", bak.display());
                        return c;
                    }
                }
                log::error!("no usable backup — starting unpaired (re-pair from the QR dialog)");
                Config::default()
            }
        },
        Err(_) => Config::default(),
    }
}

/// Local LAN IP best-effort: connect a UDP socket to a public IP (no packets sent).
pub fn local_ip() -> Option<String> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.88.99.1:65535").ok()?; // benchmark addr — only used for routing
    let ip = socket.local_addr().ok()?.ip().to_string();
    if ip.starts_with("127.") {
        // fallback: probe a LAN broadcast route
        let s2 = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
        s2.connect("192.168.255.255:65535").ok()?;
        let ip2 = s2.local_addr().ok()?.ip().to_string();
        if !ip2.starts_with("127.") {
            return Some(ip2);
        }
        return None;
    }
    Some(ip)
}

pub type SharedState = Arc<AppState>;
