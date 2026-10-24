//! Folder-based app & data backup / restore (PROTOCOL.md §4.11).
//!
//! A backup is a plain folder the user can open, copy, and read — no opaque
//! archive format to go stale:
//!
//!   <backup dir>/
//!     contacts.vcf      contacts as vCard 3.0
//!     sms.json          messages as a JSON array
//!     manifest.json     what this folder contains + when it was taken
//!     media/…           every photo and video on the phone
//!
//! Contacts and SMS travel inline in `backup.export` / `backup.import`
//! responses (they're small text). Photos use the ordinary `media.get` and
//! `file.offer` streaming paths, so a 20k-photo library never needs a giant
//! archive sitting in the phone's storage.

use crate::commands::send_file;
use crate::crypto;
use crate::server::{assemble_incoming, b64u};
use crate::state::SharedState;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::time::Duration;

/// `media.list` caps its page size on the phone side.
const PAGE: u64 = 200;

/// Write a fresh backup into `out_dir` (default: <download dir>/backups/<ts>).
pub async fn start(
    state: &SharedState,
    sections: Vec<String>,
    out_dir: Option<String>,
) -> Result<Value, String> {
    let want_photos = sections.iter().any(|s| s == "photos");
    let want_contacts = sections.is_empty() || sections.iter().any(|s| s == "contacts");
    let want_sms = sections.is_empty() || sections.iter().any(|s| s == "sms");

    let base = out_dir
        .map(PathBuf::from)
        .unwrap_or_else(|| default_backup_dir());
    tokio::fs::create_dir_all(&base)
        .await
        .map_err(|e| format!("internal: {e}"))?;

    let mut manifest = json!({
        "at": now_millis(),
        "device": peer_name(state).await,
        "sections": sections,
    });

    if want_contacts || want_sms {
        let mut req_sections = Vec::new();
        if want_contacts {
            req_sections.push("contacts");
        }
        if want_sms {
            req_sections.push("sms");
        }
        let d = state
            .request(
                "backup.export",
                json!({ "sections": req_sections }),
                Duration::from_secs(60),
            )
            .await?
            .d;
        if let Some(vcf) = d["contacts"].as_str() {
            write_text(&base.join("contacts.vcf"), vcf).await?;
            manifest["contacts"] = json!(count_vcards(vcf));
        }
        if let Some(sms) = d["sms"].as_str() {
            write_text(&base.join("sms.json"), sms).await?;
            manifest["sms"] = json!(count_array(sms));
        }
    }

    let mut photos = 0u64;
    if want_photos {
        let media_dir = base.join("media");
        tokio::fs::create_dir_all(&media_dir)
            .await
            .map_err(|e| format!("internal: {e}"))?;
        photos = download_all_media(state, &media_dir).await?;
        manifest["photos"] = json!(photos);
    }

    write_text(
        &base.join("manifest.json"),
        &serde_json::to_string_pretty(&manifest).unwrap_or_default(),
    )
    .await?;

    Ok(json!({
        "ok": true,
        "path": base.display().to_string(),
        "contacts": manifest["contacts"].as_i64().unwrap_or(0),
        "sms": manifest["sms"].as_i64().unwrap_or(0),
        "photos": photos,
    }))
}

/// Replay a backup folder onto the phone.
///
/// Contacts and SMS go through `backup.import` (SMS reports `blocked` when
/// InterLink isn't the phone's default SMS app — Android forbids anyone else
/// writing to the telephony provider); photos are re-sent as media files.
pub async fn restore(state: &SharedState, dir: String) -> Result<Value, String> {
    let base = PathBuf::from(&dir);
    if !base.is_dir() {
        return Err("internal: not a folder".into());
    }

    let mut contacts = Value::Null;
    let mut sms = Value::Null;
    let mut imported = 0i64;
    let mut skipped = 0i64;

    for (kind, file) in [("contacts", "contacts.vcf"), ("sms", "sms.json")] {
        let p = base.join(file);
        if !p.is_file() {
            continue;
        }
        let data = tokio::fs::read_to_string(&p)
            .await
            .map_err(|e| format!("internal: {e}"))?;
        let d = state
            .request(
                "backup.import",
                json!({ "kind": kind, "data": data }),
                // Contacts/SMS import is many provider writes; give it room.
                Duration::from_secs(300),
            )
            .await?
            .d;
        imported += d["imported"].as_i64().unwrap_or(0);
        skipped += d["skipped"].as_i64().unwrap_or(0);
        if kind == "contacts" {
            contacts = d.clone();
        } else {
            sms = d.clone();
        }
    }

    // Photos & videos: replay as ordinary media files.
    let mut media = 0i64;
    let mut media_failed = 0i64;
    let media_dir = base.join("media");
    if media_dir.is_dir() {
        let mut rd = tokio::fs::read_dir(&media_dir)
            .await
            .map_err(|e| format!("internal: {e}"))?;
        while let Some(entry) = rd.next_entry().await.map_err(|e| format!("internal: {e}"))? {
            let path = entry.path();
            if !path.is_file() {
                continue;
            }
            let p = path.display().to_string();
            match send_file(state, &p, "media").await {
                Ok(r) if r["completed"].as_bool().unwrap_or(false) => media += 1,
                _ => media_failed += 1,
            }
        }
    }

    Ok(json!({
        "ok": true,
        "contacts": imported,
        "skipped": skipped,
        "sms": sms,
        "contactsResult": contacts,
        "media": media,
        "mediaFailed": media_failed,
    }))
}

// ——— helpers ———

/// Describe a backup folder (used by the restore picker in the UI).
pub async fn list(dir: String) -> Result<Value, String> {
    let base = PathBuf::from(&dir);
    if !base.is_dir() {
        return Err("internal: not a folder".into());
    }
    let manifest: Value = tokio::fs::read_to_string(base.join("manifest.json"))
        .await
        .ok()
        .and_then(|s| serde_json::from_str(&s).ok())
        .unwrap_or(Value::Null);
    let mut media = 0i64;
    let media_dir = base.join("media");
    if media_dir.is_dir() {
        let mut rd = tokio::fs::read_dir(&media_dir)
            .await
            .map_err(|e| format!("internal: {e}"))?;
        while let Some(entry) = rd.next_entry().await.map_err(|e| format!("internal: {e}"))? {
            if entry.path().is_file() {
                media += 1;
            }
        }
    }
    Ok(json!({
        "path": base.display().to_string(),
        "manifest": manifest,
        "contacts": base.join("contacts.vcf").is_file(),
        "sms": base.join("sms.json").is_file(),
        "media": media,
    }))
}

/// Stream every photo and video on the phone into `dir` via media.get.
async fn download_all_media(state: &SharedState, dir: &Path) -> Result<u64, String> {
    let mut offset = 0u64;
    let mut total = i64::MAX;
    let mut count = 0u64;
    while (offset as i64) < total {
        let d = state
            .request(
                "media.list",
                json!({ "offset": offset, "limit": PAGE, "type": "all" }),
                Duration::from_secs(30),
            )
            .await?
            .d;
        let items = d["items"].as_array().cloned().unwrap_or_default();
        if let Some(t) = d["total"].as_i64() {
            total = t;
        }
        if items.is_empty() {
            break;
        }
        let n = items.len();
        for it in items {
            let Some(id) = it["id"].as_str() else { continue };
            let id = id.to_string();
            let name = it["name"]
                .as_str()
                .map(String::from)
                .unwrap_or_else(|| format!("media_{id}"));
            let tid = crypto::new_transfer_id();
            let (tx, mut rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
            state.inner.lock().await.data_collectors.insert(tid, tx);
            let res = async {
                let d = state
                    .request_json("media.get", json!({ "id": id, "tid": b64u(&tid) }))
                    .await?;
                let size = d["size"].as_u64().unwrap_or(0);
                let mime = d["mime"].as_str().unwrap_or("").to_string();
                assemble_incoming(
                    state,
                    tid,
                    size,
                    name,
                    mime,
                    Some(dir.display().to_string()),
                    &mut rx,
                )
                .await;
                Ok::<(), String>(())
            }
            .await;
            state.inner.lock().await.data_collectors.remove(&tid);
            res?;
            count += 1;
        }
        offset += n as u64;
        if n < PAGE as usize {
            break;
        }
    }
    Ok(count)
}

fn default_backup_dir() -> PathBuf {
    let stamp = chrono_stamp();
    let root = {
        let home = std::env::var("HOME").unwrap_or_else(|_| "/tmp".into());
        PathBuf::from(home).join("InterLink")
    };
    root.join("backups").join(stamp)
}

/// `YYYY-MM-DD_HHMMSS` — sortable and filesystem-safe everywhere.
///
/// Deliberately UTC, not local time: the name only has to be consistent and
/// sortable, and dragging in a timezone means the same instant can sort
/// before or after depending on where the laptop happens to be.
fn chrono_stamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (y, mo, d, h, mi, s) = civil_from_unix(secs as i64);
    format!("{y:04}-{mo:02}-{d:02}_{h:02}{mi:02}{s:02}")
}

/// Days-from-civil inverse (Howard Hinnant's algorithm), UTC.
fn civil_from_unix(z: i64) -> (i64, u32, u32, u32, u32, u32) {
    let secs_of_day = z.rem_euclid(86_400) as u32;
    let z = z.div_euclid(86_400) + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097; // [0, 146096]
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365; // [0, 399]
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    (y, m as u32, d, secs_of_day / 3600, (secs_of_day / 60) % 60, secs_of_day % 60)
}

async fn write_text(path: &Path, contents: &str) -> Result<(), String> {
    tokio::fs::write(path, contents)
        .await
        .map_err(|e| format!("internal: {}: {e}", path.display()))
}

async fn peer_name(state: &SharedState) -> String {
    let inner = state.inner.lock().await;
    inner
        .conn
        .as_ref()
        .map(|c| c.peer.name.clone())
        .unwrap_or_default()
}

fn count_vcards(vcf: &str) -> i64 {
    vcf.lines()
        .filter(|l| l.trim().eq_ignore_ascii_case("BEGIN:VCARD"))
        .count() as i64
}

fn count_array(text: &str) -> i64 {
    serde_json::from_str::<Vec<Value>>(text)
        .map(|v| v.len() as i64)
        .unwrap_or(0)
}

pub fn now_millis() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Backup folder names are hand-derived from a unix timestamp — check the
    /// civil-date math on known instants so a folder never gets a date 31 Dec.
    #[test]
    fn civil_dates_land_on_the_right_calendar_day() {
        // 2026-01-01T00:00:00Z
        let (y, mo, d, h, mi, s) = civil_from_unix(1_767_225_600);
        assert_eq!((y, mo, d, h, mi, s), (2026, 1, 1, 0, 0, 0));

        // 2026-10-24T12:34:56Z
        let (y, mo, d, h, mi, s) = civil_from_unix(1_792_845_296);
        assert_eq!((y, mo, d, h, mi, s), (2026, 10, 24, 12, 34, 56));

        // 2000-02-29T06:07:08Z — a leap day inside a leap year.
        let (y, mo, d, h, mi, s) = civil_from_unix(951_804_428);
        assert_eq!((y, mo, d, h, mi, s), (2000, 2, 29, 6, 7, 8));

        // 1970-01-01T00:00:00Z
        assert_eq!(civil_from_unix(0).0, 1970);
    }

    /// Folder names must be sortable and safe on every filesystem.
    #[test]
    fn stamp_is_sortable_and_filename_safe() {
        let stamp = chrono_stamp();
        // "2026-10-24_123456"
        assert_eq!(stamp.len(), 17, "expected YYYY-MM-DD_HHMMSS, got {stamp}");
        assert!(stamp.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
        assert_eq!(&stamp[4..5], "-");
        assert_eq!(&stamp[10..11], "_");
    }

    #[test]
    fn backup_payload_counts_are_not_type_sensitive() {
        assert_eq!(count_vcards("BEGIN:VCARD\nFN:a\nEND:VCARD\nBEGIN:VCARD\nFN:b\nEND:VCARD"), 2);
        assert_eq!(count_array(r#"[{"a":1}, {"b":2}]"#), 2);
        // A truncated/garbled file reports zero rather than panicking or lying.
        assert_eq!(count_array("[{"), 0);
        assert_eq!(count_vcards(""), 0);
    }
}
