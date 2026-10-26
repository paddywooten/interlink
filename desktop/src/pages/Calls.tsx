import { useEffect, useState } from "react";
import * as api from "../api";
import type { AudioDevice, CallEntry, CallKind, CallState, Contact } from "../types";
import { getState, setState, toast, useStore } from "../store";

/** Select value meaning "leave this direction on the phone". */
const PHONE = "__phone__";

/**
 * What a device option is called. The list carries PipeWire's own node name
 * plus the description the desktop shows, and Bluetooth is spelled out —
 * "anything connected over Bluetooth" is one of the things people come here
 * to pick, and `bluez_output.44_55_….a2dp` says nothing to anyone.
 */
const devLabel = (d: AudioDevice) => {
  const tags = [
    d.name.startsWith("bluez_") ? "Bluetooth" : null,
    d.default ? "system default" : null,
  ].filter(Boolean);
  return tags.length ? `${d.label ?? d.name} (${tags.join(", ")})` : d.label ?? d.name;
};

/** Everything that is neither the phone nor the laptop's own device — a
 *  connected Bluetooth headset, HDMI, … The saved selection is kept even when
 *  it *is* the default node, or the `<select>` would sit on no option. */
const otherDevices = (devs: AudioDevice[], selected: string) =>
  devs.filter((d) => !d.default || d.name === selected);

/** Direction glyph for one history row — the colour lives in CSS. */
const KIND_ICON: Record<CallKind, string> = {
  in: "↙",
  out: "↗",
  missed: "❗",
  rejected: "✕",
  blocked: "⛔",
  voicemail: "✉",
  answered: "↙",
  unknown: "•",
};

function fmtDur(sec: number) {
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${m}:${s.toString().padStart(2, "0")}`;
}

/** "14:32" today, "Yesterday 14:32", "Mar 3 14:32" otherwise — a list of
 * 100 absolute timestamps is unreadable; the day is what people scan for. */
function fmtWhen(ms: number) {
  const d = new Date(ms);
  const now = new Date();
  const time = d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  if (d.toDateString() === now.toDateString()) return time;
  const yesterday = new Date(now.getTime() - 86_400_000);
  if (d.toDateString() === yesterday.toDateString()) return `Yesterday ${time}`;
  return `${d.toLocaleDateString([], { month: "short", day: "numeric" })} ${time}`;
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
  const [mics, setMics] = useState<AudioDevice[]>([]);
  const [spks, setSpks] = useState<AudioDevice[]>([]);
  const [micSel, setMicSel] = useState<string>(PHONE);
  const [spkSel, setSpkSel] = useState<string>(PHONE);
  const [elapsed, setElapsed] = useState(0);

  // Call history: read once per connection and again after every call ends
  // (that's when the phone writes the row). `perm:` errors are kept — an
  // empty list and a denied permission look identical otherwise, and only
  // one of them is fixed by a trip to the phone's Settings.
  const [tab, setTab] = useState<"recent" | "contacts">("recent");
  const [log, setLog] = useState<CallEntry[]>([]);
  const [logLoading, setLogLoading] = useState(false);
  const [logErr, setLogErr] = useState<string | null>(null);
  const [logFilter, setLogFilter] = useState<"all" | "missed">("all");

  const loadLog = async () => {
    if (!connected) {
      setLog([]);
      return;
    }
    setLogLoading(true);
    setLogErr(null);
    try {
      setLog(await api.callLogList(100));
    } catch (e) {
      setLogErr(String(e).replace(/^Error:\s*/i, ""));
    } finally {
      setLogLoading(false);
    }
  };

  useEffect(() => {
    loadLog();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected]);

  useEffect(() => {
    if (call?.state === "ended") loadLog();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [call?.state]);

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
      const r = await api.callDial(num);
      // The phone pushes a state only once Telecom reports one, and for an
      // outgoing call that is a beat after the request lands. Put the call on
      // screen straight away — that is what carries the end-call button while
      // the far end is still ringing.
      const seeded: CallState = {
        state: "dialing",
        dir: "out",
        number: r.number ?? num,
        name: r.name ?? "",
        callId: r.callId,
      };
      setState({ call: seeded });
      toast(`Calling ${seeded.number}…`, "info");
      // …but `call.dial` only means the phone *accepted* the request. If the
      // handset never starts a call — Android blocks activity launches from a
      // service with no window on screen, and it blocks them silently — no
      // state ever follows and this card would sit there describing a call
      // that does not exist. Any real call replaces `seeded` with its own
      // object well before this fires, so identity is the test.
      window.setTimeout(() => {
        const now = getState();
        if (now.call === seeded && now.connected) {
          setState({ call: null });
          toast(
            "The phone did not place the call — open InterLink on the phone and dial again",
            "error",
          );
        }
      }, 6000);
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

  /** Name of the laptop's own device, so "Laptop microphone" can name the
   *  hardware instead of just saying "default". Absent when the list is the
   *  raw ALSA one (no `pactl`), where the name would only be noise. */
  const micLaptop = mics.find((d) => d.default)?.label;
  const spkLaptop = spks.find((d) => d.default)?.label;
  const shownLog = logFilter === "missed" ? log.filter((c) => c.kind === "missed") : log;

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
                  {/* Phone first: the phone's own mic is where a call starts. */}
                  <option value={PHONE}>📱 Phone microphone</option>
                  <option value="">
                    🎙 Laptop microphone{micLaptop ? ` — ${micLaptop}` : ""}
                  </option>
                  {otherDevices(mics, micSel).map((d) => (
                    <option key={d.name} value={d.name}>
                      🎙 {devLabel(d)}
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
                  {/* Laptop first: this is where the far end usually lands. */}
                  <option value="">
                    🔊 Laptop speaker{spkLaptop ? ` — ${spkLaptop}` : ""}
                  </option>
                  <option value={PHONE}>📱 Phone speaker</option>
                  {otherDevices(spks, spkSel).map((d) => (
                    <option key={d.name} value={d.name}>
                      🔊 {devLabel(d)}
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
                          ? audioBroken === "capture_silent"
                            ? "This phone won't hand over its call audio — the call stays on the phone"
                            : "Relay unavailable (phone restricted audio) — call uses phone speaker"
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
          <div className="row gap" style={{ marginBottom: 10 }}>
            <button
              className={tab === "recent" ? "btn primary" : "btn ghost"}
              onClick={() => setTab("recent")}
            >
              Recent
            </button>
            <button
              className={tab === "contacts" ? "btn primary" : "btn ghost"}
              onClick={() => setTab("contacts")}
            >
              Contacts
            </button>
            {tab === "recent" && (
              <>
                <button
                  className={logFilter === "missed" ? "btn primary" : "btn ghost"}
                  onClick={() => setLogFilter((f) => (f === "missed" ? "all" : "missed"))}
                  title="Missed calls only"
                >
                  Missed
                </button>
                <button
                  className="btn ghost"
                  onClick={loadLog}
                  disabled={logLoading}
                  title="Refresh from phone"
                >
                  ⟳
                </button>
              </>
            )}
          </div>

          {tab === "recent" ? (
            !connected ? (
              <div className="muted" style={{ marginTop: 8 }}>
                Phone offline
              </div>
            ) : logLoading && log.length === 0 ? (
              <div className="muted" style={{ marginTop: 8 }}>
                Loading call log from phone…
              </div>
            ) : logErr ? (
              <div className="muted" style={{ marginTop: 8 }}>
                {logErr}
              </div>
            ) : shownLog.length === 0 ? (
              <div className="muted" style={{ marginTop: 8 }}>
                {logFilter === "missed"
                  ? "No missed calls."
                  : log.length === 0
                    ? "No calls on the phone yet."
                    : "No missed calls."}
              </div>
            ) : (
              <div className="contact-list">
                {shownLog.map((e) => (
                  <button
                    key={e.id}
                    className="contact-row"
                    onClick={() => dial(e.number)}
                    title={`Call ${e.number}`}
                  >
                    <div>
                      <b className={e.kind === "missed" ? "missed-num" : undefined}>
                        {e.name || e.number}
                        {e.new && <span className="log-new" />}
                      </b>
                      <div className="muted mono">
                        {e.name ? `${e.number} · ` : ""}
                        {fmtWhen(e.date)}
                        {e.duration > 0
                          ? ` · ${fmtDur(e.duration)}`
                          : e.kind === "missed"
                            ? " · no answer"
                            : ""}
                      </div>
                    </div>
                    <span className={`log-ico ${e.kind}`} title={e.kind}>
                      {KIND_ICON[e.kind]}
                    </span>
                  </button>
                ))}
              </div>
            )
          ) : (
            <>
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
            </>
          )}

          <div className="muted" style={{ marginTop: 12 }}>
            {tab === "recent" && (
              <>
                History is read straight from the phone — grant{" "}
                <b>Call log (recent calls on laptop)</b> on the phone&apos;s InterLink app if this
                stays empty; dialling does not imply permission to read it.
                <br />
              </>
            )}
            Calls are always placed on your phone. The two pickers above decide where each
            direction goes — the phone&apos;s own microphone or speaker, this laptop&apos;s, or any
            device the laptop has connected (Bluetooth included) — and leaving both on the
            phone keeps the call entirely on the phone. If the phone restricts call audio
            capture, that direction falls back to the phone speaker.
          </div>
        </div>
      </div>
    </div>
  );
}
