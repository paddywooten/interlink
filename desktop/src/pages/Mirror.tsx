import { useEffect, useRef, useState } from "react";
import * as api from "../api";
import { toast, useStore } from "../store";

type Phase = "idle" | "consent" | "live" | "stopping";

/**
 * Live screen mirror. Frames arrive as `mirror.frame` pushes (one JPEG per
 * push, STANDARD base64) — painted straight into an <img> via a ref so the
 * 8fps stream never re-renders React. Lifecycle comes from `mirror.state`
 * pushes ({active, pending?} on start, {active:false, reason} on stop/deny).
 */
export default function Mirror() {
  const connected = useStore((s) => s.connected);
  const [phase, setPhase] = useState<Phase>("idle");
  const [fps, setFps] = useState(8);
  const [size, setSize] = useState(720);
  const [lastSeq, setLastSeq] = useState(0);
  const [kbPerSec, setKbPerSec] = useState(0);
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
        if (d.reason === "denied") toast("Screen capture denied on the phone", "error");
        else if (d.reason) toast(`Mirror stopped: ${d.reason}`, "info");
      }
    };
    window.addEventListener("il:mirror-frame", onFrame);
    window.addEventListener("il:mirror-state", onState);
    return () => {
      window.removeEventListener("il:mirror-frame", onFrame);
      window.removeEventListener("il:mirror-state", onState);
    };
  }, []);

  const start = async () => {
    setPhase("consent");
    try {
      await api.mirrorStart(size, fps);
      // active/pending state arrives via mirror.state; fall back to "live" on
      // the first frame if the push raced the response.
    } catch (e) {
      setPhase("idle");
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
