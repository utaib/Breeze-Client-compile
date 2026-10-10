import { createRequire } from "node:module";
import { test, expect } from "./fixtures";

/**
 * The Mods page on a real mods folder (preview scenario ?mods=folder): jars the
 * launcher did not install, Fabric API twice, a mod missing its library. The
 * answers come from dev/preview.ts, which mirrors the Rust commands' shapes.
 */

const require = createRequire(import.meta.url);
const AXE = require.resolve("axe-core/axe.min.js");

/** The row of the mod with exactly this name (another row's text may mention it). */
const row = (page: import("@playwright/test").Page, name: string) =>
  page.locator(".mod-item").filter({ has: page.locator(".mn2", { hasText: new RegExp(`^${name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}$`) }) });

async function openMods(page: import("@playwright/test").Page) {
  await page.goto("/preview.html?nosplash=1&mods=folder&version=1.21.11");
  await page.locator(".ni", { hasText: "Mods" }).click();
  await page.getByRole("button", { name: "My Mods" }).click();
  await expect(page.locator(".mod-item")).toHaveCount(6);
}

test("every jar in the folder is listed, with duplicates and missing libraries named", async ({ preview: page }) => {
  await openMods(page);
  await expect(page.getByText("No mods installed")).toHaveCount(0);
  for (const name of ["Mod Menu", "Zoomify", "Reese's Sodium Options"]) {
    await expect(page.locator(".mn2", { hasText: name })).toBeVisible();
  }
  await expect(row(page, "Mod Menu").locator(".mm")).toContainText("added outside the launcher");

  const dup = page.getByTestId("duplicate-fabric-api");
  await expect(dup).toContainText("Fabric API is installed 2 times");
  await expect(dup).toContainText("fabric-api-0.139.1+1.21.11.jar");
  await expect(dup).toContainText("fabric-api-0.138.0+1.21.11.jar");
  await expect(page.locator(".mods-alert-log")).toContainText("Incompatible mods found!");

  await expect(row(page, "Zoomify").locator(".mod-flag.err"))
    .toHaveText("This mod needs yet_another_config_lib_v3 >=3.6.0, which is not installed or is switched off.");
});

test("a duplicate is removed only after a second press, and can be put back", async ({ preview: page }) => {
  await openMods(page);
  const dup = page.getByTestId("duplicate-fabric-api");
  const older = dup.locator("li", { hasText: "fabric-api-0.138.0" }).getByRole("button");
  await older.click();
  await expect(older).toHaveText("Press again to move it to the backups");
  await expect(page.locator(".mod-item")).toHaveCount(6);
  await older.click();
  await expect(page.getByTestId("duplicate-fabric-api")).toHaveCount(0);
  await expect(page.locator(".mods-alert-log")).toHaveCount(0);
  await expect(page.locator(".mod-item")).toHaveCount(5);

  // Putting the older file back swaps it for the one installed: the folder
  // never holds two copies of one mod again.
  await page.getByRole("button", { name: /Show earlier files/ }).click();
  const back = page.locator(".mod-backups li", { hasText: "Fabric API 0.138.0" }).getByRole("button");
  await back.click();
  await expect(back).toHaveText("Press again: the current copy goes to the backups");
  await back.click();
  await expect(page.getByTestId("duplicate-fabric-api")).toHaveCount(0);
  await expect(row(page, "Fabric API").locator(".mm")).toContainText("0.138.0+1.21.11");
  await expect(page.locator(".mod-backups li", { hasText: "Fabric API 0.139.1" })).toBeVisible();
});

test("changing a mod's version shows what it breaks first, and the old file can be put back", async ({ preview: page }) => {
  await openMods(page);
  const sodium = row(page, "Sodium");
  await sodium.hover();
  await sodium.getByRole("button", { name: "Versions" }).click();
  const list = page.getByTestId("version-list");
  await expect(list).toContainText("Installed: 0.6.13+mc1.21.11");
  await expect(list.locator("li.is-installed")).toContainText("mc1.21.11-0.6.13-fabric");
  await list.locator("li", { hasText: "0.7.0" }).getByRole("button", { name: "Use this version" }).click();

  const change = page.getByTestId("version-change");
  await expect(change).toContainText("sodium-fabric-0.6.13+mc1.21.11.jar (0.6.13+mc1.21.11) will go to the backups");
  await expect(change).toContainText("Reese's Sodium Options needs Sodium <0.7.0, but 0.7.0+mc1.21.11 is installed afterwards.");
  await expect(sodium.locator(".mm")).toContainText("0.6.13");
  await change.getByRole("button", { name: "Change anyway" }).click();

  await expect(page.getByTestId("version-change")).toHaveCount(0);
  await expect(sodium.locator(".mm")).toContainText("sodium-fabric-0.7.0+mc1.21.11.jar");
  await expect(row(page, "Reese's Sodium Options").locator(".mod-flag.err")).toContainText("needs Sodium <0.7.0");

  await page.getByRole("button", { name: /Show earlier files/ }).click();
  const back = page.locator(".mod-backups li", { hasText: "Sodium 0.6.13" }).getByRole("button");
  await back.click();
  await back.click();
  await expect(sodium.locator(".mm")).toContainText("0.6.13+mc1.21.11");
  await expect(row(page, "Reese's Sodium Options").locator(".mod-flag")).toHaveCount(0);
});

test("a jar Modrinth does not know offers no invented versions", async ({ preview: page }) => {
  await openMods(page);
  const menu = row(page, "Mod Menu");
  await menu.hover();
  await menu.getByRole("button", { name: "Versions" }).click();
  await expect(page.getByTestId("version-list")).toContainText("Modrinth does not know this file");
  await expect(page.getByTestId("version-list").locator(".mod-vers")).toHaveCount(0);
});

test("the folders open from the page, and it passes axe", async ({ preview: page }) => {
  await openMods(page);
  for (const label of ["Open mods folder", "Open instance folder", "Open logs"]) {
    await expect(page.getByRole("button", { name: label })).toBeVisible();
  }
  await page.addScriptTag({ path: AXE });
  const result = await page.evaluate(async () => {
    const axe = (window as unknown as { axe: { run: (c: unknown, o: unknown) => Promise<{ violations: { id: string; nodes: unknown[] }[] }> } }).axe;
    return axe.run(document.querySelector(".mv"), { runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"] } });
  });
  expect(result.violations.map((v) => `${v.id} (${v.nodes.length})`)).toEqual([]);
});

test("a duplicate is named on the other tabs too, with a way to the choice", async ({ preview: page }) => {
  await page.goto("/preview.html?nosplash=1&mods=folder&version=1.21.11");
  await page.locator(".ni", { hasText: "Mods" }).click();
  await expect(page.locator(".mods-alert-short")).toContainText(/^A mod is installed more than once for [\d.]+\./);
  await page.getByRole("button", { name: "Choose which copy to keep" }).click();
  await expect(page.getByTestId("duplicate-fabric-api")).toBeVisible();
});
