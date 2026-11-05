import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { getPairingQr, onEvent, pairDecide, pairMode, unpair } from "../api";
import { getState, setState, toast, useStore } from "../store";

interface PairRequest {
  id: string;
  device: string;
  code: string;
}

export default function QrModal() {
  const open = useStore((s) => s.qrModal);
  const status = useStore((s) => s.status);
  const [qr, setQr] = useState<string | null>(null);
  const [raw, setRaw] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [request, setRequest] = useState<PairRequest | null>(null);

  useEffect(() => {
    if (!open) return;
    load(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // Pairing mode is exactly "this dialog is open". Requests that arrive while
  // it is closed are refused by the backend, so a stray LAN client cannot pop
  // approval prompts at will.
  useEffect(() => {
    if (!open) return;
    let alive = true;
    let unlisten: (() => void) | undefined;
    pairMode(true).catch(() => undefined);
    onEvent((ev) => {
      if (!alive || ev.kind !== "pair") return;
      setRequest(ev.data.request ?? null);
    }).then((u) => {
      if (alive) unlisten = u;
      else u();
    });
    return () => {
      alive = false;
      unlisten?.();
      setRequest(null);
      pairMode(false).catch(() => undefined);
    };
  }, [open]);

  const load = async (rotate: boolean) => {
    setBusy(true);
    try {
      const r = await getPairingQr(rotate);
      // Re-assert pairing mode once the async work is done: a StrictMode
      // mount/unmount pair can otherwise leave the flag off after the second
      // effect wins the race. Check the live store in case the dialog has been
      // closed while this request was in flight.
      if (getState().qrModal) pairMode(true).catch(() => undefined);
      // NOTE: modules MUST be dark-on-light — phone scanners (zxing) cannot
      // decode inverted (light-on-dark) QR codes.
      const dataUrl = await QRCode.toDataURL(r.qr, {
        margin: 1,
        width: 320,
        color: { dark: "#0b0e14", light: "#f5f7fa" },
      });
      setQr(dataUrl);
      setRaw(r.qr);
      if (rotate && status?.paired) toast("Rotated pairing code — phone must re-pair", "info");
    } catch (e) {
      toast(String(e), "error");
    } finally {
      setBusy(false);
    }
  };

  if (!open) return null;
  const paired = status?.paired;

  return (
    <div className="modal-back" onClick={() => setState({ qrModal: false })}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">
          <h2>{paired ? "Re-pair a phone" : "Pair your phone"}</h2>
          <button className="icon-btn" onClick={() => setState({ qrModal: false })}>
            ✕
          </button>
        </div>
        <p className="muted">
          Open <b>InterLink on your phone</b> → <b>Scan QR</b>, and point it at this code.
          Or pick <b>Approve</b> on the phone and confirm the six digits shown here.
          Both devices must be on the same network.
        </p>
        {request && (
          <div className="pair-card">
            <p className="muted">
              <b>{request.device}</b> wants to pair. The code on the phone must read
              exactly:
            </p>
            <div className="pair-code" aria-label={`pairing code ${request.code}`}>
              {request.code.slice(0, 3)} {request.code.slice(3)}
            </div>
            <p className="muted" style={{ fontSize: 12 }}>
              Approve only if the phone shows the same six digits. If they differ,
              someone is interfering with the connection — reject.
            </p>
            <div className="modal-actions">
              <button
                className="btn ghost"
                onClick={async () => {
                  const id = request.id;
                  setRequest(null);
                  await pairDecide(id, false).catch(() => undefined);
                }}
              >
                Reject
              </button>
              <button
                className="btn primary"
                onClick={async () => {
                  const id = request.id;
                  setRequest(null);
                  await pairDecide(id, true).catch(() => undefined);
                  toast("Pairing approved — waiting for the phone…", "info");
                }}
              >
                Approve
              </button>
            </div>
          </div>
        )}
        <div className="qr-box">
          {qr ? <img src={qr} alt="pairing QR" /> : <div className="qr-loading">Generating…</div>}
        </div>
        {raw && (
          <p className="muted" style={{ fontSize: 12, wordBreak: "break-all" }}>
            Camera not working? On the phone pick <b>Enter code</b> and paste:{" "}
            <code>{raw}</code>{" "}
            <button
              className="icon-btn"
              onClick={() => {
                navigator.clipboard.writeText(raw);
                toast("Pairing code copied", "info");
              }}
            >
              ⧉
            </button>
          </p>
        )}
        <div className="modal-actions">
          <button className="btn ghost" disabled={busy} onClick={() => load(true)}>
            Rotate code
          </button>
          {paired && (
            <button
              className="btn danger"
              onClick={async () => {
                await unpair();
                toast("Unpaired", "info");
                setState({ qrModal: false });
              }}
            >
              Unpair this phone
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
