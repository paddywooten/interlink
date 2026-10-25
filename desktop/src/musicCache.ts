import * as api from "./api";

/**
 * media id → path on disk, for tracks already pulled off the phone.
 *
 * Persisted because a song costs real bytes to move: re-fetching a track you
 * played an hour ago would be a noticeable pause for a file that is already
 * sitting in `~/InterLink/Music`. Surviving a restart also means a session
 * restart doesn't cost the library twice.
 *
 * A stale entry (file deleted behind our back) must not wedge the player, so
 * the player drops the entry and fetches again when `<audio>` rejects the
 * source — a bad cache costs one failed load, never a stuck UI.
 */
const KEY = "il.music.paths";

let paths: Record<string, string> = (() => {
  try {
    const raw = localStorage.getItem(KEY);
    const parsed = raw ? JSON.parse(raw) : null;
    return parsed && typeof parsed === "object" ? (parsed as Record<string, string>) : {};
  } catch {
    return {};
  }
})();

function flush() {
  try {
    localStorage.setItem(KEY, JSON.stringify(paths));
  } catch {
    // Private mode or quota: the cache is an optimisation, not state.
  }
}

export function cachedPath(id: string): string | undefined {
  return paths[id];
}

export function rememberPath(id: string, path: string) {
  paths[id] = path;
  flush();
}

export function forgetPath(id: string) {
  delete paths[id];
  flush();
}

/** One download per id at a time — see below. */
const inflight = new Map<string, Promise<string>>();

/**
 * The track's path on the laptop, fetching it off the phone if needed.
 *
 * Concurrent calls for the same id share a single transfer. That matters
 * because the player pre-warm-fetches the next track while the current one
 * plays: press next before that lands and two writers would otherwise race
 * into the same folder, producing `Song (1).mp3` and a second copy of the
 * same bytes.
 */
export function ensurePath(id: string, suggest: string): Promise<string> {
  const hit = cachedPath(id);
  if (hit) return Promise.resolve(hit);
  const running = inflight.get(id);
  if (running) return running;
  const p = api
    .mediaDownload(id, suggest, "Music")
    .then((r) => {
      rememberPath(id, r.path);
      return r.path;
    })
    .finally(() => inflight.delete(id));
  inflight.set(id, p);
  return p;
}
