import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type {
  CallState,
  Contact,
  MediaItem,
  NotifItem,
  SeenDevice,
  SmsMessage,
  SmsThread,
  Status,
} from "./types";

// ——— Commands ———

export const getStatus = () => invoke<Status>("get_status");
export const getPairingQr = (rotate = false) =>
  invoke<{ qr: string; rotated: boolean }>("get_pairing_qr", { rotate });
export const unpair = () => invoke("unpair");
export const listAudioInputs = () =>
  invoke<{ devices: { name: string; default: boolean }[] }>("list_audio_inputs");
export const getSettings = () => invoke<Record<string, unknown>>("get_settings");
export const getDevices = () =>
  invoke<{ devices: SeenDevice[] }>("get_devices").then((r) => r.devices);
export const setSettings = (patch: Record<string, unknown>) =>
  invoke<Record<string, unknown>>("set_settings", { patch });
export const pickDirectory = () =>
  invoke<{ path: string | null }>("pick_directory");
export const pickFiles = () =>
  invoke<{ paths: string[] }>("pick_files").then((r) => r.paths);
export const revealInFolder = (path: string) =>
  invoke("reveal_in_folder", { path });
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
 * `mirror.state` pushes. */
export const mirrorStart = (w = 720, fps = 8) =>
  invoke<{ status: string }>("mirror_start", { w, fps });
export const mirrorStop = () => invoke<{ ok: boolean }>("mirror_stop");
/** `dest:"media"` puts the file straight into the phone's photo/video
 * library instead of Downloads/InterLink. Resolves once the phone confirms
 * it finished writing. */
export const fileSend = (path: string, dest?: string) =>
  invoke<{ ok: boolean; size: number; completed: boolean; name: string }>(
    "file_send",
    { path, dest },
  );
export const listAudioOutputs = () =>
  invoke<{ devices: { name: string; default: boolean }[] }>("list_audio_outputs");
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
  invoke<{ path: string | null }>("pick_apk").then((r) => r.path);

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
  invoke<{ path: string | null }>("pick_backup_dir").then((r) => r.path);

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
export const notifHistory = (limit = 100) =>
  req<{ items: NotifItem[] }>("notif.history", { limit }).then((r) => r.items ?? []);
export const notifAction = (key: string, token: string, reply?: string) =>
  req("notif.action", { key, token, reply });
export const notifDismiss = (key: string) => req("notif.dismiss", { key }).catch(() => undefined);
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
export const callDial = (number: string) => req<{ callId: string }>("call.dial", { number });
export const callAnswer = (callId: string) => req("call.answer", { callId }).catch(() => undefined);
export const callDecline = (callId: string) => req("call.decline", { callId }).catch(() => undefined);
export const callEnd = (callId: string) => req("call.end", { callId }).catch(() => undefined);
export const ringPhone = (seconds = 15) => req("device.ring", { seconds }).catch(() => undefined);
export const deviceInfo = () => req<Record<string, unknown>>("device.info");
export const clipSet = (text: string) => req("clip.set", { text, ts: Date.now() }).catch(() => undefined);
export const clipGet = () => req<{ text: string; ts: number }>("clip.get").catch(() => null);

// ——— Events ———

export interface EvPayload {
  kind: "conn" | "push" | "progress" | "devices";
  data: any;
}

export async function onEvent(cb: (ev: EvPayload) => void) {
  return listen<EvPayload>("il:ev", (e) => cb(e.payload));
}

export type { CallState };
