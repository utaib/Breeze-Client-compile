/**
 * The Wardrobe's capes, one list built from what the API says (1.1.0):
 *
 *   bought     yours, bought in the Store
 *   gift       yours, given by another player
 *   granted    yours, granted by Breeze (often private: not in the Store)
 *   personal   your own uploaded cape (Pay and Equip)
 *   free       anyone can have it from the Store
 *   store      in the Store, for Wind Charges
 *   revoked    was given to you and taken back
 *
 * Whether a player may wear a cape is the server's decision every time
 * (/capes/equip checks ownership); this list only decides what is shown and
 * how it is labelled, so a stale or cached list can never grant anything.
 * Pure: tested in Node.
 */

const HOW = ["bought", "gift", "granted", "personal", "free", "store", "revoked"];

export const CAPE_LABEL = {
  bought: "Yours",
  gift: "A gift",
  granted: "Granted to you",
  personal: "Your upload",
  free: "Free for everyone",
  store: "In the Store",
  revoked: "Taken back",
};

/** The order in the grid: what you wear, then yours, then the Store, then taken back. */
const RANK = { personal: 1, granted: 2, gift: 3, bought: 4, free: 5, store: 6, revoked: 7 };

const httpUrl = (u) => (typeof u === "string" && /^(https?:\/\/|\/(?!\/)|data:image\/|blob:)/i.test(u) ? u : null);
const frameList = (c) => (Array.isArray(c?.animation_frames) ? c.animation_frames.filter(httpUrl).slice(0, 120) : []);

function item(cape, extra) {
  const frames = frameList(cape);
  return {
    id: String(extra.id),
    name: String(cape?.name || (extra.how === "personal" ? "Your uploaded cape" : "Cape")).slice(0, 80),
    image_url: httpUrl(cape?.image_url) || (frames[0] ?? null),
    frames: frames.length > 1 ? frames : null,
    fps: Number(cape?.animation_fps) > 0 ? Number(cape.animation_fps) : null,
    rarity: cape?.rarity || null,
    price: Number.isFinite(Number(cape?.price_usd)) ? Number(cape.price_usd) : null,
    private: cape?.is_public === false,
    limited: cape?.is_limited === true,
    owned: false,
    equipped: false,
    ...extra,
  };
}

/**
 * Build the list from /capes/owned ({ capes, revoked }), the public /capes
 * list, and the player's own uploaded cape image (profile.capeUrl).
 */
export function capeCatalog({ owned = [], revoked = [], publicCapes = [], personalCapeUrl = null } = {}) {
  const publicById = new Map((publicCapes || []).filter((c) => c && c.id).map((c) => [String(c.id), c]));
  const out = [];
  const ownedIds = new Set();
  for (const entry of owned || []) {
    if (!entry || !entry.cape_id) continue;
    const id = String(entry.cape_id);
    if (ownedIds.has(id)) continue;
    ownedIds.add(id);
    const cape = { ...(publicById.get(id) || {}), ...(entry.cape || {}) };
    const how = HOW.includes(entry.obtained) && ["bought", "gift", "granted"].includes(entry.obtained) ? entry.obtained : "bought";
    out.push(item(cape, { id, owned: true, equipped: entry.equipped === true, how }));
  }
  const wearing = out.some((c) => c.equipped);
  const personal = httpUrl(personalCapeUrl);
  if (personal && !out.some((c) => c.image_url === personal)) {
    out.push(item({ name: "Your uploaded cape", image_url: personal, is_public: false }, { id: "personal", owned: true, equipped: !wearing, how: "personal" }));
  }
  for (const [id, cape] of publicById) {
    if (ownedIds.has(id)) continue;
    out.push(item(cape, { id, how: Number(cape.price_usd) > 0 ? "store" : "free" }));
  }
  for (const r of revoked || []) {
    if (!r || !r.cape_id || ownedIds.has(String(r.cape_id))) continue;
    const id = String(r.cape_id);
    if (out.some((c) => c.id === id && c.how === "revoked")) continue;
    out.push(item(r.cape || {}, { id, how: "revoked" }));
  }
  return out.sort((a, b) => (b.equipped - a.equipped) || (RANK[a.how] - RANK[b.how]) || a.name.localeCompare(b.name));
}

/** The filters above the grid. */
export const CAPE_FILTERS = [
  { id: "all", label: "All", test: () => true },
  { id: "mine", label: "Yours", test: (c) => c.owned },
  { id: "store", label: "Store", test: (c) => c.how === "store" || c.how === "free" },
];

/**
 * Where to crop a cape texture for a thumbnail of its outside (the 10 by 16
 * face at 1,1 of the 64 by 32 sheet; HD sheets are multiples of it; old
 * 22 by 17 sheets have the same face at the same place). CSS
 * background-size and background-position for a box the face's shape.
 */
export function capeCrop(width, height) {
  const w = Number(width);
  const h = Number(height);
  const legacy = w > 0 && h > 0 && Math.abs(w / h - 22 / 17) < 0.05;
  const [sheetW, sheetH] = legacy ? [22, 17] : [64, 32];
  const pct = (v) => `${Math.round(v * 10000) / 100}%`;
  return {
    backgroundSize: `${pct(sheetW / 10)} ${pct(sheetH / 16)}`,
    backgroundPosition: `${pct(1 / (sheetW - 10))} ${pct(1 / (sheetH - 16))}`,
  };
}
