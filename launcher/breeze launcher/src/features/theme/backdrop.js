import { rgbOf, luminance, contrast, over, mix, rgba } from "./color.js";

/**
 * Keeps the launcher readable over the player's own background image.
 *
 * The image is measured once when it loads (measureImage): its light and dark
 * ends (the 98th and 2nd percentile by brightness, so a few stars or specks do
 * not count), its average colour and how busy it is. What sits behind a panel
 * is then worked out as the window really draws it: the image with the
 * player's brightness and opacity over the theme's window colour, under the
 * dim layer (which is the theme's own window colour, so a light theme gets a
 * light haze rather than a dark one).
 *
 * backdropGuard() gives each translucent layer (panels, the top bar, the side
 * rail) the least opacity at which its text still passes WCAG AA against the
 * worst of those colours: 4.5:1 for body text and 3:1 for secondary and
 * tertiary text (labels), with a little headroom for scaling. A busy image
 * also gets a frosted blur behind the panels: a contrast ratio assumes a
 * flat colour, and a busy picture breaks text up even when the ratio passes. A dark night sky leaves Breeze Glass as
 * glassy as it is; a bright sky or a busy picture makes the panels as solid as
 * they need to be and no more. Opaque themes are never touched. An image that
 * could not be measured is treated as the worst case (pure white and pure
 * black), so text still reads.
 *
 * It also picks the character stage's treatment over the image: an outline
 * dark or light against the image's average, and how strong a soft halo of
 * the window colour behind the player should be (stronger on busy images).
 */

const PRIMARY = 4.7; // 4.5 plus headroom
const SECONDARY = 3.2; // 3 plus headroom
const BUSY = 0.12; // luminance spread above which an image counts as busy

/** Light and dark ends, average and busyness of RGBA pixel data. */
export function measurePixels(data) {
  const px = [];
  let clear = 0;
  for (let i = 0; i + 3 < data.length; i += 4) {
    if (data[i + 3] < 128) {
      clear++;
      continue;
    }
    px.push([data[i], data[i + 1], data[i + 2]]);
  }
  if (!px.length) return clear ? { light: null, dark: null, mean: null, busy: 0, clear: true } : null;
  const lum = px.map((p) => luminance(p));
  const order = lum.map((_, i) => i).sort((a, b) => lum[a] - lum[b]);
  const at = (q) => px[order[Math.min(order.length - 1, Math.max(0, Math.round(q * (order.length - 1))))]];
  const mean = [0, 1, 2].map((k) => Math.round(px.reduce((s, p) => s + p[k], 0) / px.length));
  const avg = lum.reduce((s, v) => s + v, 0) / lum.length;
  const busy = Math.sqrt(lum.reduce((s, v) => s + (v - avg) ** 2, 0) / lum.length);
  return { light: at(0.98), dark: at(0.02), mean, busy: Math.round(busy * 1000) / 1000, clear: clear > 0 };
}

/** The settings that change how the image is drawn, from the launcher settings. */
export function adjustOf(settings = {}) {
  const n = (v, d) => (Number.isFinite(Number(v)) ? Number(v) : d);
  return {
    dim: Math.min(0.9, Math.max(0, n(settings.bgDim, 35) / 100)),
    brightness: Math.min(1.6, Math.max(0.4, n(settings.bgBrightness, 100) / 100)),
    opacity: Math.min(1, Math.max(0.2, n(settings.bgOpacity, 100) / 100)),
  };
}

/** One image colour as the window shows it: brightness, then opacity over the window, then the dim layer. */
export function seenColour(pixel, adjust, windowColour) {
  const lit = rgbOf(pixel).map((v) => Math.min(255, v * adjust.brightness));
  const shown = over(lit, adjust.opacity, windowColour);
  return over(windowColour, adjust.dim, shown);
}

/** The colours that can sit behind a translucent layer. Unknown image: anything, from black to white. */
export function behindColours(stats, adjust, windowColour) {
  const source = stats
    ? [stats.light, stats.dark, stats.mean].filter(Boolean).concat(stats.clear ? [rgbOf(windowColour)] : [])
    : [[255, 255, 255], [0, 0, 0]];
  return source.map((p) => seenColour(p, adjust, windowColour));
}

/** Does text ([body, secondary, tertiary?]) read on this colour, with headroom? */
export function readsOn([primary, secondary, tertiary], colour) {
  return contrast(primary, colour) >= PRIMARY && contrast(secondary, colour) >= SECONDARY
    && (!tertiary || contrast(tertiary, colour) >= SECONDARY);
}

/**
 * The least opacity (from `from` up) at which a layer of colour `ink` over any
 * of `behind` keeps `primary` text at 4.5:1 and `secondary` (and `tertiary`,
 * when given) at 3:1. Blending
 * can pass through a mid-tone, so it is the least value from which every
 * higher one passes; fully opaque is the theme's own design and always counts.
 */
export function opacityFor(ink, text, behind, from = 0) {
  const ok = (a) => behind.every((b) => readsOn(text, over(ink, a, b)));
  let least = 1;
  for (let i = 99; i >= Math.round(Math.max(0, from) * 100); i--) {
    if (!ok(i / 100)) break;
    least = i / 100;
  }
  return least;
}

/**
 * CSS variables that keep a theme readable over a measured background.
 * `material` is the theme's (themes.js materialOf). Returns { vars, report }.
 */
export function backdropGuard(material, stats, adjust) {
  const window = rgbOf(material.bg);
  const behind = behindColours(stats, adjust, window);
  const text = material.text;
  const towards = material.darkText ? [0, 0, 0] : [255, 255, 255];
  const vars = { "--bg-scrim": window.join(" ") };
  const report = {};

  const layer = (name, ink, from, write) => {
    const a = opacityFor(ink, text, behind, from);
    report[name] = a;
    if (a > from + 0.005) write(rgbOf(ink), a);
  };
  // A glass theme keeps its own faint sheen for panels while that reads over
  // the image. Otherwise its panels take the theme's panel colour, as solid as
  // needed (and at least a tint, never a bare hole in the page).
  const sheenReads = Boolean(material.sheen) && behind.every((b) => readsOn(text, over(material.sheen[0], material.sheen[1], b)));
  if (sheenReads) {
    report.panel = 0;
  } else {
    const a = Math.max(material.sheen ? 0.1 : 0, opacityFor(material.panel, text, behind, material.panelAlpha));
    report.panel = a;
    if (material.sheen || a > material.panelAlpha + 0.005) {
      const ink = rgbOf(material.panel);
      Object.assign(vars, {
        "--surface": rgba(ink, a),
        "--surface-hover": rgba(mix(ink, towards, 0.06), a),
        "--surface-active": rgba(mix(ink, towards, 0.12), Math.min(1, a + 0.05)),
      });
    }
  }
  layer("chrome", material.chrome[0], material.chrome[1], (ink, a) => { vars["--glass-bg"] = rgba(ink, a); });
  layer("rail", material.deep[0], material.deep[1], (ink, a) => { vars["--bg-deep"] = rgba(ink, a); });
  report.raised = !sheenReads && report.panel > material.panelAlpha + 0.005 && material.panelAlpha > 0;
  report.adjusted = Boolean(vars["--surface"]);
  vars["--panel-floor"] = report.adjusted ? "1" : "0";
  report.busy = !stats || stats.busy > BUSY;
  if (report.adjusted && report.busy) vars["--panel-blur"] = "blur(14px) saturate(120%)";

  // Text drawn straight on the window (page titles): when the image under the
  // dim layer leaves it below 4.5:1, it gets a soft halo of the window colour.
  report.windowText = Math.min(...behind.map((c) => contrast(text[0], c)));
  if (report.windowText < 4.5) {
    vars["--window-text-shadow"] = `0 1px 2px rgb(${window.join(" ")} / 0.9), 0 0 14px rgb(${window.join(" ")} / 0.8)`;
    vars["--window-icon-shadow"] = `drop-shadow(0 0 4px rgb(${window.join(" ")} / 0.9))`;
  }

  // The character over the image: an outline against its average, and a soft
  // halo of the window colour, stronger the busier the image.
  const average = seenColour(stats?.mean || [128, 128, 128], adjust, window);
  vars["--stage-outline"] = luminance(average) > 0.22 ? "rgba(8,10,16,0.55)" : "rgba(235,240,250,0.32)";
  const halo = stats ? Math.min(0.5, Math.max(0.1, 0.08 + stats.busy * 1.3)) : 0.4;
  vars["--stage-halo"] = String(Math.round(halo * 100) / 100);
  return { vars, report };
}

/**
 * Load an image and measure it. Resolves { ok: false } when the image cannot
 * be loaded at all (a missing or damaged file), { ok: true, stats } when it
 * can (stats null when it loaded but could not be read, which is then
 * treated as the worst case). The launcher's files come over Tauri's asset
 * protocol, which allows this page to read them.
 */
export function measureImage(url, { size = 192 } = {}) {
  const load = (cors) => new Promise((resolve) => {
    const img = new Image();
    if (cors) img.crossOrigin = "anonymous";
    img.decoding = "async";
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = url;
  });
  return (async () => {
    if (!url || typeof Image === "undefined") return { ok: false };
    const img = (await load(true)) || (await load(false));
    if (!img || !img.naturalWidth) return { ok: false };
    try {
      const scale = Math.min(1, size / Math.max(img.naturalWidth, img.naturalHeight));
      const w = Math.max(1, Math.round(img.naturalWidth * scale));
      const h = Math.max(1, Math.round(img.naturalHeight * scale));
      const canvas = document.createElement("canvas");
      canvas.width = w;
      canvas.height = h;
      const g = canvas.getContext("2d", { willReadFrequently: true });
      g.drawImage(img, 0, 0, w, h);
      return { ok: true, stats: measurePixels(g.getImageData(0, 0, w, h).data), width: img.naturalWidth, height: img.naturalHeight };
    } catch {
      return { ok: true, stats: null, width: img.naturalWidth, height: img.naturalHeight };
    }
  })();
}
