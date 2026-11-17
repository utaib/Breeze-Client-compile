import { useState } from "react";
import { validatePromoCode } from "../services/promoCodes";


export default function PromoCodeInput({ token, priceUsd, appliedCode, appliedDiscount, onApply, onRemove, disabled }) {
  const [code, setCode]       = useState("");
  const [busy, setBusy]       = useState(false);
  const [error, setError]     = useState("");
  const applied = Boolean(appliedCode);
  const hasPrice = typeof priceUsd === "number" && priceUsd > 0;

  async function handleApply() {
    const trimmed = code.trim().toUpperCase();
    if (!trimmed) { setError("Enter a promo code"); return; }
    if (!token)   { setError("Sign in first"); return; }
    setBusy(true); setError("");
    try {
      const result = await validatePromoCode(token, trimmed);
      onApply?.(trimmed, result.discount_percent);
      setCode("");
    } catch (err) {
      setError(err?.message || "Invalid promo code");
    } finally {
      setBusy(false);
    }
  }

  function handleRemove() {
    onRemove?.();
    setError("");
  }

  
  const discountedPrice = applied && hasPrice
    ? Math.max(0, priceUsd * (1 - appliedDiscount / 100))
    : null;

  return (
    <div className="promo-input-wrap">
      {!applied ? (
        <>
          <div className="promo-input-row">
            <input
              className="settings-input promo-input"
              type="text"
              placeholder="Promo code (optional)"
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              onKeyDown={(e) => { if (e.key === "Enter") handleApply(); }}
              maxLength={20}
              disabled={busy || disabled}
              autoCapitalize="characters"
              spellCheck={false}
            />
            <button
              className="btn"
              onClick={handleApply}
              disabled={busy || disabled || !code.trim()}
              style={{ flexShrink: 0 }}
            >
              {busy ? "Checking…" : "Apply"}
            </button>
          </div>
          {error && <div className="promo-error">{error}</div>}
        </>
      ) : (
        <div className="promo-applied-row">
          <div className="promo-applied-info">
            <div className="promo-applied-code">
              <span className="promo-tag">✓ {appliedCode}</span>
              <span className="promo-pct">−{appliedDiscount}%</span>
            </div>
            {hasPrice && discountedPrice !== null && (
              <div className="promo-price-preview">
                <span className="promo-price-orig">${priceUsd.toFixed(2)}</span>
                <span className="promo-price-new">${discountedPrice.toFixed(2)}</span>
              </div>
            )}
          </div>
          <button
            className="btn"
            onClick={handleRemove}
            disabled={disabled}
            style={{ flexShrink: 0, fontSize: 10.5, padding: "3px 8px" }}
          >
            Remove
          </button>
        </div>
      )}
    </div>
  );
}