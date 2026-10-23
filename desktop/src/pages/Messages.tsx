import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "../api";
import type { Contact, SmsMessage, SmsThread } from "../types";
import { toast, useStore } from "../store";

function fmtDate(ts: number) {
  const d = new Date(ts);
  const today = new Date();
  if (d.toDateString() === today.toDateString()) {
    return d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }
  return d.toLocaleDateString([], { month: "short", day: "numeric" });
}

export default function Messages() {
  const connected = useStore((s) => s.connected);
  const [threads, setThreads] = useState<SmsThread[]>([]);
  const [active, setActive] = useState<string | null>(null);
  const [activeAddr, setActiveAddr] = useState<string>("");
  const [messages, setMessages] = useState<SmsMessage[]>([]);
  const [draft, setDraft] = useState("");
  const [loading, setLoading] = useState(false);
  const [newMsg, setNewMsg] = useState(false);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [contactQ, setContactQ] = useState("");
  const [sendTo, setSendTo] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);

  const loadThreads = useCallback(async () => {
    if (!connected) return;
    try {
      setThreads(await api.smsThreads());
    } catch (e) {
      console.warn("sms.threads:", e);
    }
  }, [connected]);

  useEffect(() => {
    loadThreads();
    const h = () => loadThreads();
    window.addEventListener("il:sms-changed", h);
    const iv = setInterval(loadThreads, 30_000);
    return () => {
      window.removeEventListener("il:sms-changed", h);
      clearInterval(iv);
    };
  }, [loadThreads]);

  const openThread = async (t: SmsThread) => {
    setActive(t.id);
    setActiveAddr(t.address);
    setNewMsg(false);
    setLoading(true);
    try {
      const msgs = await api.smsList(t.id);
      setMessages(msgs);
      if (t.unread > 0) api.smsRead(t.id);
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setLoading(false);
    }
    setTimeout(() => bottomRef.current?.scrollIntoView({ behavior: "smooth" }), 60);
  };

  // New incoming SMS → refresh active conversation live
  useEffect(() => {
    const h = async (ev: Event) => {
      const d = (ev as CustomEvent).detail;
      if (!active) return;
      if (d.kind === "new" && d.threadId === active) {
        try {
          setMessages(await api.smsList(active));
        } catch {
          /* offline */
        }
      }
      if (d.kind === "sent") {
        try {
          setMessages(await api.smsList(active));
        } catch {
          /* noop */
        }
      }
    };
    window.addEventListener("il:sms-changed", h);
    return () => window.removeEventListener("il:sms-changed", h);
  }, [active]);

  const send = async () => {
    const body = draft.trim();
    if (!body || !connected) return;
    setDraft("");
    try {
      if (newMsg) {
        const target = sendTo.trim();
        if (!target) return;
        await api.smsSend({ address: target, body });
        toast("Sent", "success");
        setNewMsg(false);
        setSendTo("");
        loadThreads();
        return;
      }
      if (!active) return;
      await api.smsSend({ threadId: active, address: activeAddr, body });
      setMessages(await api.smsList(active));
      loadThreads();
    } catch (e) {
      toast(String(e), "error");
      setDraft(body);
    }
  };

  const searchContacts = async (q: string) => {
    setContactQ(q);
    if (q.length < 1) return setContacts([]);
    try {
      setContacts(await api.contactsQuery(q, 12));
    } catch {
      setContacts([]);
    }
  };

  return (
    <div className="page messages-page">
      <header className="page-head">
        <h1>Messages</h1>
        <button className="btn" onClick={() => { setNewMsg(true); setActive(null); setMessages([]); }}>
          + New message
        </button>
      </header>

      <div className="split">
        <div className="thread-list">
          {!connected && <div className="muted pad">Phone offline</div>}
          {newMsg && (
            <div className="new-msg-box">
              <input
                className="input"
                placeholder="Search contact or number…"
                value={contactQ}
                onChange={(e) => searchContacts(e.target.value)}
                autoFocus
              />
              {contacts.map((c) => (
                <button
                  key={c.id}
                  className="thread-item"
                  onClick={() => {
                    setSendTo(c.phones[0] ?? "");
                    setContactQ(c.name);
                    setContacts([]);
                  }}
                >
                  <b>{c.name}</b>
                  <span className="muted mono">{c.phones[0]}</span>
                </button>
              ))}
            </div>
          )}
          {threads.map((t) => (
            <button
              key={t.id}
              className={`thread-item ${active === t.id ? "active" : ""}`}
              onClick={() => openThread(t)}
            >
              <div className="thread-top">
                <span className="thread-name">{t.name || t.address}</span>
                <span className="thread-time">{fmtDate(t.date)}</span>
              </div>
              <div className="thread-snip">
                {t.unread > 0 && <span className="dot blue" />}
                {t.snippet}
              </div>
            </button>
          ))}
          {connected && threads.length === 0 && !newMsg && (
            <div className="muted pad">No conversations found</div>
          )}
        </div>

        <div className="convo">
          {active || newMsg ? (
            <>
              <div className="convo-head">
                {newMsg ? (
                  <input
                    className="input bare"
                    placeholder="+1 555 123 4567"
                    value={sendTo}
                    onChange={(e) => setSendTo(e.target.value)}
                  />
                ) : (
                  <b>{activeAddr}</b>
                )}
              </div>
              <div className="convo-body">
                {loading && <div className="muted pad">Loading…</div>}
                {messages.map((m) => (
                  <div key={m.id} className={`bubble ${m.box === "sent" ? "out" : "in"}`}>
                    <div className="bubble-text">{m.body}</div>
                    <div className="bubble-time">{fmtDate(m.date)}</div>
                  </div>
                ))}
                <div ref={bottomRef} />
              </div>
              <div className="composer">
                <input
                  className="input"
                  placeholder="Type a message…"
                  value={draft}
                  onChange={(e) => setDraft(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && send()}
                  disabled={!connected}
                />
                <button className="btn primary" onClick={send} disabled={!connected || !draft.trim()}>
                  Send
                </button>
              </div>
            </>
          ) : (
            <div className="empty">
              <div className="empty-icon">✉</div>
              <p className="muted">Select a conversation</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
