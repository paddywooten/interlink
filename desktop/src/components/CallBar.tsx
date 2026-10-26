import { useEffect, useState } from "react";
import * as api from "../api";
import { setState, useStore } from "../store";

function mmss(sec: number) {
  return `${Math.floor(sec / 60)}:${(sec % 60).toString().padStart(2, "0")}`;
}

/**
 * The call that follows you around the app.
 *
 * The Calls page owns the full card — device pickers, mute, history — but it
 * is only on screen while you are *on* that page, and a phone that is ringing
 * does not wait for you to navigate there. This bar keeps the controls that
 * matter everywhere else: answer or decline while it rings, end while it runs.
 *
 * It stands down on the Calls page, where the card already says all this.
 */
export default function CallBar() {
  const call = useStore((s) => s.call);
  const page = useStore((s) => s.page);
  const [elapsed, setElapsed] = useState(0);

  // Seconds since the call went active — the same rule the Calls card uses.
  useEffect(() => {
    if (call?.state !== "active") {
      setElapsed(0);
      return;
    }
    const start = Date.now();
    const iv = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => clearInterval(iv);
  }, [call?.callId, call?.state]);

  if (!call || page === "calls") return null;

  const who = call.name || call.number || "Unknown";
  const ring = call.state === "incoming";

  const hangup = async () => {
    await api.callEnd(call.callId);
    api.callAudioStop(call.callId).catch(() => undefined);
    setState({ call: null, audioBroken: null });
  };

  return (
    <div className="call-bar">
      <span className={`cb-dot ${call.state}`} aria-hidden />
      <div className="cb-meta">
        <div className="cb-who">{who}</div>
        <div className="cb-sub">
          {ring
            ? `Incoming call${call.number ? ` · ${call.number}` : ""}`
            : call.state === "active"
              ? `In call · ${mmss(elapsed)}`
              : `Calling${call.number ? ` ${call.number}` : ""}…`}
        </div>
      </div>
      <div className="cb-actions">
        {ring ? (
          <>
            <button
              className="btn primary"
              onClick={async () => {
                await api.callAnswer(call.callId);
                setState({ call: { ...call, state: "active" } });
              }}
            >
              ✓ Answer
            </button>
            <button
              className="btn ghost"
              onClick={async () => {
                await api.callDecline(call.callId);
                setState({ call: null, audioBroken: null });
              }}
            >
              ✕ Decline
            </button>
          </>
        ) : (
          <button className="btn danger" onClick={hangup} title="End call">
            ◼ End call
          </button>
        )}
      </div>
    </div>
  );
}
