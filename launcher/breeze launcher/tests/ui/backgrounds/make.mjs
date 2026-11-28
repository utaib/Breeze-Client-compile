/**
 * Test wallpapers for tests/ui/backgrounds.spec.ts, drawn here (in Chromium's
 * canvas) so they are Breeze's own: no photos whose licences need tracking.
 * Deterministic: a seeded random, the same files every run.
 * Run from the launcher folder: node tests/ui/backgrounds/make.mjs
 */
import { chromium } from "@playwright/test";
import { writeFileSync } from "node:fs";

const W = 1280, H = 800;
const draw = () => {
  const W = 1280, H = 800;
  let seed = 7;
  const rnd = () => ((seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648);
  const range = (a, b) => a + Math.floor(rnd() * (b - a));
  const make = (paint) => {
    const c = document.createElement("canvas");
    c.width = W; c.height = H;
    const g = c.getContext("2d");
    paint(g);
    return c.toDataURL("image/jpeg", 0.82);
  };
  const vertical = (g, top, bottom) => {
    const lg = g.createLinearGradient(0, 0, 0, H);
    lg.addColorStop(0, top); lg.addColorStop(1, bottom);
    g.fillStyle = lg; g.fillRect(0, 0, W, H);
  };
  const out = {};
  // Bright: a daylight sky with clouds and a sun, almost white in places.
  out.bright = make((g) => {
    vertical(g, "rgb(150,200,255)", "rgb(245,250,255)");
    g.filter = "blur(26px)";
    for (let i = 0; i < 28; i++) {
      g.fillStyle = `rgba(255,255,255,${0.65 + rnd() * 0.35})`;
      const x = range(0, W), y = range(0, H), r = range(60, 200);
      g.beginPath(); g.ellipse(x, y, r * 1.8, r * 0.6, 0, 0, Math.PI * 2); g.fill();
    }
    g.filter = "none";
    g.fillStyle = "rgb(255,250,220)"; g.beginPath(); g.arc(1060, 140, 80, 0, Math.PI * 2); g.fill();
  });
  // Dark: a night sky with stars.
  out.dark = make((g) => {
    vertical(g, "rgb(4,6,16)", "rgb(20,28,54)");
    for (let i = 0; i < 500; i++) {
      const v = range(120, 255);
      g.fillStyle = `rgb(${v},${v},${Math.min(255, v + 20)})`;
      g.fillRect(range(0, W), range(0, H), 1 + (rnd() > 0.9), 1 + (rnd() > 0.9));
    }
  });
  // Busy: saturated Minecraft-like blocks with noise, white and black among them.
  out.busy = make((g) => {
    const palette = [[220, 60, 50], [250, 200, 40], [60, 170, 70], [40, 120, 230], [240, 240, 240],
      [30, 30, 30], [160, 90, 200], [250, 140, 40], [90, 220, 230], [120, 80, 40]];
    for (let by = 0; by < H; by += 32) for (let bx = 0; bx < W; bx += 32) {
      const base = palette[range(0, palette.length)];
      for (let py = by; py < by + 32; py += 4) for (let px = bx; px < bx + 32; px += 4) {
        const n = range(-28, 28);
        g.fillStyle = `rgb(${base.map((c) => Math.max(0, Math.min(255, c + n))).join(",")})`;
        g.fillRect(px, py, 4, 4);
      }
    }
  });
  // Gradient: vivid orange to magenta to cyan, diagonally.
  out.gradient = make((g) => {
    const lg = g.createLinearGradient(0, 0, W, H);
    lg.addColorStop(0, "rgb(255,140,30)"); lg.addColorStop(0.5, "rgb(230,40,160)"); lg.addColorStop(1, "rgb(40,220,240)");
    g.fillStyle = lg; g.fillRect(0, 0, W, H);
  });
  // Mid-tone: a grey-green landscape, neither light nor dark.
  out.midtone = make((g) => {
    vertical(g, "rgb(120,140,125)", "rgb(85,105,80)");
    for (let i = 0; i < 12; i++) {
      const y = 380 + i * 30;
      g.fillStyle = `rgb(${70 + i * 6},${95 + i * 5},${65 + i * 4})`;
      g.beginPath(); g.moveTo(0, H); g.lineTo(0, y);
      for (let x = 0; x <= W; x += 40) g.lineTo(x, y + 40 * Math.sin(x / 140 + i));
      g.lineTo(W, H); g.closePath(); g.fill();
    }
  });
  return out;
};

const browser = await chromium.launch(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {});
const page = await browser.newPage();
const images = await page.evaluate(draw);
for (const [name, url] of Object.entries(images)) {
  writeFileSync(new URL(`./${name}.jpg`, import.meta.url), Buffer.from(url.split(",")[1], "base64"));
}
await browser.close();
console.log("wrote", Object.keys(images).join(", "), `(${W}x${H})`);
