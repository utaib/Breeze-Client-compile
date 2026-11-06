/**
 * The pure part of a course (course.js): reading one from the animation
 * file and where the player is on it at a time. No three.js and no DOM, so
 * it is tested in Node.
 */

/** The blocks a course can use, and the colours their broken bits take. */
export const BLOCK_KINDS = {
  oak_planks: { bits: ["#a37f48", "#b08a50", "#73562d"] },
  stone_bricks: { bits: ["#8b8b8b", "#7a7a7a", "#5c5c5c"] },
};

export const BLOCK = 16;
const HOWS = new Set(["stand", "walk", "run", "jump", "land"]);
const finite = (n, d = 0) => (typeof n === "number" && Number.isFinite(n) ? n : d);

/** A course from the animation file, checked: anything malformed is dropped. */
export function readCourse(raw) {
  if (!raw || typeof raw !== "object") return null;
  const path = (Array.isArray(raw.path) ? raw.path : [])
    .filter((k) => Array.isArray(k) && k.length >= 5 && HOWS.has(k[4]))
    .map(([t, x, z, y, how, face]) => ({
      t: finite(t), x: finite(x), z: finite(z), y: finite(y), how,
      face: typeof face === "number" && Number.isFinite(face) ? face : null,
    }))
    .sort((a, b) => a.t - b.t)
    .slice(0, 64);
  const blocks = (Array.isArray(raw.blocks) ? raw.blocks : [])
    .filter((b) => b && typeof b === "object" && BLOCK_KINDS[b.kind])
    .map((b) => ({
      x: finite(b.x), z: finite(b.z), y: Math.max(0, Math.round(finite(b.y))), kind: b.kind,
      in: finite(b.in), out: finite(b.out, Infinity), placed: Boolean(b.placed), broken: Boolean(b.broken),
    }))
    .slice(0, 16);
  const f = Array.isArray(raw.frame) && raw.frame.length === 4 && raw.frame.every((n) => Number.isFinite(n)) ? raw.frame : null;
  if (!path.length) return null;
  return { path, blocks, frame: f ? { x: f[0], y: f[1], halfWidth: Math.max(10, f[2]), halfHeight: Math.max(10, f[3]) } : null };
}

const smooth = (f) => f * f * (3 - 2 * f);
const clamp01 = (f) => Math.min(1, Math.max(0, f));
const len = (a, b) => Math.hypot(b.x - a.x, b.z - a.z);

/**
 * Where the feet are at time t (pure, so it is tested in Node): the stage
 * position (x, y up from the floor in player pixels, z), what the player is
 * doing, the way it moves or looks (yaw in radians, or null to keep the last
 * one), how far it has run so far (for the stride), and how far into a
 * landing it is.
 */
export function pathAt(path, t) {
  if (!path?.length) return null;
  let dist = 0;
  for (let i = 0; i < path.length; i++) {
    const a = path[i];
    const b = path[i + 1];
    if (!b || t < b.t) {
      if (!b || t < a.t) {
        // Before the first key or after the last: standing there.
        const k = t < a.t ? path[0] : a;
        return { x: k.x, y: k.y * BLOCK, z: k.z, how: "stand", yaw: k.face != null ? (k.face * Math.PI) / 180 : null, dist, air: false, landing: null, moving: false };
      }
      const span = Math.max(1e-3, b.t - a.t);
      const f = clamp01((t - a.t) / span);
      const moving = a.how === "walk" || a.how === "run" || a.how === "jump";
      // A jump up rises first and then moves over the edge; a jump down
      // moves out first and then drops, so neither clips the block's corner.
      let g = f;
      if (a.how === "jump" && b.y > a.y) g = Math.pow(f, 1.8);
      else if (a.how === "jump" && b.y < a.y) g = 1 - Math.pow(1 - f, 1.8);
      const x = moving ? a.x + (b.x - a.x) * g : a.x;
      const z = moving ? a.z + (b.z - a.z) * g : a.z;
      let y = a.y * BLOCK;
      if (a.how === "jump") {
        const y0 = a.y * BLOCK;
        const y1 = b.y * BLOCK;
        // An arc through both ends whose top is a little over the higher one.
        const apex = Math.max(y0, y1) + (y1 > y0 ? 5 : 7);
        const h = Math.max(0, apex - (y0 + y1) / 2);
        y = y0 + (y1 - y0) * f + 4 * h * f * (1 - f);
      } else if (moving) {
        y = (a.y + (b.y - a.y) * f) * BLOCK;
      }
      let yaw = null;
      if (moving && len(a, b) > 0.01) yaw = Math.atan2(b.x - a.x, b.z - a.z);
      else if (a.face != null) yaw = (a.face * Math.PI) / 180;
      return {
        x, y, z, how: a.how, yaw, dist: dist + (moving ? len(a, b) * g : 0),
        air: a.how === "jump", landing: a.how === "land" ? f : null, moving,
      };
    }
    if (a.how === "walk" || a.how === "run" || a.how === "jump") dist += len(a, b);
  }
  return null;
}

/** How far the camera has pulled back for the course, 0 to 1. */
export function framePull(elapsed, length) {
  return smooth(clamp01(Math.min(elapsed / 0.6, (length - elapsed) / 0.8)));
}

