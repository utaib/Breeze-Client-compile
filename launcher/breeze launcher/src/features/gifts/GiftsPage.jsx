import { useState } from "react";
import { I, CRAFATAR_AVATAR } from "../../ui/icons";
import { PlayerViewer3D } from "../../components/SkinViewer3D";
import { priceInWc } from "../../services/breezeApi";

/**
 * Two ways to give someone an item, and the page says which is which:
 *
 *   Buy a gift    you pay, the creator earns, they receive it.
 *   Send mine     the item moves out of your inventory into theirs.
 *
 * Gifting used to copy an item you owned, free and unlimited.
 */
export default function GiftsPage({
  inventory, catalogue, friends, target, setTarget, type, setType, itemId, setItemId,
  sendGift, busy, isAdmin, featureFlags, userSkinUrl, windCharges,
}) {
  const [mode, setMode] = useState("buy");
  const items = (mode === "buy" ? catalogue : inventory) || [];
  const selected = items.find((i) => i.id === itemId && i.type === type) || null;
  const friendList = Array.isArray(friends) ? friends : [];
  const price = selected?.price_usd != null ? priceInWc(selected.price_usd) : null;
  const tooPoor = mode === "buy" && price != null && Number(windCharges ?? 0) < price;

  if (featureFlags?.gifting_disabled) {
    return (
      <div className="sv page-enter social-unavailable">
        <div className="social-unavailable-card">
          <I.Gift />
          <div className="social-unavailable-title">Gifting is unavailable</div>
          <div className="social-unavailable-sub">This feature is switched off for now.</div>
        </div>
      </div>
    );
  }

  const pick = (item) => { setType(item.type); setItemId(item.id); };

  return (
    <div className="sv page-enter ecosystem-page ecosystem-single">
      <div className="ecosystem-main">
        <div className="vtl"><I.Gift /> Gift Center</div>

        <div className="gift-workspace">
          <div className="platform-card vertical">
            <div className="gift-modes">
              <button
                className={`gift-mode ${mode === "buy" ? "on" : ""}`}
                onClick={() => { setMode("buy"); setItemId(""); }}
              >
                <I.Store />
                <span className="gift-mode-name">Buy a gift</span>
                <span className="gift-mode-sub">You pay, they keep it</span>
              </button>
              <button
                className={`gift-mode ${mode === "own" ? "on" : ""}`}
                onClick={() => { setMode("own"); setItemId(""); }}
              >
                <I.Transfer />
                <span className="gift-mode-name">Send one of mine</span>
                <span className="gift-mode-sub">It leaves your inventory</span>
              </button>
            </div>

            <div className="panel-title spaced">Who is it for?</div>
            <input
              className="settings-input"
              value={target}
              onChange={(e) => setTarget(e.target.value)}
              placeholder="Minecraft username"
            />
            {friendList.length > 0 && (
              <div className="gift-friends">
                {friendList.slice(0, 8).map((f) => {
                  const who = f.user?.username;
                  const uuid = f.uuid || f.user?.uuid;
                  if (!who) return null;
                  return (
                    <button
                      key={f.id}
                      className={`gift-friend ${target === who ? "on" : ""}`}
                      onClick={() => setTarget(who)}
                      title={`Gift to ${who}`}
                    >
                      <img src={CRAFATAR_AVATAR(uuid)} alt="" className="avatar-head sm" />
                      <span>{who}</span>
                    </button>
                  );
                })}
              </div>
            )}

            <div className="gift-preview">
              {selected ? (
                selected.type === "cape" ? (
                  <PlayerViewer3D skinUrl={userSkinUrl} capeUrl={selected.image_url || null} width={150} height={230} />
                ) : selected.image_url ? (
                  <img src={selected.image_url} alt={selected.name} className="gift-preview-img" />
                ) : (
                  <div className="gift-preview-empty"><I.Gift /><span>No preview for this item</span></div>
                )
              ) : (
                <div className="gift-preview-empty"><I.Gift /><span>Pick an item to preview</span></div>
              )}
              {selected && (
                <div className="gift-preview-name">
                  {selected.name} · to {target || "…"}
                  {mode === "buy" && price != null && (
                    <span className="gift-price"><I.WindCharge /> {price.toLocaleString()}</span>
                  )}
                </div>
              )}
            </div>

            {mode === "own" && selected && (
              <div className="social-note">
                {selected.name} moves to {target || "them"}. You will no longer own it.
              </div>
            )}
            {tooPoor && (
              <div className="social-note bad">
                That costs {price.toLocaleString()} Wind Charges and you have {Number(windCharges ?? 0).toLocaleString()}.
              </div>
            )}

            <div className="radio-controls">
              <button
                className="btn accent"
                onClick={() => sendGift(mode)}
                disabled={!target.trim() || !itemId || busy || tooPoor}
              >
                {busy ? <I.Spin /> : <I.Gift />}
                {mode === "buy" ? "Buy and send" : "Send gift"}
              </button>
              {isAdmin && (
                <button
                  className="btn"
                  onClick={() => sendGift("grant")}
                  disabled={!target.trim() || !itemId || busy}
                  title="Grant without spending, staff only"
                >
                  <I.Shield /> Grant
                </button>
              )}
            </div>
          </div>

          <div className="platform-card vertical">
            <div className="panel-title">{mode === "buy" ? "Store items" : "Your items"}</div>
            <div className="gift-inventory-list">
              {items.length ? (
                items.map((item) => (
                  <button
                    key={`${item.type}:${item.id}`}
                    className={`gift-item-row ${itemId === item.id && type === item.type ? "on" : ""}`}
                    onClick={() => pick(item)}
                  >
                    <div className="gift-item-icon">
                      {item.image_url ? <img src={item.image_url} alt="" className="gift-item-img" /> : <I.Gift />}
                    </div>
                    <span className="gift-item-name">{item.name}</span>
                    {mode === "buy" && item.price_usd != null && (
                      <span className="gift-item-price"><I.WindCharge /> {priceInWc(item.price_usd).toLocaleString()}</span>
                    )}
                    <small className="badge bi gift-item-type">{item.type}</small>
                  </button>
                ))
              ) : (
                <div className="empty-panel">
                  {mode === "buy" ? "The store has nothing to gift yet." : "You do not own anything you can send yet."}
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
