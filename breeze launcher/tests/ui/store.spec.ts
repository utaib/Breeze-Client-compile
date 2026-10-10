import { test, expect } from "./fixtures";

/**
 * The store's Owned tab (fixtures: three sample capes, the first two owned).
 * BreezeApp used to filter the items by slot before StorePage did, and no item
 * has the slot "owned", so the tab was always empty.
 */
test("the Owned tab lists what the account owns", async ({ preview: page }) => {
  await page.goto("/preview.html?nosplash=1");
  await page.locator(".ni", { hasText: "Store" }).click();
  await expect(page.getByText("Sample cape three").first()).toBeVisible();
  await page.locator(".store-tabs .tab", { hasText: /^Owned$/ }).click();
  await expect(page.getByText("Sample cape one").first()).toBeVisible();
  await expect(page.getByText("Sample cape two").first()).toBeVisible();
  await expect(page.getByText("Sample cape three")).toHaveCount(0);
  await expect(page.getByText("You don't own any cosmetics yet")).toHaveCount(0);
});
