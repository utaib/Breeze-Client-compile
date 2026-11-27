/**
 * How the Store sorts, groups and labels what it sells. Pure functions, so
 * the rules are tested without a browser (tests/unit/store.test.mjs).
 *
 * Rarity comes from the admin panel (common, uncommon, rare, epic,
 * legendary); older rows also carry premium, limited, exclusive, event or
 * free, and anything else counts as uncommon. Every item gets a `rar-<tone>`
 * class whose colour the stylesheet sets, so a rarity looks the same on a
 * card, in the spotlight and in the item's window.
 */

export const RARITY = {
  common: { rank: 0, label: "Common", tone: "common" },
  uncommon: { rank: 1, label: "Uncommon", tone: "uncommon" },
  free: { rank: 1, label: "Free", tone: "uncommon" },
  premium: { rank: 2, label: "Premium", tone: "premium" },
  rare: { rank: 2, label: "Rare", tone: "rare" },
  event: { rank: 3, label: "Event", tone: "epic" },
  epic: { rank: 3, label: "Epic", tone: "epic" },
  limited: { rank: 4, label: "Limited", tone: "legendary" },
  exclusive: { rank: 4, label: "Exclusive", tone: "legendary" },
  legendary: { rank: 5, label: "Legendary", tone: "legendary" },
};
const UNKNOWN_RARITY = { rank: 1, label: "Uncommon", tone: "uncommon" };

export function rarityOf(item) {
  const key = String(item?.rarity || "").trim().toLowerCase();
  return RARITY[key] || UNKNOWN_RARITY;
}

/** The slots the Store knows, in the order its tabs and sections use. */
export const SLOT_ORDER = ["cape", "hat", "pet", "wings", "aura", "shield", "back", "trail"];
const SLOT_NAMES = {
  cape: ["Cape", "Capes"],
  hat: ["Hat", "Hats"],
  pet: ["Pet", "Pets"],
  wings: ["Wings", "Wings"],
  aura: ["Aura", "Auras"],
  shield: ["Shield", "Shields"],
  back: ["Back item", "Back items"],
  trail: ["Trail", "Trails"],
};

export function slotOf(item) {
  if (item?._type === "cape") return "cape";
  const slot = String(item?.slot || "").trim().toLowerCase();
  return slot || "other";
}

export function slotLabel(slot, plural = false) {
  const names = SLOT_NAMES[slot];
  if (names) return names[plural ? 1 : 0];
  if (!slot || slot === "other") return plural ? "Other items" : "Item";
  const word = slot.charAt(0).toUpperCase() + slot.slice(1);
  return plural ? `${word}s` : word;
}

export const isAnimated = (item) => Array.isArray(item?.animation_frames) && item.animation_frames.length > 1;

const price = (item) => {
  const n = Number(item?.price_usd);
  return Number.isFinite(n) && n > 0 ? n : 0;
};
const created = (item) => {
  const t = Date.parse(item?.created_at || "");
  return Number.isFinite(t) ? t : 0;
};
const byName = (a, b) => String(a?.name || "").localeCompare(String(b?.name || ""));

/** Rarest first; among equals, animated first, then newest, then by name. */
const byStanding = (a, b) =>
  rarityOf(b).rank - rarityOf(a).rank ||
  Number(isAnimated(b)) - Number(isAnimated(a)) ||
  created(b) - created(a) ||
  byName(a, b);

export const SORTS = [
  { id: "featured", label: "Featured" },
  { id: "newest", label: "Newest" },
  { id: "price-asc", label: "Price, low to high" },
  { id: "price-desc", label: "Price, high to low" },
  { id: "name", label: "Name" },
];

export function sortItems(items, mode = "featured") {
  const list = [...(items || [])];
  switch (mode) {
    case "newest": return list.sort((a, b) => created(b) - created(a) || byName(a, b));
    case "price-asc": return list.sort((a, b) => price(a) - price(b) || byName(a, b));
    case "price-desc": return list.sort((a, b) => price(b) - price(a) || byName(a, b));
    case "name": return list.sort(byName);
    default: return list.sort(byStanding);
  }
}

/**
 * What the spotlight shows: items marked featured, then what the player does
 * not own yet, rarest first. At most `count`; fewer when the Store has fewer.
 */
export function featuredPicks(items, count = 5) {
  return [...(items || [])]
    .filter((item) => item && item.id != null)
    .sort((a, b) =>
      Number(Boolean(b.featured)) - Number(Boolean(a.featured)) ||
      Number(Boolean(a.owned)) - Number(Boolean(b.owned)) ||
      byStanding(a, b))
    .slice(0, Math.max(0, count));
}

/** Items in one section per slot, in SLOT_ORDER, unknown slots last. */
export function groupBySlot(items) {
  const groups = new Map();
  for (const item of items || []) {
    const slot = slotOf(item);
    if (!groups.has(slot)) groups.set(slot, []);
    groups.get(slot).push(item);
  }
  const rank = (slot) => {
    const i = SLOT_ORDER.indexOf(slot);
    return i === -1 ? SLOT_ORDER.length : i;
  };
  return [...groups.entries()]
    .sort(([a], [b]) => rank(a) - rank(b) || a.localeCompare(b))
    .map(([slot, list]) => ({ slot, title: slotLabel(slot, true), items: list }));
}

/** The tabs: All, Owned, then only the slots something is sold in (and the open one). */
export function storeTabs(items, current = "all") {
  const present = new Set((items || []).map(slotOf));
  const slots = SLOT_ORDER.filter((s) => present.has(s));
  for (const s of present) if (!slots.includes(s)) slots.push(s);
  if (current && current !== "all" && current !== "owned" && !slots.includes(current)) slots.push(current);
  return [
    { id: "all", label: "All" },
    { id: "owned", label: "Owned" },
    ...slots.map((s) => ({ id: s, label: slotLabel(s, true) })),
  ];
}

/** Which items a tab and a search leave. */
export function filterItems(items, filter = "all", search = "") {
  const q = String(search || "").trim().toLowerCase();
  return (items || []).filter((item) => {
    const tabOk = filter === "all" ? true : filter === "owned" ? Boolean(item.owned) : slotOf(item) === filter;
    if (!tabOk) return false;
    if (!q) return true;
    return [item.name, item.description, item.creator_name, slotLabel(slotOf(item))]
      .some((text) => String(text || "").toLowerCase().includes(q));
  });
}

/** A price after a promo code, as the API charges it (src/economy.js). */
export function discountedWc(priceWc, discountPercent) {
  const wc = Math.max(0, Number(priceWc) || 0);
  const pct = Math.min(100, Math.max(0, Number(discountPercent) || 0));
  return pct ? Math.max(0, Math.round(wc * (1 - pct / 100))) : wc;
}
