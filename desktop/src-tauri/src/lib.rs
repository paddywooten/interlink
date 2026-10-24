use tauri::{Emitter, Manager};

mod audio;
mod backup;
mod commands;
mod connection;
mod crypto;
mod protocol;
mod server;
mod state;

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
                    while let Ok(v) = rx.recv().await {
                        let _ = handle.emit("il:ev", v);
                    }
                });
            }

            // Start the WS server + UDP discovery.
            {
                let st = state.clone();
                let handle = app.handle().clone();
                tauri::async_runtime::spawn(async move {
                    match server::start(st, handle).await {
                        Ok(()) => log::info!("InterLink server ready"),
                        Err(e) => log::error!("server failed to start: {e}"),
                    }
                });
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
            commands::send_request,
            commands::media_thumb,
            commands::media_download,
            commands::file_send,
            commands::mirror_start,
            commands::mirror_stop,
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
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
