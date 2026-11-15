/**
 * The player's capes as last loaded, kept on this computer so the Wardrobe and
 * the stage show them at once (and while offline) instead of an empty model:
 *
 *   breeze.wardrobe.<account uuid> = { version: 1, at, owned: [...], revoked: [...] }
 *
 * Shown only, never trusted: equipping always asks the API, which checks
 * ownership, and the list is replaced as soon as the API answers. Every read
 * is validated (sizes capped, addresses http(s) or the launcher's own), so a
 * damaged or edited file gives an empty list, not a broken page.
 */
const VERSION = 1;
const MAX = 500;
const keyFor = (uuid) => `breeze.wardrobe.${String(uuid || "").replace(/[^A-Za-z0-9-]/g, "").slice(0, 40)}`;
const str = (v, n) => (typeof v === "string" ? v.slice(0, n) : null);
const url = (v) => (typeof v === "string" && /^(https?:\/\/|\/(?!\/))/i.test(v) && v.length < 2048 ? v : null);

function cleanCape(c) {
  if (!c || typeof c !== "object") return {};
  const frames = Array.isArray(c.animation_frames) ? c.animation_frames.map(url).filter(Boolean).slice(0, 120) : undefined;
  return {
    id: str(c.id, 64),
    name: str(c.name, 80),
    image_url: url(c.image_url),
    rarity: str(c.rarity, 24),
    price_usd: Number.isFinite(Number(c.price_usd)) ? Number(c.price_usd) : null,
    is_public: c.is_public === false ? false : true,
    is_limited: c.is_limited === true,
    ...(frames ? { animation_frames: frames } : {}),
  };
}

export function cleanOwned(list) {
  return (Array.isArray(list) ? list : []).slice(0, MAX)
    .filter((e) => e && typeof e.cape_id === "string" && e.cape_id.length <= 64)
    .map((e) => ({
      cape_id: e.cape_id,
      equipped: e.equipped === true,
      obtained: ["bought", "gift", "granted"].includes(e.obtained) ? e.obtained : "bought",
      cape: cleanCape(e.cape),
    }));
}

const cleanRevoked = (list) => (Array.isArray(list) ? list : []).slice(0, MAX)
  .filter((r) => r && typeof r.cape_id === "string" && r.cape_id.length <= 64)
  .map((r) => ({ cape_id: r.cape_id, cape: cleanCape(r.cape) }));

export function loadWardrobeCache(uuid, storage) {
  if (!uuid) return null;
  try {
    const raw = JSON.parse(storage?.getItem(keyFor(uuid)) || "null");
    if (!raw || raw.version !== VERSION) return null;
    const at = Number(raw.at);
    return { at: Number.isFinite(at) ? at : null, owned: cleanOwned(raw.owned), revoked: cleanRevoked(raw.revoked) };
  } catch {
    return null;
  }
}

export function saveWardrobeCache(uuid, { owned, revoked }, storage, now = Date.now()) {
  if (!uuid) return false;
  try {
    storage?.setItem(keyFor(uuid), JSON.stringify({ version: VERSION, at: now, owned: cleanOwned(owned), revoked: cleanRevoked(revoked) }));
    return true;
  } catch {
    return false;
  }
}
