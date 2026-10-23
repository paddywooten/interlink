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

/// Enumerate microphones: [{name, default}]
pub fn list_inputs() -> Vec<serde_json::Value> {
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

/// Start relaying call audio. `call_id` ties frames to the active call.
pub async fn start(
    state: SharedState,
    call_id: String,
    mic: Option<String>,
) -> Result<CallAudioHandle, String> {
    let tid = tid_from_str(&call_id);
    let muted = Arc::new(AtomicBool::new(false));

    // ——— Downlink registration: route call.audio data frames to our collector ———
    let (chunk_tx, mut chunk_rx) = tokio::sync::mpsc::channel::<(u32, Vec<u8>)>(512);
    {
        let mut inner = state.inner.lock().await;
        inner.data_collectors.remove(&tid);
        inner.data_collectors.insert(tid, chunk_tx);
    }

    // Downlink worker thread: chunks → decode → resample → device-rate ring.
    let ring = Arc::new(OutRing::new());
    {
        let ring = ring.clone();
        std::thread::Builder::new()
            .name("il-downlink".into())
            .spawn(move || {
                // out_rate learned from the first chunk? We fix rate = RATE here and
                // resample to the device rate once known via channel metadata — but the
                // audio thread owns that. Instead: downlink thread converts 16k PCM →
                // f32 ONLY; resampling to device rate happens with a shared Resampler
                // installed by the audio thread (fallback: identity 16k pass-through
                // when device runs at 16k — rare). Simpler: ring stores 16k samples and
                // the OUTPUT CALLBACK resamples. See OutRing note below.
                while let Some((_seq, chunk)) = chunk_rx.blocking_recv() {
                    if chunk.len() < 2 {
                        continue;
                    }
                    let samples: Vec<f32> = chunk
                        .chunks_exact(2)
                        .map(|b| i16::from_le_bytes([b[0], b[1]]) as f32 / 32768.0)
                        .collect();
                    ring.push(&samples);
                }
            })
            .map_err(|e| format!("internal: {e}"))?;
    }

    // ——— Uplink pump: frames → seal with session key → send ———
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

    let pipeline = Arc::new(MicPipeline {
        tx: frame_tx,
        // Placeholder until the audio thread learns the device rate (it
        // rewrites this before any callback can run).
        resampler: Mutex::new(Resampler::new(RATE, RATE)),
        pending: Mutex::new(Vec::with_capacity(FRAME_SAMPLES * 4)),
        seq: Mutex::new(0),
        channels: std::sync::atomic::AtomicU32::new(1),
        muted: muted.clone(),
    });

    // ——— Audio thread: owns both cpal streams (they are !Send) ———
    // Reports the negotiated mic rate once both streams are running.
    let (ready_tx, ready_rx) = std::sync::mpsc::sync_channel::<Result<u32, String>>(1);
    let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();

    let pipeline_thread = pipeline.clone();
    let ring_thread = ring.clone();
    std::thread::Builder::new()
        .name("il-call-audio".into())
        .spawn(move || {
            let in_device = match pick_input(mic.as_deref()) {
                Ok(d) => d,
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                    return;
                }
            };
            let out_device = match cpal::default_host().default_output_device() {
                Some(d) => d,
                None => {
                    let _ = ready_tx.send(Err("internal: no output device".into()));
                    return;
                }
            };
            let (in_cfg, out_cfg, out_format) = match (
                in_device.default_input_config(),
                out_device.default_output_config(),
            ) {
                (Ok(ic), Ok(oc)) => {
                    let fmt = oc.sample_format();
                    (StreamConfig::from(ic), StreamConfig::from(oc), fmt)
                }
                _ => {
                    let _ = ready_tx.send(Err("internal: cannot query audio configs".into()));
                    return;
                }
            };
            let in_rate = in_cfg.sample_rate.0;
            // Reconfigure the pipeline with the true mic rate.
            {
                let mut rs = pipeline_thread.resampler.lock().unwrap();
                *rs = Resampler::new(in_rate, RATE);
            }
            pipeline_thread
                .channels
                .store(in_cfg.channels as u32, Ordering::Relaxed);

            // The ring receives 16 kHz samples; the output callback resamples on pull.
            let out_resampler = Arc::new(Mutex::new(Resampler::new(RATE, out_cfg.sample_rate.0)));

            let in_stream = match build_input_stream(&in_device, in_cfg, pipeline_thread.clone()) {
                Ok(s) => s,
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                    return;
                }
            };
            let out_stream = match build_output_stream(
                &out_device,
                out_cfg,
                out_format,
                ring_thread,
                out_resampler,
            ) {
                Ok(s) => s,
                Err(e) => {
                    let _ = ready_tx.send(Err(e));
                    return;
                }
            };

            if ready_tx.send(Ok(in_rate)).is_err() {
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
    let started: Result<Result<u32, String>, String> = tokio::task::spawn_blocking(move || {
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
        Ok(Ok(mic_rate)) => {
            log::info!("call audio relay active for call {call_id} (mic {mic_rate} Hz)");
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
    sink: Arc<MicPipeline>,
) -> Result<Stream, String> {
    let fmt = device
        .default_input_config()
        .map_err(|e| format!("internal: {e}"))?
        .sample_format();
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
) -> Result<Stream, String> {
    let ch = config.channels.max(1) as usize;
    let err_fn = |e| log::error!("speaker stream error: {e}");

    // Pull 16 kHz mono → resample to device rate → expand channels.
    let out_rate = config.sample_rate.0;
    let src_per_frame = RATE as f64 / out_rate as f64;
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
) -> Result<CallAudioHandle, String> {
    let resp = state
        .request(
            "call.audio_start",
            json!({ "callId": call_id, "codec": "pcm_s16le", "rate": RATE, "ch": 1, "frameMs": 20 }),
            std::time::Duration::from_secs(8),
        )
        .await?;
    if resp.ok == Some(false) {
        return Err(resp.e.unwrap_or_else(|| "internal: phone refused audio".into()));
    }
    match start(state.clone(), call_id.to_string(), mic).await {
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
