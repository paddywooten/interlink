//! Call audio relay: laptop mic → PCM 16 kHz mono frames → phone;
//! phone → 16 kHz frames → laptop speakers.
//!
//! cpal `Stream` is `!Send`, so BOTH streams are built and owned by a dedicated
//! audio thread; the public handle only carries Send-safe primitives.
//! Devices negotiate native rates; linear resampling bridges to 16 kHz.

use crate::crypto::tid_from_str;
use crate::state::SharedState;
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{Device, SampleFormat, Stream, StreamConfig};
use serde_json::json;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

pub const RATE: u32 = 16_000;
const FRAME_SAMPLES: usize = RATE as usize / 50; // 20 ms @ 16k
const MAX_RING: usize = RATE as usize * 3; // 3 s of playout headroom

/// Streaming linear resampler (input-rate → output-rate).
struct Resampler {
    step: f64, // input samples consumed per output sample
    hist: Vec<f32>,
    idx: f64,
}

impl Resampler {
    fn new(in_rate: u32, out_rate: u32) -> Self {
        Resampler {
            step: in_rate as f64 / out_rate as f64,
            hist: Vec::with_capacity(2048),
            idx: 0.0,
        }
    }

    fn push(&mut self, input: &[f32], out: &mut Vec<f32>) {
        if input.is_empty() {
            return;
        }
        self.hist.extend_from_slice(input);
        let hist_len = self.hist.len() as f64;
        while self.idx + 1.0 < hist_len {
            let i = self.idx.floor() as usize;
            if i + 1 >= self.hist.len() {
                break;
            }
            let frac = (self.idx - i as f64) as f32;
            out.push(self.hist[i] * (1.0 - frac) + self.hist[i + 1] * frac);
            self.idx += self.step;
        }
        let consume = (self.idx.floor() as usize)
            .saturating_sub(1)
            .min(self.hist.len().saturating_sub(2));
        if consume > 0 {
            self.hist.drain(..consume);
            self.idx -= consume as f64;
        }
    }
}

/// Device-rate playout ring (mono f32; channels expanded in the callback).
struct OutRing {
    q: Mutex<VecDeque<f32>>,
}

impl OutRing {
    fn new() -> Self {
        OutRing { q: Mutex::new(VecDeque::with_capacity(MAX_RING)) }
    }
    fn push(&self, samples: &[f32]) {
        let mut q = self.q.lock().unwrap();
        for &s in samples {
            if q.len() >= MAX_RING {
                q.pop_front(); // latency bound: drop oldest
            }
            q.push_back(s);
        }
    }
    fn pop_into(&self, out: &mut [f32]) -> usize {
        let mut q = self.q.lock().unwrap();
        let mut got = 0;
        while got < out.len() && !q.is_empty() {
            out[got] = q.pop_front().unwrap();
            got += 1;
        }
        got
    }
}

/// Send-safe handle. Dropping it stops the audio thread (streams die there).
pub struct CallAudioHandle {
    stop: std::sync::mpsc::Sender<()>,
    pub muted: Arc<AtomicBool>,
    pub call_id: String,
}

impl Drop for CallAudioHandle {
    fn drop(&mut self) {
        let _ = self.stop.send(());
        // Thread detaches and exits promptly; streams are dropped there.
    }
}

/// Enumerate microphones: [{name, label?, default}].
///
/// Prefers PipeWire's list (see `pulse_devices`) and only falls back to what
/// cpal can see when `pactl` is unavailable.
pub fn list_inputs() -> Vec<serde_json::Value> {
    let pulse = pulse_json(Track::Capture);
    if pulse.is_empty() {
        alsa_inputs()
    } else {
        pulse
    }
}

fn alsa_inputs() -> Vec<serde_json::Value> {
    let host = cpal::default_host();
    let default_name = host.default_input_device().and_then(|d| d.name().ok());
    let mut out: Vec<serde_json::Value> = Vec::new();
    if let Ok(devs) = host.input_devices() {
        for d in devs {
            let name = d.name().unwrap_or_else(|_| "unknown".into());
            let is_default = default_name.as_deref() == Some(name.as_str());
            if !out.iter().any(|v| v["name"] == name) {
                out.push(json!({ "name": name, "default": is_default }));
            }
        }
    }
    out
}

fn pick_input(want: Option<&str>) -> Result<Device, String> {
    let host = cpal::default_host();
    if let Some(want) = want {
        if let Ok(mut devs) = host.input_devices() {
            if let Some(d) = devs.find(|d| d.name().map(|n| n == want).unwrap_or(false)) {
                return Ok(d);
            }
        }
    }
    host.default_input_device().ok_or_else(|| "internal: no microphone found".into())
}

/// Enumerate output devices (laptop speakers, Bluetooth speakers, HDMI, …):
/// [{name, label?, default}] — PipeWire's view when `pactl` is available.
pub fn list_outputs() -> Vec<serde_json::Value> {
    let pulse = pulse_json(Track::Playback);
    if pulse.is_empty() {
        alsa_outputs()
    } else {
        pulse
    }
}

fn alsa_outputs() -> Vec<serde_json::Value> {
    let host = cpal::default_host();
    let default_name = host.default_output_device().and_then(|d| d.name().ok());
    let mut out: Vec<serde_json::Value> = Vec::new();
    if let Ok(devs) = host.output_devices() {
        for d in devs {
            let name = d.name().unwrap_or_else(|_| "unknown".into());
            let is_default = default_name.as_deref() == Some(name.as_str());
            if !out.iter().any(|v| v["name"] == name) {
                out.push(json!({ "name": name, "default": is_default }));
            }
        }
    }
    out
}

fn pick_output(want: Option<&str>) -> Result<Device, String> {
    let host = cpal::default_host();
    if let Some(want) = want {
        if let Ok(mut devs) = host.output_devices() {
            if let Some(d) = devs.find(|d| d.name().map(|n| n == want).unwrap_or(false)) {
                return Ok(d);
            }
        }
    }
    host.default_output_device().ok_or_else(|| "internal: no output device".into())
}

// ——— Real devices: PipeWire is where Bluetooth actually lives —————————————
//
// cpal only speaks ALSA, and ALSA's view of this machine is pseudo-devices
// (`upmix`, `surround51:CARD=PCH,DEV=0`, `hdmi:CARD=NVidia,DEV=1`) that mean
// nothing to a person — while every *real* device, Bluetooth included, sits
// behind the single `default` PCM. So the pickers read the device list from
// PulseAudio's compatibility layer (`pactl`, installed wherever PipeWire is),
// and honouring a choice means relinking *our* stream onto that node rather
// than opening it: `pactl move-sink-input` moves one stream, leaving the
// system default — and every other app — exactly where it was.

/// Which end of the relay a device sits on.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Track {
    /// What the far end is played through (a Pulse/PipeWire *sink*).
    Playback,
    /// What the near end is captured from (a *source*).
    Capture,
}

/// One device a user can actually pick: node name, the friendly name the
/// desktop shows, and whether it is the node new streams land on by default.
#[derive(Clone, Debug)]
pub struct PulseDevice {
    pub name: String,
    pub label: String,
    pub is_default: bool,
}

fn pactl_text(args: &[&str]) -> Option<String> {
    let out = std::process::Command::new("pactl").args(args).output().ok()?;
    if !out.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&out.stdout).into_owned())
}

fn pactl_json(args: &[&str]) -> Option<serde_json::Value> {
    let mut full: Vec<&str> = vec!["--format", "json"];
    full.extend_from_slice(args);
    let out = std::process::Command::new("pactl").args(&full).output().ok()?;
    if !out.status.success() {
        return None;
    }
    serde_json::from_slice(&out.stdout).ok()
}

/// `pactl info`'s two default lines: (default sink, default source).
fn pactl_defaults() -> (Option<String>, Option<String>) {
    let Some(text) = pactl_text(&["info"]) else { return (None, None) };
    let grab = |key: &str| {
        text.lines()
            .find_map(|l| l.strip_prefix(key))
            .map(|v| v.trim().to_string())
    };
    (grab("Default Sink: "), grab("Default Source: "))
}

/// Turn a `pactl --format json list sinks|sources` payload into devices,
/// dropping sink monitors — a monitor is a speaker's own output heard back,
/// and listing it would show every output a second time as a microphone.
fn parse_devices(listing: &serde_json::Value, capture: bool, default: Option<&str>) -> Vec<PulseDevice> {
    let Some(arr) = listing.as_array() else { return Vec::new() };
    arr.iter()
        .filter_map(|node| {
            let name = node["name"].as_str()?.to_string();
            if name.is_empty() || (capture && name.ends_with(".monitor")) {
                return None;
            }
            let label = node["description"]
                .as_str()
                .filter(|s| !s.is_empty())
                .unwrap_or(name.as_str())
                .to_string();
            Some(PulseDevice {
                is_default: default == Some(name.as_str()),
                name,
                label,
            })
        })
        .collect()
}

/// Every node of `track` PipeWire will route to; `None` when `pactl` is
/// unavailable (callers then fall back to cpal's ALSA view, so a system
/// without the PulseAudio compat layer behaves as it always has).
pub fn pulse_devices(track: Track) -> Option<Vec<PulseDevice>> {
    let listing = pactl_json(&[
        "--format",
        "json",
        "list",
        match track {
            Track::Playback => "sinks",
            Track::Capture => "sources",
        },
    ])?;
    let (sink, source) = pactl_defaults();
    let default = match track {
        Track::Playback => sink,
        Track::Capture => source,
    };
    Some(parse_devices(
        &listing,
        track == Track::Capture,
        default.as_deref(),
    ))
}

/// Device list for the pickers: `[{name, label, default}]`, or `[]` when
/// `pactl` is missing (the caller then falls back to the ALSA list).
fn pulse_json(track: Track) -> Vec<serde_json::Value> {
    pulse_devices(track)
        .unwrap_or_default()
        .into_iter()
        .map(|d| json!({ "name": d.name, "label": d.label, "default": d.is_default }))
        .collect()
}

/// Basename of this binary — Pulse/PipeWire calls our client
/// `PipeWire ALSA [<exe>]`, which is how we recognise our own stream.
fn exe_name() -> String {
    std::env::current_exe()
        .ok()
        .and_then(|p| p.file_name().map(|f| f.to_string_lossy().into_owned()))
        .unwrap_or_default()
}

/// Split a `pactl list sink-inputs|source-outputs` dump into
/// (index, application.name) pairs.
fn parse_stream_blocks(listing: &str, head: &str) -> Vec<(u32, Option<String>)> {
    let mut out: Vec<(u32, Option<String>)> = Vec::new();
    let mut cur: Option<(u32, Option<String>)> = None;
    for line in listing.lines() {
        if let Some(rest) = line.strip_prefix(head) {
            if let Some(block) = cur.take() {
                out.push(block);
            }
            if let Ok(id) = rest.trim().parse::<u32>() {
                cur = Some((id, None));
            }
        } else if let Some(block) = cur.as_mut() {
            if let Some(v) = line.trim().strip_prefix("application.name = ") {
                block.1 = Some(v.trim().trim_matches('"').to_string());
            }
        }
    }
    if let Some(block) = cur.take() {
        out.push(block);
    }
    out
}

/// Relink *our* relay stream onto the `target` node.
///
/// Identification matters: the mirror relay is the same binary, so when both
/// are up there are two of our streams — the newest is the one just opened,
/// which is why this is called right after a relay starts.
pub fn route_stream(track: Track, target: &str) -> Result<(), String> {
    let (listing_arg, move_arg, head) = match track {
        Track::Playback => ("sink-inputs", "move-sink-input", "Sink Input #"),
        Track::Capture => ("source-outputs", "move-source-output", "Source Output #"),
    };
    let listing = pactl_text(&["list", listing_arg])
        .ok_or_else(|| "pactl unavailable".to_string())?;
    let marker = format!("[{}]", exe_name());
    let exe = exe_name();
    let own = parse_stream_blocks(&listing, head)
        .into_iter()
        .filter(|(_, app)| match app.as_deref() {
            Some(a) => a.contains(&marker) || a == exe,
            None => false,
        })
        .map(|(i, _)| i)
        .max();
    let id = own.ok_or_else(|| format!("our {listing_arg} stream is not registered yet"))?;
    let out = std::process::Command::new("pactl")
        .args([move_arg, &id.to_string(), target])
        .output()
        .map_err(|e| format!("pactl: {e}"))?;
    if !out.status.success() {
        return Err(format!(
            "pactl {move_arg}: {}",
            String::from_utf8_lossy(&out.stderr).trim()
        ));
    }
    Ok(())
}

/// A stream only registers with PulseAudio once it is actually running, and
/// this is called the moment the relay starts — so keep trying for ~2 s.
pub async fn route_with_retry(track: Track, target: &str) -> Result<(), String> {
    let mut last = "stream not registered".to_string();
    for _ in 0..20 {
        let target = target.to_string();
        match tokio::task::spawn_blocking(move || route_stream(track, &target)).await {
            Ok(Ok(())) => return Ok(()),
            Ok(Err(e)) => last = e,
            Err(e) => last = e.to_string(),
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    }
    Err(last)
}

/// Schedule the move for a device the user picked, if it is a real
/// PipeWire node: `""` (default) and legacy ALSA names need nothing — cpal
/// opens those itself — and a node that *is* the default is where the stream
/// lands anyway.
pub fn route_async(track: Track, want: Option<&str>) {
    let Some(want) = want.filter(|w| !w.is_empty()) else { return };
    let Some(dev) = pulse_devices(track).and_then(|ds| ds.into_iter().find(|d| d.name == want))
    else {
        return;
    };
    if dev.is_default {
        return;
    }
    let (name, label) = (dev.name, dev.label);
    tokio::spawn(async move {
        match route_with_retry(track, &name).await {
            Ok(()) => log::info!("relay audio routed to {label} ({name})"),
            Err(e) => log::warn!("could not route relay audio to {label} ({name}): {e}"),
        }
    });
}

/// Start relaying call audio. `call_id` ties frames to the active call.
///
/// The two directions are independent so the user can e.g. hear the far end
/// on a Bluetooth speaker while still speaking into the phone:
///   `uplink`   — laptop mic → phone (speak through the laptop)
///   `downlink` — phone → chosen laptop output (hear through the laptop)
pub async fn start(
    state: SharedState,
    call_id: String,
    mic: Option<String>,
    speaker: Option<String>,
    uplink: bool,
    downlink: bool,
) -> Result<CallAudioHandle, String> {
    if !uplink && !downlink {
        return Err("internal: nothing to relay (both directions off)".into());
    }
    let tid = tid_from_str(&call_id);
    let muted = Arc::new(AtomicBool::new(false));
    let ring = Arc::new(OutRing::new());

    // ——— Downlink registration: route call.audio data frames to our collector ———
    if downlink {
        let (chunk_tx, mut chunk_rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
        {
            let mut inner = state.inner.lock().await;
            inner.data_collectors.remove(&tid);
            inner.data_collectors.insert(tid, chunk_tx);
        }
        // Downlink worker thread: chunks → PCM f32 → device-rate ring. The
        // output callback resamples on pull, so the ring stays at 16 kHz.
        //
        // Frames are counted as they land: "relay active" only ever meant we
        // *opened* an output, and silence arriving from the phone looks
        // exactly like audio unless someone writes the numbers down.
        let ring = ring.clone();
        std::thread::Builder::new()
            .name("il-downlink".into())
            .spawn(move || {
                let mut frames: u64 = 0;
                let mut peak: i32 = 0;
                let mut window = std::time::Instant::now();
                while let Some((_seq, chunk)) = chunk_rx.blocking_recv() {
                    if chunk.len() < 2 {
                        continue;
                    }
                    let samples: Vec<f32> = chunk
                        .chunks_exact(2)
                        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
                        .collect();
                    for s in &samples {
                        let a = (s.abs() * 32_768.0) as i32;
                        if a > peak {
                            peak = a;
                        }
                    }
                    frames += 1;
                    ring.push(&samples);
                    if window.elapsed() >= std::time::Duration::from_secs(2) {
                        log::info!("downlink from phone: {frames} frames, peak {peak}");
                        frames = 0;
                        peak = 0;
                        window = std::time::Instant::now();
                    }
                }
                log::info!("downlink from phone: collector closed ({frames} frames in the last window)");
            })
            .map_err(|e| format!("internal: {e}"))?;
    }

    // ——— Uplink pipeline: mic → frames → seal with session key → send ———
    let pipeline: Option<Arc<MicPipeline>> = if uplink {
        let (frame_tx, mut frame_rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
        {
            let state = state.clone();
            tokio::spawn(async move {
                while let Some((seq, pcm)) = frame_rx.recv().await {
                    if !state.send_data_frame(tid, seq, &pcm).await {
                        break; // disconnected
                    }
                }
            });
        }
        Some(Arc::new(MicPipeline {
            tx: frame_tx,
            // Placeholder until the audio thread learns the device rate (it
            // rewrites this before any callback can run).
            resampler: Mutex::new(Resampler::new(RATE, RATE)),
            pending: Mutex::new(Vec::with_capacity(FRAME_SAMPLES * 4)),
            seq: Mutex::new(0),
            channels: std::sync::atomic::AtomicU32::new(1),
            muted: muted.clone(),
        }))
    } else {
        None
    };

    // ——— Audio thread: owns both cpal streams (they are !Send) ———
    // Reports readiness (or a failure reason) once the streams are running.
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel::<Result<(), String>>(1);
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();

    let pipeline_thread = pipeline.clone();
    let ring_thread = ring.clone();
    std::thread::Builder::new()
        .name("il-call-audio".into())
        .spawn(move || {
            // ——— Uplink: chosen input device → MicPipeline ———
            let in_stream: Option<Stream> = match pipeline_thread.clone() {
                Some(pipeline) => {
                    let (device, cfg) = match pick_input(mic.as_deref()).and_then(|d| {
                        let c = d.default_input_config().map_err(|e| format!("internal: {e}"))?;
                        Ok((d, c))
                    }) {
                        Ok(v) => v,
                        Err(e) => {
                            let _ = ready_tx.send(Err(e));
                            return;
                        }
                    };
                    let channels = cfg.channels();
                    let rate = cfg.sample_rate().0;
                    let fmt = cfg.sample_format();
                    let config = StreamConfig::from(cfg);
                    match build_input_stream(&device, config, fmt, pipeline.clone()) {
                        Ok(s) => {
                            // Reconfigure the pipeline with the true mic rate.
                            {
                                let mut rs = pipeline.resampler.lock().unwrap();
                                *rs = Resampler::new(rate, RATE);
                            }
                            pipeline.channels.store(channels as u32, Ordering::Relaxed);
                            Some(s)
                        }
                        Err(e) => {
                            let _ = ready_tx.send(Err(e));
                            return;
                        }
                    }
                }
                None => None,
            };

            // ——— Downlink: chosen output device ← ring ———
            let out_stream: Option<Stream> = if downlink {
                let out_device = match pick_output(speaker.as_deref()) {
                    Ok(d) => d,
                    Err(e) => {
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                };
                let cfg = match out_device.default_output_config() {
                    Ok(c) => c,
                    Err(e) => {
                        let _ = ready_tx.send(Err(format!("internal: {e}")));
                        return;
                    }
                };
                let fmt = cfg.sample_format();
                // The ring receives 16 kHz samples; the callback resamples on pull.
                let out_resampler =
                    Arc::new(Mutex::new(Resampler::new(RATE, cfg.sample_rate().0)));
                match build_output_stream(
                    &out_device,
                    StreamConfig::from(cfg),
                    fmt,
                    ring_thread,
                    out_resampler,
                    RATE,
                ) {
                    Ok(s) => Some(s),
                    Err(e) => {
                        let _ = ready_tx.send(Err(e));
                        return;
                    }
                }
            } else {
                None
            };

            if ready_tx.send(Ok(())).is_err() {
                return;
            }
            // Stay alive until stopped.
            loop {
                match stop_rx.recv_timeout(std::time::Duration::from_millis(500)) {
                    Ok(()) | Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                }
            }
            drop(in_stream);
            drop(out_stream);
        })
        .map_err(|e| format!("internal: {e}"))?;

    // Wait for the audio thread without blocking the async runtime.
    let started: Result<Result<(), String>, String> = tokio::task::spawn_blocking(move || {
        match ready_rx.recv_timeout(std::time::Duration::from_secs(10)) {
            Ok(r) => r,
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                Err("internal: audio thread failed to start".into())
            }
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                Err("internal: audio thread start timeout".into())
            }
        }
    })
    .await
    .map_err(|e| format!("internal: {e}"));
    match started {
        Ok(Ok(())) => {
            log::info!(
                "call audio relay active for call {call_id} (uplink {uplink}, downlink {downlink})"
            );
            Ok(CallAudioHandle { stop: stop_tx, muted, call_id })
        }
        // Failed or never reported in: drop the downlink collector so data
        // frames don't pile up behind a relay that never started.
        Ok(Err(e)) | Err(e) => {
            state.inner.lock().await.data_collectors.remove(&tid);
            Err(e)
        }
    }
}

fn build_input_stream(
    device: &Device,
    config: StreamConfig,
    fmt: SampleFormat,
    sink: Arc<MicPipeline>,
) -> Result<Stream, String> {
    let err_fn = |e| log::error!("mic stream error: {e}");
    let stream = match fmt {
        SampleFormat::F32 => {
            device.build_input_stream(&config, move |d: &[f32], _| sink.feed_f32(d), err_fn, None)
        }
        SampleFormat::I16 => device.build_input_stream(
            &config,
            move |d: &[i16], _| {
                let f: Vec<f32> = d.iter().map(|&s| s as f32 / 32768.0).collect();
                sink.feed_f32(&f);
            },
            err_fn,
            None,
        ),
        SampleFormat::U16 => device.build_input_stream(
            &config,
            move |d: &[u16], _| {
                let f: Vec<f32> = d.iter().map(|&s| (s as f32 - 32768.0) / 32768.0).collect();
                sink.feed_f32(&f);
            },
            err_fn,
            None,
        ),
        SampleFormat::I32 => device.build_input_stream(
            &config,
            move |d: &[i32], _| {
                let f: Vec<f32> = d.iter().map(|&s| s as f32 / 2_147_483_648.0).collect();
                sink.feed_f32(&f);
            },
            err_fn,
            None,
        ),
        other => return Err(format!("internal: unsupported mic format {other:?}")),
    }
    .map_err(|e| format!("internal: {e}"))?;
    stream.play().map_err(|e| format!("internal: {e}"))?;
    Ok(stream)
}

fn build_output_stream(
    device: &Device,
    config: StreamConfig,
    fmt: SampleFormat,
    ring: Arc<OutRing>,
    resampler: Arc<Mutex<Resampler>>,
    in_rate: u32,
) -> Result<Stream, String> {
    let ch = config.channels.max(1) as usize;
    let err_fn = |e| log::error!("speaker stream error: {e}");

    // Pull mono at `in_rate` → resample to device rate → expand channels.
    let out_rate = config.sample_rate.0;
    let src_per_frame = in_rate as f64 / out_rate as f64;
    let pull = move |out: &mut [f32]| {
        let frames = out.len() / ch;
        // How many 16 kHz source samples this device period consumes.
        let want_src = ((frames as f64 * src_per_frame).ceil() as usize) + 2;
        let mut src = vec![0.0f32; want_src];
        let got = ring.pop_into(&mut src);
        let mut up: Vec<f32> = Vec::with_capacity(want_src * 4 + 8);
        {
            let mut rs = resampler.lock().unwrap();
            rs.push(&src[..got], &mut up);
        }
        for i in 0..frames {
            let s = up.get(i).copied().unwrap_or(0.0);
            for c in 0..ch {
                out[i * ch + c] = s;
            }
        }
    };

    let stream = match fmt {
        SampleFormat::F32 => device.build_output_stream(&config, move |out: &mut [f32], _| pull(out), err_fn, None),
        SampleFormat::I16 => device.build_output_stream(
            &config,
            move |out: &mut [i16], _| {
                let mut f = vec![0.0f32; out.len()];
                pull(&mut f);
                for (o, s) in out.iter_mut().zip(f.iter()) {
                    *o = (s.clamp(-1.0, 1.0) * 32767.0) as i16;
                }
            },
            err_fn,
            None,
        ),
        SampleFormat::U16 => device.build_output_stream(
            &config,
            move |out: &mut [u16], _| {
                let mut f = vec![0.0f32; out.len()];
                pull(&mut f);
                for (o, s) in out.iter_mut().zip(f.iter()) {
                    *o = ((s.clamp(-1.0, 1.0) * 32767.0) as i16 as u16).wrapping_add(32768);
                }
            },
            err_fn,
            None,
        ),
        other => return Err(format!("internal: unsupported output format {other:?}")),
    }
    .map_err(|e| format!("internal: {e}"))?;
    stream.play().map_err(|e| format!("internal: {e}"))?;
    Ok(stream)
}

/// Negotiate with the phone, then start local streams. On local failure the
/// phone is told to stop. Returns handle or `perm:/internal:` error string.
pub async fn start_negotiated(
    state: &SharedState,
    call_id: &str,
    mic: Option<String>,
    speaker: Option<String>,
    uplink: bool,
    downlink: bool,
) -> Result<CallAudioHandle, String> {
    let resp = state
        .request(
            "call.audio_start",
            json!({
                "callId": call_id, "codec": "pcm_s16le", "rate": RATE, "ch": 1, "frameMs": 20,
                "uplink": uplink, "downlink": downlink,
            }),
            std::time::Duration::from_secs(8),
        )
        .await?;
    if resp.ok == Some(false) {
        return Err(resp.e.unwrap_or_else(|| "internal: phone refused audio".into()));
    }
    match start(state.clone(), call_id.to_string(), mic, speaker, uplink, downlink).await {
        Ok(h) => Ok(h),
        Err(e) => {
            state
                .send_envelope(&crate::protocol::Envelope::push(
                    "call.audio_stop",
                    json!({ "callId": call_id }),
                ))
                .await;
            Err(e)
        }
    }
}

/// Stop relaying: deregister collector + tell the phone.
pub async fn stop(state: &SharedState, call_id: &str) {
    if !call_id.is_empty() {
        let tid = tid_from_str(call_id);
        state.inner.lock().await.data_collectors.remove(&tid);
        let _ = state
            .request(
                "call.audio_stop",
                json!({ "callId": call_id }),
                std::time::Duration::from_secs(5),
            )
            .await;
    }
}

// ——— Screen-mirror audio: the phone's playback capture → laptop speakers ———

/// Handle for the mirror audio relay. Dropping it signals the audio thread,
/// which drops the cpal stream there (cpal `Stream` is `!Send`).
pub struct MirrorAudioHandle {
    stop: std::sync::mpsc::Sender<()>,
    /// The phone's capture rate. A repeated "started" push at the same rate
    /// is a no-op instead of a tear-down/rebuild that would glitch playback.
    pub rate: u32,
    /// The ingest channel this relay is fed through, kept so its collector
    /// can be re-seated. Every connection change clears `data_collectors`,
    /// and a relay whose entry went missing keeps reporting "active" while
    /// every frame is dropped on the floor — which is how mirrored sound
    /// went quiet with nothing logged to say so.
    pub tx: tokio::sync::mpsc::Sender<(u32, Vec<u8>)>,
}

impl Drop for MirrorAudioHandle {
    fn drop(&mut self) {
        let _ = self.stop.send(());
    }
}

/// React to a phone-side mirror push: `mirror.audio {ok, rate}` when playback
/// capture starts or stops, and `mirror.state {active:false}` when the whole
/// mirror goes away.
///
/// The phone is the source of truth for *whether* audio is flowing — it owns
/// the projection and the permission — so the laptop only ever follows and
/// never originates a capture. The frames themselves arrive on
/// [crate::crypto::mirror_audio_tid] and are routed by the ordinary
/// data-collector branch in `server.rs` (registered by [start_mirror]).
pub async fn on_mirror_push(state: &SharedState, t: &str, d: &serde_json::Value) {
    let on = d.get("ok").and_then(|v| v.as_bool()) == Some(true);
    if t == "mirror.state" {
        // The mirror itself ended — audio goes with it.
        if d.get("active").and_then(|v| v.as_bool()) == Some(false) {
            stop_mirror(state).await;
        }
        return;
    }
    if on {
        let rate = d.get("rate").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
        if let Err(e) = start_mirror(state, rate).await {
            log::warn!("mirror audio relay not started: {e}");
            // The phone is capturing into a sink that will never exist. Ask
            // it to stop rather than run a capture nobody hears.
            let _ = state
                .request(
                    "mirror.audio",
                    json!({ "on": false }),
                    std::time::Duration::from_secs(5),
                )
                .await;
        }
    } else {
        stop_mirror(state).await;
    }
}

/// Start relaying mirror audio: register the fixed-tid collector, then open
/// the output stream. `rate` is the phone's capture rate — it picks whatever
/// its playback mix accepts (48 kHz, falling back) and reports it.
pub async fn start_mirror(state: &SharedState, rate: u32) -> Result<(), String> {
    let _gate = state.mirror_audio_gate.lock().await;
    start_mirror_locked(state, rate).await
}

/// Stop relaying mirror audio. Local only: by the time this runs the phone
/// has already stopped capturing (or never started).
pub async fn stop_mirror(state: &SharedState) {
    let _gate = state.mirror_audio_gate.lock().await;
    stop_mirror_locked(state).await;
}

async fn start_mirror_locked(state: &SharedState, rate: u32) -> Result<(), String> {
    let rate = if rate == 0 { RATE } else { rate };
    let tid = crate::crypto::mirror_audio_tid();
    {
        let mut inner = state.inner.lock().await;
        // Same stream already relaying — but only if it still has a collector.
        // A reconnect clears the map, and returning "already relaying" over a
        // missing entry means the relay stays up while every frame it is
        // sent is dropped: mirrored picture, mirrored silence. The handle
        // keeps the channel, so re-seat it and keep the stream running.
        let same_stream = inner
            .mirror_audio
            .as_ref()
            .filter(|h| h.rate == rate)
            .map(|h| h.tx.clone());
        if let Some(tx) = same_stream {
            inner.data_collectors.entry(tid).or_insert(tx);
            return Ok(());
        }
    }
    // A relay left over at a different rate would play at the wrong pitch, so
    // replace it wholesale — dropping its collector ends the ingest worker.
    stop_mirror_locked(state).await;

    let ring = Arc::new(OutRing::new());

    // ——— Ingest: kind-0x02 frames → f32 samples → ring ———
    let (chunk_tx, mut chunk_rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
    state
        .inner
        .lock()
        .await
        .data_collectors
        .insert(tid, chunk_tx.clone());
    let ring_in = ring.clone();
    std::thread::Builder::new()
        .name("il-mirror-audio-in".into())
        .spawn(move || {
            // Windowed stats, like the call downlink's: "no sound" has to be
            // tellable apart from "nothing ever arrived", and without these
            // two lines it isn't.
            let mut frames = 0u32;
            let mut loudest = 0i32;
            let mut log_at = std::time::Instant::now();
            while let Some((_seq, chunk)) = chunk_rx.blocking_recv() {
                if chunk.len() < 2 {
                    continue;
                }
                let mut samples = Vec::with_capacity(chunk.len() / 2);
                let mut window_peak = 0i32;
                for b in chunk.chunks_exact(2) {
                    let v = i16::from_le_bytes([b[0], b[1]]);
                    let a = (v as i32).abs();
                    if a > window_peak {
                        window_peak = a;
                    }
                    samples.push(v as f32 / 32768.0);
                }
                frames += 1;
                if window_peak > loudest {
                    loudest = window_peak;
                }
                ring_in.push(&samples);
                if log_at.elapsed() >= std::time::Duration::from_secs(2) {
                    log::info!(
                        "mirror audio from phone: {frames} frames, peak {loudest} in the last window"
                    );
                    frames = 0;
                    loudest = 0;
                    log_at = std::time::Instant::now();
                }
            }
            if frames > 0 {
                log::info!(
                    "mirror audio from phone: collector closed ({frames} frames in the last window)"
                );
            }
        })
        .map_err(|e| format!("internal: {e}"))?;

    // ——— Audio thread: owns the cpal stream (!Send), reports readiness ———
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel::<Result<(), String>>(1);
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
    let ring_out = ring.clone();
    std::thread::Builder::new()
        .name("il-mirror-audio".into())
        .spawn(move || {
            let out_device = match pick_output(None) {
                Ok(d) => d,
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                    return;
                }
            };
            // Which device, exactly — "laptop output" hides the case where
            // something pinned the relay somewhere nobody is listening.
            log::info!(
                "mirror audio playing to {}",
                out_device.name().unwrap_or_else(|_| "unknown".into())
            );
            let cfg = match out_device.default_output_config() {
                Ok(c) => c,
                Err(e) => {
                    let _ = ready_tx.send(Err(format!("internal: {e}")));
                    return;
                }
            };
            let fmt = cfg.sample_format();
            let out_resampler = Arc::new(Mutex::new(Resampler::new(rate, cfg.sample_rate().0)));
            let stream = match build_output_stream(
                &out_device,
                StreamConfig::from(cfg),
                fmt,
                ring_out,
                out_resampler,
                rate,
            ) {
                Ok(s) => s,
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                    return;
                }
            };
            if ready_tx.send(Ok(())).is_err() {
                return;
            }
            loop {
                match stop_rx.recv_timeout(std::time::Duration::from_millis(500)) {
                    Ok(()) | Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                    Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                }
            }
            drop(stream);
        })
        .map_err(|e| format!("internal: {e}"))?;

    // Wait for the audio thread without blocking the async runtime.
    let started: Result<Result<(), String>, String> =
        tokio::task::spawn_blocking(move || {
            match ready_rx.recv_timeout(std::time::Duration::from_secs(10)) {
                Ok(r) => r,
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    Err("internal: audio thread failed to start".into())
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                    Err("internal: audio thread start timeout".into())
                }
            }
        })
        .await
        .map_err(|e| format!("internal: {e}"));

    match started {
        Ok(Ok(())) => {
            let mut inner = state.inner.lock().await;
            // A disconnect that raced the device open already cleared the
            // collector (clear_connection); installing the handle now would
            // strand a stream with nothing feeding it.
            if inner.conn.is_none() {
                inner.data_collectors.remove(&tid);
                return Err("offline: phone is not connected".into());
            }
            inner.mirror_audio = Some(MirrorAudioHandle {
                stop: stop_tx,
                rate,
                tx: chunk_tx,
            });
            log::info!("mirror audio relay active ({rate} Hz → laptop output)");
            Ok(())
        }
        // Failed or never reported in: drop the collector so frames don't
        // pile up behind a relay that never started.
        Ok(Err(e)) | Err(e) => {
            state.inner.lock().await.data_collectors.remove(&tid);
            Err(e)
        }
    }
}

/// Deregister the collector and drop the handle (which ends the audio
/// thread). Idempotent — safe to call for a mirror that never had audio.
async fn stop_mirror_locked(state: &SharedState) {
    let mut inner = state.inner.lock().await;
    inner.data_collectors.remove(&crate::crypto::mirror_audio_tid());
    inner.mirror_audio.take();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn devices_drop_sink_monitors() {
        // A monitor is a speaker heard back, not a microphone: without this
        // filter every output would be offered twice, once as an input.
        let listing = serde_json::json!([
            { "name": "alsa_output.pci-0000_00_1f.3.analog-stereo.monitor",
              "description": "Monitor of Built-in Audio Analog Stereo" },
            { "name": "alsa_input.pci-0000_00_1f.3.analog-stereo",
              "description": "Built-in Audio Analog Stereo" },
            { "name": "bluez_input.44_55_66.77_88_99", "description": "WH-1000XM4" },
            { "name": "nameless" },
        ]);
        let devices = parse_devices(&listing, true, Some("alsa_input.pci-0000_00_1f.3.analog-stereo"));
        let names: Vec<&str> = devices.iter().map(|d| d.name.as_str()).collect();
        assert_eq!(
            names,
            ["alsa_input.pci-0000_00_1f.3.analog-stereo", "bluez_input.44_55_66.77_88_99", "nameless"]
        );
        assert_eq!(devices[0].label, "Built-in Audio Analog Stereo");
        assert!(devices[0].is_default);
        assert!(!devices[1].is_default);
        // No description → the node name is still a usable label.
        assert_eq!(devices[2].label, "nameless");

        // Playback keeps monitors out of the way entirely (they are sinks'
        // own names) but must not drop anything else.
        let outputs = parse_devices(&listing, false, None);
        assert_eq!(outputs.len(), 4);
    }

    #[test]
    fn stream_blocks_find_our_stream() {
        let listing = "\
Sink Input #497\n\
\tDriver: PipeWire\n\
\tProperties:\n\
\t\tapplication.name = \"Spotify\"\n\
\t\tmedia.name = \"AudioStream\"\n\
\n\
Sink Input #498\n\
\tDriver: PipeWire\n\
\tProperties:\n\
\t\tapplication.name = \"PipeWire ALSA [desktop]\"\n\
\t\tmedia.name = \"ALSA Playback\"\n\
";
        let blocks = parse_stream_blocks(listing, "Sink Input #");
        assert_eq!(blocks.len(), 2);
        assert_eq!(blocks[1].0, 498);
        assert_eq!(blocks[1].1.as_deref(), Some("PipeWire ALSA [desktop]"));
        // Our own stream is the highest index: the relay started last.
        let mine = blocks
            .iter()
            .filter(|(_, app)| app.as_deref() == Some("PipeWire ALSA [desktop]"))
            .map(|(i, _)| *i)
            .max();
        assert_eq!(mine, Some(498));
    }

    /// Open a real stream the way the relay does, move it onto a chosen
    /// device, and prove it actually moved.
    ///
    /// Ignored by default: it needs a sound server and an output device, so
    /// it is run by hand (`cargo test -- --ignored`) on a real machine.
    /// A mirror relay outlives a connection change — its picture does, since
    /// the frame sink is rebuilt on demand — so its collector has to outlive
    /// one too. Losing the collector while keeping the relay is the silent
    /// failure that took mirrored sound off the laptop: the handle reports
    /// "active", the phone keeps sending, and every frame lands in a map with
    /// no entry in it, with nothing logged either way.
    #[tokio::test]
    async fn mirror_collector_survives_a_connection_reset() {
        let state =
            crate::state::AppState::new(std::env::temp_dir().join("il-test-mirror-collector.json"));
        let tid = crate::crypto::mirror_audio_tid();
        let (stop_tx, _stop) = std::sync::mpsc::channel::<()>();
        let (tx, _rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(8);
        {
            let mut inner = state.inner.lock().await;
            inner.mirror_audio = Some(MirrorAudioHandle {
                stop: stop_tx,
                rate: 48_000,
                tx: tx.clone(),
            });
            inner.data_collectors.insert(tid, tx);
        }

        // Both ways of losing the link wipe the collector map.
        state.clear_connection().await;
        assert!(
            state.inner.lock().await.data_collectors.contains_key(&tid),
            "a relay still running must stay fed across a dropped link"
        );

        // And if something did clear it, the phone's next "started" push at
        // the same rate has to heal the gap rather than answer over it.
        state.inner.lock().await.data_collectors.clear();
        start_mirror(&state, 48_000)
            .await
            .expect("a repeat announce must re-seat the collector");
        assert!(
            state.inner.lock().await.data_collectors.contains_key(&tid),
            "'already relaying' must come with a live collector"
        );
    }

    #[test]
    #[ignore = "needs a sound server and a device; run with `cargo test -- --ignored`"]
    fn picked_device_receives_our_stream() {
        // Stand-in for a Bluetooth speaker: a null sink, which is a node
        // exactly like a real one as far as routing is concerned.
        let loaded = std::process::Command::new("pactl")
            .args(["load-module", "module-null-sink", "sink_name=iltest_route"])
            .output()
            .expect("pactl not installed");
        assert!(loaded.status.success(), "could not create the stand-in sink");
        let module = String::from_utf8_lossy(&loaded.stdout).trim().to_string();

        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let host = cpal::default_host();
            let dev = host.default_output_device().expect("no output device");
            let cfg = dev.default_output_config().expect("no output config");
            let stream = dev
                .build_output_stream(
                    &cfg.config(),
                    |d: &mut [f32], _| {
                        for v in d {
                            *v = 0.02;
                        }
                    },
                    |_| {},
                    None,
                )
                .expect("could not open the stream");
            stream.play().expect("stream did not start");

            // The stream only registers with PulseAudio once it is producing.
            std::thread::sleep(std::time::Duration::from_millis(700));
            route_stream(Track::Playback, "iltest_route").expect("route_stream failed");

            // Where does OUR stream point now?
            let full = pactl_text(&["list", "sink-inputs"]).expect("pactl list sink-inputs");
            let marker = format!("[{}]", exe_name());
            let exe = exe_name();
            let blocks = parse_stream_blocks(&full, "Sink Input #");
            let ours = blocks
                .iter()
                .filter(|(_, app)| match app.as_deref() {
                    Some(a) => a.contains(&marker) || a == exe,
                    None => false,
                })
                .map(|(i, _)| *i)
                .max()
                .unwrap_or_else(|| {
                    panic!(
                        "our stream was not identified; PulseAudio saw {:?}",
                        blocks.iter().map(|(i, a)| (i, a.clone())).collect::<Vec<_>>()
                    )
                });
            let short = pactl_text(&["list", "short", "sink-inputs"]).expect("pactl short");
            let row = short
                .lines()
                .find(|l| l.split('\t').next() == Some(&ours.to_string()))
                .unwrap_or_else(|| panic!("stream {ours} vanished from the listing:\n{short}"));
            let sink_index = row.split('\t').nth(1).unwrap_or("");
            let sinks = pactl_text(&["list", "short", "sinks"]).expect("pactl short sinks");
            let target = sinks
                .lines()
                .find(|l| l.split('\t').nth(1) == Some("iltest_route"))
                .and_then(|l| l.split('\t').next())
                .expect("stand-in sink not listed");
            assert_eq!(sink_index, target, "stream is on the wrong sink: {row}");
        }));

        let _ = std::process::Command::new("pactl")
            .args(["unload-module", &module])
            .output();
        if let Err(e) = result {
            std::panic::resume_unwind(e);
        }
    }
}

/// Uplink pipeline: runs inside the cpal input callback (non-async).
/// Normalizes device format → f32 mono → 16 kHz → 20 ms frames → try_send.
struct MicPipeline {
    tx: tokio::sync::mpsc::Sender<(u32, Vec<u8>)>,
    resampler: Mutex<Resampler>,
    pending: Mutex<Vec<f32>>,
    seq: Mutex<u32>,
    channels: std::sync::atomic::AtomicU32,
    muted: Arc<AtomicBool>,
}

impl MicPipeline {
    fn feed_f32(&self, interleaved: &[f32]) {
        let ch = self.channels.load(Ordering::Relaxed).max(1) as usize;
        if ch > 1 {
            let mono: Vec<f32> = interleaved
                .chunks_exact(ch)
                .map(|f| f.iter().sum::<f32>() / ch as f32)
                .collect();
            self.feed_mono(&mono);
        } else {
            self.feed_mono(interleaved);
        }
    }

    fn feed_mono(&self, mono: &[f32]) {
        let muted = self.muted.load(Ordering::Relaxed);
        let mut out16 = Vec::with_capacity(mono.len() + 8);
        {
            let mut rs = self.resampler.lock().unwrap();
            rs.push(mono, &mut out16);
        }
        if muted {
            out16.fill(0.0);
        }
        let mut pending = self.pending.lock().unwrap();
        pending.extend_from_slice(&out16);
        while pending.len() >= FRAME_SAMPLES {
            let frame: Vec<f32> = pending.drain(..FRAME_SAMPLES).collect();
            let mut pcm = Vec::with_capacity(FRAME_SAMPLES * 2);
            for &s in &frame {
                let v = (s.clamp(-1.0, 1.0) * 32767.0) as i16;
                pcm.extend_from_slice(&v.to_le_bytes());
            }
            let mut seq = self.seq.lock().unwrap();
            let s = *seq;
            *seq += 1;
            drop(seq);
            let _ = self.tx.try_send((s, pcm)); // bounded; drops under overload
        }
    }
}
