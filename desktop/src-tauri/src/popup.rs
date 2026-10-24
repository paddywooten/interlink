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
use serde_json::Value;
use tauri::AppHandle;
use tauri_plugin_notification::NotificationExt;

/// Raise a native popup for push `t`, if the user has them enabled.
pub async fn maybe_show(state: &SharedState, app: &AppHandle, t: &str, d: &Value) {
    if !state.notif_popup().await {
        return;
    }

    let (title, body) = match t {
        "notif.post" => {
            let app_name = d["app"].as_str().unwrap_or("Phone");
            let title = d["title"].as_str().unwrap_or(app_name);
            let body = d["text"]
                .as_str()
                .or_else(|| d["body"].as_str())
                .unwrap_or_default();
            (title.to_string(), body.to_string())
        }
        // Only the transition into ringing — state echoes for `active`/`ended`
        // must not re-pop the same call.
        "call.state" if d["state"].as_str() == Some("ringing") => {
            let from = d["name"]
                .as_str()
                .or_else(|| d["number"].as_str())
                .unwrap_or("Unknown");
            ("Incoming call".to_string(), format!("{from} is calling"))
        }
        _ => return,
    };

    if title.trim().is_empty() && body.trim().is_empty() {
        return;
    }

    // Best-effort: an OS that refuses a popup must not turn into an error the
    // user sees for something they didn't ask for in the first place.
    let _ = app
        .notification()
        .builder()
        .title(title)
        .body(body)
        .show();
}
