import { useCallback, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import * as api from "../api";
import type { MusicSort } from "../api";
import type { MediaItem } from "../types";
import { extendMusicQueue, playMusic, toast, useStore } from "../store";

const PAGE = 60;

function fmtDur(ms?: number) {
  if (!ms || ms <= 0) return "–:––";
  const total = Math.round(ms / 1000);
  return `${Math.floor(total / 60)}:${(total % 60).toString().padStart(2, "0")}`;
}

/**
 * A column header that re-orders the list when clicked.
 *
 * It stays a `<span>` because `.track-head` aligns its first and last cells by
 * element type, and putting the `<button>` inside keeps that layout intact
 * while giving the interaction a real control. Clicking the column you are
 * already sorted by reverses it — how every table anyone has used behaves.
 */
function Th(props: {
  field: MusicSort;
  active: MusicSort;
  dir: "asc" | "desc";
  onPick: (f: MusicSort) => void;
  children: ReactNode;
}) {
  const on = props.active === props.field;
  return (
    <span>
      <button
        type="button"
        className={`th${on ? " on" : ""}`}
        onClick={() => props.onPick(props.field)}
        title={
          on
            ? `${props.children} — click to reverse`
            : `Sort by ${String(props.children).toLowerCase()}`
        }
      >
        {props.children}
        {on ? (props.dir === "asc" ? " ▲" : " ▼") : ""}
      </button>
    </span>
  );
}

/**
 * The phone's music library, browsed and played back through the laptop.
 *
 * Search runs on the phone rather than over the loaded list: with ~5,000
 * tracks there is no page here big enough to filter locally, and shipping the
 * whole library to find three matches would be the wrong trade. Sorting is
 * the same argument — the phone owns the ordering (and the `ORDER BY` it
 * builds), so this page just says which column and which direction.
 */
export default function Music() {
  const connected = useStore((s) => s.connected);
  const nowPlaying = useStore((s) => s.nowPlaying);
  const music = useStore((s) => s.music);

  const [items, setItems] = useState<MediaItem[]>([]);
  const [total, setTotal] = useState(0);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [q, setQ] = useState("");
  const [term, setTerm] = useState("");
  const [sort, setSort] = useState<MusicSort>("title");
  const [dir, setDir] = useState<"asc" | "desc">("asc");
  /** The phone handed back a short page — there is nothing after this one. */
  const [exhausted, setExhausted] = useState(false);
  const sentinel = useRef<HTMLDivElement>(null);
  /**
   * Invalidates in-flight pages. A search typed while a page was loading used
   * to land on top of the new one, splicing rows from the old term into the
   * new results — the list looked fine and was wrong.
   */
  const reqToken = useRef(0);

  // Debounced: a request per keystroke against 5,000 rows is rude, and the
  // list flickers anyway while each one lands.
  useEffect(() => {
    const t = setTimeout(() => setTerm(q.trim()), 300);
    return () => clearTimeout(t);
  }, [q]);

  const fetchPage = useCallback(
    async (from: number, reset = false) => {
      // A reset is allowed to start over the top of an in-flight page — that
      // is the whole point of bumping the token below.
      if (!connected || (loading && !reset) || (exhausted && !reset)) return;
      const tok = reqToken.current;
      setLoading(true);
      try {
        const r = await api.mediaList(from, PAGE, "audio", term, sort, dir);
        if (tok !== reqToken.current) return; // a newer search owns the list
        setTotal(r.total ?? 0);
        setItems((prev) => (reset ? r.items : [...prev, ...r.items]));
        setOffset(from + r.items.length);
        // A page shorter than the limit means the collection ran out, not
        // that more is coming. Without this, a total that disagrees with
        // reality (a stale count) keeps the sentinel asking forever.
        if (r.items.length < PAGE) setExhausted(true);
      } catch (e) {
        if (tok === reqToken.current) toast(String(e), "error");
      } finally {
        if (tok === reqToken.current) setLoading(false);
      }
    },
    [connected, term, sort, dir, loading, exhausted],
  );

  // Keep whatever queue the player is holding in step with what's loaded
  // here, so "next" walks past the first page instead of stopping at 60.
  useEffect(() => {
    extendMusicQueue(items);
  }, [items]);

  useEffect(() => {
    // Drop anything still in the air for the previous term — or the previous
    // ordering — before asking again.
    reqToken.current++;
    setItems([]);
    setOffset(0);
    setExhausted(false);
    setLoading(false);
    fetchPage(0, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected, term, sort, dir]);

  // Infinite scroll
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && offset < total && !loading && !exhausted) {
          fetchPage(offset);
        }
      },
      { rootMargin: "600px" },
    );
    io.observe(el);
    return () => io.disconnect();
  }, [offset, total, loading, exhausted, fetchPage]);

  // Is the player walking the same list this page is showing? One has to be a
  // prefix of the other — otherwise paging *this* list forward does nothing
  // for a queue that came from a different search, and the fetch repeats.
  const inSync =
    !!music &&
    music.queue.length > 0 &&
    items.length > 0 &&
    (music.queue.length >= items.length
      ? items.every((s, i) => music.queue[i]?.id === s.id)
      : music.queue.every((s, i) => items[i]?.id === s.id));

  // Keep the queue ahead of the player. Without this, "next" hits a wall the
  // moment it reaches the end of the first loaded page while 4,000 more songs
  // sit untouched on the phone.
  useEffect(() => {
    if (!music || !inSync || exhausted || loading || offset >= total) return;
    if (music.queue.length - music.index <= 20) fetchPage(offset);
  }, [music, inSync, exhausted, loading, offset, total, fetchPage]);

  const start = (index: number) => playMusic(items, index);

  /**
   * Pick a column, or reverse it if it's already the ordering. Re-sorting
   * rebuilds the list from page 0 (see the effect above), which is the only
   * honest thing to do: the phone owns the `ORDER BY`, so a new order makes
   * every already-loaded row's position meaningless.
   */
  const chooseSort = (field: MusicSort) => {
    if (field === sort) {
      setDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSort(field);
      setDir("asc");
    }
  };

  return (
    <div className="page">
      <header className="page-head">
        <h1>Music</h1>
        <div className="row gap">
          <input
            className="music-search"
            placeholder="Search title, artist or album…"
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
          <button
            className="btn"
            disabled={!connected || items.length === 0}
            onClick={() => start(0)}
          >
            ▶ Play all
          </button>
          <button
            className="btn"
            disabled={!connected || items.length === 0}
            onClick={() => start(Math.floor(Math.random() * items.length))}
          >
            🔀 Random
          </button>
        </div>
      </header>

      {!connected ? (
        <div className="empty">
          <div className="empty-icon">🎵</div>
          <p className="muted">Phone offline</p>
        </div>
      ) : items.length === 0 && !loading ? (
        <div className="empty">
          <div className="empty-icon">🎵</div>
          <p className="muted">
            {term ? `Nothing on the phone matches “${term}”.` : "No music found on the phone"}
          </p>
          {!term && (
            <p className="muted">
              Grant the <b>Media</b> permission on the phone (InterLink app) to see your library.
            </p>
          )}
        </div>
      ) : (
        <>
          <div className="muted" style={{ marginBottom: 10 }}>
            {term ? `${total} match${total === 1 ? "" : "es"} for “${term}”` : `${total} songs on phone`}
          </div>
          <div className="track-head">
            <span>#</span>
            <Th field="title" active={sort} dir={dir} onPick={chooseSort}>
              Title
            </Th>
            <Th field="artist" active={sort} dir={dir} onPick={chooseSort}>
              Artist
            </Th>
            <Th field="album" active={sort} dir={dir} onPick={chooseSort}>
              Album
            </Th>
            <Th field="duration" active={sort} dir={dir} onPick={chooseSort}>
              ⏱
            </Th>
          </div>
          <div className="track-list">
            {items.map((m, i) => {
              const cur = nowPlaying?.id === m.id;
              return (
                <div
                  key={m.id}
                  className={`track ${cur ? "cur" : ""}`}
                  onClick={() => start(i)}
                >
                  <span className="track-no">{cur ? (nowPlaying.playing ? "▶" : "❚❚") : i + 1}</span>
                  <span className="track-title" title={m.title ?? m.name}>
                    {m.title ?? m.name}
                  </span>
                  <span className="track-artist" title={m.artist}>
                    {m.artist || "—"}
                  </span>
                  <span className="track-album" title={m.album}>
                    {m.album || "—"}
                  </span>
                  <span className="track-dur">{fmtDur(m.dur)}</span>
                </div>
              );
            })}
          </div>
          <div ref={sentinel} />
          {loading && <div className="muted pad">Loading…</div>}
        </>
      )}

      <div className="page-foot">
        <span className="muted">
          Playback streams straight off the phone — nothing is copied into{" "}
          <b>~/InterLink/Music</b>.
        </span>
      </div>
    </div>
  );
}
