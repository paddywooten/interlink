//! Native drag-out of Gallery items (desktop app only).
//!
//! A webview's HTML drag cannot hand a real file to the OS, so the Gallery asks
//! for this command instead: it downloads the item and then starts a native
//! drag from the app window. The browser build cannot do that — it offers a
//! normal download instead.

use crate::commands::save_media;
use crate::state::SharedState;
use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;
use tauri::State;

/// Drag a gallery item out of the window, onto the desktop or a file manager.
///
/// A webview's HTML drag cannot hand a real file to the OS, so this downloads
/// the item first (the OS drag needs a path that exists) and then starts a
/// native drag from the app window. The drop target gets a copy; the file
/// stays in the InterLink folder like "Save to laptop" would leave it.
///
/// `path` lets the UI reuse a file downloaded by an earlier drag in this
/// session, so the second drag starts immediately instead of re-downloading.
#[tauri::command]
pub async fn media_drag(
    state: State<'_, SharedState>,
    app: tauri::AppHandle,
    window: tauri::Window,
    id: String,
    suggested: Option<String>,
    path: Option<String>,
) -> Result<Value, String> {
    // One drag at a time. While the button is held the webview keeps raising
    // `dragstart` for every tile the pointer crosses; without this a single
    // gesture would download and start a native drag for each of them.
    if DRAG_ACTIVE.swap(true, Ordering::SeqCst) {
        return Ok(json!({ "ok": false, "busy": true }));
    }
    let result = prepare_and_drag(&state, &app, window, id, suggested, path).await;
    if result.is_err() {
        DRAG_ACTIVE.store(false, Ordering::SeqCst);
    }
    result
}

/// Set while a drag-out is downloading or being dragged; cleared when the
/// native drag ends (dropped or cancelled) or fails to start.
static DRAG_ACTIVE: AtomicBool = AtomicBool::new(false);

fn drag_finished() {
    DRAG_ACTIVE.store(false, Ordering::SeqCst);
}

async fn prepare_and_drag(
    state: &SharedState,
    app: &tauri::AppHandle,
    window: tauri::Window,
    id: String,
    suggested: Option<String>,
    path: Option<String>,
) -> Result<Value, String> {
    let reused = match path {
        Some(p) if std::path::Path::new(&p).is_file() => p,
        _ => {
            let saved = save_media(state, id, suggested, None).await?;
            saved
                .get("path")
                .and_then(Value::as_str)
                .map(String::from)
                .ok_or_else(|| "internal: media path missing".to_string())?
        }
    };

    let file = std::path::PathBuf::from(&reused);
    let icon: &'static [u8] = include_bytes!("../icons/128x128.png");

    // GTK (and AppKit) must start the drag on the main thread; scheduling the
    // drag here also keeps the async download off the UI thread.
    app.run_on_main_thread(move || {
        if let Err(e) = start_file_drag(&window, file, icon, drag_finished) {
            log::warn!("native drag failed: {e}");
            drag_finished();
        }
    })
    .map_err(|e| format!("internal: could not reach the UI thread: {e}"))?;

    // Safety net: if a platform never reports the drag ending, don't leave
    // drag-out disabled for the rest of the session.
    tauri::async_runtime::spawn(async {
        tokio::time::sleep(Duration::from_secs(120)).await;
        DRAG_ACTIVE.store(false, Ordering::SeqCst);
    });

    Ok(json!({ "ok": true, "path": reused }))
}

/// Start the OS drag for `file` from the app window (main thread only).
/// `finished` runs once when the drag ends, whether dropped or cancelled.
#[cfg(not(target_os = "linux"))]
fn start_file_drag(
    window: &tauri::Window,
    file: std::path::PathBuf,
    icon: &[u8],
    finished: fn(),
) -> Result<(), String> {
    let on_drop = move |result: drag::DragResult, _pos: drag::CursorPosition| {
        log::debug!("media drag finished: {result:?}");
        finished();
    };
    drag::start_drag(
        window,
        drag::DragItem::Files(vec![file]),
        drag::Image::Raw(icon.to_vec()),
        on_drop,
        drag::Options::default(),
    )
    .map_err(|e| e.to_string())
}

/// GTK drag source offering only `text/uri-list`.
///
/// Not `drag::start_drag`: that calls `drag_source_add_uri_targets`, which on
/// GTK >= 3.24.37 also advertises the FileTransfer-portal targets. Plasma
/// reads those on drag-enter, which consumes the one-shot transfer, and then
/// the real read at the drop fails with "Invalid transfer", so nothing lands.
/// A plain uri-list is read the same way by Dolphin, the desktop and GTK file
/// managers.
#[cfg(target_os = "linux")]
fn start_file_drag(
    window: &tauri::Window,
    file: std::path::PathBuf,
    icon: &[u8],
    finished: fn(),
) -> Result<(), String> {
    use gtk::gdk;
    use gtk::prelude::*;
    use std::cell::RefCell;
    use std::rc::Rc;

    let win = window.gtk_window().map_err(|e| e.to_string())?;
    let uri = gtk::gio::File::for_path(&file).uri().to_string();

    let targets = [gtk::TargetEntry::new(
        "text/uri-list",
        gtk::TargetFlags::OTHER_APP,
        0,
    )];
    let list = gtk::TargetList::new(&targets);
    win.drag_source_set(gdk::ModifierType::BUTTON1_MASK, &targets, gdk::DragAction::COPY);

    let ids: Rc<RefCell<Vec<gtk::glib::SignalHandlerId>>> = Rc::new(RefCell::new(Vec::new()));
    ids.borrow_mut().push(win.connect_drag_data_get(move |_, _, data, _, _| {
        log::debug!("media drag: handing over {uri} as {:?}", data.target().name());
        data.set_uris(&[uri.as_str()]);
    }));
    {
        let ids = ids.clone();
        let win2 = win.clone();
        ids.clone().borrow_mut().push(win.connect_drag_end(move |_, _| {
            for id in ids.borrow_mut().drain(..) {
                win2.disconnect(id);
            }
            win2.drag_source_unset();
            log::debug!("media drag ended");
            finished();
        }));
    }

    let ctx = win
        .drag_begin_with_coordinates(&list, gdk::DragAction::COPY, 1, None, -1, -1)
        .ok_or_else(|| "could not start the drag".to_string())?;

    let loader = gtk::gdk_pixbuf::PixbufLoader::new();
    if loader.write(icon).is_ok() && loader.close().is_ok() {
        if let Some(pixbuf) = loader.pixbuf() {
            ctx.drag_set_icon_pixbuf(&pixbuf, 0, 0);
        }
    }
    Ok(())
}
