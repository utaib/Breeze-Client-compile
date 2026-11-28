import { test, expect } from "./fixtures";

/**
 * Settings, "Keep Launcher Open". Off (afterLaunch "minimize"), the launcher
 * minimises once Minecraft is running; on, it stays. The preview replays the
 * launch events the Rust side emits and counts window minimise calls.
 */
const minimized = (page: import("@playwright/test").Page) =>
  page.evaluate(() => (window as unknown as { __previewMinimized?: number }).__previewMinimized || 0);

for (const [afterLaunch, expected] of [["minimize", 1], ["keep-open", 0]] as const) {
  test(`with afterLaunch ${afterLaunch}, the window is minimised ${expected} times`, async ({ preview: page }) => {
    await page.goto(`/preview.html?nosplash=1&afterLaunch=${afterLaunch}`);
    const launch = page.locator("button.launch");
    await expect(launch).toBeEnabled();
    await launch.click();
    await expect(launch).toHaveText("Running", { timeout: 20_000 });
    await page.waitForTimeout(2000);
    expect(await minimized(page)).toBe(expected);
  });
}
