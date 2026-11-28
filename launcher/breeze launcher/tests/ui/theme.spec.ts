import { test, expect } from "./fixtures";

/**
 * One theme system (1.1.0): the presets and the player's own theme live in
 * Config, Appearance. Picking one changes the whole launcher through the
 * root's class and variables; the custom colours are kept on this computer,
 * and the Style page no longer has a second picker.
 */
const KEY = "breeze.theme.custom";
const rootVar = (page, name: string) => page.locator(".root").first().evaluate((el, n) => (el as HTMLElement).style.getPropertyValue(n), name);
const rootClass = (page) => page.locator(".root").first().getAttribute("class");

async function openAppearance(page, query = "") {
  await page.goto(`/preview.html?nosplash=1${query}`);
  await expect(page.locator("button.launch")).toBeVisible();
  await page.locator(".ni", { hasText: "Config" }).click();
  await expect(page.locator(".sgl", { hasText: "Appearance" })).toBeVisible();
}

test("a preset theme applies at once to the whole launcher", async ({ preview: page }) => {
  await openAppearance(page);
  await page.locator(".theme-card", { hasText: "Ember" }).click();
  expect(await rootClass(page)).toMatch(/\btheme-ember\b/);
  expect(await rootVar(page, "--accent")).toBe("rgba(255,146,92,0.95)");
  await expect(page.locator(".theme-card", { hasText: "Ember" })).toHaveAttribute("aria-pressed", "true");
});

test("your own theme takes your accent and keeps the theme it is built on", async ({ preview: page }) => {
  await openAppearance(page, "&theme=midnight");
  await page.locator(".theme-card-custom").click();
  await expect(page.getByLabel("Built on")).toHaveValue("midnight");
  await page.getByRole("button", { name: "Accent #62d698" }).click();
  expect(await rootClass(page)).toMatch(/\btheme-midnight\b.*\btheme-custom\b/);
  expect(await rootVar(page, "--accent")).toBe("rgba(98,214,152,0.95)");
  const saved = JSON.parse(await page.evaluate((k) => localStorage.getItem(k), KEY));
  expect(saved).toEqual({ version: 1, base: "midnight", accent: "#62d698", panel: null, panelOpacity: 100 });
});

test("a light panel colour switches the text to dark so it stays readable", async ({ preview: page }) => {
  await openAppearance(page);
  await page.locator(".theme-card-custom").click();
  await page.getByLabel("Panel colour").fill("#f4e9d8");
  await expect(page.getByText("Dark text on these panels")).toBeVisible();
  expect(await rootVar(page, "--surface")).toBe("rgba(244,233,216,1)");
  expect(await rootVar(page, "--text-primary")).toBe("#1a2233");
  const title = await page.locator(".vtl").first().evaluate((el) => getComputedStyle(el).color);
  expect(title).toBe("rgb(26, 34, 51)");
  await page.getByLabel("Panel opacity").fill("70");
  expect(await rootVar(page, "--surface")).toBe("rgba(244,233,216,0.7)");
});

test("your theme is still there after a restart, and Reset clears it", async ({ preview: page }) => {
  await page.addInitScript((key) => {
    if (!sessionStorage.getItem("seeded")) {
      localStorage.setItem(key, JSON.stringify({ version: 1, base: "forest", accent: "#ff7abc", panel: null, panelOpacity: 100 }));
      sessionStorage.setItem("seeded", "1");
    }
  }, KEY);
  await openAppearance(page, "&theme=custom");
  expect(await rootClass(page)).toMatch(/\btheme-forest\b.*\btheme-custom\b/);
  expect(await rootVar(page, "--accent")).toBe("rgba(255,122,188,0.95)");
  await page.getByRole("button", { name: "Reset your theme" }).click();
  expect(await rootVar(page, "--accent")).toBe("rgba(98,214,152,0.94)");
  const saved = JSON.parse(await page.evaluate((k) => localStorage.getItem(k), KEY));
  expect(saved.accent).toBeNull();
});

test("a damaged saved theme falls back instead of breaking the page", async ({ preview: page }) => {
  await page.addInitScript((key) => localStorage.setItem(key, JSON.stringify({ base: "<img>", accent: "red;}", panel: 5, panelOpacity: "x" })), KEY);
  await openAppearance(page, "&theme=custom");
  expect(await rootClass(page)).toMatch(/\btheme-glass\b.*\btheme-custom\b/);
  expect(await rootVar(page, "--accent")).toBe("rgba(120,178,255,0.95)");
});

test("an accent that would vanish on the panels is pointed out", async ({ preview: page }) => {
  await openAppearance(page, "&theme=black");
  await page.locator(".theme-card-custom").click();
  await page.getByLabel("Any accent colour").fill("#121316");
  await expect(page.getByText("This accent is hard to see on these panels.")).toBeVisible();
});

test("the Style page has no second theme picker, only a way to Config", async ({ preview: page }) => {
  await page.goto("/preview.html?nosplash=1");
  await expect(page.locator("button.launch")).toBeVisible();
  await page.locator(".ni", { hasText: "Style" }).click();
  await expect(page.locator(".theme-card")).toHaveCount(0);
  await page.getByRole("button", { name: "Open Settings" }).click();
  await expect(page.locator(".sgl", { hasText: "Appearance" })).toBeVisible();
});
