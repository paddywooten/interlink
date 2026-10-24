import { useEffect, useState } from "react";
import * as api from "../api";
import type { Contact } from "../types";
import { setState, toast, useStore } from "../store";

/** Select value meaning "leave this direction on the phone". */
const PHONE = "__phone__";

function fmtDur(sec: number) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

export default function Calls() {
  const connected = useStore((s) => s.connected);
  const status = useStore((s) => s.status);
  const call = useStore((s) => s.call);
  const muted = useStore((s) => s.callMuted);
  const audioBroken = useStore((s) => s.audioBroken);
  const [number, setNumber] = useState("");
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [q, setQ] = useState("");
  const [dialing, setDialing] = useState(false);
  const [audioOn, setAudioOn] = useState(false);
  const [mics, setMics] = useState<{ name: string; default: boolean }[]>([]);
  const [spks, setSpks] = useState<{ name: string; default: boolean }[]>([]);
  const [micSel, setMicSel] = useState<string>(PHONE);
  const [spkSel, setSpkSel] = useState<string>(PHONE);
  const [elapsed, setElapsed] = useState(0);

  // The two directions are independent: `uplink` = speak through the laptop,
  // `downlink` = hear through the laptop. Both false means the call stays
  // entirely on the phone and we don't start a relay at all.
  const uplink = micSel !== PHONE;
  const downlink = spkSel !== PHONE;

  useEffect(() => {
    api.listAudioInputs().then((r) => setMics(r.devices)).catch(() => undefined);
    api.listAudioOutputs().then((r) => setSpks(r.devices)).catch(() => undefined);
  }, []);

  // Seed both pickers from the saved config so a call starts the way the
  // user left it (null = "this phone").
  useEffect(() => {
    if (!status) return;
    setMicSel(status.micDevice ?? PHONE);
    setSpkSel(status.speakerDevice ?? PHONE);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status?.micDevice, status?.speakerDevice]);

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

  // Keep the relay in step with the call and the two direction flags. This
  // effect owns the whole lifecycle, so changing a select just re-runs it —
  // no separate "restart" path to keep correct.
  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      if (!call || call.state !== "active" || !connected) return;
      if (!uplink && !downlink) {
        // Phone handles both directions itself; make sure nothing is relaying.
        setAudioOn(false);
        api.callAudioStop(call.callId).catch(() => undefined);
        return;
      }
      try {
        await api.callAudioStart(call.callId, {
          mic: uplink && micSel !== "" ? micSel : undefined,
          speaker: downlink && spkSel !== "" ? spkSel : undefined,
          uplink,
          downlink,
        });
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
    run();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [call?.callId, call?.state, connected, uplink, downlink, micSel, spkSel]);

  useEffect(() => {
    if (call?.state === "ended") {
      setAudioOn(false);
      api.callAudioStop(call.callId).catch(() => undefined);
    }
  }, [call?.state, call?.callId]);

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

  /** Save both directions and re-key the relay (the effect above restarts it). */
  const applyAudio = async (micNext: string, spkNext: string) => {
    setMicSel(micNext);
    setSpkSel(spkNext);
    const mic = micNext === PHONE || micNext === "" ? null : micNext;
    const speaker = spkNext === PHONE || spkNext === "" ? null : spkNext;
    try {
      await api.setSettings({ mic_device: mic, speaker_device: speaker });
    } catch (e) {
      toast(String(e), "error");
    }
    if (call?.state === "active") setAudioOn(false);
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
                <label className="muted">Speak through:</label>
                <select
                  className="select"
                  value={micSel}
                  onChange={(e) => applyAudio(e.target.value, spkSel)}
                >
                  <option value={PHONE}>📱 This phone</option>
                  <option value="">🎙 Laptop default mic</option>
                  {mics.map((m) => (
                    <option key={m.name} value={m.name}>
                      {m.name}
                      {m.default ? " (default)" : ""}
                    </option>
                  ))}
                </select>
              </div>
              <div className="row gap">
                <label className="muted">Hear through:</label>
                <select
                  className="select"
                  value={spkSel}
                  onChange={(e) => applyAudio(micSel, e.target.value)}
                >
                  <option value={PHONE}>📱 This phone</option>
                  <option value="">🔊 Laptop default output</option>
                  {spks.map((s) => (
                    <option key={s.name} value={s.name}>
                      {s.name}
                      {s.default ? " (default)" : ""}
                    </option>
                  ))}
                </select>
              </div>
              <div
                className={`audio-status ${
                  (audioOn && !audioBroken) || (!uplink && !downlink) ? "ok" : "warn"
                }`}
              >
                {!uplink && !downlink
                  ? "🔈 Call audio stays on the phone"
                  : audioOn && !audioBroken
                    ? uplink && downlink
                      ? "🔊 Speaking and hearing through your laptop"
                      : downlink
                        ? "🔊 Hearing through your laptop — speaking into the phone"
                        : "🔊 Speaking through your laptop — hearing on the phone"
                    : `🔈 ${
                        audioBroken
                          ? "Relay unavailable (phone restricted audio) — call uses phone speaker"
                          : "Connecting audio…"
                      }`}
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
            Calls are always placed on your phone. Use the two pickers above to decide whether
            you speak and/or hear through this laptop (any mic or speaker it has, including
            Bluetooth) — leaving both on "This phone" keeps the call entirely on the phone.
            If the phone restricts call audio capture, that direction falls back to the phone
            speaker.
          </div>
        </div>
      </div>
    </div>
  );
}
