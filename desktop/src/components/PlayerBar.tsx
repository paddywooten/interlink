import { useCallback, useEffect, useRef, useState } from "react";
import type { MediaItem } from "../types";
import { playMusic, setState, toast, useStore } from "../store";
import { mediaStreamUrl } from "../api";

function fmtTime(s: number) {
  if (!Number.isFinite(s) || s <= 0) return "0:00";
  const m = Math.floor(s / 60);
  return `${m}:${Math.floor(s % 60)
    .toString()
    .padStart(2, "0")}`;
}

/**
 * The always-on player: streams a track straight off the phone and plays it.
 *
 * It lives at the app level rather than inside the Music page so that
 * browsing elsewhere — or going back to Messages mid-song — doesn't stop the
 * music.
 *
 * Playback asks the phone for a URL rather than for a file. The previous
 * arrangement downloaded each track into `~/InterLink/Music` and then played
 * it back through Tauri's `asset://` scheme, which had two problems: WebKitGTK
 * rejects that scheme on media elements outright (`MEDIA_ERR_SRC_NOT_SUPPORTED`),
 * so nothing played at all — and even had it worked, the first note would have
 * waited on a 7 MB transfer. A plain HTTP URL with range support starts
 * decoding from the first block, seeks with a `Range` header, and writes
 * nothing to disk.
 *
 * The RPC that fetches the URL is a millisecond-scale round trip, not a
 * download, so the `play()` that follows still sits inside the window of user
 * activation WebKit requires — which is why autoplay is normally refused now
 * only when the OS is being strict, not every time.
 */
export default function PlayerBar() {
  const cmd = useStore((s) => s.music);

  const audioRef = useRef<HTMLAudioElement | null>(null);
  /** Bumped per load so a slow response can't overwrite a newer click. */
  const tokenRef = useRef(0);

  const [queue, setQueue] = useState<MediaItem[]>([]);
  const [index, setIndex] = useState(-1);
  const [src, setSrc] = useState<string | null>(null);
  const [nonce, setNonce] = useState(0);
  const [loading, setLoading] = useState(false);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolume] = useState(0.9);
  /** Non-fatal, shown in the bar: blocked autoplay, unreachable phone, bad file. */
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
    // while the next one's URL is being resolved, under a bar that already
    // says something else.
    audioRef.current?.pause();
    setPlaying(false);
    setProgress(0);
    // MediaStore reports milliseconds; the element counts in seconds.
    setDuration((s.dur ?? 0) / 1000);
    setLoading(true);
    setHint(null);
    mediaStreamUrl(s.id)
      .then((url) => {
        if (tok !== tokenRef.current) return; // a newer click already won
        setSrc(url);
        // Bumped alongside `src` so replaying a track that is already
        // buffered still reloads it — an unchanged src string would leave
        // the element exactly where it was and the click would look dead.
        setNonce((n) => n + 1);
      })
      .catch((e) => {
        if (tok !== tokenRef.current) return;
        console.error(`[music] could not get a stream url for ${s.id}: ${String(e)}`);
        setLoading(false);
        setHint("Could not reach the phone");
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
      // WebKitGTK gates play() on a user gesture. Getting here is usually
      // still inside one — resolving the URL is a round trip, not a download
      // — but if the OS is strict about it, say so rather than sitting
      // there silently paused.
      () => setHint("Press play to start"),
    );
  }, [src, nonce]);

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
    // Same path as a click from the list: URL, errors and progress all
    // behave identically however a track was chosen.
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
    // MediaError codes: 1 aborted, 2 network, 3 decode, 4 unsupported source.
    // Worth logging because it is the one failure the bar cannot describe: a
    // 2 means the phone stopped answering partway through, a 3 means the bytes
    // arrived and the webview refused the codec, and neither reads differently
    // on screen. Vite relays console.error into the dev log, so this is how
    // the failure is told apart without a debugger attached to the webview.
    const err = audioRef.current?.error;
    console.error(
      `[music] media error ${err?.code ?? "?"}: ${err?.message || "no message"}` +
        ` (src=${src})`,
    );
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
              ? "Buffering…"
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
