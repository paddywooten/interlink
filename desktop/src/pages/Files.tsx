import { useCallback, useEffect, useRef, useState } from "react";
import * as api from "../api";
import type { FileItem, FileList } from "../types";
import { addRecentFile, toast, useStore } from "../store";

function fmtSize(n: number) {
  if (n > 1024 * 1024 * 1024) return `${(n / 1024 ** 3).toFixed(1)} GB`;
  if (n > 1024 * 1024) return `${(n / 1024 ** 2).toFixed(1)} MB`;
  if (n > 1024) return `${(n / 1024).toFixed(0)} KB`;
  return `${n} B`;
}

function glyph(f: FileItem) {
  if (f.dir) return "📁";
  if (f.mime.startsWith("image/")) return "🖼";
  if (f.mime.startsWith("video/")) return "▶";
  if (f.mime.startsWith("audio/")) return "♪";
  if (f.mime.includes("pdf")) return "📕";
  return "📄";
}

export default function Files() {
  const connected = useStore((s) => s.connected);
  const [path, setPath] = useState("");
  const [query, setQuery] = useState("");
  const [search, setSearch] = useState("");
  const [data, setData] = useState<FileList | null>(null);
  const [loading, setLoading] = useState(false);
  const [needGrant, setNeedGrant] = useState(false);
  const [reload, setReload] = useState(0);
  const [saving, setSaving] = useState<string | null>(null);
  const [preparing, setPreparing] = useState<string | null>(null);
  const draggedPaths = useRef<Record<string, string>>({});
  const dragBusy = useRef(false);

  const load = useCallback(async () => {
    if (!connected) {
      setData(null);
      return;
    }
    setLoading(true);
    setNeedGrant(false);
    try {
      setData(await api.fileList(path, search || undefined));
    } catch (e) {
      const msg = String(e);
      if (msg.includes("need_grant")) setNeedGrant(true);
      else toast(msg, "error");
    } finally {
      setLoading(false);
    }
  }, [connected, path, search]);

  useEffect(() => {
    load();
  }, [load, reload]);

  // The folder picker finishes on the phone; App turns file.granted into this.
  useEffect(() => {
    const changed = () => {
      setPath("");
      setSearch("");
      setQuery("");
      setReload((n) => n + 1);
    };
    window.addEventListener("il:files-changed", changed);
    return () => window.removeEventListener("il:files-changed", changed);
  }, []);

  const grant = async () => {
    try {
      await api.fileGrant();
      toast("Choose a folder on the phone to browse its files", "info");
    } catch (e) {
      toast(String(e), "error");
    }
  };

  const upload = async () => {
    try {
      const paths = await api.pickFiles();
      if (paths.length === 0) return;
      for (const p of paths) await api.fileSend(p);
      toast(`Sent ${paths.length} file${paths.length === 1 ? "" : "s"} to phone`, "success");
      setReload((n) => n + 1);
    } catch (e) {
      toast(String(e), "error");
    }
  };

  const download = async (f: FileItem) => {
    if (api.isWeb) {
      const a = document.createElement("a");
      a.href = api.fileDownloadUrl(f.id, f.name);
      a.download = f.name;
      document.body.appendChild(a);
      a.click();
      a.remove();
      toast(`Downloading ${f.name}…`, "info");
      return;
    }
    setSaving(f.id);
    try {
      const r = await api.fileDownload(f.id, f.name);
      addRecentFile(f.name, r.path);
      toast(`Saved to ${r.path}`, "success");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setSaving(null);
    }
  };

  const dragOut = async (f: FileItem) => {
    if (dragBusy.current || f.dir) return;
    dragBusy.current = true;
    setPreparing(f.id);
    try {
      const r = await api.fileDrag(f.id, f.name, draggedPaths.current[f.id], f.size);
      if (r.path) draggedPaths.current[f.id] = r.path;
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setPreparing(null);
      setTimeout(() => {
        dragBusy.current = false;
      }, 1500);
    }
  };

  const go = (p: string) => {
    setPath(p);
    setSearch("");
    setQuery("");
  };

  return (
    <div className="page">
      <header className="page-head">
        <div>
          <h1>Files</h1>
          <div className="muted">
            {data ? `${data.root}${path ? `/${path}` : ""}` : "Phone files"}
          </div>
        </div>
        <div className="row gap">
          <button className="btn" onClick={grant} disabled={!connected}>
            📂 Choose phone folder
          </button>
          <button className="btn" onClick={upload} disabled={!connected}>
            ↑ Send files to phone
          </button>
          <button className="btn ghost" onClick={() => setReload((n) => n + 1)} disabled={!connected}>
            ↻ Refresh
          </button>
        </div>
      </header>

      <div className="row gap" style={{ marginBottom: 14 }}>
        <input
          className="file-search"
          value={query}
          placeholder="Search this folder"
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") setSearch(query.trim());
          }}
        />
        <button className="btn" onClick={() => setSearch(query.trim())}>Search</button>
        {search && (
          <button className="btn ghost" onClick={() => { setSearch(""); setQuery(""); }}>
            Clear
          </button>
        )}
      </div>

      {data && data.crumbs.length > 0 && (
        <div className="crumbs">
          <button className="crumb" onClick={() => go("")}>{data.root}</button>
          {data.crumbs.map((c) => (
            <span key={c.path} className="crumb-wrap">
              <span className="muted"> / </span>
              <button className="crumb" onClick={() => go(c.path)}>{c.name}</button>
            </span>
          ))}
        </div>
      )}

      {!connected ? (
        <div className="empty">
          <div className="empty-icon">📂</div>
          <p className="muted">Phone offline</p>
        </div>
      ) : needGrant ? (
        <div className="empty">
          <div className="empty-icon">🔓</div>
          <p className="muted">
            Android will not let InterLink list all files yet. Choose a folder on the phone
            (Download, Documents, or internal storage) and it will appear here.
          </p>
          <button className="btn primary" onClick={grant}>Choose folder on phone</button>
        </div>
      ) : loading && !data ? (
        <div className="empty"><div className="empty-icon">⏳</div><p className="muted">Loading…</p></div>
      ) : !data || data.items.length === 0 ? (
        <div className="empty">
          <div className="empty-icon">📂</div>
          <p className="muted">{search ? "No matching files" : "This folder is empty"}</p>
          {!data?.granted && (
            <button className="btn" onClick={grant}>Browse a phone folder instead</button>
          )}
        </div>
      ) : (
        <>
          <div className="muted" style={{ marginBottom: 8 }}>
            {data.total} item{data.total === 1 ? "" : "s"}
          </div>
          <div className="file-list">
            {data.items.map((f) => (
              <div
                key={f.id}
                className={`file-row ${preparing === f.id ? "preparing" : ""}`}
                draggable={!api.isWeb && !f.dir}
                title={
                  f.dir
                    ? "Open folder"
                    : api.isWeb
                      ? "Download to this computer"
                      : "Drag out to your desktop, or press Download"
                }
                onClick={() => f.dir && go(f.path ?? "")}
                onDragStart={(e) => {
                  if (api.isWeb || f.dir) return;
                  e.preventDefault();
                  dragOut(f);
                }}
              >
                <span className="file-glyph">{glyph(f)}</span>
                <span className="file-name">{f.name}</span>
                <span className="file-meta muted">{fmtSize(f.size)}</span>
                <span className="file-meta muted">
                  {f.date ? new Date(f.date).toLocaleDateString() : "—"}
                </span>
                {!f.dir && (
                  <button
                    className="btn tiny"
                    onClick={(e) => {
                      e.stopPropagation();
                      download(f);
                    }}
                    disabled={saving === f.id}
                  >
                    {saving === f.id ? "Saving…" : api.isWeb ? "Download" : "💾 Save"}
                  </button>
                )}
                {preparing === f.id && <div className="file-drag-overlay">Copying…</div>}
              </div>
            ))}
          </div>
        </>
      )}

      <div className="page-foot">
        <span className="muted">
          {api.isWeb ? (
            <>Drop files anywhere in this page to copy them to the phone; downloads use your browser.</>
          ) : (
            <>
              Drop files anywhere in the window to copy them to the phone. Drag phone files out
              to copy them to your desktop. Saved files land in <b>~/InterLink</b>.
            </>
          )}
        </span>
      </div>
    </div>
  );
}
