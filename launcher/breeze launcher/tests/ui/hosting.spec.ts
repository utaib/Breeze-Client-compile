import { test, expect } from "./fixtures";

/**
 * Hosting (1.1.0 directive, item fourteen): a server on this computer, made
 * only after the player has read what it uses and ticked both boxes (one is
 * the Minecraft EULA). Local for now: this computer and the home network.
 * The preview stands in for the Rust side (src/dev/preview.ts): ?servers=1
 * starts with one server made.
 */
const openHosting = async (page, query = "") => {
  await page.goto(`/preview.html?nosplash=1${query}`);
  await page.locator(".ni", { hasText: "Hosting" }).click();
};
const newServer = async (page) => {
  await page.getByRole("button", { name: "New server" }).click();
  return page.getByRole("dialog", { name: "New server" });
};

test("Hosting is in the navigation and, with no server yet, says plainly what it is", async ({ preview: page }) => {
  await openHosting(page);
  await expect(page.getByText(/Host a Minecraft server on this computer\. For now it's local/)).toBeVisible();
  await expect(page.getByText("You have no servers yet.")).toBeVisible();
  // No feature boxes, icon tiles or tinted banner (owner, 1.1.0_test_9).
  await expect(page.locator(".host-facts, .host-intro-icon, .host-banner")).toHaveCount(0);
  await expect(page.getByText(/not implemented|coming soon/i)).toHaveCount(0);
});

test("nothing is made until both boxes are ticked, and the EULA answer goes with the request", async ({ preview: page }) => {
  await openHosting(page);
  const dialog = await newServer(page);
  const create = dialog.getByRole("button", { name: "Create" });
  await expect(dialog.getByText(/People on this computer and your home network can join/)).toBeVisible();
  await expect(create).toBeDisabled();
  await dialog.getByLabel(/I understand it runs on this computer/).check();
  await expect(create).toBeDisabled();
  await dialog.getByLabel(/I accept the/).check();
  await dialog.getByLabel("Software").selectOption("paper");
  await dialog.getByLabel("Name").fill("Weekend World");
  await expect(create).toBeEnabled();
  await create.click();
  await expect(dialog).toHaveCount(0);
  const card = page.getByRole("article", { name: "Weekend World" });
  await expect(card).toContainText("Paper");
  await expect(card.locator(".host-status")).toHaveText("Stopped");
  const sent = await page.evaluate(() => (window as any).__previewServerMade);
  expect(sent).toMatchObject({ name: "Weekend World", loader: "paper", port: 25565, eulaAccepted: true });
});

test("a port outside 1024 to 65535 cannot be used", async ({ preview: page }) => {
  await openHosting(page);
  const dialog = await newServer(page);
  await dialog.getByLabel(/I understand/).check();
  await dialog.getByLabel(/I accept the/).check();
  await dialog.getByLabel("Port").fill("80");
  await expect(dialog.getByText("From 1024 to 65535.", { exact: false })).toBeVisible();
  await expect(dialog.getByRole("button", { name: "Create" })).toBeDisabled();
});

test("memory is limited to a quarter of the computer, so the game still has room", async ({ preview: page }) => {
  await openHosting(page);
  const dialog = await newServer(page);
  const slider = dialog.getByLabel("Memory for the server");
  // The preview computer has 16 GB.
  await expect(slider).toHaveAttribute("max", "4096");
  await expect(dialog.getByText("Up to 4 GB on this computer", { exact: false })).toBeVisible();
});

test("a running server shows how to join, its console takes commands, and Stop saves first", async ({ preview: page }) => {
  await openHosting(page, "&servers=1");
  const card = page.getByRole("article", { name: "Friday Survival" });
  await card.getByRole("button", { name: "Start" }).click();
  await expect(card.locator(".host-status")).toHaveText("Running");
  // A plain count on the tab, no green dot or banner.
  await expect(page.locator(".ni", { hasText: "Hosting" }).locator(".ni-badge")).toHaveText("1");
  await expect(page.locator(".host-banner, .host-dot")).toHaveCount(0);
  await expect(card.getByText("localhost:25565")).toBeVisible();
  await expect(card.getByText("192.168.1.20:25565")).toBeVisible();
  await expect(card.getByRole("button", { name: "Delete" })).toHaveCount(0);
  await expect(card.getByLabel("Friday Survival console")).toContainText("Done");

  await card.getByLabel("Server command").fill("/say hi everyone");
  await card.getByRole("button", { name: "Send" }).click();
  await expect(card.getByLabel("Friday Survival console")).toContainText("[Server] hi everyone");

  await card.getByRole("button", { name: "Stop" }).click();
  await expect(card.getByLabel("Friday Survival console")).toContainText("Saving worlds");
  await expect(card.locator(".host-status")).toHaveText("Stopped", { timeout: 8000 });
  await expect(page.locator(".ni", { hasText: "Hosting" }).locator(".ni-badge")).toHaveCount(0, { timeout: 12000 });
  await expect(card.getByLabel("Server command")).toBeDisabled();
});

test("a stopped server can be deleted after a confirmation", async ({ preview: page }) => {
  await openHosting(page, "&servers=1");
  const card = page.getByRole("article", { name: "Friday Survival" });
  await card.getByRole("button", { name: "Delete" }).click();
  await expect(card.getByText("Delete Friday Survival and its world? This can't be undone.")).toBeVisible();
  // Cancel keeps it.
  await card.getByRole("button", { name: "Cancel" }).click();
  await expect(card.getByRole("button", { name: "Start" })).toBeVisible();
  await card.getByRole("button", { name: "Delete" }).click();
  await card.getByRole("group", { name: "Delete Friday Survival" }).getByRole("button", { name: "Delete" }).click();
  await expect(page.getByRole("article", { name: "Friday Survival" })).toHaveCount(0);
  await expect(page.getByText("You have no servers yet.")).toBeVisible();
});

test("a port another program holds is reported, and the server stays stopped", async ({ preview: page }) => {
  await openHosting(page);
  const dialog = await newServer(page);
  await dialog.getByLabel("Name").fill("Busy Port");
  await dialog.getByLabel("Port").fill("25570");
  await dialog.getByLabel(/I understand/).check();
  await dialog.getByLabel(/I accept the/).check();
  await dialog.getByRole("button", { name: "Create" }).click();
  const card = page.getByRole("article", { name: "Busy Port" });
  await card.getByRole("button", { name: "Start" }).click();
  await expect(page.getByText(/Port 25570 is in use/).first()).toBeVisible();
  await expect(card.locator(".host-status")).toHaveText("Stopped");
});

test("the rail fits a 640 px window with Hosting in it", async ({ preview: page }) => {
  await page.setViewportSize({ width: 960, height: 640 });
  await page.goto("/preview.html?nosplash=1");
  const rail = page.locator("aside.sb");
  await expect(rail.locator(".ni", { hasText: "Hosting" })).toBeVisible();
  const fits = await rail.evaluate((el) => el.scrollHeight <= el.clientHeight + 1);
  expect(fits).toBe(true);
  const last = await rail.locator(".ni").last().boundingBox();
  const avatar = await rail.locator(".sb-ft").boundingBox();
  expect(last!.y + last!.height).toBeLessThanOrEqual(avatar!.y + 1);
});
