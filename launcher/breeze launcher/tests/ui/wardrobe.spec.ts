import { test, expect } from "./fixtures";

/**
 * The Wardrobe (1.1.0): the player large, capes labelled by how the player has
 * them, a click to preview and Equip that waits for the API, and honest
 * loading, offline, error and empty states, with the list saved on this
 * computer shown but never trusted.
 */
const ACCOUNT = "00000000-0000-4000-8000-000000000001";
const CACHE_KEY = `breeze.wardrobe.${ACCOUNT}`;
const img = (n: string) => `https://api.breezeclient.net/preview/${n}.png`;
const entry = (id: string, name: string, extra: Record<string, unknown> = {}) => ({
  cape_id: id, equipped: false, obtained: "bought", cape: { id, name, image_url: img("testcape"), is_public: true }, ...extra,
});
const OWNED = {
  success: true,
  capes: [
    entry("p1", "Sample cape one", { equipped: true }),
    entry("g1", "Partner cape", { obtained: "granted", cape: { id: "g1", name: "Partner cape", image_url: img("onemorecape"), is_public: false } }),
    entry("f1", "Friend's gift", { obtained: "gift", cape: { id: "f1", name: "Friend's gift", image_url: "https://api.breezeclient.net/missing.png" } }),
  ],
  revoked: [{ cape_id: "x1", cape: { id: "x1", name: "Taken back cape", image_url: img("skinmc-custom-cape") } }],
};
const tile = (page, name: string) => page.locator(".wd-cape", { hasText: name });
const bar = (page) => page.locator(".wardrobe-bar");

async function openWardrobe(page) {
  await page.goto("/preview.html?nosplash=1");
  await expect(page.locator("button.launch")).toBeVisible();
  await page.locator(".ni", { hasText: "Style" }).click();
  await expect(page.locator(".wardrobe")).toBeVisible();
}

test("capes are labelled by how the player has them, worn first", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  await expect(tile(page, "Sample cape one")).toContainText("Wearing");
  await expect(tile(page, "Partner cape")).toContainText("Granted to you");
  await expect(tile(page, "Friend's gift")).toContainText("A gift");
  await expect(tile(page, "Sample cape three")).toContainText("In the Store");
  await expect(tile(page, "Taken back cape")).toContainText("Taken back");
  await expect(page.locator(".wd-cape").first()).toContainText("Sample cape one");
  await expect(tile(page, "Friend's gift").locator(".wd-thumb.missing")).toBeVisible();
  await page.getByRole("button", { name: /^Yours/ }).click();
  await expect(page.locator(".wd-cape")).toHaveCount(3);
  await page.getByRole("group", { name: "Show" }).getByRole("button", { name: "Store", exact: true }).click();
  await expect(page.locator(".wd-cape")).toHaveCount(2);
});

test("a Store cape is a preview with a way to the Store, and Back returns to what is worn", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  await tile(page, "Sample cape three").click();
  await expect(bar(page)).toContainText("A preview. Get it in the Store to wear it.");
  await expect(bar(page).getByRole("button", { name: "Equip" })).toHaveCount(0);
  await bar(page).getByRole("button", { name: "Back to what you wear" }).click();
  await expect(bar(page)).toContainText("You are wearing this");
  await tile(page, "Taken back cape").click();
  await expect(bar(page)).toContainText("taken back, so it can no longer be worn");
  await expect(bar(page).getByRole("button", { name: "Equip" })).toHaveCount(0);
});

test("Equip waits for the API and shows what it says; the player checks the new cape", async ({ preview: page }) => {
  let equipped = "p1";
  const asked: string[] = [];
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({
    json: { ...OWNED, capes: OWNED.capes.map((c) => ({ ...c, equipped: c.cape_id === equipped })) },
  }));
  await page.route("https://api.breezeclient.net/capes/equip", async (r) => {
    const id = JSON.parse(r.request().postData() || "{}").cape_id;
    asked.push(id);
    equipped = id;
    await r.fulfill({ json: { success: true, cape_id: id } });
  });
  await openWardrobe(page);
  await tile(page, "Partner cape").click();
  await expect(bar(page)).toContainText("Yours. Equip it to wear it here and in game.");
  await bar(page).getByRole("button", { name: "Equip" }).click();
  await expect(tile(page, "Partner cape")).toContainText("Wearing");
  expect(asked).toEqual(["g1"]);
  await expect(bar(page)).toContainText("You are wearing this");
  await expect.poll(() => page.evaluate(() => (document.querySelector(".wardrobe-stage canvas") as any)?.__breezeStage?.moment?.name ?? null))
    .toBe("animation.breeze.cape_check");
});

test("a refused Equip changes nothing and plays nothing", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await page.route("https://api.breezeclient.net/capes/equip", (r) => r.fulfill({ status: 403, json: { success: false, error: "You do not own this cape" } }));
  await openWardrobe(page);
  await tile(page, "Partner cape").click();
  await bar(page).getByRole("button", { name: "Equip" }).click();
  await expect(page.getByText("You do not own this cape")).toBeVisible();
  await expect(tile(page, "Sample cape one")).toContainText("Wearing");
  expect(await page.evaluate(() => (document.querySelector(".wardrobe-stage canvas") as any)?.__breezeStage?.moment?.name ?? null)).not.toBe("animation.breeze.cape_check");
});

test("picking a cape turns the player round to show it, then back", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  const turn = () => page.evaluate(() => (document.querySelector(".wardrobe-stage canvas") as any).__breezeStage.turnTarget);
  const rest = await turn();
  await tile(page, "Partner cape").click();
  expect(await turn()).toBeGreaterThan(rest + 2);
  await expect.poll(turn, { timeout: 6000 }).toBe(rest);
});

test("offline, the list saved on this computer shows, marked, and Equip waits", async ({ preview: page }) => {
  await page.addInitScript(([key, owned]) => {
    localStorage.setItem(key, JSON.stringify({ version: 1, at: Date.UTC(2026, 9, 29, 12), owned: owned.capes, revoked: owned.revoked }));
  }, [CACHE_KEY, OWNED] as const);
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.abort("internetdisconnected"));
  await openWardrobe(page);
  await expect(page.getByText(/Breeze could not be reached, so these are your capes as of/)).toBeVisible();
  await expect(tile(page, "Partner cape")).toContainText("Granted to you");
  await tile(page, "Partner cape").click();
  await expect(bar(page).getByRole("button", { name: "Equip" })).toBeDisabled();
  await page.unroute("https://api.breezeclient.net/capes/owned");
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByText(/could not be reached/)).toHaveCount(0);
});

test("what the API says replaces the saved list, whatever the saved list claimed", async ({ preview: page }) => {
  await page.addInitScript((key) => {
    localStorage.setItem(key, JSON.stringify({ version: 1, at: 1, owned: [
      { cape_id: "fake", equipped: true, obtained: "granted", cape: { id: "fake", name: "Claimed cape", image_url: "javascript:alert(1)" } },
    ] }));
  }, CACHE_KEY);
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  await expect(tile(page, "Sample cape one")).toContainText("Wearing");
  await expect(tile(page, "Claimed cape")).toHaveCount(0);
  const saved = await page.evaluate((key) => JSON.parse(localStorage.getItem(key)!), CACHE_KEY);
  expect(saved.owned.map((o) => o.cape_id)).toEqual(["p1", "g1", "f1"]);
});

test("an API error says so and Try again recovers; with no capes, the way to the Store", async ({ preview: page }) => {
  let fail = true;
  await page.route("https://api.breezeclient.net/capes/owned", (r) => (fail
    ? r.fulfill({ status: 500, json: { success: false, error: "Failed to fetch owned capes" } })
    : r.fulfill({ json: { success: true, capes: [], revoked: [] } })));
  await openWardrobe(page);
  await expect(page.getByText("Your capes could not be loaded.")).toBeVisible();
  fail = false;
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByText("Your capes could not be loaded.")).toHaveCount(0);
  await page.getByRole("button", { name: /^Yours/ }).click();
  await expect(page.getByText("You do not have a cape yet.")).toBeVisible();
  await page.getByRole("button", { name: "Browse the Store" }).click();
  await expect(page.locator(".store-tabs")).toBeVisible();
});

test("switching tabs and filters does not fetch the thumbnails again", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  let fetched = 0;
  page.on("request", (req) => { if (req.url().endsWith("/preview/onemorecape.png")) fetched++; });
  await openWardrobe(page);
  await expect(tile(page, "Partner cape").locator(".wd-thumb:not(.loading)")).toBeVisible();
  const before = fetched;
  for (const name of ["3D cosmetics", "Tag", "Capes"]) await page.getByRole("tab", { name }).click();
  await page.getByRole("button", { name: /^Yours/ }).click();
  await page.getByRole("group", { name: "Show" }).getByRole("button", { name: "All", exact: true }).click();
  await expect(tile(page, "Partner cape").locator(".wd-thumb:not(.loading)")).toBeVisible();
  expect(fetched).toBe(before);
});

test("Try a moment plays the one asked for, even with the random ones off", async ({ preview: page }) => {
  await page.addInitScript(() => localStorage.setItem("breeze.motion", JSON.stringify({ version: 1, moments: false })));
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  const playing = () => page.evaluate(() => (document.querySelector(".wardrobe-stage canvas") as any)?.__breezeStage?.moment?.name ?? null);
  const row = page.getByRole("group", { name: "Try a moment" });
  await expect(row.getByRole("button")).toHaveText(["Look around", "Check the cape", "Wave", "Hop", "Stretch", "Spin", "Backflip", "Dance", "Cheer"]);
  for (const [label, id] of [["Wave", "wave"], ["Hop", "hop"], ["Look around", "look_around"], ["Backflip", "backflip"], ["Dance", "dance"]]) {
    await row.getByRole("button", { name: label }).click();
    await expect.poll(playing, { timeout: 3000 }).toBe(`animation.breeze.${id}`);
  }
});

test("a worn 3D cosmetic is taken off where it is listed, once the API says so", async ({ preview: page }) => {
  const hat = { id: "h1", name: "Sample hat", slot: "head", model_url: "https://api.breezeclient.net/preview/none.glb" };
  let equipped: Record<string, string> = { head: "h1" };
  const asked: string[] = [];
  await page.route("https://api.breezeclient.net/cosmetics/owned", (r) => r.fulfill({
    json: { success: true, owned: [{ cosmetic_id: "h1", cosmetic: hat }], equipped, placements: {} },
  }));
  await page.route("https://api.breezeclient.net/cosmetics/unequip", async (r) => {
    asked.push(JSON.parse(r.request().postData() || "{}").slot);
    equipped = {};
    await r.fulfill({ json: { success: true } });
  });
  await openWardrobe(page);
  await page.getByRole("tab", { name: "3D cosmetics" }).click();
  await expect(page.getByText("Sample hat")).toBeVisible();
  await page.getByRole("button", { name: "Take off Sample hat" }).click();
  await expect(page.getByText("You are not wearing a 3D cosmetic.")).toBeVisible();
  expect(asked).toEqual(["head"]);
  await expect(page.getByText("Sample hat taken off")).toBeVisible();
});

const stageOf = (page) => page.evaluate(() => {
  const st = (document.querySelector(".wardrobe-stage canvas") as any)?.__breezeStage;
  return st ? { moment: st.moment?.name ?? null, turn: st.turn } : null;
});

test("turning the player lets a moment play on, and it stays turned for a moment after", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  await page.getByRole("group", { name: "Try a moment" }).getByRole("button", { name: "Check the cape" }).click();
  await expect.poll(async () => (await stageOf(page))?.moment, { timeout: 3000 }).toBe("animation.breeze.cape_check");
  const box = (await page.locator(".wardrobe-stage canvas").boundingBox())!;
  const before = (await stageOf(page))!.turn;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  for (let i = 1; i <= 10; i++) await page.mouse.move(box.x + box.width / 2 + i * 18, box.y + box.height / 2);
  // While turning, the cape check plays on (owner, 1.1.0_test_3).
  expect((await stageOf(page))!.moment).toBe("animation.breeze.cape_check");
  await page.mouse.up();
  await page.waitForTimeout(600);
  const after = (await stageOf(page))!;
  expect(after.moment).toBe("animation.breeze.cape_check");
  expect(Math.abs(after.turn - before)).toBeGreaterThan(1);
  // Then it eases back to its three-quarter rest.
  await expect.poll(async () => Math.abs((await stageOf(page))!.turn - before), { timeout: 8000 }).toBeLessThan(0.2);
});

test("moments come on their own, even with the pointer moving", async ({ preview: page }) => {
  await page.route("https://api.breezeclient.net/capes/owned", (r) => r.fulfill({ json: OWNED }));
  await openWardrobe(page);
  const seen = new Set<string>();
  for (let i = 0; i < 70 && seen.size < 2; i++) {
    await page.mouse.move(200 + (i % 10) * 30, 300 + (i % 7) * 20);
    const m = (await stageOf(page))?.moment;
    if (m) seen.add(m);
    await page.waitForTimeout(250);
  }
  expect(seen.size, [...seen].join(", ")).toBeGreaterThan(0);
});

test("the player is drawn less often while nothing moves and the window is behind another, and at full rate while the pointer moves", async ({ preview: page }) => {
  await page.addInitScript(() => { try { localStorage.setItem("breeze.motion", JSON.stringify({ moments: false })); } catch {} });
  await page.goto("/preview.html?nosplash=1");
  const canvas = page.locator("canvas").first();
  await expect.poll(() => canvas.evaluate((c: any) => Boolean(c.__breezeStage))).toBe(true);
  // Count the frames actually drawn.
  await canvas.evaluate((c: any) => { const v = c.__breezeViewer; v.__frames = 0; const r = v.render.bind(v); v.render = () => { v.__frames++; r(); }; });
  const framesIn = async (ms: number) => {
    const a = await canvas.evaluate((c: any) => c.__breezeViewer.__frames);
    await page.waitForTimeout(ms);
    return ((await canvas.evaluate((c: any) => c.__breezeViewer.__frames)) - a) / (ms / 1000);
  };
  await page.mouse.move(2, 2);
  await expect.poll(() => canvas.evaluate((c: any) => c.__breezeStage.lively), { timeout: 8000 }).toBe(false);
  // Another window in front (the game): about 10 a second.
  await page.evaluate(() => window.dispatchEvent(new Event("blur")));
  const behind = await framesIn(3000);
  expect(behind).toBeGreaterThan(5);
  expect(behind).toBeLessThan(12.5);
  expect(await canvas.evaluate((c: any) => c.__breezeViewer.drawsSkipped)).toBeGreaterThan(0);
  // Back in front, the pointer moving: the head follows it at full rate.
  await page.evaluate(() => window.dispatchEvent(new Event("focus")));
  await page.mouse.move(400, 300);
  expect(await canvas.evaluate((c: any) => c.__breezeStage.lively)).toBe(true);
});
