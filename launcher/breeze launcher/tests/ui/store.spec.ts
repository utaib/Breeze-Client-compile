import { test, expect, type Page } from "./fixtures";

/**
 * The Store (fixtures: three sample capes, the first two owned, the first
 * worn; a wallet with 0 Wind Charges). The page has a spotlight of featured
 * items worn by the player, one section per kind of item, and cards that
 * show each item's own picture without a 3D viewer each.
 */
const openStore = async (page: Page) => {
  await page.goto("/preview.html?nosplash=1");
  await page.locator(".ni", { hasText: "Store" }).click();
  await expect(page.locator(".store-card").first()).toBeVisible();
};

/** BreezeApp used to filter by slot before StorePage did, and no item has the slot "owned", so the tab was always empty. */
test("the Owned tab lists what the account owns", async ({ preview: page }) => {
  await openStore(page);
  await expect(page.getByText("Sample cape three").first()).toBeVisible();
  await page.locator(".store-tabs .tab", { hasText: /^Owned$/ }).click();
  await expect(page.getByText("Sample cape one").first()).toBeVisible();
  await expect(page.getByText("Sample cape two").first()).toBeVisible();
  await expect(page.getByText("Sample cape three")).toHaveCount(0);
  await expect(page.locator(".store-spot")).toHaveCount(0);
});

test("cards show each cape's outside, with no 3D viewer per card, and tabs list only what is sold", async ({ preview: page }) => {
  await openStore(page);
  const cards = page.locator(".store-section .store-card");
  await expect(cards).toHaveCount(3);
  await expect(page.locator(".store-section canvas")).toHaveCount(0);
  const face = cards.filter({ hasText: "Sample cape three" }).locator(".cape-face");
  await expect(face).toHaveAttribute("style", /skinmc-custom-cape\.png/);
  await expect(cards.filter({ hasText: "Sample cape three" }).locator(".store-chip.rar")).toHaveText("Epic");
  await expect(cards.filter({ hasText: "Sample cape one" })).toContainText("Equipped");
  await expect(page.locator(".store-tabs .tab")).toHaveText(["All", "Owned", "Capes"]);
  await expect(page.locator(".store-section-head h3")).toHaveText(["Capes"]);
});

test("the spotlight shows a featured item worn by the player, its list picks one, and Buy opens it", async ({ preview: page }) => {
  await openStore(page);
  const spot = page.locator(".store-spot");
  // Not owned and rarest first: Sample cape three (epic, the only one not owned).
  await expect(spot.locator(".store-spot-name")).toHaveText("Sample cape three");
  await expect(spot.locator(".store-spot-stage canvas")).toHaveCount(1);
  await spot.getByRole("button", { name: "Show Sample cape one" }).click();
  await expect(spot.locator(".store-spot-name")).toHaveText("Sample cape one");
  await expect(spot.locator(".store-spot-cta")).toHaveText("Equipped");
  await spot.getByRole("button", { name: "Show Sample cape three" }).click();
  await spot.locator(".store-spot-cta", { hasText: "Buy" }).click();
  await expect(page.getByRole("dialog", { name: "Sample cape three" })).toBeVisible();
});

test("the spotlight moves on by itself, but not while the pointer is on it or with reduced motion", async ({ preview: page }) => {
  await openStore(page);
  const spot = page.locator(".store-spot");
  await expect(spot.locator(".store-spot-pick-time")).toHaveCount(1);
  await spot.hover();
  await expect(spot.locator(".store-spot-pick-time")).toHaveCount(0);
  await page.mouse.move(5, 5);
  await expect(spot.locator(".store-spot-pick-time")).toHaveCount(1);

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.reload();
  await page.locator(".ni", { hasText: "Store" }).click();
  await expect(page.locator(".store-spot .store-spot-name")).toBeVisible();
  await expect(page.locator(".store-spot-pick-time")).toHaveCount(0);
});

test("sorting by price and a search that matches nothing", async ({ preview: page }) => {
  await openStore(page);
  await page.getByLabel("Sort by").selectOption("price-desc");
  await expect(page.locator(".store-section .store-card-name")).toHaveText(["Sample cape three", "Sample cape one", "Sample cape two"]);
  await page.getByLabel("Sort by").selectOption("price-asc");
  await expect(page.locator(".store-section .store-card-name")).toHaveText(["Sample cape two", "Sample cape one", "Sample cape three"]);

  await page.getByPlaceholder("Search the marketplace").fill("zzz");
  await expect(page.getByText('Nothing matches "zzz".')).toBeVisible();
  await expect(page.locator(".store-spot")).toHaveCount(0);
  await page.getByRole("button", { name: "Show everything" }).click();
  await expect(page.locator(".store-section .store-card")).toHaveCount(3);
});

test("the item window: price, what the player has, a promo field on request, and a way to get Wind Charges", async ({ preview: page }) => {
  await openStore(page);
  await page.locator(".store-section .store-card", { hasText: "Sample cape three" }).click();
  const dialog = page.getByRole("dialog", { name: "Sample cape three" });
  await expect(dialog.locator(".store-modal-price-value")).toContainText("320");
  await expect(dialog.locator(".store-modal-balance")).toHaveText("You have 0, 320 short");
  await expect(dialog.locator(".store-modal-facts")).toContainText("Not owned yet");
  await expect(dialog.locator(".store-modal-stage canvas")).toHaveCount(1);
  await expect(dialog.getByPlaceholder("Promo code (optional)")).toHaveCount(0);
  await dialog.getByRole("button", { name: "Have a promo code?" }).click();
  await expect(dialog.getByPlaceholder("Promo code (optional)")).toBeVisible();
  await dialog.getByRole("button", { name: "Get Wind Charges" }).click();
  await expect(page.locator(".wallet-modal")).toBeVisible();
});

test("an owned item offers Equip or Unequip, and Escape closes the window", async ({ preview: page }) => {
  await openStore(page);
  await page.locator(".store-section .store-card", { hasText: "Sample cape one" }).click();
  const dialog = page.getByRole("dialog", { name: "Sample cape one" });
  await expect(dialog.getByRole("button", { name: "Unequip" })).toBeVisible();
  await expect(dialog.locator(".store-modal-facts")).toContainText("Wearing it now");
  await expect(dialog.getByRole("button", { name: "Have a promo code?" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
});

test("an animated cape steps through its frames on its card, and holds still with reduced motion", async ({ preview: page }) => {
  const frames = ["f0", "f1", "f2"].map((f) => `https://api.breezeclient.net/preview/${f}.png`);
  const capes = [{ id: "a1", name: "Flicker", rarity: "legendary", price_usd: 3, image_url: frames[0], animation_frames: frames, animation_fps: 10, is_public: true }];
  await page.route("https://api.breezeclient.net/capes", (r) => r.fulfill({ json: { success: true, capes } }));
  await openStore(page);
  const face = page.locator(".store-section .store-card .cape-face");
  await expect(page.locator(".store-section .store-card .store-chip.anim")).toHaveText("Animated");
  await expect.poll(async () => face.getAttribute("data-frame"), { timeout: 5000 }).not.toBe("0");

  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.reload();
  await page.locator(".ni", { hasText: "Store" }).click();
  await expect(face).toHaveAttribute("data-frame", "0");
  await page.waitForTimeout(800);
  await expect(face).toHaveAttribute("data-frame", "0");
});

test("a 3D cosmetic gets a section of its own, and a model that will not load shows a plain picture", async ({ preview: page }) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  const cosmetics = [{ id: "c1", name: "Broken Fox", slot: "pet", rarity: "epic", price_usd: 5, model_url: "https://api.breezeclient.net/preview/not-a-model.glb", is_public: true }];
  await page.route("https://api.breezeclient.net/cosmetics", (r) => r.fulfill({ json: { success: true, cosmetics } }));
  await openStore(page);
  await expect(page.locator(".store-tabs .tab")).toHaveText(["All", "Owned", "Capes", "Pets"]);
  const card = page.locator(".store-section .store-card", { hasText: "Broken Fox" });
  await expect(card.getByRole("img", { name: "Broken Fox: preview unavailable" })).toBeVisible({ timeout: 15000 });
  expect(errors).toEqual([]);
});
