import { test, expect } from "./fixtures";

/**
 * The edition switch (title bar) and the mod source switch (Mods, Browse):
 * Java Edition, Bedrock Edition (1.3.0, its page in bedrock.spec.ts) and
 * Modrinth work; CurseForge is marked Planned and changes nothing (1.1.0
 * directive, items nineteen and twenty).
 */
const open = async (page) => {
  await page.goto("/preview.html?nosplash=1");
  await expect(page.locator("button.launch")).toBeVisible();
};

test("the title bar says Java Edition, and its menu offers Bedrock", async ({ preview: page }) => {
  await open(page);
  const button = page.getByRole("button", { name: "Minecraft edition: Java Edition" });
  await expect(button).toHaveAttribute("aria-expanded", "false");
  await button.click();
  const menu = page.getByRole("menu", { name: "Minecraft edition" });
  await expect(menu).toBeVisible();
  const java = menu.getByRole("menuitemradio", { name: /Java Edition/ });
  const bedrock = menu.getByRole("menuitemradio", { name: /Bedrock Edition/ });
  await expect(java).toHaveAttribute("aria-checked", "true");
  await expect(bedrock).toHaveAttribute("aria-checked", "false");
  await expect(bedrock).not.toContainText("Planned");
  await expect(bedrock).toContainText("Windows");
  // The keys move between the two, and Escape closes the menu.
  await expect(java).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(bedrock).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(menu).toHaveCount(0);
  await expect(button).toBeFocused();
});

test("choosing Bedrock opens its page, and the switch leads back to Java", async ({ preview: page }) => {
  await open(page);
  await page.getByRole("button", { name: "Minecraft edition: Java Edition" }).click();
  await page.getByRole("menuitemradio", { name: /Bedrock Edition/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Bedrock Edition" })).toBeVisible();
  await expect(page.locator(".tb-pg")).toHaveText("Bedrock Edition");
  // The wind lines played once, under the page.
  await expect(page.locator(".ct .ed-wind i")).toHaveCount(4);
  await expect(page.getByRole("button", { name: "Play Bedrock" })).toBeVisible();
  await expect(page.getByText(/Bedrock uses add-ons, not mods/)).toBeVisible();
  await expect(page.locator(".ed-page")).not.toContainText(/coming soon|not implemented|[\u2013\u2014]/i);

  await page.getByRole("button", { name: "Minecraft edition: Bedrock Edition" }).click();
  await page.getByRole("menuitemradio", { name: /Java Edition/ }).click();
  await expect(page.locator("button.launch")).toBeVisible();
  await expect(page.getByRole("button", { name: "Minecraft edition: Java Edition" })).toBeVisible();
});

test("a restart is on Java again: Bedrock is never saved as the edition", async ({ preview: page }) => {
  await open(page);
  await page.getByRole("button", { name: "Minecraft edition: Java Edition" }).click();
  await page.getByRole("menuitemradio", { name: /Bedrock Edition/ }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Bedrock Edition" })).toBeVisible();
  await page.reload();
  await expect(page.locator("button.launch")).toBeVisible();
  await expect(page.getByRole("button", { name: "Minecraft edition: Java Edition" })).toBeVisible();
});

test("Browse says mods come from Modrinth; CurseForge explains it is planned and changes nothing", async ({ preview: page }) => {
  await open(page);
  await page.locator(".ni", { hasText: "Mods" }).click();
  const sources = page.getByRole("radiogroup", { name: "Where mods come from" });
  await expect(sources).toBeVisible();
  const modrinth = sources.getByRole("radio", { name: /Modrinth/ });
  const curseforge = sources.getByRole("radio", { name: /CurseForge/ });
  await expect(modrinth).toHaveAttribute("aria-checked", "true");
  await expect(curseforge).toHaveAttribute("aria-checked", "false");
  await curseforge.click();
  await expect(page.getByText(/CurseForge is planned for a later release/)).toBeVisible();
  await expect(modrinth).toHaveAttribute("aria-checked", "true");
  await expect(curseforge).toHaveAttribute("aria-checked", "false");
  await page.screenshot({ path: "test-results/edition-sources.png" });
  // Only on Browse: My Mods is about this computer's files.
  await page.locator(".tab", { hasText: "My Mods" }).click();
  await expect(sources).toHaveCount(0);
});

// ── CurseForge once Breeze's server has its key ─────────────────────────────

const SHA = "a".repeat(40);
const JEI = { id: 238222, slug: "jei", name: "Just Enough Items", summary: "View items and recipes.", downloads: 300000000, authors: "mezz", icon: null, websiteUrl: "https://www.curseforge.com/minecraft/mc-mods/jei", distributable: true, updated: null };
const KEPT = { id: 999, slug: "kept", name: "Kept Close", summary: "Only on CurseForge.", downloads: 5, authors: "someone", icon: null, websiteUrl: "https://www.curseforge.com/minecraft/mc-mods/kept", distributable: false, updated: null };
const file = (id: number, extra = {}) => ({ id, name: `file-${id}.jar`, fileName: `file-${id}.jar`, release: "release", date: "2026-09-01T00:00:00Z", size: 1000, gameVersions: ["1.21.4"], loaders: ["fabric"], download: `https://edge.forgecdn.net/files/${id}/file-${id}.jar`, sha1: SHA, required: [], ...extra });

const withCurseForge = async (page, seen: string[]) => {
  await page.route("https://api.breezeclient.net/curseforge/**", (r) => {
    const url = new URL(r.request().url());
    seen.push(`${url.pathname}${url.search}`);
    if (url.pathname === "/curseforge/status") return r.fulfill({ json: { success: true, configured: true } });
    if (url.pathname === "/curseforge/search") return r.fulfill({ json: { success: true, configured: true, results: [JEI, KEPT], total: 2, index: 0, pageSize: 20 } });
    if (url.pathname === "/curseforge/mods/238222/files") {
      // A beta that is newer, the release to take, one for another loader, and one the author keeps back.
      return r.fulfill({ json: { success: true, configured: true, id: 238222, name: "Just Enough Items", websiteUrl: JEI.websiteUrl, distributable: true, files: [
        file(5003, { release: "beta", date: "2026-10-01T00:00:00Z" }), file(5001, { required: [306612] }),
        file(5002, { loaders: ["forge"], date: "2026-11-01T00:00:00Z" }), file(5004, { download: null, date: "2026-12-01T00:00:00Z" }),
      ] } });
    }
    if (url.pathname === "/curseforge/mods/306612/files") {
      return r.fulfill({ json: { success: true, configured: true, id: 306612, name: "A Library", websiteUrl: null, distributable: true, files: [file(7001)] } });
    }
    return r.fulfill({ status: 404, json: { success: false, error: "CurseForge has no such project." } });
  });
  await open(page);
  await page.locator(".ni", { hasText: "Mods" }).click();
  const sources = page.getByRole("radiogroup", { name: "Where mods come from" });
  await sources.getByRole("radio", { name: /CurseForge/ }).click();
  return sources;
};

test("with CurseForge set up on the server, Browse can switch to it and lists Fabric mods for this version", async ({ preview: page }) => {
  const seen: string[] = [];
  const sources = await withCurseForge(page, seen);
  const curseforge = sources.getByRole("radio", { name: /CurseForge/ });
  await expect(curseforge).toHaveAttribute("aria-checked", "true");
  await expect(curseforge).not.toContainText("Planned");
  await expect(page.getByText("Just Enough Items")).toBeVisible();
  await expect(page.getByText(/View items and recipes\. By mezz\./)).toBeVisible();
  const search = seen.find((s) => s.startsWith("/curseforge/search"));
  expect(search).toContain("loader=fabric");
  expect(search).toContain("gameVersion=1.21.4");
  // A mod its author keeps on CurseForge is not installable from here: its row leads to CurseForge.
  await expect(page.getByRole("button", { name: "Open Kept Close on CurseForge" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Install Kept Close" })).toHaveCount(0);
  // Back to Modrinth with one click.
  await sources.getByRole("radio", { name: /Modrinth/ }).click();
  await expect(page.getByText("Just Enough Items")).toHaveCount(0);
});

test("installing from CurseForge takes the release for this version and what it requires, with CurseForge's address and checksum", async ({ preview: page }) => {
  await withCurseForge(page, []);
  await page.getByRole("button", { name: "Install Just Enough Items" }).click();
  await expect(page.getByRole("button", { name: "Just Enough Items is installed" })).toBeVisible();
  const asked = await page.evaluate(() => (window as unknown as { __previewCurseForge?: Array<Record<string, unknown>> }).__previewCurseForge || []);
  expect(asked.map((a) => a.fileId)).toEqual([5001, 7001]);
  expect(asked[0]).toMatchObject({
    modId: 238222, profileId: "1.21.4", gameVersion: "1.21.4", loader: "fabric", fileName: "file-5001.jar",
    downloadUrl: "https://edge.forgecdn.net/files/5001/file-5001.jar", sha1: SHA, title: "Just Enough Items", slug: "jei",
  });
  expect(asked[1]).toMatchObject({ modId: 306612, title: "A Library" });
});

test("on a Forge instance CurseForge is asked for Forge mods, and the Forge file goes into that instance", async ({ preview: page }) => {
  const seen: string[] = [];
  await withCurseForge(page, seen);
  await page.getByRole("combobox", { name: "Mod loader" }).first().selectOption("forge");
  await expect.poll(() => seen.some((s) => s.startsWith("/curseforge/search") && s.includes("loader=forge"))).toBe(true);
  await page.getByRole("button", { name: "Install Just Enough Items" }).click();
  await expect(page.getByRole("button", { name: "Just Enough Items is installed" })).toBeVisible();
  const asked = await page.evaluate(() => (window as unknown as { __previewCurseForge?: Array<Record<string, unknown>> }).__previewCurseForge || []);
  // The one file made for Forge, not the newer ones for Fabric.
  expect(asked.map((a) => a.fileId)).toEqual([5002]);
  expect(asked[0]).toMatchObject({ profileId: "1.21.4-forge", gameVersion: "1.21.4", loader: "forge", modId: 238222 });
});
