import { useEffect, useState } from "react";
import * as api from "../api";
import type { Contact } from "../types";
import { setState, toast, useStore } from "../store";

function fmtDur(sec: number) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

export default function Calls() {
  const connected = useStore((s) => s.connected);
  const call = useStore((s) => s.call);
  const muted = useStore((s) => s.callMuted);
  const audioBroken = useStore((s) => s.audioBroken);
  const [number, setNumber] = useState("");
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [q, setQ] = useState("");
  const [dialing, setDialing] = useState(false);
  const [audioOn, setAudioOn] = useState(false);
  const [mics, setMics] = useState<{ name: string; default: boolean }[]>([]);
  const [mic, setMic] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    api.listAudioInputs().then((r) => setMics(r.devices)).catch(() => undefined);
  }, []);

  // Call duration ticker
  useEffect(() => {
    if (!call || call.state !== "active") {
      setElapsed(0);
      return;
    }
    const start = Date.now();
    const iv = setInterval(() => setElapsed(Math.floor((Date.now() - start) / 1000)), 1000);
    return () => clearInterval(iv);
  }, [call?.callId, call?.state]);

  // When a call goes active (outgoing or answered), auto-start audio relay.
  useEffect(() => {
    let cancelled = false;
    const maybeStart = async () => {
      if (!call || call.state !== "active" || audioOn || !connected) return;
      try {
        await api.callAudioStart(call.callId, mic ?? undefined);
        if (!cancelled) {
          setAudioOn(true);
          setState({ audioBroken: null });
        }
      } catch (e) {
        if (!cancelled) {
          setState({ audioBroken: String(e) });
          toast(`Audio relay failed: ${e}`, "error");
        }
      }
    };
    maybeStart();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [call?.callId, call?.state, connected]);

  useEffect(() => {
    if (call?.state === "ended" && audioOn) {
      setAudioOn(false);
      api.callAudioStop(call.callId).catch(() => undefined);
    }
  }, [call?.state, call?.callId, audioOn]);

  const search = async (v: string) => {
    setQ(v);
    if (v.length < 1) return setContacts([]);
    try {
      setContacts(await api.contactsQuery(v, 8));
    } catch {
      setContacts([]);
    }
  };

  const dial = async (target?: string) => {
    const num = (target ?? number).trim();
    if (!num || !connected || dialing) return;
    setDialing(true);
    try {
      await api.callDial(num);
      toast(`Calling ${num}…`, "info");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setDialing(false);
    }
  };

  const hangup = async () => {
    if (!call) return;
    await api.callEnd(call.callId);
    setAudioOn(false);
    api.callAudioStop(call.callId).catch(() => undefined);
    setState({ call: null, audioBroken: null });
  };

  const toggleMute = async () => {
    const next = !muted;
    try {
      await api.callMute(next);
      setState({ callMuted: next });
    } catch (e) {
      toast(String(e), "error");
    }
  };

  const changeMic = async (name: string) => {
    setMic(name);
    await api.setSettings({ mic_device: name });
    if (audioOn && call) {
      // Restart relay with the new device
      setAudioOn(false);
      await api.callAudioStop(call.callId).catch(() => undefined);
      try {
        await api.callAudioStart(call.callId, name);
        setAudioOn(true);
        toast("Microphone switched", "success");
      } catch (e) {
        setState({ audioBroken: String(e) });
        toast(String(e), "error");
      }
    }
  };

  const keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#"];

  return (
    <div className="page calls-page">
      <header className="page-head">
        <h1>Calls</h1>
        {call && (
          <span className={`pill ${call.state === "active" ? "on" : "warn"}`}>
            <span className="dot" />
            {call.state === "incoming" ? "Incoming" : call.state === "active" ? "Active" : "Dialing"}
          </span>
        )}
      </header>

      {call && (
        <div className={`call-card ${call.state}`}>
          <div className="call-who">
            <div className="call-avatar">{(call.name || call.number || "?").slice(0, 1)}</div>
            <div>
              <div className="call-name">{call.name || call.number}</div>
              <div className="muted mono">{call.number}</div>
              <div className="call-timer">
                {call.state === "active" ? fmtDur(elapsed) : call.dir === "in" ? "Ringing…" : "Calling…"}
              </div>
            </div>
          </div>

          <div className="call-controls">
            {call.state === "incoming" ? (
              <>
                <button
                  className="btn round green"
                  onClick={async () => {
                    await api.callAnswer(call.callId);
                    setState({ call: { ...call, state: "active" } });
                  }}
                >
                  ✓
                </button>
                <button className="btn round red" onClick={async () => { await api.callDecline(call.callId); setState({ call: null }); }}>
                  ✕
                </button>
              </>
            ) : (
              <>
                <button className={`btn round ${muted ? "yellow" : ""}`} onClick={toggleMute} title="Mute">
                  {muted ? "🔇" : "🎙"}
                </button>
                <button className="btn round red" onClick={hangup} title="Hang up">
                  ☎
                </button>
              </>
            )}
          </div>

          {call.state === "active" && (
            <div className="call-audio">
              <div className="row gap">
                <label className="muted">Mic:</label>
                <select className="select" value={mic ?? ""} onChange={(e) => changeMic(e.target.value)}>
                  <option value="">System default</option>
                  {mics.map((m) => (
                    <option key={m.name} value={m.name}>
                      {m.name}
                      {m.default ? " (default)" : ""}
                    </option>
                  ))}
                </select>
              </div>
              <div className={`audio-status ${audioOn && !audioBroken ? "ok" : "warn"}`}>
                {audioOn && !audioBroken
                  ? "🔊 Relaying audio — talk through your laptop"
                  : `🔈 ${audioBroken ? "Relay unavailable (phone restricted audio) — call uses phone speaker" : "Connecting audio…"}`}
              </div>
            </div>
          )}
        </div>
      )}

      <div className="dial-grid">
        <div className="dialpad">
          <input
            className="input dial-input"
            placeholder="Enter number"
            value={number}
            onChange={(e) => setNumber(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && dial()}
            disabled={!connected}
          />
          <div className="keys">
            {keys.map((k) => (
              <button key={k} className="key" onClick={() => setNumber((n) => n + k)}>
                {k}
              </button>
            ))}
          </div>
          <div className="row gap center">
            <button className="btn primary lg" disabled={!connected || !number.trim() || dialing} onClick={() => dial()}>
              ☎ Call
            </button>
            {number && (
              <button className="btn ghost" onClick={() => setNumber((n) => n.slice(0, -1))}>
                ⌫
              </button>
            )}
          </div>
          {!connected && <div className="muted center" style={{ marginTop: 8 }}>Phone offline</div>}
        </div>

        <div className="contact-search">
          <input
            className="input"
            placeholder="Search contacts to call…"
            value={q}
            onChange={(e) => search(e.target.value)}
          />
          <div className="contact-list">
            {contacts.map((c) =>
              c.phones.map((p) => (
                <button key={c.id + p} className="contact-row" onClick={() => dial(p)}>
                  <div>
                    <b>{c.name}</b>
                    <div className="muted mono">{p}</div>
                  </div>
                  <span className="call-ico">☎</span>
                </button>
              )),
            )}
          </div>
          <div className="muted" style={{ marginTop: 12 }}>
            Calls are placed on your phone; audio streams to your laptop microphone &
            speakers. If your phone restricts call audio capture, the call still works on
            the phone speaker.
          </div>
        </div>
      </div>
    </div>
  );
}
