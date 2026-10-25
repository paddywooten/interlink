import { useSyncExternalStore } from "react";
import type {
  CallState,
  MediaItem,
  NotifItem,
  SeenDevice,
  Status,
  Toast,
  Transfer,
} from "./types";

// ——— Tiny global store (no external deps) ———

/**
 * "Play this queue, starting here." A command rather than plain state: the
 * player has to notice *repeats* too (clicking the row you're already
 * playing should restart it), which plain object identity would swallow.
 */
export interface MusicCmd {
  queue: MediaItem[];
  index: number;
  nonce: number;
}

/** What the always-on player is showing — the Music page highlights from this. */
export interface NowPlaying {
  id: string;
  playing: boolean;
}

export interface AppState {
  page: string;
  status: Status | null;
  connected: boolean;
  notifications: NotifItem[];
  call: CallState | null;
  callMuted: boolean;
  audioBroken: string | null;
  /** Why the link is failing (e.g. pairing mismatch) — shown as a banner. */
  linkError: string | null;
  transfers: Record<string, Transfer>;
  toasts: Toast[];
  qrModal: boolean;
  recentFiles: { name: string; path: string; when: number }[];
  /** Devices discovered on the LAN via UDP beacons. */
  devices: SeenDevice[];
  music: MusicCmd | null;
  nowPlaying: NowPlaying | null;
}

let state: AppState = {
  page: "dashboard",
  status: null,
  connected: false,
  notifications: [],
  call: null,
  callMuted: false,
  audioBroken: null,
  linkError: null,
  transfers: {},
  toasts: [],
  qrModal: false,
  recentFiles: [],
  devices: [],
  music: null,
  nowPlaying: null,
};

const listeners = new Set<() => void>();

export function setState(patch: Partial<AppState>) {
  state = { ...state, ...patch };
  listeners.forEach((l) => l());
}

export function getState() {
  return state;
}

export function subscribe(l: () => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

export function useStore<T>(sel: (s: AppState) => T): T {
  return useSyncExternalStore(
    subscribe,
    () => sel(state),
    () => sel(state),
  );
}

let toastId = 0;
export function toast(text: string, kind: Toast["kind"] = "info") {
  const id = ++toastId;
  setState({ toasts: [...getState().toasts, { id, text, kind }] });
  setTimeout(() => {
    setState({ toasts: getState().toasts.filter((t) => t.id !== id) });
  }, 4200);
}

export function addNotification(n: NotifItem) {
  const list = [n, ...getState().notifications.filter((x) => x.key !== n.key)];
  setState({ notifications: list.slice(0, 200) });
}

export function removeNotification(key: string) {
  setState({ notifications: getState().notifications.filter((n) => n.key !== key) });
}

export function upsertTransfer(t: Transfer) {
  setState({ transfers: { ...getState().transfers, [t.tid]: t } });
}

export function addRecentFile(name: string, path: string) {
  setState({
    recentFiles: [{ name, path, when: Date.now() }, ...getState().recentFiles].slice(0, 20),
  });
}

export function setDevices(devices: SeenDevice[]) {
  setState({ devices });
}

/** Hand a queue to the always-on player. Safe to call with an empty queue. */
export function playMusic(queue: MediaItem[], index: number) {
  if (queue.length === 0) return;
  const nonce = (getState().music?.nonce ?? 0) + 1;
  setState({ music: { queue, index, nonce } });
}

/**
 * Grow a live queue when the Music page scrolls another page in.
 *
 * Extend only — a queue is positional, and swapping it out from under a
 * playing track would silently move "next" somewhere else entirely. Requiring
 * the existing queue to be a prefix of the new list is what tells "I scrolled
 * further" apart from "I typed a new search", without carrying a session id
 * around for the privilege.
 */
export function extendMusicQueue(queue: MediaItem[]) {
  const m = getState().music;
  if (!m || queue.length <= m.queue.length) return;
  if (m.queue.every((s, i) => queue[i]?.id === s.id)) {
    setState({ music: { ...m, queue } });
  }
}
