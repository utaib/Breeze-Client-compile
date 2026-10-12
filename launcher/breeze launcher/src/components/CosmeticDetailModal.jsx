import { useState } from "react";
import useModalExit from "../ui/useModalExit";
import GlbPreview from "./GlbPreview";
import { priceInWc } from "../services/breezeApi";

const SLOT_LABELS = {
  hat: "Hat",
  wings: "Wings",
  pet: "Pet",
  aura: "Aura",
  shield: "Shield",
  back: "Back",
  trail: "Trail",
  cape: "Cape",
};

export default function CosmeticDetailModal({
  item,
  ownedCosmetics,
  onClose: onCloseProp,
  onEquip,
  onUnequip,
}) {
  // Delays the parent's unmount so the dialog can animate out. Shadowing
  // the prop means every existing onClose call site below is unchanged.
  const { closing, close: onClose } = useModalExit(onCloseProp);
  const [busy, setBusy] = useState(false);

  const owned = (ownedCosmetics?.owned || []).some((o) => o.cosmetic_id === item.id);
  const equipped = ownedCosmetics?.equipped?.[item.slot] === item.id;
  const price = Number(item.price_usd ?? 0);
  const priceWc = priceInWc(price);
  const isFree = price <= 0;
  const modelUrl = item._raw?.model_url || item.model_url || null;
  const hasModel = Boolean(modelUrl);
  const slotLabel = SLOT_LABELS[item.slot] || "Cosmetic";

  async function handleEquip() {
    if (busy) return;
    setBusy(true);
    try { await onEquip(item); } finally { setBusy(false); }
  }

  async function handleUnequip() {
    if (busy) return;
    setBusy(true);
    try { await onUnequip(item); } finally { setBusy(false); }
  }

  return (
    <div className={`modal-backdrop${closing ? " closing" : ""}`} onClick={onClose}>
      <div
        className="modal"
        onClick={(event) => event.stopPropagation()}
        style={{
          maxWidth: 640,
          width: "90vw",
          display: "flex",
          flexDirection: "row",
          padding: 0,
          overflow: "hidden",
          minHeight: 380,
        }}
      >
        <div style={{
          width: 240,
          minWidth: 240,
          background: "rgba(6,8,18,0.9)",
          borderRight: "1px solid rgba(255,255,255,0.06)",
          display: "flex",
          alignItems: "center",
          justifyContent: "center",
          flexDirection: "column",
          gap: 10,
          position: "relative",
        }}>
          <div className={`item-rarity-stripe ${item.rarity || "premium"}`}
            style={{ position: "absolute", top: 0, left: 0, right: 0, height: 3 }} />

          {hasModel ? (
            <GlbPreview src={modelUrl} size={200} />
          ) : item.thumbSrc ? (
            <img
              src={item.thumbSrc}
              alt={item.name}
              style={{ width: "75%", maxHeight: 200, objectFit: "contain", borderRadius: 10 }}
              onError={(event) => { event.currentTarget.style.display = "none"; }}
            />
          ) : (
            <div style={{ fontSize: 14, opacity: 0.65, fontWeight: 700 }}>{slotLabel}</div>
          )}

          {hasModel && (
            <div style={{
              fontSize: 10,
              color: "rgba(100,180,255,0.7)",
              background: "rgba(59,130,246,0.08)",
              border: "1px solid rgba(59,130,246,0.2)",
              borderRadius: 6,
              padding: "2px 8px",
            }}>
              Drag to rotate
            </div>
          )}

          {equipped && (
            <div style={{
              fontSize: 10,
              fontWeight: 700,
              color: "rgba(61,214,140,0.9)",
              background: "rgba(61,214,140,0.1)",
              border: "1px solid rgba(61,214,140,0.25)",
              borderRadius: 6,
              padding: "2px 10px",
            }}>
              Equipped
            </div>
          )}
        </div>

        <div className="modal-body" style={{
          flex: 1,
          padding: "22px 24px",
          display: "flex",
          flexDirection: "column",
          gap: 12,
        }}>
          <div className="modal-header">
            <div>
              <div className="modal-title">{item.name}</div>
              <div style={{ fontSize: 11, color: "var(--text-faint)", marginTop: 3 }}>
                {slotLabel}
                {item._raw?.creator_id ? ` · by ${item._raw.creator_id}` : ""}
              </div>
            </div>
          </div>

          {item.description && (
            <div style={{ fontSize: 12, color: "var(--text-secondary)", lineHeight: 1.5 }}>
              {item.description}
            </div>
          )}

          <div className="store-modal-stats" style={{ marginTop: 4 }}>
            {[
              ["Type", slotLabel],
              ["Rarity", item.rarity || "premium"],
              ["Price", isFree ? "Free" : `${priceWc.toLocaleString()} WC`],
            ].map(([label, val]) => (
              <div className="store-modal-stat" key={label}>
                <div className="store-modal-stat-label">{label}</div>
                <div className="store-modal-stat-value">{val}</div>
              </div>
            ))}
          </div>

          <div className="modal-actions" style={{ marginTop: "auto" }}>
            <div className="modal-price">
              {isFree ? <span className="free-label">Free</span> : <><span className="wc-ico" />{priceWc.toLocaleString()}</>}
            </div>

            <button className="modal-close" onClick={onClose}>Close</button>

            {equipped && (
              <button
                className="modal-buy"
                style={{
                  background: "rgba(255,100,100,0.12)",
                  color: "rgba(255,130,130,0.9)",
                  border: "1px solid rgba(255,100,100,0.3)",
                }}
                onClick={handleUnequip}
                disabled={busy}
              >
                {busy ? "..." : "Unequip"}
              </button>
            )}
            {owned && !equipped && (
              <button className="modal-buy" onClick={handleEquip} disabled={busy}>
                {busy ? "..." : "Equip"}
              </button>
            )}
            {!owned && (
              <button
                className="modal-buy"
                onClick={isFree ? handleEquip : undefined}
                disabled={busy || !isFree}
                style={!isFree ? { opacity: 0.55, cursor: "not-allowed" } : {}}
              >
                {busy ? "..." : isFree ? "Claim Free" : "Buy"}
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
