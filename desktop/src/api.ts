import { invoke as tauriInvoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { writeText } from "@tauri-apps/plugin-clipboard-manager";
import type {
  AudioDevice,
  CallEntry,
  CallState,
  Contact,
  MediaItem,
  NotifItem,
  SeenDevice,
  SmsMessage,
  SmsThread,
  Status,
} from "./types";

// ——— Transport ———
//
// The same UI runs in two places. In the desktop app it talks to the Rust
// backend through Tauri's `invoke` and event channel. In a browser it talks to
// `interlink-web` (the same backend, served over HTTP): commands are
// `POST /api/<name>` with the same JSON arguments, and events arrive as
// Server-Sent Events. Everything below this block is transport-agnostic.

/** True when running as a web page rather than inside the Tauri window. */
export const isWeb = typeof window === "undefined" || !("__TAURI_INTERNALS__" in window);

/** Failures are plain strings in both transports (Tauri rejects with one). */
async function invoke<T = unknown>(cmd: string, args?: Record<string, unknown>): Promise<T> {
  if (!isWeb) return tauriInvoke<T>(cmd, args);
  let res: Response;
  try {
    res = await fetch(`/api/${cmd}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(args ?? {}),
    });
  } catch {
    throw "offline: cannot reach InterLink web — is interlink-web still running?";
  }
  const body = await res.json().catch(() => null);
  if (!res.ok) {
    if (res.status === 401) {
      throw "Session expired — open the link printed by interlink-web again.";
    }
    throw (body && body.error) || `HTTP ${res.status}`;
  }
  return body as T;
}

/** Copy text to the clipboard (Tauri plugin, or the browser's own API). */
export async function clipboardWrite(text: string): Promise<void> {
  if (!isWeb) return writeText(text);
  await navigator.clipboard.writeText(text);
}

// ——— Commands ———

export const getStatus = () => invoke<Status>("get_status");
export const getPairingQr = (rotate = false) =>
  invoke<{ qr: string; rotated: boolean }>("get_pairing_qr", { rotate });
export const unpair = () => invoke("unpair");
/** Allow (or stop allowing) approve-to-pair requests from unpaired phones. */
export const pairMode = (enabled: boolean) =>
  invoke<{ enabled: boolean }>("pair_mode", { enabled });
/** Approve or reject a pending pair request by its id. */
export const pairDecide = (id: string, approve: boolean) =>
  invoke<{ ok: boolean }>("pair_decide", { id, approve });
export const listAudioInputs = () =>
  invoke<{ devices: AudioDevice[] }>("list_audio_inputs");
export const getSettings = () => invoke<Record<string, unknown>>("get_settings");
export const getDevices = () =>
  invoke<{ devices: SeenDevice[] }>("get_devices").then((r) => r.devices);
export const setSettings = (patch: Record<string, unknown>) =>
  invoke<Record<string, unknown>>("set_settings", { patch });
export const pickDirectory = () =>
  isWeb
    ? Promise.resolve({ path: webAskFolder("Folder on this computer to save files into:") })
    : invoke<{ path: string | null }>("pick_directory");
export const pickFiles = () =>
  isWeb ? webPickFiles() : invoke<{ paths: string[] }>("pick_files").then((r) => r.paths);
/** Showing a file in the OS file manager only exists in the desktop app. */
export const canReveal = !isWeb;
export const revealInFolder = (path: string) =>
  isWeb ? Promise.resolve() : invoke("reveal_in_folder", { path });
/** A phone photo/video as a normal browser download (web build only). */
export const mediaDownloadUrl = (id: string, name?: string) =>
  `/api/media/file?id=${encodeURIComponent(id)}${name ? `&name=${encodeURIComponent(name)}` : ""}`;
export const mediaThumb = (id: string, w = 320) =>
  invoke<{ data: string; mime: string }>("media_thumb", { id, w });
/** Save a photo/video from the phone onto the laptop. Playback deliberately
 * does *not* use this — see [mediaStreamUrl]. */
export const mediaDownload = (id: string, suggested?: string, subdir?: string) =>
  invoke<{ ok: boolean; path: string; size: number }>("media_download", {
    id,
    suggested,
    subdir,
  });

/** Drag a gallery item onto the desktop / a file manager.
 *
 * An HTML drag cannot hand a real file to the OS, so this downloads the item
 * (unless `path` already points at a file from an earlier drag) and then
 * starts a native drag from the window, which the user finishes by releasing
 * the mouse over the drop target. */
export const mediaDrag = (id: string, suggested?: string, path?: string) =>
  invoke<{ ok: boolean; path?: string; busy?: boolean }>("media_drag", { id, suggested, path });

/** Sort keys the phone accepts for `type:"audio"`. A closed set on purpose:
 * the value lands in an SQL `ORDER BY`, so the phone only ever honours names
 * it recognises. */
export type MusicSort =
  | "title"
  | "artist"
  | "album"
  | "genre"
  | "duration"
  | "date"
  | "name"
  | "size";

/**
 * A URL the webview can put straight into `<audio>` / `<video>` and play.
 *
 * The phone serves the bytes itself with HTTP range support, so playback
 * begins at the first block instead of after a full download, seeking costs
 * one request rather than a re-fetch, and nothing is written to the laptop.
 * The URL carries a per-process token the phone only hands out over the
 * encrypted link, so a stale one simply gets a 401.
 */
export const mediaStreamUrl = (id: string) => invoke<string>("media_stream_url", { id });
/** Kick off screen mirroring: the phone shows its MediaProjection consent
 * dialog, then streams `mirror.frame` pushes and reports lifecycle via
 * `mirror.state` pushes. `audio` relays what the phone is playing to the
 * laptop as well — see `mirrorAudio`. */
export const mirrorStart = (w = 720, fps = 8, audio = true) =>
  invoke<{ status: string }>("mirror_start", { w, fps, audio });
export const mirrorStop = () => invoke<{ ok: boolean }>("mirror_stop");
/** Turn the phone's mirrored audio on/off while mirroring. The phone's
 * answer is only an acknowledgement — whether sound actually flows arrives
 * as a `mirror.audio` push carrying its capture rate (or the reason it
 * can't), which the server turns into the laptop's speaker relay. */
export const mirrorAudio = (on: boolean) =>
  invoke<{ ok: boolean; started?: boolean }>("mirror_audio", { on });
/** `dest:"media"` puts the file straight into the phone's photo/video
 * library instead of Downloads/InterLink. Resolves once the phone confirms
 * it finished writing. */
export const fileSend = (path: string, dest?: string) =>
  invoke<{ ok: boolean; size: number; completed: boolean; name: string }>(
    "file_send",
    { path, dest },
  );
export const listAudioOutputs = () =>
  invoke<{ devices: AudioDevice[] }>("list_audio_outputs");
/** Start the call audio relay. `uplink` = speak through the laptop,
 * `downlink` = hear through the laptop; both false keeps the call on the
 * phone. `PHONE` device values are handled by the caller. */
export const callAudioStart = (
  callId: string,
  opts: {
    mic?: string;
    speaker?: string;
    uplink?: boolean;
    downlink?: boolean;
  } = {},
) => invoke("call_audio_start", { callId, ...opts });
export const callAudioStop = (callId?: string) =>
  invoke("call_audio_stop", { callId });
export const callMute = (muted: boolean) =>
  invoke<{ muted: boolean }>("call_mute", { muted });

// ——— Connection (USB / WiFi) ———
export const connectionRefresh = () =>
  invoke<{
    connection: string;
    adbDevice: string | null;
    transport: "usb" | "wifi" | null;
  }>("connection_refresh");

// ——— Remote app install ———
export const pickApk = () =>
  isWeb
    ? webPickFiles(".apk", false).then((p) => p[0] ?? null)
    : invoke<{ path: string | null }>("pick_apk").then((r) => r.path);

// ——— Backup / restore ———
export const backupStart = (sections: string[], outDir?: string) =>
  invoke<{ ok: boolean; path: string; contacts: number; sms: number; photos: number }>(
    "backup_start",
    { sections, outDir },
  );
export const backupRestore = (dir: string) =>
  invoke<{
    ok: boolean;
    contacts: number;
    skipped: number;
    media: number;
    mediaFailed: number;
    sms: { imported?: number; blocked?: boolean; error?: string } | null;
  }>("backup_restore", { dir });
export const backupList = (dir: string) =>
  invoke<{
    path: string;
    manifest: Record<string, unknown> | null;
    contacts: boolean;
    sms: boolean;
    media: number;
  }>("backup_list", { dir });
export const pickBackupDir = () =>
  isWeb
    ? Promise.resolve(webAskFolder("Backup folder on this computer:"))
    : invoke<{ path: string | null }>("pick_backup_dir").then((r) => r.path);

/** Generic protocol request to the phone. Resolves with the response payload. */
export async function req<T = unknown>(
  t: string,
  d: Record<string, unknown> = {},
  timeoutMs?: number,
): Promise<T> {
  const res = await invoke<{ d: T }>("send_request", { t, d, timeoutMs });
  return res.d;
}

// ——— Typed protocol helpers ———

export const smsThreads = () => req<SmsThread[]>("sms.threads").then((r) => (r as unknown as { threads: SmsThread[] }).threads);
export const smsList = (threadId: string, limit?: number) =>
  req<{ messages: SmsMessage[] }>("sms.list", { threadId, limit }).then((r) => r.messages);
export const smsSend = (args: { address?: string; threadId?: string; body: string }) =>
  req("sms.send", args);
export const smsRead = (threadId: string) => req("sms.read", { threadId }).catch(() => undefined);
export const contactsQuery = (q?: string, limit = 30) =>
  req<{ contacts: Contact[] }>("contacts.query", { q, limit }).then((r) => r.contacts);
/** The phone's call history, newest first. `READ_CALL_LOG` is its own
 * permission over there, so a refusal arrives as a `perm:` error — show it
 * as an instruction, not as an empty list. */
export const callLogList = (limit = 100) =>
  req<{ calls: CallEntry[] }>("calllog.list", { limit }).then((r) => r.calls);
export const notifHistory = (limit = 100) =>
  req<{ items: NotifItem[] }>("notif.history", { limit }).then((r) => r.items ?? []);
export const notifAction = (key: string, token: string, reply?: string) =>
  req("notif.action", { key, token, reply });
export const notifDismiss = (key: string) => req("notif.dismiss", { key }).catch(() => undefined);
/** Base64 of a mirrored notification's attachment, for the thumbnail. */
export const notifImage = (key: string) =>
  invoke<{ mime: string; data: string }>("notif_image", { key });
/** Save an attachment into the download folder; returns the path it landed on. */
export const notifSaveImage = (key: string, name?: string) =>
  invoke<string>("notif_save_image", { key, name });
export const mediaList = (
  offset: number,
  limit: number,
  type = "all",
  q?: string,
  sort?: MusicSort,
  dir?: "asc" | "desc",
) =>
  req<{ total: number; items: MediaItem[] }>("media.list", {
    offset,
    limit,
    type,
    q,
    sort,
    dir,
  });
/** The phone reports what it knows: an id, and how it resolved the number. */
export const callDial = (number: string) =>
  req<{ callId: string; number?: string; name?: string }>("call.dial", { number });
export const callAnswer = (callId: string) => req("call.answer", { callId }).catch(() => undefined);
export const callDecline = (callId: string) => req("call.decline", { callId }).catch(() => undefined);
export const callEnd = (callId: string) => req("call.end", { callId }).catch(() => undefined);
export const ringPhone = (seconds = 15) => req("device.ring", { seconds }).catch(() => undefined);
export const deviceInfo = () => req<Record<string, unknown>>("device.info");
export const clipSet = (text: string) => req("clip.set", { text, ts: Date.now() }).catch(() => undefined);
export const clipGet = () => req<{ text: string; ts: number }>("clip.get").catch(() => null);

// ——— Events ———

export interface EvPayload {
  kind: "conn" | "push" | "progress" | "devices" | "pair";
  data: any;
}

export async function onEvent(cb: (ev: EvPayload) => void): Promise<() => void> {
  if (!isWeb) return listen<EvPayload>("il:ev", (e) => cb(e.payload));

  // Browser: Server-Sent Events. EventSource reconnects by itself; if the
  // connection was lost, whatever happened meanwhile was missed, so re-read
  // everything with a reload rather than show stale state.
  const es = new EventSource("/api/events");
  let wasDown = false;
  es.onmessage = (m) => {
    try {
      cb(JSON.parse(m.data) as EvPayload);
    } catch {
      /* ignore a malformed frame */
    }
  };
  es.onerror = () => {
    wasDown = true;
  };
  es.onopen = () => {
    if (wasDown) window.location.reload();
  };
  return () => es.close();
}

// ——— Browser-only helpers ———

/** The browser has no folder picker that yields a path on the machine running
 * InterLink, so ask for one. Empty / cancelled → null. */
function webAskFolder(question: string): string | null {
  const v = window.prompt(question);
  return v && v.trim() ? v.trim() : null;
}

/** The browser's file picker. The bytes go to the machine running InterLink
 * (`PUT /api/upload`), and the paths that come back are what `fileSend` /
 * the APK installer then read from disk, exactly like a native dialog's. */
function webPickFiles(accept?: string, multiple = true): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const input = document.createElement("input");
    input.type = "file";
    input.multiple = multiple;
    if (accept) input.accept = accept;
    input.style.display = "none";
    const done = (fn: () => void) => {
      input.remove();
      fn();
    };
    input.addEventListener("cancel", () => done(() => resolve([])));
    input.addEventListener("change", async () => {
      const files = Array.from(input.files ?? []);
      try {
        const paths: string[] = [];
        for (const f of files) {
          const res = await fetch(`/api/upload?name=${encodeURIComponent(f.name)}`, {
            method: "PUT",
            body: f,
          });
          const body = await res.json().catch(() => null);
          if (!res.ok) throw (body && body.error) || `upload failed (HTTP ${res.status})`;
          paths.push(body.path as string);
        }
        done(() => resolve(paths));
      } catch (e) {
        done(() => reject(e));
      }
    });
    document.body.appendChild(input);
    input.click();
  });
}

export type { CallState };
