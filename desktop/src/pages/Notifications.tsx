import { useEffect, useState } from "react";
import * as api from "../api";
import type { NotifItem } from "../types";
import { removeNotification, setState, toast, useStore } from "../store";

/**
 * Thumbnail data URLs by notification key.
 *
 * Kept out of the store on purpose: the attachment bytes are large, the store
 * holds 200 notifications, and only the rows on screen ever ask for one.
 */
const thumbs = new Map<string, string>();

function timeAgo(ts: number) {
  const s = Math.floor((Date.now() - ts) / 1000);
  if (s < 60) return "now";
  if (s < 3600) return `${Math.floor(s / 60)}m`;
  if (s < 86400) return `${Math.floor(s / 3600)}h`;
  return `${Math.floor(s / 86400)}d`;
}

/**
 * A picture that arrived with the notification.
 *
 * Notifications carrying images are usually cancelled on the phone within
 * seconds — that is exactly why the picture is mirrored with the notification
 * rather than fetched later. Downloading writes it into the download folder
 * under the app and title, so it is findable afterwards.
 */
function NotifAttachment({ n }: { n: NotifItem }) {
  const [src, setSrc] = useState<string | null>(() => thumbs.get(n.key) ?? null);

  useEffect(() => {
    if (src) return;
    let alive = true;
    api
      .notifImage(n.key)
      .then((r) => {
        const url = `data:${r.mime};base64,${r.data}`;
        if (thumbs.size > 100) thumbs.clear();
        thumbs.set(n.key, url);
        if (alive) setSrc(url);
      })
      .catch(() => {
        // Offline or already evicted; the Download button still reports why.
        if (alive) setSrc(null);
      });
    return () => {
      alive = false;
    };
  }, [n.key, src]);

  const save = async () => {
    try {
      const name = `${n.app || n.pkg} ${n.title}`.trim();
      const path = await api.notifSaveImage(n.key, name);
      toast(`Saved to ${path}`, "success");
    } catch (e) {
      toast(String(e), "error");
    }
  };

  return (
    <div className="notif-attachment">
      {src ? (
        <img src={src} alt="" loading="lazy" />
      ) : (
        <div className="notif-attachment-pending">Picture</div>
      )}
      <button className="btn tiny ghost" onClick={save}>
        Download
      </button>
    </div>
  );
}

export default function Notifications() {
  const connected = useStore((s) => s.connected);
  const live = useStore((s) => s.notifications);
  const [history, setHistory] = useState<NotifItem[]>([]);
  const [tab, setTab] = useState<"live" | "history">("live");
  const [replyFor, setReplyFor] = useState<string | null>(null);
  const [replyText, setReplyText] = useState("");

  const loadHistory = async () => {
    if (!connected) return;
    try {
      setHistory(await api.notifHistory(80));
    } catch (e) {
      console.warn("notif.history:", e);
    }
  };

  useEffect(() => {
    if (tab === "history") loadHistory();
    const h = () => tab === "history" && loadHistory();
    window.addEventListener("il:sms-changed", h);
    return () => window.removeEventListener("il:sms-changed", h);
  }, [tab, connected]);

  const items = tab === "live" ? live : history;

  const act = async (n: NotifItem, token: string, reply?: string) => {
    try {
      await api.notifAction(n.key, token, reply);
      if (tab === "live") removeNotification(n.key);
      toast(reply ? "Reply sent" : "Action performed", "success");
      setReplyFor(null);
      setReplyText("");
      if (tab === "history") loadHistory();
    } catch (e) {
      toast(String(e), "error");
    }
  };

  const dismiss = async (n: NotifItem) => {
    removeNotification(n.key);
    try {
      await api.notifDismiss(n.key);
    } catch {
      /* phone offline */
    }
    if (tab === "history") loadHistory();
  };

  return (
    <div className="page">
      <header className="page-head">
        <h1>Notifications</h1>
        <div className="tabs">
          <button className={`tab ${tab === "live" ? "on" : ""}`} onClick={() => setTab("live")}>
            Live <span className="badge">{live.length}</span>
          </button>
          <button
            className={`tab ${tab === "history" ? "on" : ""}`}
            onClick={() => {
              setTab("history");
              loadHistory();
            }}
          >
            History
          </button>
        </div>
      </header>

      {items.length === 0 && (
        <div className="empty">
          <div className="empty-icon">🔔</div>
          <p className="muted">
            {connected
              ? tab === "live"
                ? "No notifications yet — they will appear here in real time."
                : "No history yet."
              : "Phone offline"}
          </p>
        </div>
      )}

      <div className="notif-list">
        {items.map((n) => {
          // The typed reply has to be delivered to whichever action carries
          // the RemoteInput — sending it against an arbitrary index (or none)
          // leaves the phone with nothing to hand to WhatsApp.
          const replyToken = (n.actions ?? []).find((a) => a.reply)?.token ?? "";
          return (
            <div key={n.key + (n.when ?? 0)} className={`notif ${n.ongoing ? "ongoing" : ""}`}>
            <div className="notif-avatar">
              {n.icon ? (
                <img className="notif-logo" src={`data:image/png;base64,${n.icon}`} alt="" />
              ) : (
                (n.app || "?").slice(0, 1).toUpperCase()
              )}
            </div>
            <div className="notif-main">
              <div className="notif-top">
                <span className="notif-app">{n.app}</span>
                <span className="notif-time">{timeAgo(n.when || n.ts || Date.now())}</span>
              </div>
              <div className="notif-title">{n.title}</div>
              {n.text && <div className="notif-text">{n.bigText || n.text}</div>}
              {n.hasImage && <NotifAttachment n={n} />}
              <div className="notif-actions">
                {n.hasReply && (
                  <button className="btn tiny" onClick={() => setReplyFor(replyFor === n.key ? null : n.key)}>
                    Reply
                  </button>
                )}
                {(n.actions ?? [])
                  // The typed Reply box below already covers the action that
                  // accepts text; offering it again here would fire that
                  // action with no text, which does nothing useful.
                  .filter((a) => !a.reply)
                  .map((a) => (
                    <button key={a.token} className="btn tiny ghost" onClick={() => act(n, a.token)}>
                      {a.label}
                    </button>
                  ))}
                {!n.ongoing && (
                  <button className="btn tiny ghost" onClick={() => dismiss(n)}>
                    Dismiss
                  </button>
                )}
              </div>
              {replyFor === n.key && (
                <div className="reply-row">
                  <input
                    className="input"
                    placeholder="Write a reply…"
                    value={replyText}
                    autoFocus
                    onChange={(e) => setReplyText(e.target.value)}
                    onKeyDown={(e) => {
                      if (e.key === "Enter" && replyText.trim()) {
                        act(n, replyToken, replyText.trim());
                      }
                    }}
                  />
                  <button
                    className="btn primary tiny"
                    disabled={!replyText.trim()}
                    onClick={() => act(n, replyToken, replyText.trim())}
                  >
                    Send
                  </button>
                </div>
              )}
            </div>
          </div>
          );
        })}
      </div>

      <div className="page-foot">
        <button
          className="btn ghost"
          disabled={!connected || live.length === 0}
          onClick={() => {
            setState({ notifications: [] });
            toast("Cleared local list", "info");
          }}
        >
          Clear list
        </button>
        <span className="muted">
          Live notifications mirror your phone with reply support (WhatsApp, SMS, etc.)
        </span>
      </div>
    </div>
  );
}
