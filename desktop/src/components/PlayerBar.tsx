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
 * `off` → `all` → `one` → `off`, the cycle every player uses. Only `one` maps
 * onto anything the element gives us for free (`loop`); the rest is decided in
 * `onEnded`.
 */
type Repeat = "off" | "all" | "one";

/** Fisher–Yates over `0..n-1`. */
function shuffledOrder(n: number): number[] {
  const a = Array.from({ length: n }, (_, i) => i);
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
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
  const [repeat, setRepeat] = useState<Repeat>("off");
  const [shuffle, setShuffle] = useState(false);
  /** A→B loop ends, in seconds. Both set *and* B after A to engage. */
  const [pointA, setPointA] = useState<number | null>(null);
  const [pointB, setPointB] = useState<number | null>(null);
  /**
   * Shuffle as a permutation of queue indices rather than "pick something
   * random next". A random pick has no history, so ⏭ wanders and ⏮ has
   * nothing to return to; a permutation gives both directions a real target
   * and only needs extending when another page of the library arrives.
   */
  const orderRef = useRef<number[]>([]);

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
    // A→B belongs to one file. Carrying the points over would jump to a
    // timestamp that may not exist in the next track, and at worst would
    // bounce there forever.
    setPointA(null);
    setPointB(null);
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

  // Repeat-one is the one mode the element implements itself. It is re-applied
  // here rather than inline on the `<audio>` because `load()` in the effect
  // above resets the element, and this runs after it.
  useEffect(() => {
    const a = audioRef.current;
    if (a) a.loop = repeat === "one";
  }, [repeat, src, nonce]);

  // Keep the shuffle plan in step with the queue. Rows that arrived since the
  // last page join at the tail; the order already laid out is left alone, so
  // paging in 60 more songs doesn't reshuffle the rotation you are halfway
  // through. With shuffle off there is nothing to keep.
  useEffect(() => {
    if (!shuffle) {
      orderRef.current = [];
      return;
    }
    const kept = orderRef.current.filter((i) => i >= 0 && i < queue.length);
    const seen = new Set(kept);
    const fresh: number[] = [];
    for (let i = 0; i < queue.length; i++) if (!seen.has(i)) fresh.push(i);
    for (let i = fresh.length - 1; i > 0; i--) {
      const j = Math.floor(Math.random() * (i + 1));
      [fresh[i], fresh[j]] = [fresh[j], fresh[i]];
    }
    orderRef.current = [...kept, ...fresh];
  }, [shuffle, queue.length]);

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

  const seekTo = (t: number) => {
    const a = audioRef.current;
    if (!a) return;
    a.currentTime = t;
    setProgress(t);
  };

  /**
   * Where `delta` takes us, or null at the end of the line. Under shuffle the
   * answer comes from the permutation, so "previous" retraces what you
   * already heard instead of guessing at a neighbour.
   */
  const stepTarget = (delta: number): number | null => {
    const order = orderRef.current;
    if (shuffle && order.length > 0) {
      const pos = order.indexOf(index);
      if (pos >= 0) return order[pos + delta] ?? null;
      // Not on the plan — the queue was swapped underneath it. Fall through.
    }
    const i = index + delta;
    return i >= 0 && i < queue.length ? i : null;
  };

  /**
   * The head of the play order. Under shuffle that is a *new* permutation, so
   * repeat-all doesn't replay the same sequence every cycle.
   */
  const wrapTarget = (): number => {
    if (shuffle) {
      orderRef.current = shuffledOrder(queue.length);
      return orderRef.current[0] ?? 0;
    }
    return 0;
  };

  const step = (delta: number) => {
    const next = stepTarget(delta);
    if (next != null) {
      // Same path as a click from the list: URL, errors and progress all
      // behave identically however a track was chosen.
      playMusic(queue, next);
      return;
    }
    // At the end with repeat on, "next" wraps rather than doing nothing —
    // that is what keeps the button live on the last track. Backwards stays a
    // dead end on purpose: landing on the final song from the first is
    // disorienting, not useful.
    if (delta > 0 && repeat !== "off") playMusic(queue, wrapTarget());
  };

  /** ±10 s, clamped to the track. */
  const nudge = (d: number) => {
    const a = audioRef.current;
    // While loading, `src` still points at the previous track — seeking it
    // would move a song the bar is no longer showing.
    if (!a || !src || loading) return;
    const max = Number.isFinite(a.duration) && a.duration > 0 ? a.duration : duration;
    seekTo(Math.max(0, Math.min(a.currentTime + d, max)));
  };

  const cycleRepeat = () =>
    setRepeat((r) => (r === "off" ? "all" : r === "all" ? "one" : "off"));

  /**
   * Place or clear one end of the A→B loop. Either end may be set first; the
   * loop engages only once both exist and B sits after A, which is why a
   * backwards pair isn't an error — it simply doesn't loop until it's fixed.
   */
  const togglePoint = (which: "a" | "b") => {
    const a = audioRef.current;
    // While loading, `currentTime` still belongs to the previous track.
    if (!a || !src || loading) return;
    const cur = which === "a" ? pointA : pointB;
    const set = which === "a" ? setPointA : setPointB;
    set(cur != null ? null : a.currentTime);
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

  const canNext = stepTarget(1) != null || repeat !== "off";
  const canPrev = stepTarget(-1) != null;
  const abLoop = pointA != null && pointB != null && pointB > pointA;

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
          if (!a) return;
          // A→B is enforced here rather than with `loop`, because `loop`
          // restarts the *whole* track — useless when B sits thirty seconds
          // in. Rewind first: setting `currentTime` and then reporting it
          // would flicker B's value for a frame.
          if (
            pointA != null &&
            pointB != null &&
            pointB > pointA &&
            a.currentTime >= pointB
          ) {
            a.currentTime = pointA;
            setProgress(pointA);
            return;
          }
          setProgress(a.currentTime);
        }}
        onEnded={() => {
          // `loop` swallows `ended` for repeat-one, so getting here means the
          // track ran out for real: advance if there is anywhere to go, wrap
          // if repeat says so, otherwise stop where we are.
          if (stepTarget(1) != null || repeat !== "off") step(1);
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
            disabled={!canPrev}
            onClick={() => step(-1)}
            title="Previous"
          >
            ⏮
          </button>
          <button
            className="icon-btn"
            disabled={loading}
            onClick={() => nudge(-10)}
            title="Back 10 seconds"
          >
            ⏪
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
            disabled={loading}
            onClick={() => nudge(10)}
            title="Forward 10 seconds"
          >
            ⏩
          </button>
          <button
            className="icon-btn"
            disabled={!canNext}
            onClick={() => step(1)}
            title={canNext ? "Next" : "End of the queue"}
          >
            ⏭
          </button>
          <button
            className={`icon-btn pb-mode${repeat !== "off" ? " on" : ""}`}
            onClick={cycleRepeat}
            title={
              repeat === "off"
                ? "Repeat: off — click to repeat the whole queue"
                : repeat === "all"
                  ? "Repeat: whole queue — click to repeat this track"
                  : "Repeat: this track — click to turn off"
            }
          >
            🔁
            {repeat === "one" && <span className="pb-one">1</span>}
          </button>
          <button
            className={`icon-btn pb-mode${shuffle ? " on" : ""}`}
            onClick={() => setShuffle((s) => !s)}
            title={shuffle ? "Shuffle: on — click to turn off" : "Shuffle: off — click to turn on"}
          >
            🔀
          </button>
          <button
            className={`icon-btn${pointA != null ? " on" : ""}`}
            onClick={() => togglePoint("a")}
            title={
              pointA != null
                ? `Loop start at ${fmtTime(pointA)} — click to clear`
                : "Set the loop start (A)"
            }
          >
            A
          </button>
          <span
            className={`pb-ab${abLoop ? " on" : ""}`}
            aria-hidden
            title={
              pointA != null && pointB != null && pointB > pointA
                ? `Looping ${fmtTime(pointA)} → ${fmtTime(pointB)}`
                : "Set both A and B to loop that stretch"
            }
          >
            ⇄
          </span>
          <button
            className={`icon-btn${pointB != null ? " on" : ""}`}
            onClick={() => togglePoint("b")}
            title={
              pointB != null
                ? `Loop end at ${fmtTime(pointB)} — click to clear`
                : "Set the loop end (B)"
            }
          >
            B
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
