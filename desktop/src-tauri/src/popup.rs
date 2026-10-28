//! Native OS notifications for phone events.
//!
//! The in-app notification list is only useful while the user is actually
//! looking at the app. Minimised to the tray, nothing would ever surface — so
//! incoming phone notifications and calls are re-raised as native popups.
//!
//! This lives on the Rust side deliberately: a popup raised from the webview
//! depends on the webview still being scheduled, which is exactly the thing
//! that stops being true when the window is hidden or the machine is busy.
//! Nothing here may delay or fail a protocol message — a popup is a courtesy.

use crate::state::SharedState;
use base64::Engine;
use serde_json::Value;
use tauri::{AppHandle, Manager};
use tauri_plugin_notification::NotificationExt;

/// Raise a native popup for push `t`, if the user has them enabled.
pub async fn maybe_show(state: &SharedState, app: &AppHandle, t: &str, d: &Value) {
    if !state.notif_popup().await {
        return;
    }

    let (title, body, icon) = match t {
        "notif.post" => {
            let app_name = d["app"].as_str().unwrap_or("Phone");
            let title = d["title"].as_str().unwrap_or(app_name);
            let body = d["text"]
                .as_str()
                .or_else(|| d["body"].as_str())
                .unwrap_or_default();
            (
                title.to_string(),
                body.to_string(),
                popup_icon(app, d),
            )
        }
        // Only the transition into ringing — state echoes for `active`/`ended`
        // must not re-pop the same call. The phone pushes "incoming" for that
        // transition (it pushes "dialing" only for calls the laptop placed).
        "call.state" if d["state"].as_str() == Some("incoming") => {
            let from = d["name"]
                .as_str()
                .or_else(|| d["number"].as_str())
                .unwrap_or("Unknown");
            (
                "Incoming call".to_string(),
                format!("{from} is calling"),
                None,
            )
        }
        _ => return,
    };

    if title.trim().is_empty() && body.trim().is_empty() {
        return;
    }

    // Best-effort: an OS that refuses a popup must not turn into an error the
    // user sees for something they didn't ask for in the first place.
    let mut notification = app.notification().builder().title(title).body(body);
    if let Some(icon) = icon {
        notification = notification.icon(icon);
    }
    let _ = notification.show();
}

/// Cache an app's logo on disk and hand back the path a native popup needs.
///
/// The logo arrives on the push as base64, but an OS popup takes a file path —
/// so it is written once per app into the cache dir and reused from there.
/// Best-effort throughout: a popup that loses its icon is still a popup, and
/// this must never be the reason a notification fails to surface.
fn popup_icon(app: &AppHandle, d: &Value) -> Option<String> {
    let pkg = d["pkg"].as_str()?;
    let data = d["icon"].as_str()?;
    let bytes = base64::engine::general_purpose::STANDARD.decode(data).ok()?;
    let dir = app.path().app_cache_dir().ok()?.join("notif-icons");
    std::fs::create_dir_all(&dir).ok()?;
    // Package names are already filename-safe ([a-z0-9._]), so this needs no
    // sanitising of its own.
    let path = dir.join(format!("{pkg}.png"));
    // Only rewrite when the bytes differ: a logo does not move, and
    // notifications arrive in bursts.
    if std::fs::read(&path).ok().as_deref() != Some(bytes.as_slice()) {
        std::fs::write(&path, &bytes).ok()?;
    }
    Some(path.display().to_string())
}
