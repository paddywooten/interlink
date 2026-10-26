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
    /// Output device for the far end of a call relay (laptop speaker, a
    /// Bluetooth speaker, …). None = system default.
    #[serde(default)]
    pub speaker_device: Option<String>,
    /// How the phone is expected to reach this laptop:
    ///   "auto" — USB (adb reverse) when a device is plugged in, else WiFi
    ///   "usb"  — force the adb reverse tunnel
    ///   "wifi" — force the LAN (never set up a reverse tunnel)
    #[serde(default = "default_connection")]
    pub connection: String,
    #[serde(default = "default_true")]
    pub clipboard_enabled: bool,
    #[serde(default = "default_true")]
    pub auto_accept_files: bool,
    /// Surface phone events as native OS notifications so they still appear
    /// with the window minimised to the tray (the in-app list is not enough
    /// when the user isn't looking at the app).
    #[serde(default = "default_true")]
    pub notif_popup: bool,
    #[serde(default)]
    pub download_dir: Option<String>,
}

fn default_true() -> bool {
    true
}

fn default_connection() -> String {
    "auto".into()
}

impl Default for Config {
    fn default() -> Self {
        Config {
            secret_b64: None,
            port: DEFAULT_PORT,
            device_name: default_device_name(),
            phone_hint: None,
            mic_device: None,
            speaker_device: None,
            connection: default_connection(),
            clipboard_enabled: true,
            auto_accept_files: true,
            notif_popup: true,
            download_dir: None,
        }
    }
}

impl Config {
    /// Normalized connection mode ("auto" | "wifi" | "usb").
    pub fn connection_mode(&self) -> &str {
        match self.connection.as_str() {
            "usb" => "usb",
            "wifi" => "wifi",
            _ => "auto",
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

/// One screen-mirror frame as it crosses the laptop: frame counter + JPEG.
///
/// `None` seeds a fresh [mirror channel](tokio::sync::watch::channel) so a
/// sink exists before the first frame arrives.
pub type MirrorFrame = Option<(u32, Vec<u8>)>;

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
    /// Serial of the USB device adb currently sees (None = no cable /
    /// no debugging). Refreshed by the connection ticker.
    pub adb_device: Option<String>,
    /// Latest-frame-wins sink for screen-mirror frames.
    ///
    /// See [crate::state::AppState::mirror_sink]: the laptop cannot always
    /// paint as fast as the phone captures, and queueing those frames would
    /// turn "a bit slow" into lag that grows without bound.
    pub mirror_tx: Option<tokio::sync::watch::Sender<MirrorFrame>>,
    /// Active screen-mirror audio relay (phone playback → laptop output).
    pub mirror_audio: Option<crate::audio::MirrorAudioHandle>,
    /// In-flight "wait for a push" reservations keyed like
    /// `filedone:<tid-b64u>` or `backupresult:<tid-b64u>`.
    pub waiters: HashMap<String, oneshot::Sender<Value>>,
}

pub struct AppState {
    pub inner: Mutex<Inner>,
    /// Broadcast channel for events destined to the webview.
    pub events: tokio::sync::broadcast::Sender<Value>,
    /// Serializes call-audio start/stop.
    ///
    /// Two quick changes on the Calls page would otherwise race: both would
    /// find `call_audio` empty (so neither stops the other), both start a
    /// relay, and the loser's handle would be overwritten — leaving its
    /// microphone stream open with nothing draining it.
    pub call_audio_gate: tokio::sync::Mutex<()>,
    /// Serializes mirror-audio start/stop for the same reason: the phone's
    /// "started"/"stopped" pushes and a local `mirror_stop` can land close
    /// together, and each half of that pair must see the other's work.
    pub mirror_audio_gate: tokio::sync::Mutex<()>,
}

impl AppState {
    pub fn new(cfg_path: PathBuf) -> Arc<Self> {
        let cfg = load_config(&cfg_path);
        let (events, _) = tokio::sync::broadcast::channel(256);
        Arc::new(AppState {
            call_audio_gate: tokio::sync::Mutex::new(()),
            mirror_audio_gate: tokio::sync::Mutex::new(()),
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
                adb_device: None,
                waiters: HashMap::new(),
                mirror_tx: None,
                mirror_audio: None,
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

    /// Whether phone events should also be re-raised as native popups.
    pub async fn notif_popup(&self) -> bool {
        self.inner.lock().await.cfg.notif_popup
    }

    /// The screen-mirror frame sink, created along with its pump task on
    /// first use.
    ///
    /// Mirroring is the one stream where "slow" must never mean "late": the
    /// phone keeps capturing whether or not the laptop has painted the last
    /// frame, so any queue between them is a queue of *past* frames and the
    /// user's latency grows by its full depth. A watch channel holds exactly
    /// one value — the newest — so a laptop that can't quite keep up shows a
    /// stuttering but live image instead of a smooth one that is seconds
    /// behind.
    pub async fn mirror_sink(
        &self,
        app: &tauri::AppHandle,
    ) -> tokio::sync::watch::Sender<MirrorFrame> {
        if let Some(tx) = self.inner.lock().await.mirror_tx.clone() {
            return tx;
        }

        let (tx, rx) = tokio::sync::watch::channel::<MirrorFrame>(None);
        let handle = app.clone();
        tauri::async_runtime::spawn(async move {
            pump_mirror_frames(rx, |seq, jpeg| {
                use base64::Engine as _;
                use tauri::Emitter;
                let _ = handle.emit(
                    "il:ev",
                    serde_json::json!({
                        "kind": "push",
                        "data": {
                            "t": "mirror.frame",
                            "d": {
                                "seq": seq,
                                "mime": "image/jpeg",
                                "data": base64::engine::general_purpose::STANDARD.encode(&jpeg),
                            }
                        }
                    }),
                );
            })
            .await;
            // Sender dropped (mirror stopped, or the link went away) — the
            // pump is done; a later start creates a fresh one.
        });

        // Another connection beat us to it; ours is dropped, which quietly
        // ends the task we just spawned.
        self.inner
            .lock()
            .await
            .mirror_tx
            .get_or_insert(tx)
            .clone()
    }

    /// Drop the current connection, failing all pending requests.
    pub async fn clear_connection(&self) -> Option<PeerInfo> {
        let mut inner = self.inner.lock().await;
        for (_, tx) in inner.pending.drain() {
            let _ = tx.send(Envelope::error_response("?", "internal", "offline: connection lost"));
        }
        // The mirror relay outlives a connection change — its picture does
        // too (`mirror_sink` rebuilds on demand) — so keep its collector
        // across the wipe. Dropping it here left a relay reporting "active"
        // while every frame was thrown away: mirrored picture, no sound.
        let mirror_tx = inner.mirror_audio.as_ref().map(|h| h.tx.clone());
        inner.data_collectors.clear();
        if let Some(tx) = mirror_tx {
            inner.data_collectors.insert(crate::crypto::mirror_audio_tid(), tx);
        }
        inner.outgoing.clear();
        // Dropping the sink ends the mirror pump; a stale frame must not keep
        // painting over a disconnected screen.
        inner.mirror_tx = None;
        // Waiters are per-transfer; drop them so callers see a closed channel
        // instead of hanging until their own timeout.
        inner.waiters.clear();
        inner.conn.take().map(|c| c.peer)
    }

    /// Reserve a slot for a push identified by `key` (see `waiter_key`).
    pub async fn add_waiter(&self, key: String) -> oneshot::Receiver<Value> {
        let (tx, rx) = oneshot::channel();
        self.inner.lock().await.waiters.insert(key, tx);
        rx
    }

    /// Deliver a push payload to whoever is waiting on `key`.
    pub async fn resolve_waiter(&self, key: &str, payload: Value) {
        if let Some(tx) = self.inner.lock().await.waiters.remove(key) {
            let _ = tx.send(payload);
        }
    }
}

/// Transport implied by the peer's address: a loopback peer can only have
/// arrived through the laptop's own `adb reverse` tunnel, i.e. over USB.
pub fn transport_of(ip: &str) -> &'static str {
    let ip = ip.trim().trim_start_matches("::ffff:");
    if ip == "::1" || ip.starts_with("127.") {
        "usb"
    } else {
        "wifi"
    }
}

/// Canonical waiter key for a push that carries a `tid`.
pub fn waiter_key(kind: &str, tid: &str) -> String {
    format!("{kind}:{tid}")
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

/// Deliver screen-mirror frames, newest only.
///
/// Mirroring is the one stream where "slow" must never mean "late": the phone
/// keeps capturing whether or not the laptop has painted the previous frame,
/// so anything queued between the two ends is a queue of *past* frames and
/// end-to-end latency grows by its full depth for as long as the mirror runs.
/// A `watch` channel carries exactly one value — the newest — so a frame
/// written while the last one is being delivered simply replaces it.
///
/// The trade is deliberate: a laptop that can't quite keep up shows stutter
/// (dropped frames) rather than a smooth picture that is seconds behind. For a
/// live screen, current-but-choppy is strictly better than smooth-and-late.
///
/// Returns once every sender is dropped — mirror stopped, or the link went
/// away.
pub(crate) async fn pump_mirror_frames<F>(
    mut rx: tokio::sync::watch::Receiver<MirrorFrame>,
    mut emit: F,
) where
    F: FnMut(u32, Vec<u8>),
{
    while rx.changed().await.is_ok() {
        // Take the newest frame *and* mark it seen in one step, so frames
        // written while this one was being emitted are picked up next pass
        // rather than replayed one at a time.
        if let Some((seq, jpeg)) = rx.borrow_and_update().clone() {
            emit(seq, jpeg);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// A peer on loopback can only have arrived through our own adb reverse
    /// tunnel — that fact IS the USB/WiFi indicator, no wire message needed.
    #[test]
    fn transport_is_loopback_vs_lan() {
        assert_eq!(transport_of("127.0.0.1"), "usb");
        assert_eq!(transport_of("127.0.0.53"), "usb");
        assert_eq!(transport_of("::1"), "usb");
        // Dual-stack listeners hand us IPv4 addresses IPv6-mapped.
        assert_eq!(transport_of("::ffff:127.0.0.1"), "usb");
        assert_eq!(transport_of("172.20.10.3"), "wifi");
        assert_eq!(transport_of("::ffff:172.20.10.3"), "wifi");
    }

    #[test]
    fn waiter_keys_are_kind_and_tid() {
        assert_eq!(waiter_key("filedone", "abc"), "filedone:abc");
        assert_eq!(waiter_key("backupresult", "x"), "backupresult:x");
    }

    /// Connection mode must tolerate whatever is on disk — anything that isn't
    /// explicitly usb/wifi falls back to auto rather than disabling the tunnel.
    #[test]
    fn connection_mode_normalizes() {
        for (raw, want) in [("auto", "auto"), ("usb", "usb"), ("wifi", "wifi"), ("", "auto")] {
            let cfg = Config {
                connection: raw.into(),
                ..Config::default()
            };
            assert_eq!(cfg.connection_mode(), want, "raw {raw:?}");
        }
    }

    /// Existing configs predate `connection` / `speaker_device`; loading one
    /// must still work and default the new fields rather than fail to parse.
    #[test]
    fn config_without_new_fields_still_parses() {
        let cfg: Config = serde_json::from_str(
            r#"{"secret_b64":"aGk","port":27341,"device_name":"Laptop"}"#,
        )
        .expect("old config must deserialize");
        assert_eq!(cfg.connection_mode(), "auto");
        assert_eq!(cfg.speaker_device, None);
        assert!(cfg.clipboard_enabled);
    }

    #[tokio::test]
    async fn a_waiter_is_delivered_once_then_gone() {
        let state = AppState::new(std::env::temp_dir().join("il-test-deliver.json"));
        let rx = state.add_waiter(waiter_key("filedone", "t1")).await;
        state
            .resolve_waiter("filedone:t1", json!({ "ok": true }))
            .await;
        let v = rx.await.expect("waiter must deliver");
        assert_eq!(v["ok"], json!(true));

        // Second delivery finds nothing to wake — resolve is a no-op, not a panic.
        state
            .resolve_waiter("filedone:t1", json!({ "ok": false }))
            .await;
        assert!(state.inner.lock().await.waiters.is_empty());
    }

    /// Losing the link must fail waiters instead of parking their callers
    /// until an arbitrary timeout — a dropped sender closes the channel.
    #[tokio::test]
    async fn losing_the_connection_closes_pending_waiters() {
        let state = AppState::new(std::env::temp_dir().join("il-test-clear.json"));
        let rx = state.add_waiter(waiter_key("filedone", "t2")).await;
        state.clear_connection().await;
        assert!(rx.await.is_err(), "closed channel, not a hung await");
        assert!(state.inner.lock().await.waiters.is_empty());
    }

    /// Ten frames written faster than they can be delivered must collapse to
    /// the newest one. Queueing them instead is precisely the unbounded lag the
    /// watch channel exists to prevent: every stale frame on the wire is
    /// bandwidth spent showing the user the past.
    #[tokio::test]
    async fn mirror_pump_keeps_only_the_newest_frame() {
        let (tx, rx) = tokio::sync::watch::channel::<MirrorFrame>(None);
        // Nobody is consuming yet, so only the latest write survives.
        for seq in 0..10u32 {
            tx.send(Some((seq, vec![0xFF]))).expect("receiver alive");
        }

        let seen = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let sink = seen.clone();
        let pump = tokio::spawn(pump_mirror_frames(rx, move |seq, jpeg| {
            sink.lock().unwrap().push((seq, jpeg));
        }));

        tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        drop(tx); // closes the channel, which ends the pump
        pump.await.expect("pump task");

        assert_eq!(*seen.lock().unwrap(), vec![(9u32, vec![0xFFu8])]);
    }

    /// Delivery must stop when the sink is dropped (mirror stopped, or the link
    /// went away) — otherwise a stale last frame could keep painting over a
    /// session that is already dead.
    #[tokio::test]
    async fn mirror_pump_ends_when_sender_drops() {
        let (tx, rx) = tokio::sync::watch::channel::<MirrorFrame>(None);
        let pump = tokio::spawn(pump_mirror_frames(rx, |_, _| {}));
        tx.send(Some((1, vec![0u8]))).expect("receiver alive");
        drop(tx);

        tokio::time::timeout(std::time::Duration::from_secs(2), pump)
            .await
            .expect("pump ends once every sender is gone")
            .expect("pump task");
    }
}
