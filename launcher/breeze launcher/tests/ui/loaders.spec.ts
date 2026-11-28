import { test, expect } from "./fixtures";

/**
 * Mod loaders (1.2.0): Fabric as before, and Quilt, Forge, NeoForge or the
 * game alone, each in its own instance folder. The native side is the
 * preview's stand-in (dev/preview.ts): Forge and NeoForge have builds for
 * some versions only, as they really do.
 */
const open = async (page, query = "") => {
  await page.goto(`/preview.html?nosplash=1${query}`);
  await expect(page.locator("button.launch")).toBeVisible();
};
const picker = (page) => page.getByRole("combobox", { name: "Mod loader" }).first();
const asked = (page, name: string) =>
  page.evaluate((key) => (window as unknown as Record<string, unknown>)[key], name);

test("the play page starts on Fabric, offers the other loaders with their builds, and launches what is chosen", async ({ preview: page }) => {
  await open(page);
  await expect(picker(page)).toHaveValue("fabric");
  await expect(page.locator(".pt")).toContainText("Fabric");
  // Each loader says the build it would use; one with none for this version cannot be chosen.
  await expect(picker(page).locator("option", { hasText: "Forge 54.1.6" })).toHaveCount(1);
  await expect(picker(page).locator("option", { hasText: "NeoForge 21.4.156" })).toHaveCount(1);
  await expect(picker(page).locator("option", { hasText: "Quilt 0.24.0" })).toHaveCount(1);
  await expect(picker(page).locator("option", { hasText: /^Vanilla$/ })).toHaveCount(1);

  await picker(page).selectOption("forge");
  await expect(page.locator(".pt")).toContainText("Forge");
  await expect(page.locator(".fabric-badge")).toContainText("54.1.6");
  await expect(page.getByText("No Breeze Mod build for Forge 1.21.4. It starts without Breeze's in-game features, in its own folder with its own mods.")).toBeVisible();
  // The Fabric-only check of Breeze's build is not shown for another loader.
  await expect(page.locator(".compat")).toHaveCount(0);

  await page.locator("button.launch").click();
  await expect.poll(() => asked(page, "__previewLaunch")).toMatchObject({ versionId: "1.21.4", loader: "forge", loaderVersion: null });
});

test("a loader with no build for the chosen version is marked, and the choice goes back to Fabric", async ({ preview: page }) => {
  await open(page);
  const version = page.getByRole("combobox", { name: "Minecraft version" }).first();
  await version.selectOption("1.21.1");
  await picker(page).selectOption("neoforge");
  await expect(page.locator(".fabric-badge")).toContainText("21.1.266");
  // 1.21.11 has Forge but no NeoForge in the preview.
  await version.selectOption("1.21.11");
  await expect(picker(page)).toHaveValue("fabric");
  await expect(page.getByText("NeoForge has no build for 1.21.11, switched to Fabric")).toBeVisible();
  const neoforge = picker(page).locator("option", { hasText: "NeoForge (none for 1.21.11)" });
  await expect(neoforge).toHaveCount(1);
  await expect(neoforge).toBeDisabled();
});

test("the choice is remembered after a restart", async ({ preview: page }) => {
  await open(page);
  await picker(page).selectOption("quilt");
  await expect(page.locator(".pt")).toContainText("Quilt");
  // The preview keeps its settings for the page's life; a new launcher start reads them back.
  const saved = await page.evaluate(() => (window as unknown as { __TAURI_INTERNALS__: { invoke: (cmd: string) => Promise<{ selectedLoader: string }> } }).__TAURI_INTERNALS__.invoke("get_launcher_settings"));
  expect(saved.selectedLoader).toBe("quilt");
});

test("a Forge instance has its own mods, searched and installed for Forge", async ({ preview: page }) => {
  const searches: string[] = [];
  page.on("request", (r) => { if (r.url().includes("api.modrinth.com") && r.url().includes("/search")) searches.push(decodeURIComponent(r.url())); });
  await open(page);
  await page.locator(".ni", { hasText: "Mods" }).click();
  // Fabric's six sample mods are this version's Fabric mods.
  await page.getByRole("button", { name: "My Mods" }).click();
  await expect(page.locator(".mod-item")).toHaveCount(6);
  await expect(page.getByRole("button", { name: "Built-in Perf" })).toBeVisible();

  await picker(page).selectOption("forge");
  await expect(page.getByRole("region", { name: "This version" })).toContainText("Minecraft 1.21.4 with Forge 54.1.6");
  await expect(page.getByRole("region", { name: "This version" })).toContainText("Forge has its own folder for this version");
  // None of Fabric's mods are here, and Fabric's tools are not offered.
  await expect(page.locator(".mod-item")).toHaveCount(0);
  await expect(page.getByText("No mods installed for this version")).toBeVisible();
  await expect(page.getByRole("button", { name: "Built-in Perf" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Recommended/ })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Export pack/ })).toHaveCount(0);

  await page.getByRole("button", { name: "Browse" }).click();
  await expect.poll(() => searches.some((url) => url.includes('"categories:forge"'))).toBe(true);
  await page.locator(".mr", { hasText: "Sodium" }).locator("button.mod-btn").click();
  await expect.poll(() => asked(page, "__previewModrinth")).toMatchObject([{ profileId: "1.21.4-forge", gameVersion: "1.21.4", loader: "forge", projectId: "AANobbMI" }]);
  await page.getByRole("button", { name: "My Mods" }).click();
  await expect(page.locator(".mod-item")).toHaveCount(1);

  // Back on Fabric, Fabric's mods are as they were.
  await picker(page).selectOption("fabric");
  await expect(page.locator(".mod-item")).toHaveCount(6);
});

test("Quilt is offered Fabric's mods too, and the game alone offers none", async ({ preview: page }) => {
  const searches: string[] = [];
  page.on("request", (r) => { if (r.url().includes("api.modrinth.com") && r.url().includes("/search")) searches.push(decodeURIComponent(r.url())); });
  await open(page, "&loader=quilt");
  await page.locator(".ni", { hasText: "Mods" }).click();
  await expect.poll(() => searches.some((url) => url.includes('["categories:quilt","categories:fabric"]'))).toBe(true);

  await picker(page).selectOption("vanilla");
  await expect(page.getByText("This version is set to start Minecraft alone, which loads no mods.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Browse" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /Import \.jar/ })).toHaveCount(0);
  await expect(page.getByRole("region", { name: "This version" })).toContainText("Minecraft 1.21.4 alone, with no mod loader");
  await page.locator(".ni", { hasText: "Play" }).click();
  await expect(page.getByText("This starts Minecraft alone: no mods, and none of Breeze's in-game features.")).toBeVisible();
  await expect(page.locator(".fabric-badge")).toHaveCount(0);
});

test("where the API serves Breeze for Forge, the play page says it is ready", async ({ preview: page }) => {
  // The API lists Forge and NeoForge jars by the instance's name.
  await page.route("https://api.breezeclient.net/versions/mod/list", (r) =>
    r.fulfill({ json: { versions: ["26.3", "1.21.11", "1.21.4", "1.21.1", "1.20.1", "1.20.1-forge", "1.21.1-neoforge"] } }));
  await open(page);
  const version = page.getByRole("combobox", { name: "Minecraft version" }).first();
  await version.selectOption("1.20.1");
  await picker(page).selectOption("forge");
  await expect(page.getByText("Breeze Mod ready for Forge 1.20.1, in its own folder with its own mods.")).toBeVisible();
  await version.selectOption("1.21.1");
  await picker(page).selectOption("neoforge");
  await expect(page.getByText("Breeze Mod ready for NeoForge 1.21.1, in its own folder with its own mods.")).toBeVisible();
  await picker(page).selectOption("quilt");
  await expect(page.getByText("No Breeze Mod build for Quilt 1.21.1. It starts without Breeze's in-game features, in its own folder with its own mods.")).toBeVisible();
});
