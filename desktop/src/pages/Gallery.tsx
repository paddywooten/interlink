import { useCallback, useEffect, useRef, useState } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import * as api from "../api";
import type { MediaItem } from "../types";
import { addRecentFile, toast, useStore } from "../store";

const PAGE = 60;

function fmtSize(n: number) {
  if (n > 1024 * 1024 * 1024) return `${(n / 1024 ** 3).toFixed(1)} GB`;
  if (n > 1024 * 1024) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  return `${(n / 1024).toFixed(0)} KB`;
}

export default function Gallery() {
  const connected = useStore((s) => s.connected);
  const [items, setItems] = useState<MediaItem[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [type, setType] = useState<"all" | "image" | "video">("all");
  const [thumbs, setThumbs] = useState<Record<string, string>>({});
  const [lightbox, setLightbox] = useState<MediaItem | null>(null);
  const [fullSrc, setFullSrc] = useState<string | null>(null);
  const [videoSrc, setVideoSrc] = useState<string | null>(null);
  const [videoLoading, setVideoLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const sentinel = useRef<HTMLDivElement>(null);
  const thumbTokens = useRef(0);

  const fetchPage = useCallback(
    async (from: number, reset = false) => {
      if (!connected || loading) return;
      setLoading(true);
      try {
        const r = await api.mediaList(from, PAGE, type);
        setTotal(r.total ?? 0);
        setItems((prev) => (reset ? r.items : [...prev, ...r.items]));
        setOffset(from + r.items.length);
        // Prime thumbnails for the new page (3 workers). Videos get one too —
        // MediaStore generates them, and without a poster the grid is a wall
        // of identical ▶ placeholders.
        const token = ++thumbTokens.current;
        const pending = r.items.filter(
          (i) => i.mime.startsWith("image/") || i.mime.startsWith("video/"),
        );
        let idx = 0;
        const worker = async () => {
          while (idx < pending.length) {
            const item = pending[idx++];
            try {
              const t = await api.mediaThumb(item.id, 320);
              if (token !== thumbTokens.current) return;
              setThumbs((m) => ({ ...m, [item.id]: `data:${t.mime};base64,${t.data}` }));
            } catch {
              return; // offline mid-load
            }
          }
        };
        await Promise.all([worker(), worker(), worker()]);
      } catch (e) {
        toast(String(e), "error");
      } finally {
        setLoading(false);
      }
    },
    [connected, type, loading],
  );

  useEffect(() => {
    setItems([]);
    setOffset(0);
    setThumbs({});
    fetchPage(0, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected, type]);

  // Infinite scroll
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && offset < total && !loading) {
          fetchPage(offset);
        }
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [offset, total, loading, fetchPage]);

  const openItem = async (item: MediaItem) => {
    setLightbox(item);
    setFullSrc(null);
    setVideoSrc(null);
    setVideoLoading(false);
    if (item.mime.startsWith("image/")) {
      try {
        const t = await api.mediaThumb(item.id, 1600);
        setFullSrc(`data:${t.mime};base64,${t.data}`);
      } catch {
        setFullSrc(thumbs[item.id] ?? null);
      }
    } else if (item.mime.startsWith("video/")) {
      // Pull the file down once, then play it from the asset URL — the
      // webview can't stream over the link protocol, and a 200 MB base64
      // blob would be much worse than a disk read.
      setVideoLoading(true);
      try {
        const r = await api.mediaDownload(item.id, item.name);
        addRecentFile(item.name, r.path);
        setVideoSrc(convertFileSrc(r.path));
      } catch (e) {
        toast(String(e), "error");
      } finally {
        setVideoLoading(false);
      }
    }
  };

  const download = async () => {
    if (!lightbox) return;
    setSaving(true);
    try {
      const r = await api.mediaDownload(lightbox.id, lightbox.name);
      addRecentFile(lightbox.name, r.path);
      toast(`Saved to ${r.path}`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setSaving(false);
    }
  };

  const uploadFiles = async () => {
    try {
      const paths = await api.pickFiles();
      if (paths.length === 0) return;
      for (const path of paths) {
        toast(`Sending ${path.split("/").pop()}…`, "info");
        await api.fileSend(path);
      }
      toast("Sent to phone", "success");
    } catch (e) {
      toast(String(e), "error");
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <h1>Gallery</h1>
        <div className="row gap">
          <div className="tabs">
            {(["all", "image", "video"] as const).map((t) => (
              <button key={t} className={`tab ${type === t ? "on" : ""}`} onClick={() => setType(t)}>
                {t === "all" ? "All" : t === "image" ? "Photos" : "Videos"}
              </button>
            ))}
          </div>
          <button className="btn" onClick={uploadFiles} disabled={!connected}>
            ↑ Send files to phone
          </button>
        </div>
      </header>

      {!connected ? (
        <div className="empty">
          <div className="empty-icon">🖼</div>
          <p className="muted">Phone offline</p>
        </div>
      ) : items.length === 0 && !loading ? (
        <div className="empty">
          <div className="empty-icon">🖼</div>
          <p className="muted">No media found on the phone</p>
        </div>
      ) : (
        <>
          <div className="muted" style={{ marginBottom: 10 }}>
            {total} items on phone
          </div>
          <div className="grid-media">
            {items.map((m) => (
              <div key={m.id} className="media-cell" onClick={() => openItem(m)}>
                {thumbs[m.id] ? (
                  <div className="media-thumb">
                    <img src={thumbs[m.id]} loading="lazy" alt={m.name} />
                    {m.mime.startsWith("video/") && <span className="media-play">▶</span>}
                  </div>
                ) : (
                  <div className="media-ph">
                    <span className="media-glyph">
                      {m.mime.startsWith("video/")
                        ? `▶${m.dur ? ` ${Math.round(m.dur / 1000)}s` : ""}`
                        : "…"}
                    </span>
                  </div>
                )}
                <div className="media-size">{fmtSize(m.size)}</div>
              </div>
            ))}
          </div>
          <div ref={sentinel} />
          {loading && <div className="muted pad">Loading…</div>}
        </>
      )}

      {lightbox && (
        <div className="modal-back" onClick={() => setLightbox(null)}>
          <div className="lightbox" onClick={(e) => e.stopPropagation()}>
            <div className="lightbox-bar">
              <div>
                <b>{lightbox.name}</b>
                <div className="muted">
                  {fmtSize(lightbox.size)} · {new Date(lightbox.date).toLocaleString()}
                </div>
              </div>
              <div className="row gap">
                <button className="btn primary" onClick={download} disabled={saving}>
                  {saving ? "Saving…" : "💾 Save to laptop"}
                </button>
                <button className="icon-btn" onClick={() => setLightbox(null)}>
                  ✕
                </button>
              </div>
            </div>
            <div className="lightbox-img">
              {lightbox.mime.startsWith("image/") ? (
                <img src={fullSrc ?? thumbs[lightbox.id] ?? ""} alt={lightbox.name} className="full-thumb" />
              ) : videoSrc ? (
                <video src={videoSrc} controls autoPlay playsInline className="video-full" />
              ) : videoLoading ? (
                <div className="empty">
                  <div className="empty-icon">⏳</div>
                  <p className="muted">
                    Downloading video to play ({fmtSize(lightbox.size)})…
                  </p>
                </div>
              ) : (
                <div className="empty">
                  <div className="empty-icon">▶</div>
                  <p className="muted">Could not load this video — try saving it instead.</p>
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      <div className="page-foot">
        <span className="muted">
          Files land in <b>~/InterLink</b> — change the folder in Settings.
        </span>
      </div>
    </div>
  );
}
