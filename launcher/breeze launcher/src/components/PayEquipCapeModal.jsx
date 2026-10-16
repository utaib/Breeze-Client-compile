import { useRef, useState } from "react";
import useModalExit from "../ui/useModalExit";
import { purchasePersonalCapeWc, priceInWc } from "../services/breezeApi";

// Personal cape purchase: paid in Wind Charges (1,280 WC = $20), applied
// instantly. No browser round-trip, no payment polling: the wallet either
// covers it or the user tops up first.
const PERSONAL_CAPE_USD = 20;

export default function PayEquipCapeModal({ session, onClose: onCloseProp, onNotify, onApplied, walletBalance = 0, onOpenWallet }) {
  // Delays the parent's unmount so the dialog can animate out. Shadowing
  // the prop means every existing onClose call site below is unchanged.
  const { closing, close: onClose } = useModalExit(onCloseProp);
  const fileInputRef = useRef(null);
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  const priceWc = priceInWc(PERSONAL_CAPE_USD);
  const notify = onNotify || (() => {});

  function handleFileSelect(event) {
    const selected = event.target.files?.[0];
    if (!selected) return;
    setFile(selected);
    setPreview((prev) => { if (prev) URL.revokeObjectURL(prev); return URL.createObjectURL(selected); });
    setError("");
  }

  async function handleSubmit() {
    if (!file) { setError("Choose a cape image first."); return; }
    if (!session?.breezeToken) { setError("Sign in first."); return; }
    setBusy(true);
    setError("");
    try {
      const result = await purchasePersonalCapeWc({ token: session.breezeToken, file, fileName: file.name });
      notify("ok", "Personal cape applied");
      if (result.cape_url) onApplied?.(result.cape_url);
      onClose?.();
    } catch (err) {
      const message = err?.message || "Personal cape purchase failed";
      setError(message);
      if (/not enough wind charges/i.test(message) && onOpenWallet) {
        onClose?.();
        onOpenWallet();
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`modal-backdrop${closing ? " closing" : ""}`} onClick={busy ? undefined : onClose}>
      <div className="modal" style={{ maxWidth: 460 }} onClick={(event) => event.stopPropagation()}>
        <div className="modal-body">
          <div className="modal-header">
            <div>
              <div style={{ fontSize: 11, color: "var(--purple)", marginBottom: 4, fontWeight: 600, letterSpacing: "0.05em", textTransform: "uppercase" }}>Personal Cape</div>
              <div className="modal-title">Upload &amp; Equip</div>
              <div style={{ fontSize: 12, color: "var(--text-tertiary)", marginTop: 3 }}>
                Private one-time upload · visible on your player, not listed in the store
              </div>
            </div>
          </div>

          <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
            <div
              className="admin-cape-preview"
              onClick={() => !busy && fileInputRef.current?.click()}
              style={{ backgroundImage: preview ? `url(${preview})` : undefined, cursor: busy ? "default" : "pointer" }}
            >
              {!preview && (
                <>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" /></svg>
                  <span>Upload PNG</span>
                </>
              )}
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept=".png,.jpg,.jpeg,image/png,image/jpeg"
              hidden
              onChange={handleFileSelect}
            />
            <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 8, fontSize: 12.5, color: "var(--text-secondary)", lineHeight: 1.55 }}>
              <div>
                Applies a <strong>personal cape</strong> to your account for{" "}
                <strong style={{ whiteSpace: "nowrap" }}><span className="wc-ico" style={{ display: "inline-block", verticalAlign: "-2px", marginRight: 3 }} />{priceWc.toLocaleString()} Wind Charges</strong>.
              </div>
              <div style={{ fontSize: 11.5, color: "var(--text-faint)" }}>
                Resized to the Minecraft cape standard on upload. Applied instantly, no waiting for payment confirmation.
              </div>
              <div style={{ fontSize: 11.5, color: walletBalance >= priceWc ? "var(--ok)" : "var(--warn)" }}>
                Your balance: {Number(walletBalance).toLocaleString()} Wind Charges
              </div>
            </div>
          </div>

          {error && <div className="promo-error">{error}</div>}

          <div className="modal-actions">
            <button className="modal-close" onClick={onClose} disabled={busy}>Cancel</button>
            <button className="modal-buy" onClick={handleSubmit} disabled={busy || !file} style={{ flex: 1 }}>
              {busy ? "Applying…" : <>Pay {priceWc.toLocaleString()} Wind Charges</>}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
