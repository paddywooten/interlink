//! InterLink's laptop side: the phone link (pairing, protocol, transfers, call
//! audio) plus two front ends over it — the Tauri window app (`desktop`) and a
//! browser UI served by `interlink-web` (`web`).

#[cfg(feature = "desktop")]
use tauri::{Emitter, Manager};

pub mod audio;
pub mod backup;
pub mod commands;
pub mod connection;
pub mod crypto;
pub mod pair;
pub mod protocol;
pub mod server;
pub mod state;

#[cfg(feature = "desktop")]
mod native_drag;
#[cfg(feature = "desktop")]
mod popup;
#[cfg(not(feature = "desktop"))]
pub mod shim;
#[cfg(feature = "web")]
pub mod web;

#[cfg(feature = "desktop")]
#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    env_logger::init();

    // Workaround for WebKitGTK's DMA-BUF renderer crashing at window creation
    // with "Error 71 (Protocol error) dispatching to Wayland display"
    // (tauri-apps/tauri#10702). Must be set before any webview is created.
    // An explicit environment variable from the user still wins.
    #[cfg(target_os = "linux")]
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none() {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_clipboard_manager::init())
        // Native popups for phone notifications / incoming calls — see popup.rs.
        .plugin(tauri_plugin_notification::init())
        .setup(|app| {
            let cfg_dir = app.path().app_config_dir()?;
            std::fs::create_dir_all(&cfg_dir)?;
            let state = state::AppState::new(cfg_dir.join("config.json"));

            // Forward backend broadcast events → webview events ("il:ev").
            {
                let handle = app.handle().clone();
                let st = state.clone();
                tauri::async_runtime::spawn(async move {
                    let mut rx = st.events.subscribe();
                    loop {
                        match rx.recv().await {
                            Ok(v) => {
                                let _ = handle.emit("il:ev", v);
                            }
                            // Falling behind is not fatal. broadcast reports
                            // Lagged when it had to drop the oldest events;
                            // bailing out here would silently stop *every*
                            // push — notifications, transfers, call state —
                            // from reaching the app until it is restarted.
                            Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => {
                                log::warn!("webview fell behind; dropped {n} events");
                            }
                            Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                        }
                    }
                });
            }

            // Start the WS server + UDP discovery.
            {
                let st = state.clone();
                tauri::async_runtime::spawn(async move {
                    match server::start(st).await {
                        Ok(()) => log::info!("InterLink server ready"),
                        Err(e) => log::error!("server failed to start: {e}"),
                    }
                });
            }

            // Phone notifications and calls also raise a native popup, so they
            // surface with the window minimised to the tray.
            {
                let st = state.clone();
                let handle = app.handle().clone();
                state.set_popup_hook(Box::new(move |t, d| {
                    let (st, handle) = (st.clone(), handle.clone());
                    let (t, d) = (t.to_string(), d.clone());
                    tauri::async_runtime::spawn(async move {
                        popup::maybe_show(&st, &handle, &t, &d).await;
                    });
                }));
            }

            // USB mode: keep the `adb reverse` tunnel reconciled with the
            // configured connection preference (also picks up a phone that
            // gets plugged in after launch).
            connection::spawn(state.clone());

            app.manage(state);
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_status,
            commands::get_devices,
            commands::get_pairing_qr,
            commands::unpair,
            commands::pair_mode,
            commands::pair_decide,
            commands::send_request,
            commands::media_thumb,
            commands::media_download,
            native_drag::media_drag,
            commands::media_stream_url,
            commands::file_download,
            native_drag::file_drag,
            commands::file_send,
            commands::mirror_start,
            commands::mirror_stop,
            commands::mirror_audio,
            commands::list_audio_inputs,
            commands::list_audio_outputs,
            commands::call_audio_start,
            commands::call_audio_stop,
            commands::call_mute,
            commands::get_settings,
            commands::set_settings,
            commands::connection_refresh,
            commands::pick_apk,
            commands::pick_backup_dir,
            commands::backup_start,
            commands::backup_restore,
            commands::backup_list,
            commands::pick_directory,
            commands::pick_files,
            commands::reveal_in_folder,
            commands::notif_image,
            commands::notif_save_image,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
