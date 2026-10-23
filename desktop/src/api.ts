import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type {
  CallState,
  Contact,
  MediaItem,
  NotifItem,
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
export const mediaDownload = (id: string, suggested?: string) =>
  invoke<{ ok: boolean }>("media_download", { id, suggested });
export const fileSend = (path: string) =>
  invoke<{ ok: boolean; size: number }>("file_send", { path });
export const callAudioStart = (callId: string, mic?: string) =>
  invoke("call_audio_start", { callId, mic });
export const callAudioStop = (callId?: string) =>
  invoke("call_audio_stop", { callId });
export const callMute = (muted: boolean) =>
  invoke<{ muted: boolean }>("call_mute", { muted });

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
export const mediaList = (offset: number, limit: number, type = "all") =>
  req<{ total: number; items: MediaItem[] }>("media.list", { offset, limit, type });
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
  kind: "conn" | "push" | "progress";
  data: any;
}

export async function onEvent(cb: (ev: EvPayload) => void) {
  return listen<EvPayload>("il:ev", (e) => cb(e.payload));
}

export type { CallState };
