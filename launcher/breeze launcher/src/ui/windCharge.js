/**
 * Tag icon resolution (Section 5.3).
 *
 * Real wind charge art already ships in public/ for the splash shower, and it
 * looks considerably better than a drawn glyph. The spec names blue for regular
 * users and red or purple for staff, which are exactly the variants that exist,
 * so a tag whose colour is close to one of them gets the real artwork.
 *
 * The art is fixed-colour PNG and cannot be tinted, so a tag using an arbitrary
 * custom colour falls back to the SVG glyph, which takes any colour. That keeps
 * "the exact colour is customisable by an admin" true rather than silently
 * snapping every custom tag to the nearest preset.
 */

/** The shipped variants, with the colour each one actually reads as. */
const CHARGE_ART = [
  { src: '/blue_wind_charge.png', rgb: [85, 200, 255] },
  { src: '/purple_wind_charge.png', rgb: [165, 110, 255] },
  { src: '/pink_wind_charge.png', rgb: [255, 120, 200] },
  { src: '/red_wind_charge.png', rgb: [255, 85, 85] },
  { src: '/wind_charge.png', rgb: [200, 235, 245] },
];

/**
 * How far a tag colour may sit from a variant and still use it. Tuned so the
 * spec's named tiers match while genuinely different colours (a yellow Donator
 * tag, for instance) keep their own colour via the SVG.
 */
const MATCH_DISTANCE = 90;

function parseHex(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex || '').trim());
  if (!m) return null;
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/**
 * The wind charge image for a tag colour, or null when nothing is close enough
 * and the caller should render the tintable SVG instead.
 */
export function windChargeArtFor(color) {
  const rgb = parseHex(color);
  if (!rgb) return null;
  let best = null;
  let bestDist = Infinity;
  for (const art of CHARGE_ART) {
    const d = Math.hypot(rgb[0] - art.rgb[0], rgb[1] - art.rgb[1], rgb[2] - art.rgb[2]);
    if (d < bestDist) { bestDist = d; best = art; }
  }
  return bestDist <= MATCH_DISTANCE ? best.src : null;
}
