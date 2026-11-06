import { useEffect, useState } from "react";
import { PlayerStage } from "../../components/PlayerStage";
import PromoCodeInput from "../../components/PromoCodeInput";
import { priceInWc } from "../../services/breezeApi";
import { I } from "../../ui/icons";
import { ItemArt } from "./StoreArt";
import { discountedWc, isAnimated, rarityOf, slotLabel, slotOf } from "./storeLook";

const TURN_AROUND = "turn-around";

/**
 * One item's window: the player wearing it on the left (a cape turns the
 * player round to show it), and on the right what it is, what it costs, what
 * the player has, and Buy, Claim or Equip. A promo code field opens on
 * request; a creator can pay with Creator Passes (a test buy, no promo).
 */
export default function StoreModal({
  item, wallet, onClose, onBuy, onEquip, busy, token,
  promoCode, promoDiscount, setPromoCode, setPromoDiscount,
  userSkinUrl, username, onGetWindCharges,
}) {
  const price = Number(item.price_usd || 0);
  const priceWc = priceInWc(price);
  const isCape = item._type === "cape";
  const [modelFailed, setModelFailed] = useState(false);
  const isWorn = !isCape && Boolean(item.model_url) && !modelFailed;
  const onPlayer = isCape || isWorn;
  const animated = isAnimated(item);
  const rarity = rarityOf(item);
  const slot = slotOf(item);
  const isCreator = Boolean(wallet?.is_creator);
  const passes = Number(wallet?.creator_passes || 0);
  const balance = Number(wallet?.wind_charges);
  const [currency, setCurrency] = useState("wind_charges");
  const usingPasses = currency === "creator_passes";
  const discount = !usingPasses && promoCode ? Number(promoDiscount) || 0 : 0;
  const finalWc = discountedWc(priceWc, discount);
  const [promoOpen, setPromoOpen] = useState(Boolean(promoCode));
  const [cue, setCue] = useState(null);
  const short = !item.owned && !usingPasses && Number.isFinite(balance) && finalWc > balance ? finalWc - balance : 0;

  // The player turns round to show a cape, once it has had a moment to load.
  useEffect(() => {
    if (!isCape) return undefined;
    const t = window.setTimeout(() => setCue({ name: TURN_AROUND, n: Date.now() }), 700);
    return () => window.clearTimeout(t);
  }, [item.id, isCape]);

  useEffect(() => {
    const onKey = (e) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose]);

  const status = item.equipped ? "Wearing it now" : item.owned ? "Yours" : "Not owned yet";
  const motion = animated
    ? `Animated, ${item.animation_frames.length} frames${item.animation_fps ? ` at ${item.animation_fps} per second` : ""}`
    : "Still";

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div
        className={`modal store-modal rar-${rarity.tone} ${onPlayer ? "store-modal-3d" : ""}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby="store-modal-title"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="store-modal-preview-panel">
          <div className="store-modal-preview-bg" aria-hidden="true" />
          {onPlayer ? (
            <PlayerStage
              name={username || null}
              skinUrl={userSkinUrl}
              capeUrl={isCape ? item.image_url || null : null}
              animationFrames={isCape && animated ? item.animation_frames : null}
              animationFps={isCape ? item.animation_fps || null : null}
              cosmetics={isWorn ? [{ key: String(item.id), url: item.model_url, cosmetic: item }] : null}
              onCosmeticError={() => setModelFailed(true)}
              showElytraToggle={isCape}
              width={300}
              height={400}
              momentPace="lively"
              momentCue={cue}
              className="store-modal-stage"
            />
          ) : (
            <div className="store-modal-still"><ItemArt item={item} /></div>
          )}
          <div className="store-modal-hint">
            {onPlayer ? `On ${username ? `${username}'s` : "your"} skin. Drag to turn.` : "Picture of the item."}
          </div>
        </div>

        <div className="modal-body store-modal-info-panel">
          <div className="modal-header">
            <div className="store-modal-eyebrow">
              <span className="store-chip rar">{rarity.label}</span>
              <span className="store-chip">{slotLabel(slot)}</span>
              {animated && <span className="store-chip anim">Animated</span>}
            </div>
            <button type="button" className="mini-btn" onClick={onClose} aria-label="Close"><I.X /></button>
          </div>
          <div className="store-modal-head">
            {onPlayer && <span className="store-modal-tile" aria-hidden="true"><ItemArt item={item} /></span>}
            <div className="store-modal-head-text">
              <h2 className="modal-title store-modal-title" id="store-modal-title">{item.name}</h2>
              <p className="modal-desc">{item.description || (isCape ? "A cape for your Breeze character." : `A ${slotLabel(slot).toLowerCase()} for your Breeze character.`)}</p>
              {item.creator_name && <div className="store-modal-by">by {item.creator_name}</div>}
            </div>
          </div>

          <dl className="store-modal-facts">
            <div><dt>Kind</dt><dd>{slotLabel(slot)}</dd></div>
            <div><dt>Rarity</dt><dd className="rar-text">{rarity.label}</dd></div>
            {isCape && <div><dt>Motion</dt><dd>{motion}</dd></div>}
            <div><dt>Status</dt><dd className={item.owned ? "ok" : ""}>{status}</dd></div>
          </dl>

          <div className="store-modal-spacer" />

          {!item.owned && price > 0 && !usingPasses && (
            promoOpen
              ? <PromoCodeInput token={token} priceUsd={price} appliedCode={promoCode} appliedDiscount={promoDiscount} onApply={(code, pct) => { setPromoCode(code); setPromoDiscount(pct); }} onRemove={() => { setPromoCode(null); setPromoDiscount(0); }} disabled={busy} />
              : <button type="button" className="store-modal-link" onClick={() => setPromoOpen(true)}>Have a promo code?</button>
          )}

          {!item.owned && priceWc > 0 && isCreator && (
            <div className="pay-with">
              <div className="pay-with-label">Pay with</div>
              <div className="pay-with-toggle">
                <button type="button" className={`pay-opt ${!usingPasses ? "on" : ""}`} onClick={() => setCurrency("wind_charges")} disabled={busy}>
                  <span className="wc-ico" /> Wind Charges
                </button>
                <button type="button" className={`pay-opt gold ${usingPasses ? "on" : ""}`} onClick={() => setCurrency("creator_passes")} disabled={busy}>
                  <span className="pass-ico" /> Creator Passes
                </button>
              </div>
              <div className="pay-with-hint">
                {usingPasses
                  ? `Test purchase, ${passes.toLocaleString()} passes available. No money goes to the creator.`
                  : "Real Wind Charges, the creator earns their commission."}
              </div>
            </div>
          )}

          <div className="store-modal-checkout">
            <div className="store-modal-price">
              <span className="store-modal-price-label">{item.owned ? "You own this" : "Price"}</span>
              <span className="store-modal-price-value">
                {priceWc <= 0
                  ? <span className="free-label">Free</span>
                  : <>
                      <span className={usingPasses ? "pass-ico" : "wc-ico"} aria-hidden="true" />
                      {discount > 0 && !item.owned && <s className="store-modal-was">{priceWc.toLocaleString()}</s>}
                      <span>{(item.owned ? priceWc : finalWc).toLocaleString()}</span>
                      <span className="sr-only">{usingPasses ? " Creator Passes" : " Wind Charges"}</span>
                    </>}
              </span>
              {!item.owned && priceWc > 0 && !usingPasses && Number.isFinite(balance) && (
                <span className={`store-modal-balance ${short ? "short" : ""}`}>
                  {short ? `You have ${balance.toLocaleString()}, ${short.toLocaleString()} short` : `You have ${balance.toLocaleString()}`}
                </span>
              )}
            </div>
            <div className="store-modal-buttons">
              <button type="button" className="modal-close" onClick={onClose}>Close</button>
              {item.owned
                ? <button type="button" className="modal-buy" onClick={() => onEquip(item)} disabled={busy}>{item.equipped ? "Unequip" : "Equip"}</button>
                : short && onGetWindCharges
                  ? <button type="button" className="modal-buy" onClick={onGetWindCharges}>Get Wind Charges</button>
                  : <button type="button" className="modal-buy" onClick={() => onBuy(item, currency)} disabled={busy}>{busy ? <I.Spin /> : priceWc <= 0 ? "Claim" : usingPasses ? "Test buy" : "Buy"}</button>}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
