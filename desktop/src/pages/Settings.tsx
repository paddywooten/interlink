import { useEffect, useState } from "react";
import * as api from "../api";
import { setState, toast, useStore } from "../store";

export default function Settings() {
  const status = useStore((s) => s.status);
  const connected = useStore((s) => s.connected);
  const [name, setName] = useState("");
  const [mics, setMics] = useState<{ name: string; default: boolean }[]>([]);
  const [phoneInfo, setPhoneInfo] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    if (status) setName(status.deviceName);
    api.listAudioInputs().then((r) => setMics(r.devices)).catch(() => undefined);
  }, [status?.deviceName]);

  useEffect(() => {
    if (connected) {
      api.deviceInfo().then(setPhoneInfo).catch(() => setPhoneInfo(null));
    } else {
      setPhoneInfo(null);
    }
  }, [connected]);

  const save = async (patch: Record<string, unknown>) => {
    try {
      const next = await api.setSettings(patch);
      // refresh status cache
      const st = await api.getStatus();
      setState({ status: st });
      return next;
    } catch (e) {
      toast(String(e), "error");
    }
  };

  return (
    <div className="page settings-page">
      <header className="page-head">
        <h1>Settings</h1>
      </header>

      <div className="settings-grid">
        <div className="card">
          <div className="card-title">This laptop</div>
          <label className="field">
            <span>Device name</span>
            <div className="row gap">
              <input className="input" value={name} onChange={(e) => setName(e.target.value)} />
              <button
                className="btn"
                disabled={!name.trim() || name === status?.deviceName}
                onClick={async () => {
                  await save({ device_name: name.trim() });
                  toast("Saved", "success");
                }}
              >
                Save
              </button>
            </div>
          </label>
          <label className="field">
            <span>Address</span>
            <div className="mono muted">
              {status?.ip ?? "?"}:{status?.port}
            </div>
          </label>
        </div>

        <div className="card">
          <div className="card-title">Calls</div>
          <label className="field">
            <span>Microphone (for call audio)</span>
            <select
              className="select"
              value={status?.micDevice ?? ""}
              onChange={async (e) => {
                await save({ mic_device: e.target.value || null });
                toast("Microphone preference saved", "success");
              }}
            >
              <option value="">System default</option>
              {mics.map((m) => (
                <option key={m.name} value={m.name}>
                  {m.name}
                  {m.default ? " (default)" : ""}
                </option>
              ))}
            </select>
            <span className="hint">
              Pick a specific microphone to avoid fan noise — it is used for call audio on
              this laptop.
            </span>
          </label>
        </div>

        <div className="card">
          <div className="card-title">Files & clipboard</div>
          <label className="switch-row">
            <input
              type="checkbox"
              checked={status?.autoAcceptFiles ?? true}
              onChange={async (e) => {
                await save({ auto_accept_files: e.target.checked });
              }}
            />
            <span>Auto-accept files sent from phone</span>
          </label>
          <label className="switch-row">
            <input
              type="checkbox"
              checked={status?.clipboardEnabled ?? true}
              onChange={async (e) => {
                await save({ clipboard_enabled: e.target.checked });
              }}
            />
            <span>Sync clipboard from phone</span>
          </label>
          <label className="field">
            <span>Download folder</span>
            <div className="row gap">
              <code className="path">{status?.downloadDir ?? "~/InterLink"}</code>
              <button
                className="btn ghost"
                onClick={async () => {
                  const r = await api.pickDirectory();
                  if (r.path) {
                    await save({ download_dir: r.path });
                    toast(`Downloads → ${r.path}`, "success");
                  }
                }}
              >
                Change…
              </button>
            </div>
          </label>
        </div>

        <div className="card">
          <div className="card-title">Pairing</div>
          <div className="muted" style={{ marginBottom: 10 }}>
            {status?.paired
              ? `Paired${status.peer ? ` with ${status.peer.name}` : ""}`
              : "No phone paired yet"}
          </div>
          <div className="row gap">
            <button className="btn" onClick={() => setState({ qrModal: true })}>
              {status?.paired ? "Re-pair…" : "Pair a phone…"}
            </button>
            <button
              className="btn danger"
              disabled={!status?.paired}
              onClick={async () => {
                await api.unpair();
                setState({ status: await api.getStatus() });
                toast("Unpaired", "info");
              }}
            >
              Unpair
            </button>
          </div>
        </div>

        <div className="card">
          <div className="card-title">Phone status</div>
          {!connected ? (
            <div className="muted">Phone offline</div>
          ) : (
            <div className="kv">
              {phoneInfo &&
                Object.entries(phoneInfo).map(([k, v]) => (
                  <div key={k} className="kv-row">
                    <span className="muted">{k}</span>
                    <span className="mono">{typeof v === "object" ? JSON.stringify(v) : String(v)}</span>
                  </div>
                ))}
              <div className="kv-row">
                <span className="muted">capabilities</span>
                <span className="mono">{status?.peer?.caps?.join(", ") ?? "—"}</span>
              </div>
            </div>
          )}
        </div>

        <div className="card">
          <div className="card-title">About</div>
          <div className="muted">
            InterLink v0.1.0 — local-network only, end-to-end encrypted (XChaCha20-Poly1305).
            No cloud, no accounts. Protocol v1.
          </div>
        </div>
      </div>
    </div>
  );
}
