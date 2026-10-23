import { useEffect, useState } from "react";
import QRCode from "qrcode";
import { getPairingQr, unpair } from "../api";
import { setState, toast, useStore } from "../store";

export default function QrModal() {
  const open = useStore((s) => s.qrModal);
  const status = useStore((s) => s.status);
  const [qr, setQr] = useState<string | null>(null);
  const [raw, setRaw] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    load(false);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const load = async (rotate: boolean) => {
    setBusy(true);
    try {
      const r = await getPairingQr(rotate);
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
          Both devices must be on the same network.
        </p>
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
