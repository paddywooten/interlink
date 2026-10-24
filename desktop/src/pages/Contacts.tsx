import { useEffect, useMemo, useState } from "react";
import * as api from "../api";
import type { Contact } from "../types";
import { toast, useStore } from "../store";

/**
 * The phone's whole address book, loaded once and filtered locally — a
 * per-keystroke round trip to the phone makes typing feel like the app is
 * broken, and a 2000-contact list is only a few hundred KB.
 */
export default function Contacts() {
  const connected = useStore((s) => s.connected);
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [q, setQ] = useState("");
  const [loading, setLoading] = useState(false);
  const [dialing, setDialing] = useState<string | null>(null);

  useEffect(() => {
    if (!connected) {
      setContacts([]);
      return;
    }
    let alive = true;
    setLoading(true);
    api
      .contactsQuery("", 5000)
      .then((r) => alive && setContacts(r))
      .catch((e) => alive && toast(String(e), "error"))
      .finally(() => alive && setLoading(false));
    return () => {
      alive = false;
    };
  }, [connected]);

  const filtered = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return contacts;
    return contacts.filter(
      (c) =>
        c.name.toLowerCase().includes(needle) ||
        c.phones.some((p) => p.toLowerCase().includes(needle)),
    );
  }, [contacts, q]);

  const call = async (phone: string) => {
    setDialing(phone);
    try {
      await api.callDial(phone);
      toast(`Calling ${phone}…`, "info");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setDialing(null);
    }
  };

  const copy = async (phone: string) => {
    try {
      await navigator.clipboard.writeText(phone);
      toast("Number copied", "success");
    } catch {
      toast(phone, "info");
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <h1>Contacts</h1>
        {connected && (
          <input
            className="input"
            style={{ maxWidth: 320 }}
            placeholder="Search name or number…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        )}
      </header>

      {!connected ? (
        <div className="empty">
          <div className="empty-icon">👥</div>
          <p className="muted">Phone offline</p>
        </div>
      ) : loading ? (
        <div className="empty">
          <div className="empty-icon">⏳</div>
          <p className="muted">Loading contacts from phone…</p>
        </div>
      ) : filtered.length === 0 ? (
        <div className="empty">
          <div className="empty-icon">👥</div>
          <p className="muted">
            {contacts.length === 0
              ? "No contacts on the phone (or Contacts permission isn't granted)"
              : `Nothing matches “${q}”`}
          </p>
        </div>
      ) : (
        <>
          <div className="muted" style={{ marginBottom: 10 }}>
            {filtered.length}
            {filtered.length !== contacts.length ? ` of ${contacts.length}` : ""} contacts on
            phone
          </div>
          <div className="thread-list">
            {filtered.map((c) => (
              <div key={c.id} className="thread-item">
                <div className="thread-top">
                  <span className="thread-name">{c.name || "(no name)"}</span>
                  <span className="contact-actions">
                    <button
                      className="btn ghost"
                      disabled={dialing !== null}
                      onClick={() => call(c.phones[0])}
                    >
                      ☎ Call
                    </button>
                  </span>
                </div>
                <div className="contact-phones">
                  {c.phones.map((p) => (
                    <button key={p} className="btn ghost" onClick={() => copy(p)} title="Copy number">
                      {p}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </>
      )}

      <div className="page-foot">
        <span className="muted">
          Read from the phone's Contacts app — grant the Contacts permission on the phone if
          this list is empty.
        </span>
      </div>
    </div>
  );
}
