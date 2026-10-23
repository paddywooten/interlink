import { setState, useStore } from "../store";

const items = [
  { id: "dashboard", label: "Dashboard", icon: "◉" },
  { id: "messages", label: "Messages", icon: "✉" },
  { id: "notifications", label: "Notifications", icon: "🔔" },
  { id: "gallery", label: "Gallery", icon: "🖼" },
  { id: "calls", label: "Calls", icon: "☎" },
  { id: "settings", label: "Settings", icon: "⚙" },
];

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
      </div>
    </aside>
  );
}
