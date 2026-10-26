import { useEffect, useRef, useState } from "react";
import * as api from "../api";
import { toast, useStore } from "../store";

type Phase = "idle" | "consent" | "live" | "stopping";

/** Where the phone's own sound is: not requested, requested but not yet
 * confirmed, flowing into the laptop, or refused (with a reason). */
type AudioPhase = "off" | "starting" | "on" | "blocked";

/**
 * Live screen mirror. Frames arrive as `mirror.frame` pushes (one JPEG per
 * push, STANDARD base64) — painted straight into an <img> via a ref so the
 * 8fps stream never re-renders React. Lifecycle comes from `mirror.state`
 * pushes ({active, pending?} on start, {active:false, reason} on stop/deny).
 *
 * Audio is a second, independent stream: the phone captures what it is
 * *playing* (not its mic) and the server relays it to the laptop speakers.
 * The state of that arrives as `mirror.audio` pushes — {ok, rate} once
 * capture is really up, {ok:false, reason} when it isn't — and the server
 * starts/stops its own playout from the same pushes, so this page only
 * reports the outcome; it never has to be mounted for sound to work.
 */
export default function Mirror() {
  const connected = useStore((s) => s.connected);
  const [phase, setPhase] = useState<Phase>("idle");
  const [fps, setFps] = useState(8);
  const [size, setSize] = useState(720);
  const [lastSeq, setLastSeq] = useState(0);
  const [kbPerSec, setKbPerSec] = useState(0);
  const [audioWanted, setAudioWanted] = useState(true);
  const [audioPhase, setAudioPhase] = useState<AudioPhase>("off");
  const [audioRate, setAudioRate] = useState(0);
  const [audioReason, setAudioReason] = useState("");
  const imgRef = useRef<HTMLImageElement | null>(null);
  const bytesWin = useRef<{ t: number; n: number }[]>([]);

  // Frames → <img>; rate + last-seq surfaced via light state (throttled).
  useEffect(() => {
    let lastPaint = 0;
    const onFrame = (e: Event) => {
      const d = (e as CustomEvent).detail as { seq: number; mime: string; data: string };
      if (imgRef.current) {
        imgRef.current.src = `data:${d.mime};base64,${d.data}`;
      }
      const now = performance.now();
      bytesWin.current.push({ t: now, n: d.data.length });
      // Keep a 2s sliding window for the throughput readout.
      bytesWin.current = bytesWin.current.filter((w) => now - w.t < 2000);
      if (now - lastPaint > 500) {
        lastPaint = now;
        const total = bytesWin.current.reduce((a, w) => a + w.n, 0);
        const span = Math.max(1, (now - (bytesWin.current[0]?.t ?? now)) / 1000);
        setKbPerSec(Math.round(total / span / 10.24) / 100);
        setLastSeq(d.seq);
        setPhase("live");
      }
    };
    const onState = (e: Event) => {
      const d = (e as CustomEvent).detail as { active?: boolean; pending?: boolean; reason?: string };
      if (d.active && d.pending) setPhase("consent");
      else if (d.active) setPhase("live");
      else {
        setPhase("idle");
        // The mirror taking its audio with it is the server's job too, but
        // the page shouldn't claim sound is still flowing if it isn't.
        setAudioPhase("off");
        setAudioRate(0);
        if (d.reason === "denied") toast("Screen capture denied on the phone", "error");
        else if (d.reason) toast(`Mirror stopped: ${d.reason}`, "info");
      }
    };
    const onAudio = (e: Event) => {
      const d = (e as CustomEvent).detail as { ok?: boolean; rate?: number; reason?: string };
      if (d.ok) {
        setAudioPhase("on");
        setAudioRate(d.rate ?? 0);
        setAudioReason("");
      } else {
        // A refusal (no permission, pre-Android 10) must not leave a checkbox
        // claiming otherwise.
        setAudioPhase("blocked");
        setAudioReason(d.reason ?? "");
        setAudioWanted(false);
        if (d.reason) toast(`Mirror audio: ${d.reason}`, "error");
      }
    };
    window.addEventListener("il:mirror-frame", onFrame);
    window.addEventListener("il:mirror-state", onState);
    window.addEventListener("il:mirror-audio", onAudio);
    return () => {
      window.removeEventListener("il:mirror-frame", onFrame);
      window.removeEventListener("il:mirror-state", onState);
      window.removeEventListener("il:mirror-audio", onAudio);
    };
  }, []);

  const start = async () => {
    setPhase("consent");
    setAudioPhase(audioWanted ? "starting" : "off");
    try {
      await api.mirrorStart(size, fps, audioWanted);
      // active/pending state arrives via mirror.state; fall back to "live" on
      // the first frame if the push raced the response.
    } catch (e) {
      setPhase("idle");
      toast(String(e), "error");
    }
  };

  /** Flip "phone audio → laptop" live. The phone's own answer only confirms
   * the request was understood; the real outcome comes back as a
   * `mirror.audio` push. While idle the flag only rides along with the next
   * `mirror.start`. */
  const toggleAudio = async (next: boolean) => {
    setAudioWanted(next);
    const mirroring = phase === "live" || phase === "consent";
    if (!mirroring) return;
    setAudioPhase(next ? "starting" : "off");
    if (!next) setAudioRate(0);
    try {
      await api.mirrorAudio(next);
    } catch (e) {
      setAudioWanted(!next);
      setAudioPhase(!next ? "on" : "off");
      toast(String(e), "error");
    }
  };

  const stop = async () => {
    setPhase("stopping");
    try {
      await api.mirrorStop();
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setPhase("idle");
    }
  };

  const running = phase === "live" || phase === "consent" || phase === "stopping";

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>Screen Mirror</h1>
          <p className="muted">
            {phase === "live" && (
              <>
                Live — {kbPerSec} KB/s · frame #{lastSeq}
              </>
            )}
            {phase === "consent" && "Approve the capture prompt on the phone…"}
            {phase === "stopping" && "Stopping…"}
            {phase === "idle" && "Mirroring is off."}
          </p>
        </div>
        <div className="row gap">
          {phase === "idle" ? (
            <button className="btn primary lg" disabled={!connected} onClick={start}>
              Start mirroring
            </button>
          ) : (
            <button className="btn danger lg" onClick={stop} disabled={phase === "stopping"}>
              Stop
            </button>
          )}
        </div>
      </div>

      <div className="row gap" style={{ marginBottom: 12, alignItems: "center" }}>
        <label className="muted">Width</label>
        <select value={size} onChange={(e) => setSize(Number(e.target.value))} disabled={running}>
          <option value={480}>480 px</option>
          <option value={720}>720 px</option>
          <option value={1080}>1080 px</option>
        </select>
        <label className="muted">FPS</label>
        <select value={fps} onChange={(e) => setFps(Number(e.target.value))} disabled={running}>
          <option value={4}>4</option>
          <option value={8}>8</option>
          <option value={12}>12</option>
        </select>
        <label className="row gap" style={{ alignItems: "center", gap: 6 }}>
          <input
            type="checkbox"
            checked={audioWanted}
            disabled={phase === "stopping" || audioPhase === "starting"}
            onChange={(e) => toggleAudio(e.target.checked)}
          />
          <span className="muted">Phone audio → laptop</span>
        </label>
        {phase !== "idle" && (
          <span className="muted">
            {audioPhase === "on" &&
              (audioRate > 0 ? `Sound on the laptop · ${(audioRate / 1000).toFixed(1)} kHz` : "Sound on the laptop")}
            {audioPhase === "starting" && "Waiting for phone audio…"}
            {audioPhase === "blocked" && (audioReason || "Phone audio unavailable")}
            {audioPhase === "off" && "Sound stays on the phone"}
          </span>
        )}
      </div>

      <div className="mirror-stage">
        {/* Single persistent <img> — first frame paints without a remount. */}
        <img ref={imgRef} className={`mirror-frame ${phase === "live" ? "" : "hidden"}`} alt="phone screen" />
        {phase !== "live" && (
          <div className="mirror-placeholder muted">
            {phase === "consent"
              ? "Waiting for the phone…"
              : connected
                ? "Press “Start mirroring”, then approve the prompt on the phone."
                : "Phone not connected."}
          </div>
        )}
      </div>
    </div>
  );
}
