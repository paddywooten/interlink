//! Browser front end: serves the InterLink UI and its API from this process.
//!
//! The phone connects *to the laptop* (WebSocket on 27341), and a web page
//! cannot accept connections, so a purely hosted site cannot be the laptop side.
//! This module is the next best thing: the same backend as the desktop app
//! (pairing, protocol, transfers, call audio), headless, plus a small HTTP
//! server that hands the browser the same React UI. Any OS with a browser
//! works; the heavy lifting (mic, speaker, adb, files) stays in this process.
//!
//! The Tauri build calls `invoke("name", args)`; here the very same command
//! functions are reached with `POST /api/name` and a JSON body, and the event
//! stream the webview gets from Tauri arrives as Server-Sent Events.
//!
//! # Threat model
//!
//! This is a local server holding the keys to a phone's SMS, files and calls, so
//! any website the user visits must not be able to talk to it:
//! * every request needs a secret token, delivered once via the URL printed at
//!   start-up and then kept in an `HttpOnly; SameSite=Strict` cookie;
//! * the `Host` header must be the loopback name we bound (defeats DNS
//!   rebinding), and state-changing requests must be same-origin;
//! * it listens on 127.0.0.1 unless told otherwise.

use crate::commands;
use crate::shim::State as S;
use crate::state::{AppState, SharedState};
use axum::body::Body;
use axum::extract::{Path as AxPath, Query, Request, State};
use axum::http::{header, HeaderMap, HeaderValue, Method, StatusCode};
use axum::middleware::{self, Next};
use axum::response::sse::{Event, KeepAlive, Sse};
use axum::response::{IntoResponse, Redirect, Response};
use axum::routing::{any, get, put};
use axum::{Json, Router};
use futures_util::{Stream, StreamExt};
use serde::Deserialize;
use serde_json::{json, Value};
use std::convert::Infallible;
use std::net::SocketAddr;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The built frontend (`npm run build` → `desktop/dist`), baked into the binary
/// so the program is a single file. Set `INTERLINK_WEB_DIST` to serve a folder
/// from disk instead (handy while developing the UI).
#[derive(rust_embed::RustEmbed)]
#[folder = "../dist/"]
struct Assets;

pub const DEFAULT_PORT: u16 = 27350;
const COOKIE: &str = "il_web";

/// How the web server was asked to run.
pub struct WebConfig {
    pub listen: SocketAddr,
    pub open_browser: bool,
    pub config_dir: PathBuf,
}

/// Where the desktop app keeps its config. The web build uses the same folder
/// on purpose: a laptop already paired through one is paired for the other.
pub fn default_config_dir() -> PathBuf {
    dirs::config_dir()
        .unwrap_or_else(|| PathBuf::from("."))
        .join("com.interlink.desktop")
}

#[derive(Clone)]
struct Ctx {
    state: SharedState,
    token: Arc<String>,
    /// `host:port` values the browser may use; empty = don't check (non-loopback
    /// listen, where the host is whatever the user typed).
    hosts: Arc<Vec<String>>,
    uploads: Arc<PathBuf>,
    cache: Arc<PathBuf>,
}

/// Start the phone link and the HTTP server; returns only when the server stops.
pub async fn run(cfg: WebConfig) -> Result<(), String> {
    std::fs::create_dir_all(&cfg.config_dir)
        .map_err(|e| format!("cannot create {}: {e}", cfg.config_dir.display()))?;

    let state = AppState::new(cfg.config_dir.join("config.json"));
    // Bind the phone-facing ports first: if the desktop app (or another copy) has
    // them, say so now instead of serving a UI that can never reach a phone.
    crate::server::start(state.clone()).await.map_err(|e| {
        format!(
            "{e}\n  Is the InterLink desktop app (or another interlink-web) already running? \
             Only one can own the phone link at a time."
        )
    })?;
    crate::connection::spawn(state.clone());

    let token = load_or_create_token(&cfg.config_dir.join("web-token"))?;
    let uploads = cfg.config_dir.join("web-uploads");
    let cache = cfg.config_dir.join("web-cache");
    sweep_old(&uploads);
    sweep_old(&cache);
    let _ = std::fs::create_dir_all(&uploads);
    let _ = std::fs::create_dir_all(&cache);

    let (listener, addr) = bind_with_fallback(cfg.listen).await?;
    let hosts = if addr.ip().is_loopback() {
        let port = addr.port();
        vec![format!("127.0.0.1:{port}"), format!("localhost:{port}"), format!("[::1]:{port}")]
    } else {
        Vec::new()
    };

    let ctx = Ctx {
        state,
        token: Arc::new(token.clone()),
        hosts: Arc::new(hosts),
        uploads: Arc::new(uploads),
        cache: Arc::new(cache),
    };

    let app = Router::new()
        .route("/api/events", get(events))
        .route("/api/upload", put(upload))
        .route("/api/media/file", get(media_file))
        .route("/api/{cmd}", any(api))
        .fallback(static_asset)
        // Uploads are whole videos and APKs, not form fields. They land in a
        // scratch folder on this machine (swept after a day), so a generous cap
        // is fine — the phone link itself still decides what it accepts.
        .layer(axum::extract::DefaultBodyLimit::max(2 * 1024 * 1024 * 1024))
        .layer(middleware::from_fn_with_state(ctx.clone(), guard))
        .with_state(ctx);

    let shown = if addr.ip().is_unspecified() { "127.0.0.1".to_string() } else { addr.ip().to_string() };
    let url = format!("http://{shown}:{}/?t={token}", addr.port());
    println!("\nInterLink web is running.\n  Open: {url}\n  (the link carries a private key — keep it to yourself)");
    if !addr.ip().is_loopback() {
        println!(
            "  WARNING: listening on {addr}. Other devices on this network can reach the page, \
             and plain HTTP exposes the key to anyone sniffing it."
        );
    }
    if cfg.open_browser {
        if let Err(e) = webbrowser::open(&url) {
            println!("  (could not open a browser automatically: {e})");
        }
    }

    axum::serve(listener, app).await.map_err(|e| format!("web server stopped: {e}"))
}

async fn bind_with_fallback(want: SocketAddr) -> Result<(tokio::net::TcpListener, SocketAddr), String> {
    let mut last = String::new();
    for offset in 0..10u16 {
        let mut addr = want;
        addr.set_port(want.port().saturating_add(offset));
        match tokio::net::TcpListener::bind(addr).await {
            Ok(l) => {
                let bound = l.local_addr().unwrap_or(addr);
                return Ok((l, bound));
            }
            Err(e) => last = format!("{addr}: {e}"),
        }
    }
    Err(format!("cannot bind a web port near {want}: {last}"))
}

// ——— auth ———

fn load_or_create_token(path: &Path) -> Result<String, String> {
    if let Ok(t) = std::fs::read_to_string(path) {
        let t = t.trim().to_string();
        if t.len() >= 32 {
            return Ok(t);
        }
    }
    let token: String = crate::crypto::random_bytes32().iter().map(|b| format!("{b:02x}")).collect();
    std::fs::write(path, &token).map_err(|e| format!("cannot write {}: {e}", path.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
    }
    Ok(token)
}

fn ct_eq(a: &str, b: &str) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.bytes().zip(b.bytes()).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

fn cookie_token(headers: &HeaderMap) -> Option<String> {
    let raw = headers.get(header::COOKIE)?.to_str().ok()?;
    raw.split(';').find_map(|kv| {
        let (k, v) = kv.trim().split_once('=')?;
        (k == COOKIE).then(|| v.to_string())
    })
}

fn plain(status: StatusCode, msg: &str) -> Response {
    (status, [(header::CONTENT_TYPE, "text/plain; charset=utf-8")], msg.to_string()).into_response()
}

async fn guard(State(ctx): State<Ctx>, req: Request, next: Next) -> Response {
    let headers = req.headers();

    // DNS-rebinding defence: a page on evil.example resolving to 127.0.0.1 still
    // sends `Host: evil.example`.
    if !ctx.hosts.is_empty() {
        let host = headers.get(header::HOST).and_then(|h| h.to_str().ok()).unwrap_or("");
        if !ctx.hosts.iter().any(|h| h.eq_ignore_ascii_case(host)) {
            return plain(StatusCode::FORBIDDEN, "unexpected Host header");
        }
    }

    // First visit via the printed link: trade `?t=` for a cookie, then drop the
    // token from the address bar.
    if let Some(q) = req.uri().query() {
        let given = q.split('&').find_map(|kv| kv.strip_prefix("t=")).unwrap_or("");
        if !given.is_empty() && ct_eq(given, &ctx.token) {
            let cookie = format!(
                "{COOKIE}={}; Path=/; HttpOnly; SameSite=Strict; Max-Age=31536000",
                ctx.token
            );
            let mut res = Redirect::to(req.uri().path()).into_response();
            if let Ok(v) = HeaderValue::from_str(&cookie) {
                res.headers_mut().insert(header::SET_COOKIE, v);
            }
            return res;
        }
    }

    let ok = cookie_token(headers).map(|c| ct_eq(&c, &ctx.token)).unwrap_or(false);
    if !ok {
        return plain(
            StatusCode::UNAUTHORIZED,
            "InterLink web: open the link printed when interlink-web started \
             (it ends in ?t=…), or run `interlink-web` again to open it.",
        );
    }

    // Same-origin only for anything that changes state.
    if req.method() != Method::GET && req.method() != Method::HEAD {
        if let Some(origin) = headers.get(header::ORIGIN).and_then(|o| o.to_str().ok()) {
            let host = headers.get(header::HOST).and_then(|h| h.to_str().ok()).unwrap_or("");
            if origin != format!("http://{host}") {
                return plain(StatusCode::FORBIDDEN, "cross-origin request refused");
            }
        }
    }
    next.run(req).await
}

// ——— API ———

async fn api(State(ctx): State<Ctx>, AxPath(cmd): AxPath<String>, body: axum::body::Bytes) -> Response {
    let args: Value = if body.is_empty() {
        json!({})
    } else {
        match serde_json::from_slice(&body) {
            Ok(v) => v,
            Err(e) => return error(StatusCode::BAD_REQUEST, format!("bad_request: {e}")),
        }
    };
    match dispatch(&ctx.state, &cmd, args).await {
        Ok(v) => Json(v).into_response(),
        Err(e) => error(StatusCode::BAD_REQUEST, e),
    }
}

fn error(status: StatusCode, msg: String) -> Response {
    (status, Json(json!({ "error": msg }))).into_response()
}

fn parse<T: serde::de::DeserializeOwned>(args: Value) -> Result<T, String> {
    serde_json::from_value(args).map_err(|e| format!("bad_request: {e}"))
}

/// Same command surface as the Tauri build, by name. Argument names are the
/// camelCase ones the frontend already sends to `invoke`.
async fn dispatch(state: &SharedState, cmd: &str, args: Value) -> Result<Value, String> {
    let st = S(state);
    match cmd {
        "get_status" => commands::get_status(st).await,
        "get_devices" => commands::get_devices(st).await,
        "get_pairing_qr" => {
            #[derive(Deserialize)]
            struct A {
                rotate: Option<bool>,
            }
            let a: A = parse(args)?;
            commands::get_pairing_qr(st, a.rotate).await
        }
        "unpair" => commands::unpair(st).await,
        "pair_mode" => {
            #[derive(Deserialize)]
            struct A {
                enabled: bool,
            }
            let a: A = parse(args)?;
            commands::pair_mode(st, a.enabled).await
        }
        "pair_decide" => {
            #[derive(Deserialize)]
            struct A {
                id: String,
                approve: bool,
            }
            let a: A = parse(args)?;
            commands::pair_decide(st, a.id, a.approve).await
        }
        "send_request" => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct A {
                t: String,
                #[serde(default)]
                d: Value,
                timeout_ms: Option<u64>,
            }
            let a: A = parse(args)?;
            commands::send_request(st, a.t, a.d, a.timeout_ms).await
        }
        "media_thumb" => {
            #[derive(Deserialize)]
            struct A {
                id: String,
                w: Option<u32>,
            }
            let a: A = parse(args)?;
            commands::media_thumb(st, a.id, a.w).await
        }
        "media_download" => {
            #[derive(Deserialize)]
            struct A {
                id: String,
                suggested: Option<String>,
                subdir: Option<String>,
            }
            let a: A = parse(args)?;
            commands::media_download(st, a.id, a.suggested, a.subdir).await
        }
        "media_stream_url" => {
            #[derive(Deserialize)]
            struct A {
                id: String,
            }
            let a: A = parse(args)?;
            commands::media_stream_url(st, a.id).await.map(Value::String)
        }
        "mirror_start" => {
            #[derive(Deserialize)]
            struct A {
                w: Option<u32>,
                fps: Option<u32>,
                audio: Option<bool>,
            }
            let a: A = parse(args)?;
            commands::mirror_start(st, a.w, a.fps, a.audio).await
        }
        "mirror_audio" => {
            #[derive(Deserialize)]
            struct A {
                on: bool,
            }
            let a: A = parse(args)?;
            commands::mirror_audio(st, a.on).await
        }
        "mirror_stop" => commands::mirror_stop(st).await,
        "file_send" => {
            #[derive(Deserialize)]
            struct A {
                path: String,
                dest: Option<String>,
            }
            let a: A = parse(args)?;
            commands::file_send(st, a.path, a.dest).await
        }
        "list_audio_inputs" => commands::list_audio_inputs(st).await,
        "list_audio_outputs" => commands::list_audio_outputs(st).await,
        "call_audio_start" => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct A {
                call_id: String,
                mic: Option<String>,
                speaker: Option<String>,
                uplink: Option<bool>,
                downlink: Option<bool>,
            }
            let a: A = parse(args)?;
            commands::call_audio_start(st, a.call_id, a.mic, a.speaker, a.uplink, a.downlink).await
        }
        "call_audio_stop" => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct A {
                call_id: Option<String>,
            }
            let a: A = parse(args)?;
            commands::call_audio_stop(st, a.call_id).await
        }
        "call_mute" => {
            #[derive(Deserialize)]
            struct A {
                muted: bool,
            }
            let a: A = parse(args)?;
            commands::call_mute(st, a.muted).await
        }
        "get_settings" => {
            // The desktop webview is trusted with the pairing secret; a browser
            // page (possibly reached over the LAN) is not.
            let mut v = commands::get_settings(st).await?;
            if let Some(o) = v.as_object_mut() {
                o.remove("secret_b64");
            }
            Ok(v)
        }
        "set_settings" => {
            #[derive(Deserialize)]
            struct A {
                patch: Value,
            }
            let a: A = parse(args)?;
            commands::set_settings(st, a.patch).await
        }
        "connection_refresh" => commands::connection_refresh(st).await,
        "backup_start" => {
            #[derive(Deserialize)]
            #[serde(rename_all = "camelCase")]
            struct A {
                sections: Option<Vec<String>>,
                out_dir: Option<String>,
            }
            let a: A = parse(args)?;
            commands::backup_start(st, a.sections, a.out_dir).await
        }
        "backup_restore" => {
            #[derive(Deserialize)]
            struct A {
                dir: String,
            }
            let a: A = parse(args)?;
            commands::backup_restore(st, a.dir).await
        }
        "backup_list" => {
            #[derive(Deserialize)]
            struct A {
                dir: String,
            }
            let a: A = parse(args)?;
            commands::backup_list(a.dir).await
        }
        "notif_image" => {
            #[derive(Deserialize)]
            struct A {
                key: String,
            }
            let a: A = parse(args)?;
            commands::notif_image(st, a.key).await
        }
        "notif_save_image" => {
            #[derive(Deserialize)]
            struct A {
                key: String,
                name: Option<String>,
            }
            let a: A = parse(args)?;
            commands::notif_save_image(st, a.key, a.name).await.map(Value::String)
        }
        // Native dialogs and OS drag belong to the window app. The browser build
        // uses its own file picker and plain downloads instead, so these only
        // answer "nothing chosen" if something still calls them.
        "pick_apk" | "pick_backup_dir" | "pick_directory" => Ok(json!({ "path": null })),
        "pick_files" => Ok(json!({ "paths": [] })),
        "reveal_in_folder" => Ok(Value::Null),
        "media_drag" => Err("unsupported: dragging out needs the desktop app; use Download".into()),
        other => Err(format!("not_found: unknown command {other}")),
    }
}

// ——— events ———

/// The Tauri `il:ev` stream, as Server-Sent Events. `EventSource` reconnects by
/// itself, and the page re-reads status when it does.
async fn events(State(ctx): State<Ctx>) -> Sse<impl Stream<Item = Result<Event, Infallible>>> {
    let rx = ctx.state.events.subscribe();
    let stream = futures_util::stream::unfold(rx, |mut rx| async move {
        loop {
            match rx.recv().await {
                Ok(v) => return Some((Ok(Event::default().data(v.to_string())), rx)),
                // Falling behind drops the oldest events; carry on with newer ones.
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(tokio::sync::broadcast::error::RecvError::Closed) => return None,
            }
        }
    });
    Sse::new(stream).keep_alive(KeepAlive::default())
}

// ——— files in and out ———

#[derive(Deserialize)]
struct UploadQuery {
    name: String,
}

/// Browser → this machine: the page's file picker sends the bytes here, and the
/// returned path is what `file_send` then pushes to the phone.
async fn upload(State(ctx): State<Ctx>, Query(q): Query<UploadQuery>, body: Body) -> Response {
    use tokio::io::AsyncWriteExt;

    let name = upload_filename(&q.name);
    let dir = ctx.uploads.join(format!("{:x}", rand::random::<u64>()));
    if let Err(e) = tokio::fs::create_dir_all(&dir).await {
        return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}"));
    }
    let path = dir.join(if name.is_empty() { "upload".to_string() } else { name });
    let mut file = match tokio::fs::File::create(&path).await {
        Ok(f) => f,
        Err(e) => return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}")),
    };
    let mut size = 0u64;
    let mut stream = body.into_data_stream();
    while let Some(chunk) = stream.next().await {
        let chunk = match chunk {
            Ok(c) => c,
            Err(e) => {
                let _ = tokio::fs::remove_dir_all(&dir).await;
                return error(StatusCode::BAD_REQUEST, format!("upload interrupted: {e}"));
            }
        };
        size += chunk.len() as u64;
        if let Err(e) = file.write_all(&chunk).await {
            let _ = tokio::fs::remove_dir_all(&dir).await;
            return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}"));
        }
    }
    if let Err(e) = file.flush().await {
        return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}"));
    }
    Json(json!({ "path": path.display().to_string(), "size": size })).into_response()
}

#[derive(Deserialize)]
struct MediaQuery {
    id: String,
    name: Option<String>,
}

/// A phone photo/video as a normal browser download (and what Chromium's
/// drag-to-desktop fetches). It is pulled over the link into a scratch folder,
/// streamed out, and swept up later.
async fn media_file(State(ctx): State<Ctx>, Query(q): Query<MediaQuery>) -> Response {
    // One folder per media id, kept for a day: a second click (or a browser
    // that retries a ranged request) reuses the bytes already pulled over the
    // link instead of downloading a whole video again. The transfer lands in
    // `<id>.part` first, so a failed or interrupted pull never leaves a
    // truncated file that a later click would happily serve.
    let key = media_cache_key(&q.id);
    let folder = ctx.cache.join(&key);
    let saved = match cached_file(&folder).await {
        Some(p) => p,
        None => {
            let staging = ctx.cache.join(format!("{key}.part"));
            let _ = tokio::fs::remove_dir_all(&staging).await;
            if let Err(e) = tokio::fs::create_dir_all(&staging).await {
                return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}"));
            }
            let pulled = commands::save_media_to(&ctx.state, q.id.clone(), q.name, &staging).await;
            let pulled = match pulled {
                Ok(p) => p,
                Err(e) => {
                    let _ = tokio::fs::remove_dir_all(&staging).await;
                    return error(StatusCode::BAD_GATEWAY, e);
                }
            };
            let _ = tokio::fs::remove_dir_all(&folder).await;
            if let Err(e) = tokio::fs::rename(&staging, &folder).await {
                let _ = tokio::fs::remove_dir_all(&staging).await;
                return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}"));
            }
            folder.join(pulled.file_name().unwrap_or_else(|| std::ffi::OsStr::new("media")))
        }
    };
    sweep_old(&ctx.cache);
    let file = match tokio::fs::File::open(&saved).await {
        Ok(f) => f,
        Err(e) => return error(StatusCode::INTERNAL_SERVER_ERROR, format!("internal: {e}")),
    };
    let name = saved.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "file".into());
    let len = file.metadata().await.map(|m| m.len()).ok();
    let mime = mime_guess::from_path(&saved).first_or_octet_stream();
    let mut res = Response::new(Body::from_stream(tokio_util::io::ReaderStream::new(file)));
    let h = res.headers_mut();
    if let Ok(v) = HeaderValue::from_str(mime.as_ref()) {
        h.insert(header::CONTENT_TYPE, v);
    }
    if let Some(len) = len {
        h.insert(header::CONTENT_LENGTH, HeaderValue::from(len));
    }
    let ascii: String = name.chars().map(|c| if c.is_ascii() && c != '"' && c != '\\' { c } else { '_' }).collect();
    let disp = format!("attachment; filename=\"{ascii}\"; filename*=UTF-8''{}", pct_encode(&name));
    if let Ok(v) = HeaderValue::from_str(&disp) {
        h.insert(header::CONTENT_DISPOSITION, v);
    }
    res
}

/// A finished download in `dir`, if there is one.
async fn cached_file(dir: &Path) -> Option<PathBuf> {
    let mut entries = tokio::fs::read_dir(dir).await.ok()?;
    while let Ok(Some(e)) = entries.next_entry().await {
        let p = e.path();
        if p.is_file() && e.metadata().await.map(|m| m.len() > 0).unwrap_or(false) {
            return Some(p);
        }
    }
    None
}

/// A media id as a folder name: ids look like `i:1000605669`.
fn media_cache_key(id: &str) -> String {
    id.chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect()
}

fn pct_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~".contains(&b) {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// A picked file's name, made safe for this machine's filesystem. Unlike
/// `safe_filename` (which names notification images and always ends in .jpg),
/// this keeps the extension the phone/target needs.
fn upload_filename(raw: &str) -> String {
    let cleaned: String = raw
        .chars()
        .map(|c| match c {
            '/' | '\\' | ':' | '*' | '?' | '"' | '<' | '>' | '|' => '-',
            c if c.is_control() => ' ',
            c => c,
        })
        .collect();
    let name = cleaned
        .chars()
        .take(150)
        .collect::<String>()
        .trim()
        .trim_matches('.')
        .trim()
        .to_string();
    if name.is_empty() {
        "upload".into()
    } else {
        name
    }
}

/// Delete scratch files older than a day so uploads and downloads don't pile up.
fn sweep_old(dir: &Path) {
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    let cutoff = std::time::SystemTime::now() - std::time::Duration::from_secs(24 * 3600);
    for e in entries.flatten() {
        let old = e.metadata().and_then(|m| m.modified()).map(|t| t < cutoff).unwrap_or(false);
        if old {
            let p = e.path();
            let _ = if p.is_dir() { std::fs::remove_dir_all(&p) } else { std::fs::remove_file(&p) };
        }
    }
}

// ——— the UI itself ———

async fn static_asset(req: Request) -> Response {
    let path = req.uri().path().trim_start_matches('/');
    let path = if path.is_empty() { "index.html" } else { path };
    if let Some(r) = asset(path) {
        return r;
    }
    // Single-page app: unknown non-file paths get the app shell. A missing real
    // file (has an extension) is a genuine 404.
    if !path.rsplit('/').next().unwrap_or("").contains('.') {
        if let Some(r) = asset("index.html") {
            return r;
        }
    }
    plain(StatusCode::NOT_FOUND, "not found")
}

fn asset(path: &str) -> Option<Response> {
    let bytes: Vec<u8> = match std::env::var_os("INTERLINK_WEB_DIST") {
        Some(dir) => {
            // Refuse `..` so the override can't be used to read arbitrary files.
            if path.split('/').any(|seg| seg == "..") {
                return None;
            }
            std::fs::read(PathBuf::from(dir).join(path)).ok()?
        }
        None => Assets::get(path)?.data.into_owned(),
    };
    let mime = mime_guess::from_path(path).first_or_octet_stream();
    let cache = if path == "index.html" { "no-cache" } else { "public, max-age=3600" };
    let mut res = Response::new(Body::from(bytes));
    let h = res.headers_mut();
    if let Ok(v) = HeaderValue::from_str(mime.as_ref()) {
        h.insert(header::CONTENT_TYPE, v);
    }
    h.insert(header::CACHE_CONTROL, HeaderValue::from_static(cache));
    // The page holds a phone's messages; don't let it be framed or sniffed.
    h.insert("x-frame-options", HeaderValue::from_static("DENY"));
    h.insert("x-content-type-options", HeaderValue::from_static("nosniff"));
    Some(res)
}
