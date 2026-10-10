import { test, expect } from "./fixtures";

/**
 * Buying Wind Charges: the payment finishes in the browser, and the charges
 * arrive once PayPal confirms it to the API. The launcher looks for them every
 * few seconds after opening the payment page (it used to wait for its 30 second
 * poll) and says when they land.
 */
test("a Wind Charge payment shows up in the launcher within seconds", async ({ preview: page }) => {
  let balance = 0;
  let purchases = 0;
  await page.route("https://api.breezeclient.net/wallet", (r) => r.fulfill({ json: { success: true, wind_charges: balance, transactions: [] } }));
  await page.route("https://api.breezeclient.net/wallet/packs", (r) =>
    r.fulfill({ json: { success: true, packs: [{ id: "breeze-320", usd: 5, wc: 320, bonus: 0, total_wc: 320 }], wc_per_usd: 64 } }));
  await page.route("https://api.breezeclient.net/wallet/purchase", (r) => {
    purchases += 1;
    return r.fulfill({ status: 201, json: { success: true, order_id: "o1", approve_url: "https://www.sandbox.paypal.com/checkoutnow?token=PREVIEW", wind_charges: 320, amount_usd: 5 } });
  });

  await page.goto("/preview.html?nosplash=1");
  await expect(page.locator("button.launch")).toBeVisible();
  await page.locator("button.wc-pill").click();
  await page.locator("button.wc-pack").first().click();
  await expect.poll(() => purchases).toBe(1);
  await expect(page.getByText("Finish paying in your browser")).toBeVisible();

  // PayPal confirms; the API credits the account.
  balance = 320;
  await expect(page.getByText("320 Wind Charges added")).toBeVisible({ timeout: 10_000 });
});
