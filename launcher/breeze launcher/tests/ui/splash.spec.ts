import { test, expect } from "./fixtures";

// A slow or broken scene must never hold the launcher behind its splash for
// the old 16-second hard-stop window. The component's hard stop plus its exit
// fallback should reveal the real UI in under 8 seconds.
test("startup splash yields to the launcher within the bounded startup window", async ({ preview: page }) => {
  await page.goto("http://localhost:1430/preview.html?fresh=1");
  const splash = page.locator(".bz-splash-host");
  await expect(splash).toBeVisible({ timeout: 5000 });
  await expect(splash).toHaveCount(0, { timeout: 8500 });
});
