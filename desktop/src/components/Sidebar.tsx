import { setState, useStore } from "../store";

const items = [
  { id: "dashboard", label: "Dashboard", icon: "◉" },
  { id: "messages", label: "Messages", icon: "✉" },
  { id: "notifications", label: "Notifications", icon: "🔔" },
  { id: "gallery", label: "Gallery", icon: "🖼" },
  { id: "music", label: "Music", icon: "♪" },
  { id: "contacts", label: "Contacts", icon: "👥" },
  { id: "calls", label: "Calls", icon: "☎" },
  { id: "mirror", label: "Mirror", icon: "📱" },
  { id: "settings", label: "Settings", icon: "⚙" },
];

/** Phone battery, if it has reported one (see device.battery). */
function Battery({ level, charging }: { level: number; charging?: boolean }) {
  const pct = Math.round(Math.max(0, Math.min(1, level)) * 100);
  const cls = pct <= 15 ? "crit" : pct <= 30 ? "low" : "";
  return (
    <div className="battery" title={`Phone battery ${pct}%`}>
      <div className="battery-bar">
        <div className={`battery-fill ${cls}`} style={{ width: `${pct}%` }} />
      </div>
      <span>
        {pct}%{charging ? " ⚡" : ""}
      </span>
    </div>
  );
}

export default function Sidebar() {
  const page = useStore((s) => s.page);
  const connected = useStore((s) => s.connected);
  const status = useStore((s) => s.status);
  const notifCount = useStore((s) => s.notifications.length);

  return (
    <aside className="sidebar">
      <div className="brand">
        <span className="brand-logo">⇄</span>
        <span className="brand-name">InterLink</span>
      </div>
      <nav>
        {items.map((it) => (
          <button
            key={it.id}
            className={`nav-item ${page === it.id ? "active" : ""}`}
            onClick={() => setState({ page: it.id })}
          >
            <span className="nav-icon">{it.icon}</span>
            <span>{it.label}</span>
            {it.id === "notifications" && notifCount > 0 && (
              <span className="badge">{notifCount > 99 ? "99+" : notifCount}</span>
            )}
          </button>
        ))}
      </nav>
      <div className="sidebar-foot">
        <div className={`pill ${connected ? "on" : "off"}`}>
          <span className="dot" />
          {connected ? "Connected" : status?.paired ? "Waiting" : "Not paired"}
        </div>
        {status?.peer?.name && <div className="peer-name">{status.peer.name}</div>}
        {connected && (
          <div className="row gap" style={{ alignItems: "center" }}>
            {status?.transport && (
              <span className={`conn-badge ${status.transport}`}>
                {status.transport === "usb" ? "🔌 USB" : "📶 WiFi"}
              </span>
            )}
            {status?.battery && (
              <Battery level={status.battery.level} charging={status.battery.charging} />
            )}
          </div>
        )}
      </div>
    </aside>
  );
}
