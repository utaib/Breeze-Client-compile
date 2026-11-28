import { test, expect } from "./fixtures";

/**
 * The Bedrock page (1.3.0, docs/PLAN_1.3.0.md) against the preview's stand-in
 * for src-tauri/src/bedrock.rs: Home (Play, the Breeze add-on, the account,
 * news), Add-ons (import, every world, back up, remove), Worlds (back up,
 * restore), and the plain answers on a Mac or Linux and on a PC without
 * Bedrock.
 */
const openBedrock = async (page, query = "") => {
  await page.goto(`/preview.html?nosplash=1${query}`);
  await expect(page.locator("button.launch")).toBeVisible();
  await page.getByRole("button", { name: "Minecraft edition: Java Edition" }).click();
  await page.getByRole("menuitemradio", { name: /Bedrock Edition/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Bedrock Edition" })).toBeVisible();
};
const calls = (page) => page.evaluate(() => (window as unknown as { __previewBedrock?: { cmd: string }[] }).__previewBedrock || []);

test("Home: Play opens the install through Windows, and the add-on installs and turns on", async ({ preview: page }) => {
  await openBedrock(page);
  await expect(page.getByRole("tab", { name: "Home" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("Signed in as PreviewPlayer.")).toBeVisible();
  await page.getByRole("button", { name: "Play Bedrock" }).click();
  await expect(page.getByRole("status")).toHaveText("Opening Minecraft Bedrock.");
  expect((await calls(page)).find((c) => c.cmd === "launch")).toMatchObject({ installId: "release" });

  await expect(page.getByText(/Not installed · Newest: 1\.0\.0/)).toBeVisible();
  await page.getByRole("button", { name: "Install the add-on" }).click();
  await expect(page.getByRole("status")).toContainText("Breeze UI 1.0.0 added");
  await expect(page.getByRole("status")).toContainText("Breeze UI is on in every world.");
  await expect(page.getByRole("button", { name: "Up to date" })).toBeDisabled();
  await expect(page.locator(".ed-page")).not.toContainText(/coming soon|not implemented|[–—]/i);
  await page.screenshot({ path: "test-results/bedrock-home.png" });
});

test("Add-ons: lists packs by kind, turns one on for every world, backs up, removes after a second press", async ({ preview: page }) => {
  await openBedrock(page);
  await page.getByRole("tab", { name: "Add-ons" }).click();
  await expect(page.getByRole("heading", { name: "Resource packs (1)" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Behaviour packs (1)" })).toBeVisible();
  const sw = page.getByRole("switch", { name: "Sample Texture Pack in every world" });
  await expect(sw).toHaveAttribute("aria-checked", "false");
  await sw.click();
  await expect(sw).toHaveAttribute("aria-checked", "true");
  // Behaviour packs are per world: no switch for them.
  await expect(page.getByRole("switch", { name: /Sample Behaviour Pack/ })).toHaveCount(0);
  const row = page.locator(".bd-item", { hasText: "Sample Behaviour Pack" });
  await row.getByRole("button", { name: "Back up" }).click();
  await expect(page.getByRole("status")).toHaveText("Backed up as Mobs_bbbb2222-1795000000.mcpack.");
  await row.getByRole("button", { name: "Remove" }).click();
  await row.getByRole("button", { name: "Remove for good" }).click();
  await expect(page.getByRole("status")).toContainText("Sample Behaviour Pack removed. A backup was kept");
  await expect(page.getByRole("heading", { name: "Behaviour packs (0)" })).toBeVisible();
  await page.screenshot({ path: "test-results/bedrock-addons.png" });
});

test("Add-ons: imports a .mcpack and says what arrived; refuses other files with the reason", async ({ preview: page }) => {
  await openBedrock(page);
  await page.getByRole("tab", { name: "Add-ons" }).click();
  const input = page.getByLabel("Bedrock file to import");
  await input.setInputFiles({ name: "Cool Pack.mcpack", mimeType: "application/zip", buffer: Buffer.from("PK\u0003\u0004 sample") });
  await expect(page.getByRole("status")).toHaveText("Imported: Cool Pack 1.0.0 added.");
  await expect(page.getByRole("heading", { name: "Resource packs (2)" })).toBeVisible();
  await input.setInputFiles({ name: "notes.txt", mimeType: "text/plain", buffer: Buffer.from("hello") });
  await expect(page.getByRole("alert")).toHaveText("Bedrock imports are .mcpack, .mcaddon or .mcworld files.");
});

test("Worlds: backs a world up and restores the backup as a new world", async ({ preview: page }) => {
  await openBedrock(page);
  await page.getByRole("tab", { name: "Worlds" }).click();
  await expect(page.getByText("Sample World")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Backups (0)" })).toBeVisible();
  await page.locator(".bd-item", { hasText: "Sample World" }).getByRole("button", { name: "Back up" }).click();
  await expect(page.getByRole("status")).toHaveText("Sample World backed up as abc123-1795000002.mcworld.");
  await expect(page.getByRole("heading", { name: "Backups (1)" })).toBeVisible();
  await page.locator(".bd-item", { hasText: "abc123-1795000002.mcworld" }).getByRole("button", { name: "Restore" }).click();
  await expect(page.getByRole("status")).toHaveText("Imported: world Sample World added.");
  expect((await calls(page)).find((c) => c.cmd === "restore")).toMatchObject({ kind: "worlds", file: "abc123-1795000002.mcworld" });
  await page.screenshot({ path: "test-results/bedrock-worlds.png" });
});

test("two installs: the picker chooses which one Play opens", async ({ preview: page }) => {
  await openBedrock(page, "&bedrock=two");
  // The add-on goes into the install that is chosen, and each says its own state.
  await page.getByRole("button", { name: "Install the add-on" }).click();
  await expect(page.getByText(/Installed: 1\.0\.0/)).toBeVisible();
  await page.getByLabel("Bedrock install").selectOption("preview");
  await expect(page.getByText(/Not installed · Newest/)).toBeVisible();
  await page.getByRole("button", { name: "Play Bedrock" }).click();
  await expect(page.getByRole("status")).toHaveText("Opening Minecraft Bedrock Preview.");
});

test("macOS and Linux: Bedrock is plainly unavailable, with the way back to Java", async ({ preview: page }) => {
  await openBedrock(page, "&bedrock=unsupported");
  await expect(page.getByRole("heading", { name: "Not available on this computer" })).toBeVisible();
  await expect(page.getByText(/macOS and Linux have no official Bedrock Edition/).first()).toBeVisible();
  await expect(page.getByRole("tab")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Play Bedrock" })).toHaveCount(0);
  await page.getByRole("button", { name: "Back to Java Edition" }).click();
  await expect(page.locator("button.launch")).toBeVisible();
});

test("a PC without Bedrock says how to get it", async ({ preview: page }) => {
  await openBedrock(page, "&bedrock=none");
  await expect(page.getByRole("heading", { name: "Bedrock is not installed" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Check again" })).toBeVisible();
  await expect(page.getByRole("tab")).toHaveCount(0);
});
