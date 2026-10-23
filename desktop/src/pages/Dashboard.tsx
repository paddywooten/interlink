import { useState } from "react";
import { revealInFolder } from "../api";
import * as api from "../api";
import { setState, toast, useStore } from "../store";

export default function Dashboard() {
  const status = useStore((s) => s.status);
  const connected = useStore((s) => s.connected);
  const linkError = useStore((s) => s.linkError);
  const transfers = useStore((s) => s.transfers);
  const recent = useStore((s) => s.recentFiles);
  const devices = useStore((s) => s.devices);
  const [ringing, setRinging] = useState(false);
  const [clipText, setClipText] = useState("");

  const paired = status?.paired;

  return (
    <div className="page">
      <header className="page-head">
        <h1>Dashboard</h1>
        {!paired && (
          <button className="btn primary" onClick={() => setState({ qrModal: true })}>
            Pair a phone
          </button>
        )}
      </header>

      {linkError && (
        <div className="banner error">
          <div>
            <b>Phone can't connect.</b> {linkError}
          </div>
          <button
            className="btn ghost"
            onClick={() => {
              setState({ linkError: null });
              setState({ qrModal: true });
            }}
          >
            Show pairing QR
          </button>
        </div>
      )}

      {!paired ? (
        <div className="hero">
          <div className="hero-icon">⇄</div>
          <h2>Welcome to InterLink</h2>
          <p className="muted">
            Mirror your phone's notifications, messages, calls, gallery and clipboard to this
            laptop. Start by pairing — scan the QR code with the InterLink phone app.
          </p>
          <button className="btn primary lg" onClick={() => setState({ qrModal: true })}>
            Show pairing QR
          </button>
        </div>
      ) : (
        <div className="grid">
          <div className="card device-card">
            <div className="card-title">Phone</div>
            {status?.peer ? (
              <div className="device-info">
                <div className="device-name">{status.peer.name}</div>
                <div className="muted">{status.peer.model || status.peer.os}</div>
                <div className="stats">
                  <div className="stat">
                    <span className="stat-k">Status</span>
                    <span className={`stat-v ${connected ? "ok" : "bad"}`}>
                      {connected ? "Connected" : "Disconnected"}
                    </span>
                  </div>
                  <div className="stat">
                    <span className="stat-k">Battery</span>
                    <span className="stat-v">
                      {status.battery
                        ? `${Math.round(status.battery.level * 100)}%${status.battery.charging ? " ⚡" : ""}`
                        : "—"}
                    </span>
                  </div>
                  <div className="stat">
                    <span className="stat-k">Laptop</span>
                    <span className="stat-v">{status.ip ?? "?"}:{status.port}</span>
                  </div>
                </div>
                <div className="row gap">
                  <button
                    className="btn"
                    disabled={!connected || ringing}
                    onClick={async () => {
                      setRinging(true);
                      await api.ringPhone(15);
                      toast("Ringing phone for 15s", "info");
                      setTimeout(() => setRinging(false), 16000);
                    }}
                  >
                    {ringing ? "Ringing…" : "🔊 Ring my phone"}
                  </button>
                  <button className="btn ghost" onClick={() => setState({ qrModal: true })}>
                    Re-pair
                  </button>
                </div>
              </div>
            ) : (
              <div className="muted">Waiting for phone to connect…</div>
            )}
          </div>

          <div className="card">
            <div className="card-title">Clipboard sync</div>
            <div className="row gap">
              <input
                className="input"
                placeholder="Text to send to phone…"
                value={clipText}
                onChange={(e) => setClipText(e.target.value)}
                disabled={!connected}
              />
              <button
                className="btn"
                disabled={!connected || !clipText.trim()}
                onClick={async () => {
                  await api.clipSet(clipText);
                  setClipText("");
                  toast("Sent to phone clipboard", "success");
                }}
              >
                Send
              </button>
            </div>
            <div className="row gap" style={{ marginTop: 8 }}>
              <button
                className="btn ghost"
                disabled={!connected}
                onClick={async () => {
                  const clip = await navigator.clipboard.readText().catch(() => "");
                  if (!clip) return toast("Laptop clipboard is empty", "error");
                  await api.clipSet(clip);
                  toast("Pushed laptop clipboard to phone", "success");
                }}
              >
                ↑ Push laptop clipboard
              </button>
              <button
                className="btn ghost"
                disabled={!connected}
                onClick={async () => {
                  const r = await api.clipGet();
                  if (!r?.text) return toast("Phone clipboard is empty", "error");
                  await navigator.clipboard.writeText(r.text).catch(() => undefined);
                  setClipText(r.text);
                  toast("Pulled phone clipboard", "success");
                }}
              >
                ↓ Pull from phone
              </button>
            </div>
          </div>

          <div className="card">
            <div className="card-title">Transfers</div>
            {Object.keys(transfers).length === 0 && recent.length === 0 && (
              <div className="muted">No transfers yet. Send files from Settings → or browse Gallery.</div>
            )}
            {Object.values(transfers).map((t) => {
              const pct = t.total ? Math.min(100, (t.received / t.total) * 100) : 0;
              return (
                <div key={t.tid} className="transfer">
                  <div className="transfer-name">
                    {t.name} <span className="muted">{t.dir === "up" ? "↑" : "↓"}</span>
                  </div>
                  <div className="bar">
                    <div className="bar-fill" style={{ width: `${pct}%` }} />
                  </div>
                </div>
              );
            })}
            {recent.map((f) => (
              <div key={f.path + f.when} className="recent-row">
                <span className="mono">{f.name}</span>
                <button className="btn tiny ghost" onClick={() => revealInFolder(f.path)}>
                  Show
                </button>
              </div>
            ))}
          </div>

          <div className="card">
            <div className="card-title">Devices on this network</div>
            {devices.length === 0 ? (
              <div className="muted">
                No other InterLink devices found yet. Make sure the phone and laptop are on the
                same Wi-Fi; devices appear automatically within a few seconds.
              </div>
            ) : (
              devices.map((d) => (
                <div className="device-row" key={`${d.ip}:${d.port}`}>
                  <div className="device-row-main">
                    <span className="device-row-icon">{d.role === "phone" ? "📱" : "💻"}</span>
                    <div>
                      <div className="device-row-name">{d.name}</div>
                      <div className="muted mono">
                        {d.ip}:{d.port}
                      </div>
                    </div>
                  </div>
                  <span className={`badge ${d.role === "phone" ? "ok" : ""}`}>
                    {d.role === "phone" ? "Phone" : "Laptop"}
                  </span>
                </div>
              ))
            )}
          </div>

          <div className="card">
            <div className="card-title">Quick actions</div>
            <div className="quick">
              <button className="quick-btn" onClick={() => setState({ page: "messages" })}>
                ✉ Messages
              </button>
              <button className="quick-btn" onClick={() => setState({ page: "notifications" })}>
                🔔 Notifications
              </button>
              <button className="quick-btn" onClick={() => setState({ page: "gallery" })}>
                🖼 Gallery
              </button>
              <button className="quick-btn" onClick={() => setState({ page: "calls" })}>
                ☎ Calls
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
