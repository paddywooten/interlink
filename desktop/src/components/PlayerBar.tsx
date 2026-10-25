import { useCallback, useEffect, useRef, useState } from "react";
import { convertFileSrc } from "@tauri-apps/api/core";
import type { MediaItem } from "../types";
import { playMusic, setState, toast, useStore } from "../store";
import { cachedPath, ensurePath, forgetPath } from "../musicCache";

function fmtTime(s: number) {
  if (!Number.isFinite(s) || s <= 0) return "0:00";
  const m = Math.floor(s / 60);
  return `${m}:${Math.floor(s % 60)
    .toString()
    .padStart(2, "0")}`;
}

/**
 * What the file is called once it lands on the laptop: `Artist - Title.ext`,
 * falling back to the phone's own filename. The composed half is not a
 * filename yet, so path separators and shell-hostile characters come out.
 */
function suggestName(song: MediaItem): string {
  const dot = song.name.lastIndexOf(".");
  const ext = dot > 0 ? song.name.slice(dot) : "";
  const fallback = dot > 0 ? song.name.slice(0, dot) : song.name;
  const stem = [song.artist, song.title].filter(Boolean).join(" - ") || fallback;
  const safe = stem
    .replace(/[/\\:*?"<>|]/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 120);
  return `${safe || "track"}${ext}`;
}

/**
 * The always-on player: pulls a track off the phone over the link, then plays
 * the file that landed in `~/InterLink/Music`.
 *
 * It lives at the app level rather than inside the Music page so that
 * browsing elsewhere — or going back to Messages mid-song — doesn't stop the
 * music. Downloading before playing (rather than streaming) is what the link
 * already does for video, and a 5 MB track crosses it fast enough that the
 * pause reads as buffering, not waiting.
 */
export default function PlayerBar() {
  const cmd = useStore((s) => s.music);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  /** Bumped per load so a slow download can't overwrite a newer click. */
  const tokenRef = useRef(0);
  const retriedRef = useRef<Set<string>>(new Set());

  const [queue, setQueue] = useState<MediaItem[]>([]);
  const [index, setIndex] = useState(-1);
  const [src, setSrc] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  const [loading, setLoading] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(0.9);
  /** Non-fatal, shown in the bar: blocked autoplay, fetch failure, bad file. */
  const [hint, setHint] = useState<string | null>(null);

  const song = index >= 0 ? queue[index] : undefined;

  /**
   * A new command's nonce. Depending on `cmd` itself would be wrong: the
   * queue is extended *in place* as the Music page scrolls more rows in, and
   * treating that as a fresh request would restart the track you're listening
   * to every time a page finished loading.
   */
  const cmdNonce = cmd?.nonce ?? -1;

  const loadTrack = useCallback((s: MediaItem) => {
    const tok = ++tokenRef.current;
    // Stop what's playing now: the previous track would otherwise keep going
    // for the whole of the next download, under a bar that already says
    // something else.
    audioRef.current?.pause();
    setPlaying(false);
    setProgress(0);
    // MediaStore reports milliseconds; the element counts in seconds.
    setDuration((s.dur ?? 0) / 1000);
    setLoading(true);
    setHint(null);
    ensurePath(s.id, suggestName(s))
      .then((path) => {
        if (tok !== tokenRef.current) return; // a newer click already won
        setSrc(convertFileSrc(path));
        // Bumped alongside `src` so replaying a track that is already local
        // still reloads it — an unchanged src string would leave the element
        // exactly where it was and the click would look dead.
        setNonce((n) => n + 1);
      })
      .catch((e) => {
        if (tok !== tokenRef.current) return;
        setLoading(false);
        setHint("Could not fetch this track from the phone");
        toast(String(e), "error");
      });
  }, []);

  // A fresh queue from the Music page — swap it in and start it.
  useEffect(() => {
    if (!cmd) return;
    const s = cmd.queue[cmd.index];
    if (!s) return;
    setQueue(cmd.queue);
    setIndex(cmd.index);
    loadTrack(s);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cmdNonce, loadTrack]);

  // The same queue, grown by a page load. Take it so "next" keeps working —
  // without touching playback.
  useEffect(() => {
    if (cmd && cmd.queue.length > queue.length) setQueue(cmd.queue);
  }, [cmd, queue]);

  // The source just changed — load it and start it.
  useEffect(() => {
    const a = audioRef.current;
    if (!src || !a) return;
    a.load();
    a.play().then(
      () => setHint(null),
      // WebKitGTK gates play() on a user gesture. The click that got us here
      // was one, but the download that followed was async, so this can still
      // be refused — say so rather than sitting there silently paused.
      () => setHint("Press play to start"),
    );
  }, [src, nonce]);

  // Queue the next track while the current one plays, so next/prev is instant.
  useEffect(() => {
    if (!playing) return;
    const next = queue[index + 1];
    if (next) ensurePath(next.id, suggestName(next)).catch(() => undefined);
  }, [playing, index, queue]);

  // What the Music page highlights.
  useEffect(() => {
    setState({ nowPlaying: song ? { id: song.id, playing } : null });
  }, [song, playing]);

  useEffect(() => {
    const a = audioRef.current;
    if (a) a.volume = volume;
  }, [volume, src, song]);

  const toggle = () => {
    const a = audioRef.current;
    // While loading, `src` still points at the previous track — starting it
    // would play something other than what the bar claims.
    if (!a || !src || loading) return;
    if (a.paused) {
      a.play().then(
        () => setHint(null),
        () => setHint("Press play to start"),
      );
    } else {
      a.pause();
    }
  };

  const step = (delta: number) => {
    const i = index + delta;
    if (i < 0 || i >= queue.length) return;
    // Same path as a click from the list: cache, fetch, errors all behave
    // identically however a track was chosen.
    playMusic(queue, i);
  };

  const seekTo = (t: number) => {
    const a = audioRef.current;
    if (!a) return;
    a.currentTime = t;
    setProgress(t);
  };

  const onAudioError = () => {
    if (!src || !song) return;
    // A cached path can outlive its file. Drop it and fetch once more; a
    // second failure means the track itself is the problem, not the cache.
    if (cachedPath(song.id) && !retriedRef.current.has(song.id)) {
      retriedRef.current.add(song.id);
      forgetPath(song.id);
      loadTrack(song);
      return;
    }
    setLoading(false);
    setPlaying(false);
    setHint("This track could not be played");
  };

  if (!song) return null;

  const atEnd = index >= queue.length - 1;

  return (
    <div className="player-bar">
      <audio
        ref={audioRef}
        src={src ?? undefined}
        onLoadedMetadata={() => {
          const a = audioRef.current;
          setLoading(false);
          if (a && Number.isFinite(a.duration) && a.duration > 0) setDuration(a.duration);
        }}
        onCanPlay={() => setLoading(false)}
        onPlay={() => {
          setPlaying(true);
          setHint(null);
        }}
        onPause={() => setPlaying(false)}
        onTimeUpdate={() => {
          const a = audioRef.current;
          if (a) setProgress(a.currentTime);
        }}
        onEnded={() => {
          if (index < queue.length - 1) step(1);
          else setPlaying(false);
        }}
        onError={onAudioError}
      />

      <div className="pb-now">
        <div className="pb-art">♪</div>
        <div className="pb-meta">
          <div className="pb-title" title={song.title ?? song.name}>
            {song.title ?? song.name}
          </div>
          <div className="pb-sub">
            {loading
              ? "Fetching from phone…"
              : hint ??
                ([song.artist, song.album].filter(Boolean).join(" · ") || "Unknown artist")}
          </div>
        </div>
      </div>

      <div className="pb-controls">
        <div className="pb-buttons">
          <button
            className="icon-btn"
            disabled={index <= 0}
            onClick={() => step(-1)}
            title="Previous"
          >
            ⏮
          </button>
          <button
            className="pb-play"
            onClick={toggle}
            disabled={loading}
            title={loading ? "Loading…" : playing ? "Pause" : "Play"}
          >
            {loading ? "…" : playing ? "⏸" : "▶"}
          </button>
          <button
            className="icon-btn"
            disabled={atEnd}
            onClick={() => step(1)}
            title="Next"
          >
            ⏭
          </button>
        </div>
        <div className="pb-seek">
          <span className="pb-time">{fmtTime(progress)}</span>
          <input
            type="range"
            min={0}
            max={Math.max(duration, 0.1)}
            step={0.5}
            disabled={loading}
            value={Math.min(progress, duration || 0.1)}
            onChange={(e) => seekTo(Number(e.target.value))}
          />
          <span className="pb-time">{fmtTime(duration)}</span>
        </div>
      </div>

      <div className="pb-vol">
        <span aria-hidden>🔊</span>
        <input
          type="range"
          min={0}
          max={1}
          step={0.05}
          value={volume}
          onChange={(e) => setVolume(Number(e.target.value))}
        />
      </div>
    </div>
  );
}
