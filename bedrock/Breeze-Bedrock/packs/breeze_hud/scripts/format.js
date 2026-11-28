// Pure helpers for the Breeze HUD: no game imports, so they are unit-tested
// in Node (tools/test_format.mjs).

/** The parts of the line, in the order they are drawn and offered. */
export const PARTS = [
  { id: "coords", label: "Coordinates", on: true },
  { id: "facing", label: "Facing", on: true },
  { id: "clock", label: "Time of day and day", on: true },
  { id: "armour", label: "Armour and held item durability", on: true },
  { id: "effects", label: "Effects with time left", on: true },
];

/** @typedef {{ enabled: boolean, parts: Record<string, boolean>, lowOnly: boolean }} Settings */

/** @returns {Settings} */
export function defaultSettings() {
  /** @type {Record<string, boolean>} */
  const parts = {};
  for (const p of PARTS) parts[p.id] = p.on;
  return { enabled: true, parts, lowOnly: true };
}

/**
 * Settings from a stored string; anything unknown or broken falls back to
 * the defaults, so an old or edited value never stops the HUD.
 * @param {unknown} raw
 * @returns {Settings}
 */
export function parseSettings(raw) {
  const s = defaultSettings();
  if (typeof raw !== "string") return s;
  try {
    const v = JSON.parse(raw);
    if (typeof v.enabled === "boolean") s.enabled = v.enabled;
    if (typeof v.lowOnly === "boolean") s.lowOnly = v.lowOnly;
    if (v.parts && typeof v.parts === "object") {
      for (const p of PARTS) {
        if (typeof v.parts[p.id] === "boolean") s.parts[p.id] = v.parts[p.id];
      }
    }
  } catch {
    // keep defaults
  }
  return s;
}

/** Eight compass points from a yaw in degrees (Bedrock: 0 is south). */
export function facing(yaw) {
  const names = ["S", "SW", "W", "NW", "N", "NE", "E", "SE"];
  const i = Math.round((((yaw % 360) + 360) % 360) / 45) % 8;
  return names[i];
}

/** "06:30" from the time of day in ticks (0 is 06:00, 24000 a day). */
export function clock(ticks) {
  const minutes = Math.floor((((ticks + 6000) % 24000) / 1000) * 60);
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}`;
}

/** "1:05" from ticks. */
export function duration(ticks) {
  const total = Math.max(0, Math.ceil(ticks / 20));
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

/** "Speed II" style name from an effect type id and amplifier. */
export function effectName(typeId, amplifier) {
  const base = typeId
    .replace(/^minecraft:/, "")
    .split("_")
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
  const roman = ["", " II", " III", " IV", " V", " VI", " VII", " VIII", " IX", " X"];
  return base + (amplifier > 0 ? roman[amplifier] || ` ${amplifier + 1}` : "");
}

