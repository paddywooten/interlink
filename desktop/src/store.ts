import { useSyncExternalStore } from "react";
import type {
  CallState,
  NotifItem,
  SeenDevice,
  Status,
  Toast,
  Transfer,
} from "./types";

// ——— Tiny global store (no external deps) ———

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
