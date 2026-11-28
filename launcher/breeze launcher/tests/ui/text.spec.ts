import { test, expect } from "./fixtures";

/**
 * Product text: no "coming soon" or "not implemented" placeholders and no em or
 * en dashes on any page a player can open (CLAUDE.md). Unfinished features are
 * hidden instead, which is why there is no Host tab: its page was only a
 * "Coming soon" card, and the Spotify panel said "This isn't implemented yet.
 * I'll implement it later." to every player.
 */
test("no page shows placeholder text or dashes", async ({ preview: page }) => {
  await page.goto("/preview.html?nosplash=1");
  await expect(page.locator("button.launch")).toBeVisible();
  const labels = await page.locator("aside.sb button.ni .ni-lbl").allTextContents();
  expect(labels).not.toContain("Host");
  // No ads in the launcher (AdSense does not allow them in desktop apps), so
  // no Rewards tab, which exists to watch them.
  expect(labels).not.toContain("Rewards");
  for (const label of labels) {
    await page.locator("aside.sb button.ni", { hasText: label }).first().click();
    await page.waitForTimeout(700);
    const text = await page.locator("main.mn").innerText();
    expect(text, `${label} page`).not.toMatch(/coming soon|not implemented|implement it later/i);
    expect(text, `${label} page`).not.toMatch(/[–—]/);
    expect(await page.locator("iframe[src*='ads']").count(), `${label} page ad frames`).toBe(0);
  }
});
