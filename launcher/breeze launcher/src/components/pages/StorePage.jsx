import GlbPreview from "../GlbPreview";
import { CapeCardViewer } from "../SkinViewer3D";
import { priceInWc } from "../../services/breezeApi";

function WcPrice({ priceUsd }) {
  const wc = priceInWc(priceUsd);
  if (wc <= 0) return <span className="free-label">Free</span>;
  return <><span className="wc-ico" />{wc.toLocaleString()}</>;
}

const SLOTS = ["all", "owned", "cape", "hat", "pet", "wings", "aura", "shield", "back", "trail"];

function StoreAssetImage({ src, alt, slot }) {
  if (!src) return <CosmeticFallback slot={slot} label={alt} />;
  return <img src={src} alt={alt || slot} style={{ width: "100%", height: "100%", objectFit: "contain", borderRadius: 8 }} onError={(e) => { e.target.style.display = "none"; }} />;
}

function CosmeticFallback({ slot = "cosmetic", label = "Breeze cosmetic" }) {
  return (
    <div className="cosmetic-fallback" aria-label={label}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 2L2 7l10 5 10-5-10-5z" /><path d="M2 17l10 5 10-5" /><path d="M2 12l10 5 10-5" />
      </svg>
      <span>{slot}</span>
    </div>
  );
}

const I = {
  Search: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" /></svg>,
  Store: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 01-8 0" /></svg>,
};

/** Card artwork: capes render live on the player's own skin (3D, lazy-loaded
 *  as they scroll into view); cosmetics use their GLB model or thumbnail. */
function ItemArt({ item, userSkinUrl, size = 150 }) {
  if (item._type === "cape") {
    const frames = Array.isArray(item.animation_frames) && item.animation_frames.length > 1 ? item.animation_frames : null;
    return (
      <CapeCardViewer
        skinUrl={userSkinUrl}
        capeUrl={item.image_url}
        animationFrames={frames}
        animationFps={item.animation_fps || null}
        width={Math.round(size * 0.78)}
        height={size}
      />
    );
  }
  if (item._type === "cosmetic" && item.model_url && !item.thumbSrc) {
    return <GlbPreview src={item.model_url} size={size} />;
  }
  return <StoreAssetImage src={item.thumbSrc} alt={item.name} slot={item.slot} />;
}

export default function StorePage({ items, filter, setFilter, search, setSearch, onOpen, featureFlags, userSkinUrl }) {
  if (featureFlags?.store_disabled) {
    return (
      <div className="sv page-enter" style={{ display: "flex", alignItems: "center", justifyContent: "center", flex: 1 }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: "var(--text-primary)", marginBottom: 6 }}>Store Unavailable</div>
          <div style={{ fontSize: 11, color: "var(--text-faint)" }}>This feature is currently under maintenance.</div>
        </div>
      </div>
    );
  }

  const visible = items.filter((item) => {
    const slotOk =
      filter === "all"
        ? true
        : filter === "owned"
        ? item.owned
        : item.slot === filter || item._type === filter;
    const searchOk = !search || (item.name || "").toLowerCase().includes(search.toLowerCase()) || (item.description || "").toLowerCase().includes(search.toLowerCase());
    return slotOk && searchOk;
  });

  const featured = visible.filter((i) => i.featured || i.rarity === "legendary" || i.rarity === "exclusive").slice(0, 3);
  const rest = visible.filter((i) => !featured.includes(i));

  return (
    <div className="sv page-enter store-layout">
      {/* Header */}
      <div className="vp store-vp">
        <div className="store-header-row">
          <div className="vtl"><I.Store /> Marketplace</div>
          <div className="sw sw-compact">
            <I.Search />
            <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search the marketplace" />
          </div>
        </div>
        <div className="tabs store-tabs">
          {SLOTS.map((slot) => (
            <button key={slot} className={`tab ${filter === slot ? "on" : ""}`} onClick={() => setFilter(slot)}>
              {slot === "all" ? "All" : slot.charAt(0).toUpperCase() + slot.slice(1)}
            </button>
          ))}
        </div>
      </div>

      <div className="store-scroll">
        {/* Featured row */}
        {featured.length > 0 && (
          <div className="store-featured-row">
            {featured.map((item) => (
              <button key={`${item._type}:${item.id}`} className="store-featured-card" onClick={() => onOpen(item)}>
                <div className="store-featured-art">
                  <ItemArt item={item} userSkinUrl={userSkinUrl} size={170} />
                </div>
                <div className="store-featured-info">
                  <span className={`badge ${item.rarity || "premium"}`}>{item.rarity || "Featured"}</span>
                  <div className="item-name" style={{ marginTop: 4 }}>{item.name}</div>
                  <div className="item-price" style={{ marginTop: 2 }}>
                    <WcPrice priceUsd={item.price_usd} />
                  </div>
                </div>
                {item.owned && <div className="store-owned-badge">{item.equipped ? "Equipped" : "Owned"}</div>}
              </button>
            ))}
          </div>
        )}

        {/* Main grid */}
        <div className="store-grid">
          {rest.map((item) => (
            <button key={`${item._type}:${item.id}`} className={`store-item ${item.owned ? "owned" : ""} ${item.equipped ? "equipped-item" : ""}`} onClick={() => onOpen(item)}>
              <div className="item-thumb item-thumb-3d">
                <ItemArt item={item} userSkinUrl={userSkinUrl} size={150} />
                {Array.isArray(item.animation_frames) && item.animation_frames.length > 1 && (
                  <span className="animated-badge">Animated</span>
                )}
              </div>
              <div className="item-body">
                <div className="item-name">{item.name}</div>
                <div className="item-desc">{item.description || item.slot}</div>
                <div className="item-footer">
                  <div className="item-price">
                    <WcPrice priceUsd={item.price_usd} />
                  </div>
                  <span className={`item-buy ${item.owned ? "equip" : "buy"}`}>
                    {item.equipped ? "Equipped" : item.owned ? "Equip" : "Buy"}
                  </span>
                </div>
              </div>
            </button>
          ))}
        </div>
        {!visible.length && (
          <div className="store-empty-state">
            <div className="store-empty-icon">
              <I.Store />
            </div>
            <p>
              {search
                ? `No results for "${search}"`
                : filter === "owned"
                ? "You don't own any cosmetics yet, buy one to see it here"
                : filter !== "all"
                ? `No ${filter}s in the marketplace yet`
                : "The marketplace is loading, make sure you're signed in"}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}
