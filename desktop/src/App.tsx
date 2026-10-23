import { useEffect } from "react";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import * as api from "./api";
import Sidebar from "./components/Sidebar";
import Toasts from "./components/Toasts";
import Dashboard from "./pages/Dashboard";
import Messages from "./pages/Messages";
import Notifications from "./pages/Notifications";
import Gallery from "./pages/Gallery";
import Calls from "./pages/Calls";
import Settings from "./pages/Settings";
import QrModal from "./components/QrModal";
import {
  addNotification,
  addRecentFile,
  getState,
  removeNotification,
  setState,
  toast,
  upsertTransfer,
  useStore,
} from "./store";

export default function App() {
  const page = useStore((s) => s.page);
  const connected = useStore((s) => s.connected);
  const status = useStore((s) => s.status);

  // Initial status + event stream
  useEffect(() => {
    let alive = true;

    const refresh = async () => {
      try {
        const st = await api.getStatus();
        if (!alive) return;
        setState({ status: st, connected: st.connected });
      } catch (e) {
        console.error("status failed", e);
      }
    };
    refresh();
    const iv = setInterval(refresh, 5000);

    const unlisten = api.onEvent(async (ev) => {
      if (!alive) return;
      if (ev.kind === "conn") {
        const connectedNow = !!ev.data.connected;
        setState({ connected: connectedNow });
        if (ev.data.error) {
          const msg = ev.data.detail ?? ev.data.error;
          setState({ linkError: msg });
          toast(msg, "error");
        } else if (connectedNow) {
          setState({ linkError: null });
        }
        refresh();
        if (!connectedNow) setState({ call: null });
        return;
      }
      if (ev.kind === "progress") {
        const { tid, received, total } = ev.data;
        const prev = getState().transfers[tid];
        upsertTransfer({
          tid,
          name: prev?.name ?? "transfer",
          received,
          total,
          dir: prev?.dir ?? "down",
        });
        return;
      }
      if (ev.kind === "push") {
        const { t, d } = ev.data;
        switch (t) {
          case "notif.post":
            addNotification({ ...d, ts: Date.now() });
            break;
          case "notif.remove":
            removeNotification(d.key);
            break;
          case "call.state":
            setState({
              call: d.state === "ended" ? null : d,
              audioBroken: d.state === "ended" ? null : getState().audioBroken,
            });
            if (d.state === "ended") {
              api.callAudioStop(d.callId).catch(() => undefined);
            }
            break;
          case "call.audio_state":
            if (d.ok === false) {
              setState({ audioBroken: d.reason ?? "unavailable" });
              toast("Call audio relay unavailable — using phone speaker", "error");
            } else {
              setState({ audioBroken: null });
            }
            break;
          case "clip.set":
            // Read the live slot — the effect below keeps it fresh; the `status`
            // closure above would be stale (mounted before the first refresh).
            if ((window as any).__il_status?.clipboardEnabled !== false) {
              writeText(d.text ?? "").catch(() => undefined);
              toast("Clipboard copied from phone", "info");
            }
            break;
          case "device.battery":
            refresh();
            break;
          case "sms.changed":
            // Pages refetch on their own cadence; nudge a global event:
            setState({}); // trigger re-render for subscribers
            window.dispatchEvent(new CustomEvent("il:sms-changed", { detail: d }));
            break;
          case "transfer.done":
            addRecentFile(d.name, d.path);
            toast(`${d.name} saved`, "success");
            break;
          case "transfer.failed":
            toast(`${d.name ?? "transfer"} failed: ${d.error}`, "error");
            break;
          case "file.offer":
            toast(`Phone offered "${d.name}" — enable auto-accept in Settings`, "info");
            break;
          case "unpair":
            refresh();
            toast("Phone unpaired", "info");
            break;
        }
      }
    }).then((u) => u);

    return () => {
      alive = false;
      clearInterval(iv);
      unlisten.then((f) => f());
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Keep latest status in a ref-like slot for the event handler above.
  useEffect(() => {
    (window as any).__il_status = status;
  }, [status]);

  let pageEl;
  switch (page) {
    case "messages":
      pageEl = <Messages />;
      break;
    case "notifications":
      pageEl = <Notifications />;
      break;
    case "gallery":
      pageEl = <Gallery />;
      break;
    case "calls":
      pageEl = <Calls />;
      break;
    case "settings":
      pageEl = <Settings />;
      break;
    default:
      pageEl = <Dashboard />;
  }

  return (
    <div className="app">
      <Sidebar />
      <main className={`main ${connected ? "" : "offline"}`}>
        {!connected && status?.paired && (
          <div className="offline-banner">
            <span className="dot" /> Phone disconnected — waiting for it to reconnect…
          </div>
        )}
        {pageEl}
      </main>
      <Toasts />
      <QrModal />
    </div>
  );
}
