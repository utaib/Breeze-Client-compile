/**
 * Colour arithmetic shared by the theme (themes.js) and the background
 * measurement (backdrop.js). Colours are sRGB [r, g, b] 0-255 or CSS strings
 * ("#rrggbb", "rgb(..)", "rgba(..)"); blending is in sRGB, as the browser
 * composites, and contrast is WCAG 2's.
 */
const HEX = /^#[0-9a-f]{6}$/i;

export function rgbOf(color) {
  if (Array.isArray(color)) return color.length >= 3 ? color.slice(0, 3).map((v) => Math.max(0, Math.min(255, Math.round(v)))) : null;
  const s = String(color || "").trim();
  if (HEX.test(s)) {
    const n = parseInt(s.slice(1), 16);
    return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
  }
  const m = /^rgba?\((\d+),\s*(\d+),\s*(\d+)/i.exec(s);
  return m ? [Number(m[1]), Number(m[2]), Number(m[3])] : null;
}

export function toHex(color) {
  const rgb = rgbOf(color);
  return rgb ? `#${rgb.map((v) => v.toString(16).padStart(2, "0")).join("")}` : null;
}

/** WCAG relative luminance, 0 (black) to 1 (white). */
export function luminance(color) {
  const rgb = rgbOf(color);
  if (!rgb) return 0;
  const [r, g, b] = rgb.map((v) => {
    const c = v / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** WCAG contrast ratio between two colours, 1 to 21. */
export function contrast(a, b) {
  const [x, y] = [luminance(a), luminance(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
}

export const rgba = ([r, g, b], a) => `rgba(${r},${g},${b},${Math.round(a * 1000) / 1000})`;
export const mix = ([r, g, b], [r2, g2, b2], t) => [r + (r2 - r) * t, g + (g2 - g) * t, b + (b2 - b) * t].map(Math.round);
/** `top` at opacity `a` over `under`, as the browser blends it. */
export const over = (top, a, under) => mix(rgbOf(under), rgbOf(top), a);
