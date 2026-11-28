import { useMemo, useState } from "react";
import StoreSpotlight from "../../features/store/StoreSpotlight";
import { ItemArt } from "../../features/store/StoreArt";
import WcPrice from "../../features/store/WcPrice";
import {
  SORTS, featuredPicks, filterItems, groupBySlot, isAnimated, rarityOf, slotLabel, slotOf, sortItems, storeTabs,
} from "../../features/store/storeLook";

const I = {
  Search: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" /></svg>,
  Store: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 01-8 0" /></svg>,
  Check: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>,
};

/** One item in the grid: its picture on a backdrop in its rarity's colour. */
function StoreCard({ item, onOpen }) {
  const rarity = rarityOf(item);
  const action = item.equipped ? "Equipped" : item.owned ? "Equip" : Number(item.price_usd) > 0 ? "Buy" : "Claim";
  return (
    <button
      type="button"
      data-store-item={`${item._type}:${item.id}`}
      className={`store-card rar-${rarity.tone} ${item._type === "cape" ? "is-cape" : "is-model"} ${item.owned ? "owned" : ""} ${item.equipped ? "equipped" : ""}`}
      onClick={() => onOpen(item)}
    >
      <span className="store-card-art">
        <span className="store-card-floor" aria-hidden="true" />
        <ItemArt item={item} />
        <span className="store-card-tags">
          <span className="store-chip rar">{rarity.label}</span>
          {isAnimated(item) && <span className="store-chip anim">Animated</span>}
        </span>
        {item.owned && (
          <span className="store-card-state"><I.Check />{item.equipped ? "Equipped" : "Owned"}</span>
        )}
      </span>
      <span className="store-card-body">
        <span className="store-card-name">{item.name}</span>
        <span className="store-card-meta">{item.description || slotLabel(slotOf(item))}</span>
        <span className="store-card-foot">
          <span className="item-price"><WcPrice priceUsd={item.price_usd} /></span>
          <span className={`store-card-action ${item.owned ? "equip" : "buy"}`}>{action}</span>
        </span>
      </span>
    </button>
  );
}

function emptyText(filter, search) {
  if (search) return `Nothing matches "${search}".`;
  if (filter === "owned") return "You don't own anything from the Store yet. What you buy or claim shows up here.";
  if (filter !== "all") return `No ${slotLabel(filter, true).toLowerCase()} in the Store yet.`;
  return "The Store is loading. Make sure you're signed in.";
}

/**
 * The Store: a spotlight of a few items worn by the player's character, then
 * everything for sale in one section per kind (capes, pets, hats...), each
 * card showing the item itself. Tabs list only the kinds the Store sells.
 */
export default function StorePage({ items, filter, setFilter, search, setSearch, onOpen, featureFlags, userSkinUrl, username }) {
  const [sort, setSort] = useState("featured");
  const tabs = useMemo(() => storeTabs(items, filter), [items, filter]);
  const visible = useMemo(() => filterItems(items, filter, search), [items, filter, search]);
  const sections = useMemo(() => groupBySlot(sortItems(visible, sort)), [visible, sort]);
  const picks = useMemo(() => featuredPicks(items, 5), [items]);
  const showSpotlight = filter === "all" && !search.trim() && picks.length > 0;

  if (featureFlags?.store_disabled) {
    return (
      <div className="sv page-enter store-off">
        <div className="store-empty-state">
          <div className="store-empty-icon"><I.Store /></div>
          <p><strong>The Store is closed for maintenance.</strong><br />Please check back soon.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="sv page-enter store-layout">
      <div className="vp store-vp">
        <div className="store-header-row">
          <div className="vtl"><I.Store /> Marketplace</div>
          <div className="store-tools">
            <label className="store-sort">
              <span className="sr-only">Sort by</span>
              <select value={sort} onChange={(e) => setSort(e.target.value)} aria-label="Sort by">
                {SORTS.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
              </select>
            </label>
            <div className="sw sw-compact">
              <I.Search />
              <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder="Search the marketplace" aria-label="Search the marketplace" />
            </div>
          </div>
        </div>
        <div className="tabs store-tabs">
          {tabs.map((tab) => (
            <button key={tab.id} type="button" className={`tab ${filter === tab.id ? "on" : ""}`} aria-pressed={filter === tab.id} onClick={() => setFilter(tab.id)}>
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      <div className="store-scroll">
        {showSpotlight && <StoreSpotlight picks={picks} onOpen={onOpen} userSkinUrl={userSkinUrl} username={username} />}

        {sections.map((section) => (
          <section key={section.slot} className="store-section" aria-label={section.title}>
            <div className="store-section-head">
              <h3>{section.title}</h3>
              <span className="store-section-count">{section.items.length}</span>
            </div>
            <div className="store-grid">
              {section.items.map((item) => <StoreCard key={`${item._type}:${item.id}`} item={item} onOpen={onOpen} />)}
            </div>
          </section>
        ))}

        {!visible.length && (
          <div className="store-empty-state">
            <div className="store-empty-icon"><I.Store /></div>
            <p>{emptyText(filter, search)}</p>
            {(search || filter !== "all") && (
              <button type="button" className="btn" onClick={() => { setSearch(""); setFilter("all"); }}>Show everything</button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
