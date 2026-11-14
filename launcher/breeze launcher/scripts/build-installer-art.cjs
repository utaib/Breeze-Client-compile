#!/usr/bin/env node
/**
 * Generate the NSIS installer artwork.
 *
 * NSIS exposes exactly two images: a 150x57 header strip shown on the interior
 * pages, and a 164x314 sidebar shown on the welcome and finish pages. Those are
 * the only surfaces a standard MUI2 installer lets you brand, which is why the
 * rest of the window stays Windows grey no matter what is put in them. Painting
 * the whole dialog needs a custom .nsi template, which is a separate job.
 *
 * Both are written as uncompressed 24-bit BMPs, because that is the one format
 * NSIS reliably accepts. Drawn procedurally rather than exported from a design
 * tool so they regenerate at any size and never drift from the palette.
 *
 * Run: node scripts/build-installer-art.cjs   (wired into npm run build)
 */
const fs = require('fs');
const path = require('path');

const OUT = path.join(__dirname, '..', 'src-tauri', 'installer');

/** Breeze palette, matching BreezeV2.css. */
const BG_DEEP = [0x0e, 0x10, 0x17];
const BG_BLUE = [0x0d, 0x1c, 0x33];
const ACCENT = [0x78, 0xb2, 0xff];

const lerp = (a, b, t) => a + (b - a) * t;
const mix = (c1, c2, t) => [lerp(c1[0], c2[0], t), lerp(c1[1], c2[1], t), lerp(c1[2], c2[2], t)];
const clamp255 = (v) => Math.max(0, Math.min(255, Math.round(v)));

/**
 * A wind charge: a soft radial orb with a brighter core.
 *
 * Additive rather than replacing, so overlapping charges build up light the way
 * the in-game particle does instead of punching holes in each other.
 */
function addCharge(px, W, H, cx, cy, radius, strength) {
  const r2 = radius * radius;
  for (let y = Math.max(0, cy - radius) | 0; y < Math.min(H, cy + radius); y++) {
    for (let x = Math.max(0, cx - radius) | 0; x < Math.min(W, cx + radius); x++) {
      const dx = x - cx, dy = y - cy;
      const d2 = dx * dx + dy * dy;
      if (d2 > r2) continue;
      const d = Math.sqrt(d2) / radius;
      // Falls off fast, then a tight core. A plain linear falloff reads as a
      // flat disc at these sizes rather than as light.
      const halo = Math.pow(1 - d, 2.6) * 0.55;
      const core = Math.pow(Math.max(0, 1 - d * 2.6), 3) * 0.9;
      const a = (halo + core) * strength;
      const i = (y * W + x) * 3;
      px[i] += ACCENT[0] * a;
      px[i + 1] += ACCENT[1] * a;
      px[i + 2] += ACCENT[2] * a;
    }
  }
}

/** A faint diagonal streak, the same wind direction the splash scenes use. */
function addStreak(px, W, H, x0, y0, len, thickness, strength) {
  for (let t = 0; t < len; t++) {
    const x = x0 + t;
    const y = y0 + t * 0.22;
    for (let o = -thickness; o <= thickness; o++) {
      const yy = Math.round(y + o);
      if (x < 0 || x >= W || yy < 0 || yy >= H) continue;
      // Fade at both ends so the streak has no hard start or stop.
      const along = Math.sin((t / len) * Math.PI);
      const across = 1 - Math.abs(o) / (thickness + 1);
      const a = along * across * strength;
      const i = (yy * W + x) * 3;
      px[i] += ACCENT[0] * a;
      px[i + 1] += ACCENT[1] * a;
      px[i + 2] += ACCENT[2] * a;
    }
  }
}

function render(W, H, kind) {
  const px = new Float64Array(W * H * 3);

  // Ground: a diagonal blue gradient, deepest at the bottom right.
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const t = (x / W) * 0.45 + (y / H) * 0.55;
      const c = mix(BG_BLUE, BG_DEEP, t);
      const i = (y * W + x) * 3;
      px[i] = c[0]; px[i + 1] = c[1]; px[i + 2] = c[2];
    }
  }

  if (kind === 'sidebar') {
    addStreak(px, W, H, -20, H * 0.30, W + 40, 1, 0.20);
    addStreak(px, W, H, -40, H * 0.55, W + 60, 2, 0.14);
    addStreak(px, W, H, -10, H * 0.78, W + 30, 1, 0.10);
    addCharge(px, W, H, W * 0.52, H * 0.30, Math.min(W, H) * 0.34, 1.0);
    addCharge(px, W, H, W * 0.22, H * 0.62, Math.min(W, H) * 0.16, 0.55);
    addCharge(px, W, H, W * 0.80, H * 0.74, Math.min(W, H) * 0.12, 0.40);
  } else {
    // The header sits beside the title text, so the artwork stays right of
    // centre and low contrast; anything busier fights the wording.
    addStreak(px, W, H, W * 0.30, H * 0.30, W, 1, 0.16);
    addCharge(px, W, H, W * 0.82, H * 0.48, H * 0.62, 0.85);
    addCharge(px, W, H, W * 0.62, H * 0.72, H * 0.30, 0.35);
  }

  return px;
}

/**
 * Uncompressed 24-bit BMP.
 *
 * Rows are bottom-up and padded to a 4-byte boundary, and channels are stored
 * BGR. Getting any of those three wrong produces a file that opens fine in some
 * viewers and renders as garbage in NSIS.
 */
function encodeBmp(px, W, H) {
  const rowRaw = W * 3;
  const row = Math.ceil(rowRaw / 4) * 4;
  const pixels = row * H;
  const offset = 54;
  const buf = Buffer.alloc(offset + pixels);

  buf.write('BM', 0, 'ascii');
  buf.writeUInt32LE(offset + pixels, 2);
  buf.writeUInt32LE(offset, 10);
  buf.writeUInt32LE(40, 14);
  buf.writeInt32LE(W, 18);
  buf.writeInt32LE(H, 22);
  buf.writeUInt16LE(1, 26);
  buf.writeUInt16LE(24, 28);
  buf.writeUInt32LE(pixels, 34);

  for (let y = 0; y < H; y++) {
    const srcY = H - 1 - y; // bottom-up
    let p = offset + y * row;
    for (let x = 0; x < W; x++) {
      const i = (srcY * W + x) * 3;
      buf[p++] = clamp255(px[i + 2]); // B
      buf[p++] = clamp255(px[i + 1]); // G
      buf[p++] = clamp255(px[i]);     // R
    }
  }
  return buf;
}

function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const jobs = [
    { name: 'sidebar.bmp', w: 164, h: 314, kind: 'sidebar' },
    { name: 'header.bmp', w: 150, h: 57, kind: 'header' },
  ];
  for (const j of jobs) {
    const buf = encodeBmp(render(j.w, j.h, j.kind), j.w, j.h);
    fs.writeFileSync(path.join(OUT, j.name), buf);
    console.log(`build-installer-art: ${j.name} ${j.w}x${j.h} 24bpp ${(buf.length / 1024).toFixed(0)} KB`);
  }
}

main();
