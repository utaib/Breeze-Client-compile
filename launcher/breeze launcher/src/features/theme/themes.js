import { rgbOf, toHex, luminance, contrast, rgba, mix } from "./color.js";
import { backdropGuard } from "./backdrop.js";

/**
 * The launcher's one theme system (1.1.0).
 *
 * A theme is a material (background, surfaces, borders, text, defined per
 * theme in BreezeV2.css as .theme-<id>) plus an accent ramp (here). The chosen
 * theme id lives in the launcher's settings (`settings.theme`, saved by the
 * Rust side). "custom" is the player's own theme: a preset material with
 * their accent and, if they want, their own panel colour and opacity. Its
 * values are kept on this computer (localStorage), validated on every read:
 *
 *   { version: 1, base: <preset id>, accent: "#rrggbb" | null,
 *     panel: "#rrggbb" | null, panelOpacity: 40-100 }
 *
 * Every theme, custom included, comes out of themeStyle() as one class and
 * one set of CSS variables on the root element, so every page and component
 * that uses the variables follows it; nothing keeps a second copy of colours.
 * A custom panel colour also picks light or dark text from the panel's own
 * brightness, so text stays readable whatever colour is chosen.
 */

// Each theme is a full material identity: background treatment, surface
// opacity, blur level, border style, and shadows are defined per-theme in
// BreezeV2.css (.theme-<id> blocks). The entries here carry the accent color
// ramp plus display metadata for the theme gallery.
export const THEMES = [
  { id: "glass", name: "Breeze Glass", tagline: "Deep blue glass", accent: "rgba(120,178,255,0.95)", accentDim: "rgba(100,160,255,0.32)", accentGhost: "rgba(90,148,255,0.12)", borderAccent: "rgba(110,168,255,0.28)", glow: "rgba(90,148,255,0.16)" },
  { id: "black", name: "Onyx", tagline: "Matte black, zero noise", accent: "rgba(210,224,248,0.92)", accentDim: "rgba(190,205,235,0.28)", accentGhost: "rgba(180,200,235,0.08)", borderAccent: "rgba(190,205,235,0.22)", glow: "rgba(190,205,235,0.1)" },
  { id: "white", name: "Frost", tagline: "Light frosted glass", accent: "rgba(45,105,235,0.92)", accentDim: "rgba(45,105,235,0.3)", accentGhost: "rgba(45,105,235,0.08)", borderAccent: "rgba(45,105,235,0.24)", glow: "rgba(45,105,235,0.12)" },
  { id: "silver", name: "Titanium", tagline: "Brushed metal panels", accent: "rgba(186,204,236,0.94)", accentDim: "rgba(166,186,222,0.3)", accentGhost: "rgba(156,176,214,0.1)", borderAccent: "rgba(166,186,222,0.26)", glow: "rgba(156,176,214,0.12)" },
  { id: "midnight", name: "Dusk", tagline: "Violet gradient wash", accent: "rgba(172,142,255,0.95)", accentDim: "rgba(172,142,255,0.32)", accentGhost: "rgba(172,142,255,0.12)", borderAccent: "rgba(172,142,255,0.26)", glow: "rgba(172,142,255,0.14)" },
  { id: "forest", name: "Grove", tagline: "Mossy dark green", accent: "rgba(98,214,152,0.94)", accentDim: "rgba(98,214,152,0.3)", accentGhost: "rgba(98,214,152,0.1)", borderAccent: "rgba(98,214,152,0.24)", glow: "rgba(98,214,152,0.12)" },
  { id: "ember", name: "Ember", tagline: "Warm charcoal + fire", accent: "rgba(255,146,92,0.95)", accentDim: "rgba(255,146,92,0.32)", accentGhost: "rgba(255,146,92,0.11)", borderAccent: "rgba(255,146,92,0.26)", glow: "rgba(255,146,92,0.13)" },
  { id: "arctic", name: "Arctic", tagline: "Ice blue clarity", accent: "rgba(118,212,255,0.95)", accentDim: "rgba(118,212,255,0.3)", accentGhost: "rgba(118,212,255,0.1)", borderAccent: "rgba(118,212,255,0.24)", glow: "rgba(118,212,255,0.12)" },
  { id: "rose", name: "Rose Noir", tagline: "Plum dark + pink", accent: "rgba(255,122,188,0.95)", accentDim: "rgba(255,122,188,0.3)", accentGhost: "rgba(255,122,188,0.11)", borderAccent: "rgba(255,122,188,0.24)", glow: "rgba(255,122,188,0.12)" },
  { id: "abyss", name: "Abyss", tagline: "Bottom-of-ocean teal", accent: "rgba(84,222,202,0.92)", accentDim: "rgba(84,222,202,0.28)", accentGhost: "rgba(84,222,202,0.09)", borderAccent: "rgba(84,222,202,0.22)", glow: "rgba(84,222,202,0.1)" },
];

export const CUSTOM_THEME_ID = "custom";
export const CUSTOM_THEME_KEY = "breeze.theme.custom";
const HEX = /^#[0-9a-f]{6}$/i;

/**
 * Each preset's material as BreezeV2.css draws it, for the readability checks:
 * the window colour, the panel colour and how opaque panels are (0 for the
 * glass themes, whose panels are a faint tinted sheen, given as [colour,
 * opacity]), the top bar and the
 * side rail ([colour, opacity]), and the body, secondary and tertiary text colours.
 * Keep in step with the .theme-<id> blocks.
 */
export const PRESET_MATERIAL = {
  glass: { bg: "#0d1220", panel: "#16213a", panelAlpha: 0, sheen: ["#ffffff", 0.045], chrome: ["#090c16", 0.72], deep: ["#080b14", 0.86], text: ["#e7e9ee", "#a6adba", "#787f8c"] },
  black: { bg: "#0a0b0d", panel: "#101114", panelAlpha: 1, chrome: ["#050607", 1], deep: ["#050607", 1], text: ["#e7e9ee", "#9aa0ab", "#787f8c"] },
  white: { bg: "#e9ebf0", panel: "#ffffff", panelAlpha: 1, chrome: ["#ffffff", 0.85], deep: ["#dfe3ea", 1], text: ["#1a2233", "#4b5568", "#778092"], darkText: true },
  silver: { bg: "#141a24", panel: "#1a2432", panelAlpha: 1, chrome: ["#10151d", 1], deep: ["#10151d", 1], text: ["#cfdae6", "#8da2b8", "#787f8c"] },
  midnight: { bg: "#100d1e", panel: "#1a1630", panelAlpha: 0, sheen: ["#beaaff", 0.055], chrome: ["#0d0a18", 0.74], deep: ["#0c0a16", 0.88], text: ["#e7e9ee", "#a6adba", "#787f8c"] },
  forest: { bg: "#0d140f", panel: "#131c15", panelAlpha: 1, chrome: ["#0a100c", 1], deep: ["#0a100c", 1], text: ["#e2ece4", "#9db3a2", "#787f8c"] },
  ember: { bg: "#181310", panel: "#201a15", panelAlpha: 1, chrome: ["#141008", 1], deep: ["#141008", 1], text: ["#ece5dc", "#b3a698", "#787f8c"] },
  arctic: { bg: "#0d1926", panel: "#122232", panelAlpha: 1, chrome: ["#0a1420", 1], deep: ["#0a1420", 1], text: ["#dcebf8", "#93aec4", "#787f8c"] },
  rose: { bg: "#170d14", panel: "#1e1520", panelAlpha: 0, sheen: ["#ffaad2", 0.05], chrome: ["#130910", 0.75], deep: ["#140a11", 0.88], text: ["#e7e9ee", "#a6adba", "#787f8c"] },
  abyss: { bg: "#0a0a0b", panel: "#0a0a0b", panelAlpha: 1, chrome: ["#0a0a0b", 1], deep: ["#0a0a0b", 1], text: ["#e7e9ee", "#a6adba", "#787f8c"] },
};

/** Surface colours of each preset, for the custom panel's starting point and the contrast check. */
export const PRESET_SURFACE = Object.fromEntries(Object.entries(PRESET_MATERIAL).map(([id, m]) => [id, m.panel]));

export const presetById = (id) => THEMES.find((t) => t.id === id) || THEMES[0];

export { toHex, luminance, contrast };

export function sanitizeCustomTheme(raw) {
  const t = raw && typeof raw === "object" ? raw : {};
  const base = THEMES.some((p) => p.id === t.base) ? t.base : "glass";
  const accent = typeof t.accent === "string" && HEX.test(t.accent) ? t.accent.toLowerCase() : null;
  const panel = typeof t.panel === "string" && HEX.test(t.panel) ? t.panel.toLowerCase() : null;
  const op = Math.round(Number(t.panelOpacity));
  const panelOpacity = Number.isFinite(op) ? Math.min(100, Math.max(40, op)) : 100;
  return { version: 1, base, accent, panel, panelOpacity };
}

export function loadCustomTheme(storage) {
  try {
    const text = storage?.getItem(CUSTOM_THEME_KEY);
    return sanitizeCustomTheme(text ? JSON.parse(text) : null);
  } catch {
    return sanitizeCustomTheme(null);
  }
}

export function saveCustomTheme(theme, storage) {
  try {
    storage?.setItem(CUSTOM_THEME_KEY, JSON.stringify(sanitizeCustomTheme(theme)));
    return true;
  } catch {
    return false;
  }
}

function accentVars(color) {
  const rgb = rgbOf(color);
  if (!rgb) return {};
  return {
    "--accent": rgba(rgb, 0.95),
    "--accent-dim": rgba(rgb, 0.32),
    "--accent-ghost": rgba(rgb, 0.12),
    "--border-accent": rgba(rgb, 0.28),
    "--accent-glow": rgba(rgb, 0.16),
  };
}

const LIGHT_TEXT = { "--text-primary": "#e7e9ee", "--text-secondary": "#a6adba", "--text-tertiary": "#787f8c", "--text-faint": "#666d7a" };
const DARK_TEXT = { "--text-primary": "#1a2233", "--text-secondary": "#4b5568", "--text-tertiary": "#5d6678", "--text-faint": "#737c8e" };
// Status colours for each kind of surface: the bright ones (:root) are made
// for dark panels, the deeper ones (.theme-white) for light panels.
const STATUS_FOR_DARK = {
  "--go": "#27b95f", "--go-hover": "#35cb6e", "--go-pressed": "#1fa251",
  "--ok": "#3dd68c", "--ok-dim": "rgba(61,214,140,0.13)", "--warn": "#f5c542", "--warn-dim": "rgba(245,197,66,0.1)",
  "--err": "#ef5350", "--err-dim": "rgba(239,83,80,0.1)", "--coin": "#f5c542", "--coin-dim": "rgba(245,197,66,0.08)",
};
const STATUS_FOR_LIGHT = {
  "--go": "#15803d", "--go-hover": "#166534", "--go-pressed": "#14532d",
  "--ok": "#15803d", "--ok-dim": "rgba(21,128,61,0.11)", "--warn": "#b45309", "--warn-dim": "rgba(180,83,9,0.11)",
  "--err": "#b91c1c", "--err-dim": "rgba(185,28,28,0.10)", "--coin": "#b45309", "--coin-dim": "rgba(180,83,9,0.10)",
};

/** Is this panel colour light enough to need dark text? */
export const needsDarkText = (panel) => contrast(panel, "#000000") > contrast(panel, "#ffffff");

/**
 * Text colours for a panel colour. The theme's own light or dark ramp when it
 * reads well (WCAG AA 4.5:1 for body text, 3:1 for secondary); on mid-tone
 * panels, where neither does, a ramp from pure black or white towards the
 * panel, which always reaches 4.5:1.
 */
export function textRampFor(panel) {
  const dark = needsDarkText(panel);
  const ramp = dark ? DARK_TEXT : LIGHT_TEXT;
  if (contrast(ramp["--text-primary"], panel) >= 4.5 && contrast(ramp["--text-secondary"], panel) >= 3
    && contrast(ramp["--text-tertiary"], panel) >= 3) return ramp;
  const ink = dark ? [0, 0, 0] : [255, 255, 255];
  const rgb = rgbOf(panel);
  // Each step is the ink moved towards the panel as far as it can go while
  // still reading (labels at 3:1), so the ramp keeps its order.
  const step = (most, ratio) => {
    for (let t = most; t > 0; t -= 0.05) {
      const c = mix(ink, rgb, t);
      if (contrast(c, panel) >= ratio) return toHex(c);
    }
    return toHex(ink);
  };
  return {
    "--text-primary": toHex(ink),
    "--text-secondary": step(0.2, 3.2),
    "--text-tertiary": step(0.35, 3.2),
    "--text-faint": step(0.45, 2.2),
  };
}

/**
 * The root element's class and CSS variables for a theme id, with the custom
 * theme's values when the id is "custom". With `backdrop` ({ stats, adjust }
 * from backdrop.js, for a background image that has loaded) the translucent
 * layers are made as solid as the image needs for their text to read, and the
 * dim layer takes the theme's window colour. Returns
 * { id, className, vars, material, guard }.
 */
export function themeStyle(themeId, custom, backdrop = null) {
  const out = themeId !== CUSTOM_THEME_ID ? presetStyle(themeId) : customStyle(custom);
  out.vars["--bg-scrim"] = rgbOf(out.material.bg).join(" ");
  out.guard = null;
  if (backdrop) {
    const { vars, report } = backdropGuard(out.material, backdrop.stats || null, backdrop.adjust);
    Object.assign(out.vars, vars);
    out.guard = report;
  }
  return out;
}

function presetStyle(themeId) {
  const t = presetById(themeId);
  return {
    id: t.id,
    className: `theme-${t.id}`,
    material: PRESET_MATERIAL[t.id],
    vars: {
      "--accent": t.accent,
      "--accent-dim": t.accentDim,
      "--accent-ghost": t.accentGhost,
      "--border-accent": t.borderAccent,
      "--accent-glow": t.glow,
    },
  };
}

function customStyle(custom) {
  const c = sanitizeCustomTheme(custom);
  const base = presetById(c.base);
  const material = { ...PRESET_MATERIAL[base.id] };
  const vars = {
    "--accent": base.accent,
    "--accent-dim": base.accentDim,
    "--accent-ghost": base.accentGhost,
    "--border-accent": base.borderAccent,
    "--accent-glow": base.glow,
    ...(c.accent ? accentVars(c.accent) : {}),
  };
  if (c.panel) {
    const rgb = rgbOf(c.panel);
    const dark = needsDarkText(c.panel);
    const ramp = textRampFor(c.panel);
    const towards = dark ? [0, 0, 0] : [255, 255, 255];
    const a = c.panelOpacity / 100;
    Object.assign(vars, {
      "--surface": rgba(rgb, a),
      "--surface-hover": rgba(mix(rgb, towards, 0.06), a),
      "--surface-active": rgba(mix(rgb, towards, 0.12), Math.min(1, a + 0.05)),
      "--border": dark ? "rgba(15,23,42,0.1)" : "rgba(255,255,255,0.07)",
      "--border-hover": dark ? "rgba(15,23,42,0.18)" : "rgba(255,255,255,0.12)",
      ...ramp,
    });
    // The base theme's accent was made for its own panels. On panels it does
    // not read on (a light blue on light panels), and when the player has not
    // picked one, it is deepened or lightened until it does (4.5:1).
    if (!c.accent) Object.assign(vars, accentVars(readableAccent(base.accent, c.panel)));
    Object.assign(material, { panel: toHex(rgb), panelAlpha: a, text: [ramp["--text-primary"], ramp["--text-secondary"], ramp["--text-tertiary"]], darkText: dark });
    // Text also sits straight on the window (titles, the side rail). When the
    // panels need the other kind of text than the base theme's, the window
    // takes the panel's colour family too, or that text would vanish. It is a
    // little deeper than the panels, unless that would make its text fail.
    if (dark !== needsDarkText(PRESET_SURFACE[base.id])) {
      const deeper = (t) => {
        const c2 = mix(rgb, [0, 0, 0], t);
        return contrast(ramp["--text-primary"], rgba(c2, 1)) >= 4.5 ? c2 : rgb;
      };
      const bg = deeper(dark ? 0.08 : 0.3);
      const deep = deeper(dark ? 0.14 : 0.45);
      Object.assign(vars, {
        "--bg": rgba(bg, 1),
        "--bg-deep": rgba(deep, 1),
        "--glass-bg": rgba(bg, 0.9),
        "--canvas-image": "none",
        ...(dark ? STATUS_FOR_LIGHT : STATUS_FOR_DARK),
      });
      Object.assign(material, { bg: toHex(bg), chrome: [toHex(bg), 0.9], deep: [toHex(deep), 1] });
    }
  }
  return { id: CUSTOM_THEME_ID, className: `theme-${base.id} theme-custom`, material, vars };
}

/** `accent`, deepened or lightened as little as needed to reach 4.5:1 on `panel`. */
export function readableAccent(accent, panel) {
  const rgb = rgbOf(accent);
  if (!rgb || contrast(rgb, panel) >= 4.5) return toHex(rgb) || accent;
  const towards = needsDarkText(panel) ? [0, 0, 0] : [255, 255, 255];
  for (let t = 0.05; t <= 1; t += 0.05) {
    const c = mix(rgb, towards, t);
    if (contrast(c, panel) >= 4.5) return toHex(c);
  }
  return toHex(towards);
}

/** A note when the chosen accent would be hard to see on the panels, or null. */
export function accentWarning(custom) {
  const c = sanitizeCustomTheme(custom);
  if (!c.accent) return null;
  const panel = c.panel || PRESET_SURFACE[c.base];
  return contrast(c.accent, panel) < 2.2 ? "This accent is hard to see on these panels. A lighter or darker one will read better." : null;
}
