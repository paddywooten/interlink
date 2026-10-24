import { useEffect, useState } from "react";
import * as api from "../api";
import { setState, toast, useStore } from "../store";

const CONN_MODES = [
  {
    id: "auto",
    label: "Auto",
    hint: "Use the USB cable when the phone is plugged in, otherwise WiFi.",
  },
  { id: "usb", label: "USB", hint: "Only over the cable — needs USB debugging enabled." },
  { id: "wifi", label: "WiFi", hint: "Only over the local network — never uses the cable." },
] as const;

interface BackupInfo {
  path: string;
  manifest: Record<string, unknown> | null;
  contacts: boolean;
  sms: boolean;
  media: number;
}

export default function Settings() {
  const status = useStore((s) => s.status);
  const connected = useStore((s) => s.connected);
  const [name, setName] = useState("");
  const [mics, setMics] = useState<{ name: string; default: boolean }[]>([]);
  const [spks, setSpks] = useState<{ name: string; default: boolean }[]>([]);
  const [phoneInfo, setPhoneInfo] = useState<Record<string, unknown> | null>(null);
  const [connMode, setConnMode] = useState("auto");

  // Remote app install
  const [installing, setInstalling] = useState<string | null>(null);

  // Backup
  const [sections, setSections] = useState<Record<string, boolean>>({
    contacts: true,
    sms: true,
    photos: false,
  });
  const [busyBackup, setBusyBackup] = useState(false);
  const [backupDir, setBackupDir] = useState<string | null>(null);
  const [backupInfo, setBackupInfo] = useState<BackupInfo | null>(null);
  const [busyRestore, setBusyRestore] = useState(false);
  const [restoreResult, setRestoreResult] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    if (status) setName(status.deviceName);
    setConnMode(status?.connection ?? "auto");
    api.listAudioInputs().then((r) => setMics(r.devices)).catch(() => undefined);
    api.listAudioOutputs().then((r) => setSpks(r.devices)).catch(() => undefined);
  }, [status?.deviceName, status?.connection]);

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

  const changeConn = async (mode: string) => {
    setConnMode(mode);
    await save({ connection: mode });
    toast(
      mode === "usb" && !status?.adbDevice
        ? "USB mode — plug the phone in (with USB debugging) to connect"
        : "Connection mode saved — reconnecting…",
      "info",
    );
  };

  const refreshConn = async () => {
    try {
      const r = await api.connectionRefresh();
      setState({ status: await api.getStatus() });
      toast(r.adbDevice ? `Found USB device ${r.adbDevice}` : "No USB device found", "info");
    } catch (e) {
      toast(String(e), "error");
    }
  };

  /** Pick an .apk, push it to the phone, then ask the phone to install it. */
  const installApk = async () => {
    if (!connected || installing) return;
    try {
      const path = await api.pickApk();
      if (!path) return;
      const name = path.split(/[\\/]/).pop() ?? "app.apk";
      setInstalling(name);
      // Push first and wait for the phone to finish writing (file_send
      // resolves on the phone's confirmation, not on our last byte).
      const sent = await api.fileSend(path);
      if (!sent.completed) {
        throw new Error("the phone never confirmed receiving the file");
      }
      await api.req("app.install", { path: name });
      toast(
        `Sent ${name} — tap Install on the phone to confirm`,
        "success",
      );
    } catch (e) {
      toast(`Install failed: ${e}`, "error");
    } finally {
      setInstalling(null);
    }
  };

  const runBackup = async () => {
    const picked = Object.keys(sections).filter((k) => sections[k]);
    if (picked.length === 0) return toast("Pick at least one thing to back up", "error");
    setBusyBackup(true);
    setRestoreResult(null);
    try {
      const r = await api.backupStart(picked, backupDir ?? undefined);
      toast(
        `Backed up ${r.contacts} contacts, ${r.sms} messages, ${r.photos} media → ${r.path}`,
        "success",
      );
      const info = await api.backupList(r.path).catch(() => null);
      setBackupInfo(info);
    } catch (e) {
      toast(`Backup failed: ${e}`, "error");
    } finally {
      setBusyBackup(false);
    }
  };

  const chooseBackupDir = async () => {
    const p = await api.pickBackupDir();
    if (p) setBackupDir(p);
  };

  const inspectBackupDir = async () => {
    const p = backupDir ?? (await api.pickBackupDir());
    if (!p) return;
    setBackupDir(p);
    try {
      setBackupInfo(await api.backupList(p));
    } catch (e) {
      setBackupInfo(null);
      toast(String(e), "error");
    }
  };

  const restoreBackup = async () => {
    if (!backupInfo) return;
    setBusyRestore(true);
    try {
      const r = await api.backupRestore(backupInfo.path);
      setRestoreResult(r as unknown as Record<string, unknown>);
      const sms = r.sms as { blocked?: boolean; error?: string; imported?: number } | null;
      if (sms?.blocked) {
        toast(
          "Contacts restored, but SMS couldn't be — InterLink must be your phone's default SMS app.",
          "info",
        );
      } else {
        toast(
          `Restored ${r.contacts} contacts${sms?.imported ? `, ${sms.imported} messages` : ""} and ${r.media} media files`,
          "success",
        );
      }
    } catch (e) {
      toast(`Restore failed: ${e}`, "error");
    } finally {
      setBusyRestore(false);
    }
  };

  return (
    <div className="page settings-page">
      <header className="page-head">
        <h1>Settings</h1>
      </header>

      <div className="settings-grid">
        <div className="card">
          <div className="card-title">Connection</div>
          <label className="field">
            <span>How should this laptop reach the phone?</span>
            <div className="conn-modes">
              {CONN_MODES.map((m) => (
                <label key={m.id} className="switch-row">
                  <input
                    type="radio"
                    name="conn-mode"
                    checked={connMode === m.id}
                    onChange={() => changeConn(m.id)}
                  />
                  <span>
                    <b>{m.label}</b> — {m.hint}
                  </span>
                </label>
              ))}
            </div>
            <span className="hint">
              Over USB the phone talks to this laptop through an <code>adb reverse</code> tunnel,
              so it works with no shared network at all. USB debugging must be on.
            </span>
          </label>
          <div className="kv">
            <div className="kv-row">
              <span className="muted">Currently using</span>
              <span className="mono">
                {status?.transport === "usb"
                  ? "USB cable"
                  : status?.transport === "wifi"
                    ? "WiFi"
                    : "not connected"}
              </span>
            </div>
            <div className="kv-row">
              <span className="muted">USB device seen</span>
              <span className="mono">{status?.adbDevice ?? "none"}</span>
            </div>
          </div>
          <button className="btn ghost" onClick={refreshConn}>
            Re-scan for USB device
          </button>
        </div>

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
              The laptop microphone you speak through during a call. On the Calls page you can
              also leave speaking entirely on the phone.
            </span>
          </label>
          <label className="field">
            <span>Speaker (for call audio)</span>
            <select
              className="select"
              value={status?.speakerDevice ?? ""}
              onChange={async (e) => {
                await save({ speaker_device: e.target.value || null });
                toast("Speaker preference saved", "success");
              }}
            >
              <option value="">System default</option>
              {spks.map((s) => (
                <option key={s.name} value={s.name}>
                  {s.name}
                  {s.default ? " (default)" : ""}
                </option>
              ))}
            </select>
            <span className="hint">
              Where you hear the far end — laptop speakers, a Bluetooth speaker, HDMI, anything
              this laptop can output to.
            </span>
          </label>
        </div>

        <div className="card">
          <div className="card-title">Install an app on the phone</div>
          <div className="muted" style={{ marginBottom: 10 }}>
            Push an <code>.apk</code> from this laptop and the phone will show its normal
            install confirmation — you tap Install on the phone, nothing installs silently.
          </div>
          <button
            className="btn primary"
            disabled={!connected || installing !== null}
            onClick={installApk}
          >
            {installing ? `Sending ${installing}…` : "Choose an .apk…"}
          </button>
          {!connected && <div className="muted">Phone offline</div>}
          <div className="hint">
            The phone needs "Install unknown apps" granted for InterLink (the phone app shows a
            row for it under its permissions).
          </div>
        </div>

        <div className="card">
          <div className="card-title">Backup &amp; restore</div>
          <div className="muted" style={{ marginBottom: 8 }}>
            A backup is a plain folder you can open and read: contacts as a vCard, messages as
            JSON, and every photo &amp; video in a <code>media</code> subfolder.
          </div>
          {(["contacts", "sms", "photos"] as const).map((k) => (
            <label key={k} className="switch-row">
              <input
                type="checkbox"
                checked={sections[k]}
                onChange={(e) => setSections((s) => ({ ...s, [k]: e.target.checked }))}
              />
              <span>
                {k === "contacts" ? "Contacts" : k === "sms" ? "Messages (SMS)" : "Photos & videos"}
              </span>
            </label>
          ))}
          <label className="field">
            <span>Folder</span>
            <div className="row gap">
              <code className="path">{backupDir ?? "~/InterLink/backups/<timestamp>"}</code>
              <button className="btn ghost" onClick={chooseBackupDir}>
                Choose…
              </button>
            </div>
          </label>
          <div className="row gap">
            <button
              className="btn primary"
              disabled={!connected || busyBackup}
              onClick={runBackup}
            >
              {busyBackup ? "Backing up…" : "Back up now"}
            </button>
            <button className="btn ghost" onClick={inspectBackupDir}>
              Restore from folder…
            </button>
          </div>
          {!connected && busyBackup === false && !backupInfo && (
            <div className="muted">Phone offline — connect to back up.</div>
          )}

          {backupInfo && (
            <div style={{ marginTop: 12 }}>
              <div className="card-title">Selected backup</div>
              <div className="backup-row">
                <span className="mono">{backupInfo.path}</span>
              </div>
              <div className="backup-row">
                <span className="muted">Contents</span>
                <span>
                  {[
                    backupInfo.contacts ? "contacts" : null,
                    backupInfo.sms ? "SMS" : null,
                    backupInfo.media > 0 ? `${backupInfo.media} media files` : null,
                  ]
                    .filter(Boolean)
                    .join(", ") || "nothing found"}
                </span>
              </div>
              <button
                className="btn"
                disabled={!connected || busyRestore}
                onClick={restoreBackup}
              >
                {busyRestore ? "Restoring…" : "Restore onto phone"}
              </button>
            </div>
          )}

          {restoreResult && (
            <div className="hint">
              Restored {String(restoreResult.contacts)} contacts
              {(restoreResult.media as number) > 0 && `, ${restoreResult.media} media files`}
              {(restoreResult.mediaFailed as number) > 0 &&
                ` (${restoreResult.mediaFailed} failed)`}
              .
              {(restoreResult.sms as { blocked?: boolean })?.blocked &&
                " SMS was skipped: InterLink must be the phone's default SMS app to write messages."}
            </div>
          )}
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
              <div className="kv-row">
                <span className="muted">battery</span>
                <span className="mono">
                  {status?.battery
                    ? `${Math.round(status.battery.level * 100)}%${status.battery.charging ? " (charging)" : ""}`
                    : "—"}
                </span>
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
