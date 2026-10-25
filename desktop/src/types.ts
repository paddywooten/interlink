export interface Battery {
  level: number;
  charging?: boolean;
  temp?: number;
}

export interface Peer {
  name: string;
  model: string;
  os: string;
  app: string;
  caps: string[];
  battery?: Battery | null;
}

/** A device discovered on the LAN via UDP beacons. */
export interface SeenDevice {
  name: string;
  role: "laptop" | "phone" | string;
  ip: string;
  port: number;
  last_seen: number;
}

export interface Status {
  paired: boolean;
  connected: boolean;
  peer: Peer | null;
  serverRunning: boolean;
  ip: string | null;
  port: number;
  deviceName: string;
  micDevice: string | null;
  /** Laptop output device for the far end of a call (null = system default). */
  speakerDevice?: string | null;
  /** "auto" | "wifi" | "usb" — the requested connection mode. */
  connection?: string;
  /** How the phone is actually reaching us right now. */
  transport?: "usb" | "wifi" | null;
  /** Serial of the device adb currently sees over USB, if any. */
  adbDevice?: string | null;
  clipboardEnabled: boolean;
  autoAcceptFiles: boolean;
  /** Raise native OS popups for phone notifications / incoming calls. */
  notifPopup?: boolean;
  downloadDir: string | null;
  battery: Battery | null;
}

export interface NotifItem {
  key: string;
  pkg: string;
  app: string;
  title: string;
  text: string;
  bigText?: string;
  subText?: string;
  category?: string;
  when: number;
  ongoing?: boolean;
  /** `reply` marks the action that accepts RemoteInput (the one a typed
   *  reply has to be sent back to). */
  actions?: { label: string; token: string; reply?: boolean }[];
  hasReply?: boolean;
  avatar?: string | null;
  ts?: number; // local receive time
}

export interface SmsThread {
  id: string;
  address: string;
  name?: string | null;
  snippet: string;
  unread: number;
  date: number;
}

export interface SmsMessage {
  id: string;
  threadId: string;
  box: "inbox" | "sent" | "draft";
  address: string;
  name?: string | null;
  body: string;
  date: number;
  read: boolean;
}

export interface Contact {
  id: string;
  name: string;
  phones: string[];
}

export interface MediaItem {
  id: string;
  name: string;
  mime: string;
  size: number;
  w?: number;
  h?: number;
  date: number;
  /** Milliseconds, when the media has a duration (video, audio). */
  dur?: number;
  /** Music only — display metadata; the file itself still arrives as `name`. */
  title?: string;
  artist?: string;
  album?: string;
  /** Music only — present when the phone's row carries one (~70% do). */
  genre?: string;
}

export interface CallState {
  state: "incoming" | "active" | "ended";
  dir: "in" | "out";
  number: string;
  name?: string;
  callId: string;
  duration?: number;
}

export interface Transfer {
  tid: string;
  name: string;
  received: number;
  total: number;
  dir: "up" | "down";
  done?: boolean;
  failed?: string;
}

export interface Toast {
  id: number;
  text: string;
  kind: "info" | "error" | "success";
}
