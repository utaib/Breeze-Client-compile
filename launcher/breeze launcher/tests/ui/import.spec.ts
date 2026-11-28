import { test, expect } from "./fixtures";

/**
 * Importing a Modrinth pack: the launcher reads it first (inspect_mrpack) and
 * says what will happen before anything changes. The answers come from
 * dev/preview.ts, which mirrors the Rust commands' shapes; the preview's
 * selected version is 1.21.4 and its sample pack is made for 1.21.1.
 */

const PACK = { mimeType: "application/zip", buffer: Buffer.from("PK\u0003\u0004 preview pack") };

async function choosePack(page: import("@playwright/test").Page, name: string) {
  await page.goto("/preview.html?nosplash=1");
  await page.locator('input[type="file"][accept=".mrpack"]').setInputFiles({ name, ...PACK });
}

test("a pack says what it will do before it is imported, then goes into its own version", async ({ preview: page }) => {
  await choosePack(page, "Preview Pack.mrpack");
  const dialog = page.getByRole("alertdialog", { name: "Import Preview Pack?" });
  await expect(dialog).toBeVisible();
  await expect(dialog).toContainText("Made for Minecraft 1.21.1 with Fabric.");
  await expect(dialog).toContainText("It goes into your 1.21.1 version, not 1.21.4");
  await expect(dialog.locator(".confirm-details li")).toHaveText([
    "11 mods and 1 other file to download (30.0 MB), each checked against the pack.",
    "4 files from the pack itself (settings, options, packs).",
    "1 file already in this version will be replaced and kept in a backup folder: options.txt.",
  ]);
  await dialog.getByRole("button", { name: "Import" }).click();
  await expect(page.locator(".toast")).toHaveText(
    "Installed Preview Pack into Minecraft 1.21.1, 11 mods; 1 file replaced, the old ones kept in a backup folder.",
  );
});

test("saying no imports nothing, and a Forge pack says why it cannot be imported", async ({ preview: page }) => {
  await choosePack(page, "Preview Pack.mrpack");
  const dialog = page.getByRole("alertdialog", { name: "Import Preview Pack?" });
  await dialog.getByRole("button", { name: "Cancel" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.locator(".toast")).toHaveCount(0);

  await page.locator('input[type="file"][accept=".mrpack"]').setInputFiles({ name: "Forge Pack.mrpack", ...PACK });
  await expect(page.locator(".toast")).toHaveText("This pack is made for Forge. Breeze runs Fabric, so only Fabric packs can be imported.");
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
});
