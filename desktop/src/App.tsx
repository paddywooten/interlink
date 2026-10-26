import { useEffect } from "react";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import * as api from "./api";
import Sidebar from "./components/Sidebar";
import Toasts from "./components/Toasts";
import Dashboard from "./pages/Dashboard";
import Messages from "./pages/Messages";
import Notifications from "./pages/Notifications";
import Gallery from "./pages/Gallery";
import Music from "./pages/Music";
import Contacts from "./pages/Contacts";
import Calls from "./pages/Calls";
import Mirror from "./pages/Mirror";
import Settings from "./pages/Settings";
import PlayerBar from "./components/PlayerBar";
import CallBar from "./components/CallBar";
import QrModal from "./components/QrModal";
import {
  addNotification,
  addRecentFile,
  getState,
  removeNotification,
  setState,
  setDevices,
  toast,
  upsertTransfer,
  useStore,
} from "./store";

export default function App() {
  const page = useStore((s) => s.page);
  const connected = useStore((s) => s.connected);
  const status = useStore((s) => s.status);
  // Truthy once a queue exists — full-height pages need to make room for the
  // player bar rather than let it sit on top of their composer.
  const music = useStore((s) => s.music !== null);
  // Same for the in-call bar, which stands down on Calls (the page's own
  // call card already carries these controls).
  const inCall = useStore((s) => s.call !== null && s.page !== "calls");

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
      try {
        const devs = await api.getDevices();
        if (!alive) return;
        setState({ devices: devs });
      } catch (e) {
        // non-fatal — device list is a convenience panel
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
      if (ev.kind === "devices") {
        // LAN device list changed — pull the fresh list.
        api.getDevices().then(setDevices).catch(() => undefined);
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
          case "install.status":
            // Pushed by the phone as the PackageInstaller session progresses.
            // The user taps confirm on the phone; this just keeps the laptop
            // informed of what happened next.
            if (d.status === "pending") {
              toast(`Installing ${d.name ?? "app"} on phone… tap Install on the phone`, "info");
            } else if (d.status === "success") {
              toast(`${d.name ?? "App"} installed on phone`, "success");
            } else {
              toast(
                `Install ${d.status ?? "failed"}: ${d.error ?? "see the phone for details"}`,
                d.status === "failure" ? "error" : "info",
              );
            }
            break;
          case "file.offer":
            toast(`Phone offered "${d.name}" — enable auto-accept in Settings`, "info");
            break;
          case "mirror.frame":
            // High-frequency (8fps): don't touch the store — hand the JPEG to
            // the Mirror page via a CustomEvent so it can paint without
            // re-rendering React.
            window.dispatchEvent(new CustomEvent("il:mirror-frame", { detail: d }));
            break;
          case "mirror.state":
            window.dispatchEvent(new CustomEvent("il:mirror-state", { detail: d }));
            break;
          case "mirror.audio":
            // Audio started (rate) or stopped/failed (reason) on the phone —
            // the server reacts by itself; this is for the Mirror page UI.
            window.dispatchEvent(new CustomEvent("il:mirror-audio", { detail: d }));
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
    case "music":
      pageEl = <Music />;
      break;
    case "contacts":
      pageEl = <Contacts />;
      break;
    case "calls":
      pageEl = <Calls />;
      break;
    case "mirror":
      pageEl = <Mirror />;
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
      <main
        className={`main ${connected ? "" : "offline"} ${music ? "has-player" : ""} ${
          inCall ? "has-call" : ""
        }`}
      >
        {!connected && status?.paired && (
          <div className="offline-banner">
            <span className="dot" /> Phone disconnected — waiting for it to reconnect…
          </div>
        )}
        {pageEl}
        {/* Outside the switch so browsing away mid-track doesn't stop it. */}
        <PlayerBar />
        {/* Same reason: an active call must not depend on which page is open. */}
        <CallBar />
      </main>
      <Toasts />
      <QrModal />
    </div>
  );
}
