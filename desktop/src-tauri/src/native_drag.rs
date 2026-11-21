//! Native drag-out of Gallery/Files items (desktop app only).
//!
//! A webview's HTML drag cannot hand a real file to the OS, so the UI asks for
//! these commands. A native drag can only be started while the mouse button is
//! still held — a phone download takes far longer than that — so `*_drag` now
//! only starts the OS drag from a file that is already on disk. The UI saves a
//! not-yet-local item first with the ordinary `*_download` command (which has
//! a visible destination and progress), then the next drag starts instantly.

use serde_json::{json, Value};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

/// Start a native OS drag for a gallery item that is already saved locally.
///
/// `path` comes from the UI's cache of earlier downloads. If it is missing or
/// stale the UI must call `media_download` first — we never start a download
/// here because the OS drag would be over long before the bytes arrived.
#[tauri::command]
pub async fn media_drag(
    app: tauri::AppHandle,
    window: tauri::Window,
    path: Option<String>,
    size: Option<u64>,
) -> Result<Value, String> {
    prepare_and_drag(&app, window, path, size)
}

/// Start a native OS drag for a generic phone file that is already saved.
#[tauri::command]
pub async fn file_drag(
    app: tauri::AppHandle,
    window: tauri::Window,
    path: Option<String>,
    size: Option<u64>,
) -> Result<Value, String> {
    prepare_and_drag(&app, window, path, size)
}

/// Set only while a real native drag is running; cleared when the OS reports
/// the drop/cancel, so one gesture cannot launch several drags.
static DRAG_ACTIVE: AtomicBool = AtomicBool::new(false);

fn drag_finished() {
    DRAG_ACTIVE.store(false, Ordering::SeqCst);
}

fn prepare_and_drag(
    app: &tauri::AppHandle,
    window: tauri::Window,
    path: Option<String>,
    size: Option<u64>,
) -> Result<Value, String> {
    if DRAG_ACTIVE.swap(true, Ordering::SeqCst) {
        return Ok(json!({ "ok": false, "busy": true }));
    }

    // A partial left behind by an interrupted transfer is never a valid drag
    // source; require the full expected length before handing it to the OS.
    let file = match path {
        Some(p)
            if std::path::Path::new(&p).is_file()
                && size.map_or(true, |want| {
                    std::fs::metadata(&p).map(|m| m.len() == want).unwrap_or(false)
                }) =>
        {
            std::path::PathBuf::from(p)
        }
        _ => {
            DRAG_ACTIVE.store(false, Ordering::SeqCst);
            return Ok(json!({ "ok": false, "needDownload": true }));
        }
    };

    let icon: &'static [u8] = include_bytes!("../icons/128x128.png");

    // GTK (and AppKit) must start the drag on the main thread.
    let drag_file = file.clone();
    app.run_on_main_thread(move || {
        if let Err(e) = start_file_drag(&window, drag_file, icon, drag_finished) {
            log::warn!("native drag failed: {e}");
            drag_finished();
        }
    })
    .map_err(|e| {
        DRAG_ACTIVE.store(false, Ordering::SeqCst);
        format!("internal: could not reach the UI thread: {e}")
    })?;

    // Safety net: if a platform never reports the drag ending, don't leave
    // drag-out disabled for the rest of the session.
    tauri::async_runtime::spawn(async {
        tokio::time::sleep(Duration::from_secs(120)).await;
        DRAG_ACTIVE.store(false, Ordering::SeqCst);
    });

    Ok(json!({ "ok": true, "path": file.display().to_string() }))
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
