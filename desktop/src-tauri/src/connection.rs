//! USB connection mode.
//!
//! The phone always prefers `127.0.0.1` as its first candidate endpoint: when
//! a tunnel exists it connects straight down the cable (instant, no WiFi
//! needed), and when none exists the connection is refused immediately so it
//! falls through to the LAN. That makes the mode enforcement entirely
//! laptop-side — this module just reconciles the `adb reverse` tunnel with
//! the configured preference:
//!
//!   auto — tunnel when a USB device with debugging is plugged in
//!   usb  — same, but report "no USB device" loudly in the UI
//!   wifi — never tunnel (tear it down if it exists)
//!
//! Transport display falls out of the same fact: a peer that arrives on
//! loopback can only have come through our own tunnel (see `transport_of`).

use crate::state::SharedState;
use std::path::PathBuf;
use std::process::Command;

/// Locate the adb binary: $ADB → PATH → the well-known SDK locations.
pub fn adb_path() -> Option<PathBuf> {
    if let Ok(p) = std::env::var("ADB") {
        let p = p.trim();
        if !p.is_empty() {
            let p = PathBuf::from(p);
            if p.exists() {
                return Some(p);
            }
        }
    }
    if let Some(paths) = std::env::var_os("PATH") {
        for dir in std::env::split_paths(&paths) {
            let cand = dir.join(exe_name("adb"));
            if cand.is_file() {
                return Some(cand);
            }
        }
    }
    let home = std::env::var_os("HOME")
        .or_else(|| std::env::var_os("USERPROFILE"))
        .map(PathBuf::from)?;
    let candidates: &[PathBuf] = if cfg!(windows) {
        &[home.join("AppData/Local/Android/Sdk/platform-tools/adb.exe")]
    } else if cfg!(target_os = "macos") {
        &[home.join("Library/Android/sdk/platform-tools/adb")]
    } else {
        &[home.join("Android/sdk/platform-tools/adb")]
    };
    candidates.iter().find(|c| c.is_file()).cloned()
}

fn exe_name(base: &str) -> String {
    if cfg!(windows) {
        format!("{base}.exe")
    } else {
        base.to_string()
    }
}

/// Serial of the first device adb reports as ready (`device` state).
fn first_device(adb: &PathBuf) -> Option<String> {
    let out = Command::new(adb).arg("devices").output().ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    // First line is the "List of devices attached" header.
    for line in text.lines().skip(1) {
        let mut parts = line.split_whitespace();
        if let (Some(serial), Some(state)) = (parts.next(), parts.next()) {
            if state == "device" && !serial.is_empty() {
                return Some(serial.to_string());
            }
        }
    }
    None
}

/// Reconcile the reverse tunnel with `mode`. Returns the adb serial seen, if
/// any. Blocking — call from `spawn_blocking`.
pub fn apply(mode: &str, port: u16) -> Option<String> {
    let adb = adb_path()?;
    let serial = first_device(&adb)?;
    match mode {
        "wifi" => {
            // `--remove` errors when there is no tunnel; that's the point.
            let _ = Command::new(&adb)
                .args(["-s", &serial, "reverse", "--remove", &format!("tcp:{port}")])
                .output();
        }
        _ => {
            // Host and device ports match: the phone connects to
            // 127.0.0.1:<port> and adb forwards to this laptop.
            let spec = format!("tcp:{port}");
            match Command::new(&adb)
                .args(["-s", &serial, "reverse", &spec, &spec])
                .output()
            {
                Ok(o) if o.status.success() => {
                    log::debug!("adb reverse {spec} active for {serial}");
                }
                Ok(o) => log::warn!(
                    "adb reverse {spec} failed: {}",
                    String::from_utf8_lossy(&o.stderr).trim()
                ),
                Err(e) => log::warn!("adb reverse {spec} failed: {e}"),
            }
        }
    }
    Some(serial)
}

/// One reconciliation pass: read the configured mode, run adb off the async
/// runtime, and publish the device serial for `get_status`.
pub async fn tick(state: &SharedState) {
    let (mode, port) = {
        let inner = state.inner.lock().await;
        (inner.cfg.connection_mode().to_string(), inner.cfg.port)
    };
    let serial = tokio::task::spawn_blocking(move || apply(&mode, port))
        .await
        .unwrap_or(None);
    state.inner.lock().await.adb_device = serial;
}

/// Keep the tunnel alive (device replug, adb restart) and notice newly
/// plugged-in phones in auto mode.
///
/// Must go through Tauri's runtime: `setup()` runs on the main thread, outside
/// any Tokio runtime, so a bare `tokio::spawn` would panic.
pub fn spawn(state: SharedState) {
    tauri::async_runtime::spawn(async move {
        loop {
            tick(&state).await;
            tokio::time::sleep(std::time::Duration::from_secs(5)).await;
        }
    });
}
