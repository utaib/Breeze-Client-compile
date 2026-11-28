import { test, expect } from "./fixtures";
import { contrast, luminance } from "../../src/features/theme/color.js";

/**
 * The player's own background image with the themes and the animated player
 * (1.1.0): the image stays behind every theme, text on top of it is checked on
 * the pixels the browser actually drew, the player stands on the image rather
 * than in a dark box, its moments still play and give way, and a missing,
 * refused or replaced image is handled. The wallpapers are tests/ui/backgrounds
 * (drawn for these tests by make.mjs).
 */
const root = (page) => page.locator(".root").first();
const rootVar = (page, name: string) => root(page).evaluate((el, n) => getComputedStyle(el).getPropertyValue(n).trim(), name);
const settingsNow = (page) => page.evaluate(() => (window as any).__TAURI_INTERNALS__.invoke("get_launcher_settings"));
const BG_KEYS = ["bgDim", "bgBlur", "bgBrightness", "bgOpacity", "bgScale", "bgPosition"];

async function open(page, query: string, size = { width: 1280, height: 800 }) {
  await page.setViewportSize(size);
  await page.goto(`/preview.html?nosplash=1&${query}`);
  await expect(page.locator("button.launch")).toBeVisible();
}

/** Every pixel the browser drew in a box, as [r, g, b]. */
async function pixels(page, clip) {
  const png = await page.screenshot({ clip });
  return page.evaluate(async (b64) => {
    const img = new Image();
    img.src = `data:image/png;base64,${b64}`;
    await img.decode();
    const c = document.createElement("canvas");
    c.width = img.width;
    c.height = img.height;
    const g = c.getContext("2d")!;
    g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, c.width, c.height).data;
    const out: number[][] = [];
    for (let i = 0; i < d.length; i += 4) out.push([d[i], d[i + 1], d[i + 2]]);
    return out;
  }, png.toString("base64"));
}

/** The worst contrast (2nd percentile) of a text colour over an empty stretch of a panel. */
async function panelContrast(page, widget: string, textVar: string) {
  const item = page.locator(`.dash-item[data-widget="${widget}"]`);
  await item.scrollIntoViewIfNeeded();
  await page.waitForTimeout(150);
  const box = (await item.boundingBox())!;
  const px = await pixels(page, { x: box.x + 24, y: box.y + box.height - 56, width: box.width - 48, height: 28 });
  const text = await rootVar(page, textVar);
  const ratios = px.map((p) => contrast(text, p)).sort((a, b) => a - b);
  return ratios[Math.floor(ratios.length * 0.02)];
}

async function waitMeasured(page) {
  await expect(root(page)).toHaveClass(/has-custom-bg/);
  // Let the image finish painting under the panels.
  await page.waitForTimeout(400);
}

const CASES = [
  { name: "Breeze Glass over a bright sky", q: "theme=glass&bg=bright" },
  { name: "Dusk over a busy picture", q: "theme=midnight&bg=busy" },
  { name: "Frost (light) over a night sky", q: "theme=white&bg=dark" },
  { name: "Onyx over a vivid gradient", q: "theme=black&bg=gradient" },
  { name: "a mid-tone custom panel over a busy picture", q: "theme=custom&bg=busy", custom: { version: 1, base: "glass", accent: "#ffd166", panel: "#3a6ea5", panelOpacity: 40 } },
  { name: "light custom panels over a mid-tone landscape", q: "theme=custom&bg=midtone", custom: { version: 1, base: "glass", accent: null, panel: "#f4e9d8", panelOpacity: 60 } },
  { name: "Breeze Glass over an image it could not measure", q: "theme=glass&bg=nocors-bright" },
];
for (const c of CASES) {
  test(`text reads on the drawn pixels: ${c.name}`, async ({ preview: page }) => {
    if (c.custom) await page.addInitScript((v) => localStorage.setItem("breeze.theme.custom", JSON.stringify(v)), c.custom);
    await open(page, c.q);
    await waitMeasured(page);
    for (const widget of ["news", "launch"]) {
      expect(await panelContrast(page, widget, "--text-primary"), `${widget} body text`).toBeGreaterThanOrEqual(4.5);
      expect(await panelContrast(page, widget, "--text-tertiary"), `${widget} labels`).toBeGreaterThanOrEqual(3);
    }
  });
}

test("the image stays through theme changes and its settings are never touched", async ({ preview: page }) => {
  await open(page, "theme=glass&bg=busy");
  await waitMeasured(page);
  const before = await settingsNow(page);
  const url = await rootVar(page, "--custom-bg");
  expect(url).toMatch(/\/preview\/bg\/busy\.jpg\?v=/);
  await page.locator(".ni", { hasText: "Config" }).click();
  for (const name of ["Frost", "Onyx", "Dusk"]) {
    await page.locator(".theme-card", { hasText: name }).click();
    await expect(root(page)).toHaveClass(/has-custom-bg/);
    expect(await rootVar(page, "--custom-bg")).toBe(url);
  }
  await page.locator(".theme-card-custom").click();
  await page.getByLabel("Panel colour").fill("#f4e9d8");
  await expect(root(page)).toHaveClass(/has-custom-bg/);
  const after = await settingsNow(page);
  for (const k of BG_KEYS) expect(after[k], k).toEqual(before[k]);
  // A light theme dims the image towards light, a dark one towards dark.
  expect(await rootVar(page, "--bg-scrim")).not.toBe("13 18 32");
});

test("the player stands on the image, outlined, not in a dark box", async ({ preview: page }) => {
  await open(page, "theme=glass&bg=bright");
  await waitMeasured(page);
  const card = page.locator(".dash-item[data-widget=player] .identity-card");
  expect(await card.evaluate((el) => getComputedStyle(el).backgroundColor)).toBe("rgba(0, 0, 0, 0)");
  const canvas = page.locator(".player-stage-viewer canvas");
  expect(await canvas.evaluate((el) => getComputedStyle(el).filter)).toContain("drop-shadow");
  const box = (await page.locator(".dash-item[data-widget=player] .player-stage").boundingBox())!;
  const corner = await pixels(page, { x: box.x + 8, y: box.y + 8, width: 40, height: 40 });
  const mean = corner.reduce((s, p) => s + luminance(p), 0) / corner.length;
  expect(mean, "the sky shows behind the player").toBeGreaterThan(0.3);
});

/** The right hand's height above the right shoulder, in the player's own units. */
const handLift = (page) => page.evaluate(() => {
  const canvas = document.querySelector(".player-stage-viewer canvas") as any;
  const arm = canvas.__breezeViewer.playerObject.skin.rightArm;
  arm.updateWorldMatrix(true, false);
  const shoulder = arm.localToWorld(arm.position.clone().set(0, 0, 0));
  const hand = arm.localToWorld(arm.position.clone().set(0, -10, 0));
  return hand.y - shoulder.y;
});
const startMoment = (page, name: string) => page.evaluate((n) => (document.querySelector(".player-stage-viewer canvas") as any).__breezeStage.play(n), name);
const momentNow = (page) => page.evaluate(() => (document.querySelector(".player-stage-viewer canvas") as any).__breezeStage.moment?.name ?? null);

test("a moment plays over the image, plays on while the player is turned, and blends back to rest", async ({ preview: page }) => {
  await open(page, "theme=glass&bg=busy");
  await waitMeasured(page);
  expect(await handLift(page)).toBeLessThan(-8);
  expect(await startMoment(page, "animation.breeze.wave")).toBe("animation.breeze.wave");
  await page.waitForTimeout(900);
  expect(await momentNow(page)).toBe("animation.breeze.wave");
  expect(await handLift(page), "the hand is up, waving").toBeGreaterThan(4);
  // Moving the pointer over the window lets the moment play on.
  const canvas = (await page.locator(".player-stage-viewer canvas").boundingBox())!;
  await page.mouse.move(canvas.x + canvas.width / 2, canvas.y + canvas.height / 2);
  await page.waitForTimeout(150);
  expect(await momentNow(page)).toBe("animation.breeze.wave");
  // Pressing on the player to turn it lets it play on too, so it can be
  // watched from any side (owner, 1.1.0_test_3).
  await page.mouse.down();
  await page.mouse.move(canvas.x + canvas.width / 2 + 60, canvas.y + canvas.height / 2);
  await page.waitForTimeout(200);
  expect(await momentNow(page)).toBe("animation.breeze.wave");
  expect(await handLift(page), "still waving while turned").toBeGreaterThan(4);
  await page.mouse.up();
  // It ends on its own, blending down: every frame's hand height is
  // recorded until it is over, and it passes through the heights between.
  const heights: number[] = await page.evaluate(() => new Promise((done) => {
    const canvas = document.querySelector(".player-stage-viewer canvas") as any;
    const arm = canvas.__breezeViewer.playerObject.skin.rightArm;
    const out: number[] = [];
    const start = performance.now();
    const tick = () => {
      arm.updateWorldMatrix(true, false);
      out.push(arm.localToWorld(arm.position.clone().set(0, -10, 0)).y - arm.localToWorld(arm.position.clone().set(0, 0, 0)).y);
      if (canvas.__breezeStage.moment && performance.now() - start < 6000) requestAnimationFrame(tick); else done(out);
    };
    requestAnimationFrame(tick);
  }));
  await expect.poll(() => momentNow(page)).toBeNull();
  expect(await handLift(page), "back at rest").toBeLessThan(-8);
  expect(heights.some((h) => h < 4 && h > -8), `blended down, not snapped: ${heights.map((h) => h.toFixed(1)).join(" ")}`).toBe(true);
});

test("Character moments can be switched off in Config, and stay off after a restart", async ({ preview: page }) => {
  await open(page, "theme=glass&bg=dark");
  await page.locator(".ni", { hasText: "Config" }).click();
  const toggle = page.getByRole("switch", { name: "Character moments" });
  await expect(toggle).toHaveAttribute("aria-checked", "true");
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-checked", "false");
  await page.reload();
  await expect(page.locator("button.launch")).toBeVisible();
  await waitMeasured(page);
  await startMoment(page, "animation.breeze.wave");
  await page.waitForTimeout(400);
  expect(await momentNow(page)).toBeNull();
  expect(await handLift(page)).toBeLessThan(-8);
});

test("with reduced motion asked for, no moment plays", async ({ preview: page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await open(page, "theme=glass&bg=dark");
  await waitMeasured(page);
  await startMoment(page, "animation.breeze.wave");
  await page.waitForTimeout(400);
  expect(await momentNow(page)).toBeNull();
  expect(await handLift(page)).toBeLessThan(-8);
  await page.locator(".ni", { hasText: "Config" }).click();
  await expect(page.getByText("Off while your system asks for reduced motion")).toBeVisible();
  await expect(page.getByRole("switch", { name: "Character moments" })).toBeDisabled();
});

test("a replaced image shows at once, a refused file changes nothing, and Remove clears it", async ({ preview: page }) => {
  await open(page, "theme=glass&bg=bright");
  await waitMeasured(page);
  const brightSurface = await rootVar(page, "--surface");
  await page.locator(".ni", { hasText: "Config" }).click();
  const input = page.locator("#settings-appearance input[type=file]");
  await input.setInputFiles({ name: "notes.png", mimeType: "image/png", buffer: Buffer.from("not an image at all") });
  await expect(page.getByText("That file is not an image.")).toBeVisible();
  expect(await rootVar(page, "--custom-bg")).toMatch(/bright\.jpg/);
  await input.setInputFiles("tests/ui/backgrounds/dark.jpg");
  await expect.poll(() => rootVar(page, "--custom-bg")).toMatch(/dark\.jpg\?v=/);
  // Measured again: a night sky needs less help than a bright one.
  await expect.poll(() => rootVar(page, "--surface")).not.toBe(brightSurface);
  await page.getByRole("button", { name: "Remove" }).click();
  await expect(root(page)).not.toHaveClass(/has-custom-bg/);
  expect(await rootVar(page, "--surface")).toBe("rgba(255,255,255,0.045)");
});

test("a background whose file has gone is reported, and the theme shows instead", async ({ preview: page }) => {
  await open(page, "theme=glass&bg=gone");
  await page.locator(".ni", { hasText: "Config" }).click();
  await expect(page.getByText("Your background image could not be loaded.")).toBeVisible();
  await expect(root(page)).not.toHaveClass(/has-custom-bg/);
  await page.getByRole("button", { name: "Remove" }).click();
  await expect(page.getByText("Your background image could not be loaded.")).toHaveCount(0);
});

for (const size of [{ width: 1000, height: 680 }, { width: 1600, height: 900 }, { width: 1920, height: 1080 }]) {
  test(`at ${size.width} by ${size.height} the image fills the window and text still reads`, async ({ preview: page }) => {
    await open(page, "theme=glass&bg=bright", size);
    await waitMeasured(page);
    const layer = await root(page).evaluate((el) => {
      const s = getComputedStyle(el, "::before");
      return { size: s.backgroundSize, inset: [s.top, s.left, s.right, s.bottom].join(" ") };
    });
    expect(layer).toEqual({ size: "cover", inset: "0px 0px 0px 0px" });
    expect(await panelContrast(page, "launch", "--text-primary")).toBeGreaterThanOrEqual(4.5);
  });
}
