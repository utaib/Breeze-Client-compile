/**
 * Whether the player on the stage plays its moments (looking around,
 * checking the cape, waving). On by default; kept on this computer
 * (localStorage, validated on read). The system's "reduce motion" setting
 * always wins: with it on there are no moments and no idle sway, whatever
 * is saved here.
 */
export const MOTION_KEY = "breeze.motion";

export const sanitizeMotionPrefs = (raw) => ({ version: 1, moments: !(raw && typeof raw === "object" && raw.moments === false) });

function storage() {
  try { return window.localStorage; } catch { return null; }
}

export function loadMotionPrefs(store = storage()) {
  try {
    const text = store?.getItem(MOTION_KEY);
    return sanitizeMotionPrefs(text ? JSON.parse(text) : null);
  } catch {
    return sanitizeMotionPrefs(null);
  }
}

let current = null;
const listeners = new Set();

export function getMotionPrefs() {
  if (!current) current = loadMotionPrefs();
  return current;
}

/** Save and tell every stage. Returns false when it could not be saved (it still applies until restart). */
export function setMotionPrefs(next, store = storage()) {
  current = sanitizeMotionPrefs(next);
  let saved = true;
  try { store?.setItem(MOTION_KEY, JSON.stringify(current)); } catch { saved = false; }
  for (const fn of listeners) fn(current);
  return saved;
}

export function subscribeMotionPrefs(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

let reduceQuery;
/** The system's "reduce motion", read live. */
export function prefersReducedMotion() {
  try {
    if (reduceQuery === undefined) reduceQuery = window.matchMedia?.("(prefers-reduced-motion: reduce)") || null;
    return Boolean(reduceQuery?.matches);
  } catch {
    return false;
  }
}
