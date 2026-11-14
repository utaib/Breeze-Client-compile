/**
 * The play page's layout: which widgets are shown, where, and how big.
 *
 * A layout is plain data on a 12-column grid, saved locally in this format:
 *
 *   { version: 1, items: [{ id, widget, x, y, w, h, style }] }
 *
 * x and w are columns (0 to 11, 1 to 12), y and h are rows. style is the
 * widget's own look ({ opacity, accent }) or absent for the theme's.
 *
 * Everything here is pure: given a layout and the widget registry it returns
 * a new layout, so it can be tested without a browser. A saved layout is never
 * trusted: sanitize() drops what it does not recognise, keeps sizes inside
 * each widget's limits, puts required widgets back, and untangles overlaps, so
 * a damaged or hand-edited file can only ever produce a valid page. Nothing in
 * a layout is code or HTML; widgets are looked up by id in the registry.
 */

export const COLS = 12;
export const LAYOUT_VERSION = 1;
export const STORAGE_KEY = "breeze.dashboard.layout";
const MAX_ROWS = 60;
const HEX = /^#[0-9a-f]{6}$/i;

const int = (v, fallback) => (Number.isFinite(Number(v)) ? Math.round(Number(v)) : fallback);
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

/** Size limits of a widget, with defaults for anything it leaves out. */
export function limitsOf(def) {
  const min = def.min || { w: 2, h: 2 };
  const max = def.max || { w: COLS, h: 20 };
  return { minW: clamp(min.w, 1, COLS), minH: clamp(min.h, 1, MAX_ROWS), maxW: clamp(max.w, 1, COLS), maxH: clamp(max.h, 1, MAX_ROWS) };
}

/** A widget's own look, validated: panel opacity 40-100 and an accent colour. */
export function sanitizeStyle(style) {
  if (!style || typeof style !== "object") return undefined;
  const out = {};
  if (style.opacity !== undefined) {
    const o = int(style.opacity, NaN);
    if (Number.isFinite(o)) out.opacity = clamp(o, 40, 100);
  }
  if (typeof style.accent === "string" && HEX.test(style.accent)) out.accent = style.accent.toLowerCase();
  return Object.keys(out).length ? out : undefined;
}

export function overlaps(a, b) {
  return a !== b && a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
}

const byPosition = (a, b) => a.y - b.y || a.x - b.x;

/**
 * Pull every widget up as far as it goes, top to bottom, so there are no
 * gaps above anything. `pinned` stays where it is (the one being dragged).
 */
export function compact(items, pinned = null) {
  const placed = [];
  const out = [];
  const order = [...items].sort(byPosition);
  if (pinned) {
    const p = order.find((i) => i.id === pinned);
    if (p) placed.push(p);
  }
  for (const item of order) {
    if (pinned && item.id === pinned) {
      out.push(item);
      continue;
    }
    const moved = { ...item };
    while (moved.y > 0 && !placed.some((p) => overlaps({ ...moved, y: moved.y - 1 }, p))) moved.y -= 1;
    while (placed.some((p) => overlaps(moved, p))) moved.y += 1;
    placed.push(moved);
    out.push(moved);
  }
  return out.sort(byPosition);
}

/**
 * Untangle overlaps by pushing widgets down, keeping `fixed` where it is,
 * then compact. Every widget ends at a free place; nothing is lost.
 */
export function resolve(items, fixed = null) {
  const sorted = [...items].sort((a, b) => (a.id === fixed ? -1 : b.id === fixed ? 1 : byPosition(a, b)));
  const placed = [];
  for (const item of sorted) {
    const moved = { ...item };
    if (item.id !== fixed) {
      while (placed.some((p) => overlaps(moved, p))) moved.y += 1;
    }
    placed.push(moved);
  }
  return compact(placed, fixed);
}

/** The first place (top first, then left) a w by h widget fits. */
export function freeSpot(items, w, h) {
  for (let y = 0; y < MAX_ROWS * 2; y++) {
    for (let x = 0; x + w <= COLS; x++) {
      const probe = { id: "\u0000probe", x, y, w, h };
      if (!items.some((i) => overlaps(probe, i))) return { x, y };
    }
  }
  return { x: 0, y: items.reduce((m, i) => Math.max(m, i.y + i.h), 0) };
}

/** The default layout, from each widget's default place. */
export function defaultLayout(registry) {
  const items = Object.values(registry)
    .filter((def) => def.defaultPlace)
    .map((def) => ({ id: def.id, widget: def.id, ...def.defaultPlace }));
  return { version: LAYOUT_VERSION, items: resolve(items) };
}

/**
 * Make any input a valid layout. Returns { layout, problems }: problems lists
 * what was dropped or repaired, so the page can say the saved layout was
 * damaged instead of quietly changing it.
 */
export function sanitize(raw, registry) {
  const problems = [];
  if (!raw || typeof raw !== "object" || !Array.isArray(raw.items)) {
    return { layout: defaultLayout(registry), problems: raw == null ? [] : ["unreadable"] };
  }
  if (raw.version !== LAYOUT_VERSION) {
    // Only version 1 exists. A newer file (a later launcher, then a
    // downgrade) is not guessed at: the default comes back instead.
    return { layout: defaultLayout(registry), problems: [`version ${raw.version}`] };
  }
  const seen = new Set();
  const items = [];
  for (const it of raw.items.slice(0, 64)) {
    const def = it && registry[it.widget];
    if (!def) {
      problems.push(`unknown widget ${String(it?.widget).slice(0, 40)}`);
      continue;
    }
    if (seen.has(def.id)) {
      problems.push(`duplicate ${def.id}`);
      continue;
    }
    seen.add(def.id);
    const { minW, minH, maxW, maxH } = limitsOf(def);
    const w = clamp(int(it.w, def.defaultPlace?.w ?? minW), minW, maxW);
    const h = clamp(int(it.h, def.defaultPlace?.h ?? minH), minH, maxH);
    const x = clamp(int(it.x, 0), 0, COLS - w);
    const y = clamp(int(it.y, 0), 0, MAX_ROWS);
    const style = sanitizeStyle(it.style);
    items.push({ id: def.id, widget: def.id, x, y, w, h, ...(style ? { style } : {}) });
  }
  if (items.length === 0 && raw.items.length > 0) {
    // Nothing in it could be used: the whole default comes back, not a page
    // holding only the required widget.
    return { layout: defaultLayout(registry), problems };
  }
  for (const def of Object.values(registry)) {
    if (def.required && !seen.has(def.id)) {
      problems.push(`missing ${def.id}`);
      const { w, h } = def.defaultPlace || { w: limitsOf(def).minW, h: limitsOf(def).minH };
      items.push({ id: def.id, widget: def.id, ...freeSpot(items, w, h), w, h });
    }
  }
  return { layout: { version: LAYOUT_VERSION, items: resolve(items) }, problems };
}

function replace(layout, id, patch, registry) {
  const def = registry[id];
  if (!def) return layout;
  const { minW, minH, maxW, maxH } = limitsOf(def);
  const items = layout.items.map((i) => {
    if (i.id !== id) return i;
    const w = clamp(int(patch.w ?? i.w, i.w), minW, maxW);
    const h = clamp(int(patch.h ?? i.h, i.h), minH, maxH);
    return { ...i, w, h, x: clamp(int(patch.x ?? i.x, i.x), 0, COLS - w), y: clamp(int(patch.y ?? i.y, i.y), 0, MAX_ROWS) };
  });
  // The moved widget wins its place, the others make room, then everything
  // settles upwards (a widget dropped into empty space floats up to the
  // nearest one above it, as in most dashboard editors).
  return { ...layout, items: compact(resolve(items, id)) };
}

export const moveItem = (layout, id, x, y, registry) => replace(layout, id, { x, y }, registry);
export const resizeItem = (layout, id, w, h, registry) => replace(layout, id, { w, h }, registry);

export function removeItem(layout, id, registry) {
  if (registry[id]?.required) return layout;
  return { ...layout, items: compact(layout.items.filter((i) => i.id !== id)) };
}

export function addItem(layout, widgetId, registry) {
  const def = registry[widgetId];
  if (!def || layout.items.some((i) => i.widget === widgetId)) return layout;
  const { w, h } = def.defaultPlace || { w: limitsOf(def).minW, h: limitsOf(def).minH };
  const spot = freeSpot(layout.items, w, h);
  return { ...layout, items: resolve([...layout.items, { id: def.id, widget: def.id, ...spot, w, h }], def.id) };
}

export function setItemStyle(layout, id, style) {
  const clean = sanitizeStyle(style);
  return {
    ...layout,
    items: layout.items.map((i) => {
      if (i.id !== id) return i;
      const { style: _old, ...rest } = i;
      return clean ? { ...rest, style: clean } : rest;
    }),
  };
}

/** Rows the layout needs, for the grid's height. */
export const rowsUsed = (layout) => layout.items.reduce((m, i) => Math.max(m, i.y + i.h), 0);

/** Read the saved layout; any failure gives the default and says so. */
export function loadLayout(registry, storage) {
  let raw = null;
  try {
    const text = storage?.getItem(STORAGE_KEY);
    raw = text ? JSON.parse(text) : null;
  } catch {
    return { ...sanitize(undefined, registry), problems: ["unreadable"] };
  }
  return sanitize(raw, registry);
}

export function saveLayout(layout, storage) {
  try {
    storage?.setItem(STORAGE_KEY, JSON.stringify({ version: LAYOUT_VERSION, items: layout.items }));
    return true;
  } catch {
    return false;
  }
}
