import { test, expect } from "./fixtures";

/**
 * The play page as a dashboard (1.1.0): widgets the player can move, resize,
 * remove and add, kept on this computer, with Undo, Reset and Cancel, and a
 * damaged saved layout that falls back to the default instead of breaking
 * the page.
 */
const KEY = "breeze.dashboard.layout";
const item = (page, widget) => page.locator(`.dash-item[data-widget="${widget}"]`);
const customize = (page) => page.getByRole("button", { name: "Customize the play page" });
const done = (page) => page.getByRole("button", { name: /Done/ });
const gridColumn = (page, widget) => item(page, widget).evaluate((el) => (el as HTMLElement).style.gridColumn);

async function open(page) {
  await page.goto("/preview.html?nosplash=1");
  await expect(page.locator("button.launch")).toBeVisible();
}

test("the default page has the player, Play, News, quick settings and recordings", async ({ preview: page }) => {
  await open(page);
  for (const w of ["player", "launch", "news", "quick", "recordings"]) await expect(item(page, w)).toBeVisible();
  await customize(page).click();
  await expect(page.getByRole("button", { name: "Remove Play" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Remove Recordings" })).toBeVisible();
});

test("a removed widget stays removed after a restart, and Reset brings the default back", async ({ preview: page }) => {
  await open(page);
  await customize(page).click();
  await page.getByRole("button", { name: "Remove Recordings" }).click();
  await done(page).click();
  await expect(item(page, "recordings")).toHaveCount(0);
  await page.reload();
  await expect(page.locator("button.launch")).toBeVisible();
  await expect(item(page, "recordings")).toHaveCount(0);
  await customize(page).click();
  await page.getByRole("button", { name: "Reset to default" }).click();
  await done(page).click();
  await expect(item(page, "recordings")).toBeVisible();
});

test("widgets can be added from the list, and the list shrinks", async ({ preview: page }) => {
  await open(page);
  await customize(page).click();
  await page.getByRole("button", { name: /Add widget/ }).click();
  const drawer = page.getByRole("region", { name: "Widgets you can add" });
  await expect(drawer.getByText("Mods", { exact: true })).toBeVisible();
  await drawer.locator(".dash-drawer-item", { hasText: "Mods" }).getByRole("button", { name: "Add" }).click();
  await expect(drawer.getByText("Mods", { exact: true })).toHaveCount(0);
  await done(page).click();
  await expect(item(page, "mods")).toBeVisible();
  await expect(item(page, "mods").getByRole("button", { name: "Manage" })).toBeVisible();
  await page.reload();
  await expect(item(page, "mods")).toBeVisible();
});

test("Cancel drops the changes and Undo steps back", async ({ preview: page }) => {
  await open(page);
  await customize(page).click();
  await page.getByRole("button", { name: "Remove News" }).click();
  await expect(item(page, "news")).toHaveCount(0);
  await page.getByRole("button", { name: "Undo" }).click();
  await expect(item(page, "news")).toBeVisible();
  await page.getByRole("button", { name: "Remove News" }).click();
  await page.getByRole("button", { name: "Cancel" }).click();
  await expect(item(page, "news")).toBeVisible();
});

test("dragging a widget by its bar moves it, and the others make room", async ({ preview: page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await open(page);
  const before = await gridColumn(page, "news");
  await customize(page).click();
  const handle = item(page, "news").locator(".dash-handle");
  const box = await handle.boundingBox();
  await page.mouse.move(box!.x + 20, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.mouse.move(box!.x - 1200, box!.y + box!.height / 2, { steps: 12 });
  await page.mouse.up();
  await done(page).click();
  const after = await gridColumn(page, "news");
  expect(before).toMatch(/^10 \//);
  expect(after).toMatch(/^1 \//);
  await expect(item(page, "launch")).toBeVisible();
});

test("a dragged widget follows the pointer, and an outline shows where it lands", async ({ preview: page }) => {
  // Owner, 1.1.0_test_3: moving the player was "a little glitchy". The widget
  // jumped a whole cell at a time and the page re-rendered on every pointer
  // move; now it moves with the pointer and the outline marks its cell.
  await page.setViewportSize({ width: 1280, height: 800 });
  await open(page);
  await customize(page).click();
  const stage = item(page, "player");
  const handle = stage.locator(".dash-handle");
  const box = await handle.boundingBox();
  const start = await stage.boundingBox();
  await page.mouse.move(box!.x + 30, box!.y + box!.height / 2);
  await page.mouse.down();
  await page.mouse.move(box!.x + 30 + 37, box!.y + box!.height / 2 + 23, { steps: 5 });
  const mid = await stage.boundingBox();
  expect(Math.round(mid!.x - start!.x)).toBe(37);
  expect(Math.round(mid!.y - start!.y)).toBe(23);
  await expect(page.locator(".dash-ghost")).toHaveCount(1);
  await page.mouse.up();
  await expect(page.locator(".dash-ghost")).toHaveCount(0);
  expect(await stage.evaluate((el) => (el as HTMLElement).style.transform)).toBe("");
});

test("the keyboard moves and resizes the selected widget", async ({ preview: page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await open(page);
  await customize(page).click();
  const quick = item(page, "quick");
  await quick.focus();
  const rowBefore = await quick.evaluate((el) => (el as HTMLElement).style.gridRow);
  await page.keyboard.press("Shift+ArrowDown");
  const rowAfter = await quick.evaluate((el) => (el as HTMLElement).style.gridRow);
  expect(rowBefore).toMatch(/span 3$/);
  expect(rowAfter).toMatch(/span 4$/);
});

test("a damaged saved layout gives the default back and says so", async ({ preview: page }) => {
  await page.addInitScript((key) => {
    if (!sessionStorage.getItem("seeded")) {
      localStorage.setItem(key, JSON.stringify({ version: 1, items: [{ id: "x", widget: "<script>", x: 0, y: 0, w: 4, h: 4 }] }));
      sessionStorage.setItem("seeded", "1");
    }
  }, KEY);
  await open(page);
  await expect(page.getByText("Your saved layout could not be read in full")).toBeVisible();
  for (const w of ["player", "launch", "news", "quick", "recordings"]) await expect(item(page, w)).toBeVisible();
  await expect(page.locator(".dash-item")).toHaveCount(5);
});

test("an unreadable saved layout does not stop the page", async ({ preview: page }) => {
  await page.addInitScript((key) => localStorage.setItem(key, "{not json"), KEY);
  await open(page);
  for (const w of ["player", "launch", "news"]) await expect(item(page, w)).toBeVisible();
  await expect(page.getByText("Your saved layout could not be read in full")).toBeVisible();
});

test("a widget can take its own accent, and Theme default clears it", async ({ preview: page }) => {
  await open(page);
  await customize(page).click();
  await page.getByRole("button", { name: "Look of News" }).click();
  await page.getByRole("button", { name: "Accent #ff925c" }).click();
  await expect(item(page, "news")).toHaveClass(/styled/);
  const accent = await item(page, "news").evaluate((el) => (el as HTMLElement).style.getPropertyValue("--accent"));
  expect(accent).toBe("rgba(255,146,92,0.95)");
  await page.getByRole("button", { name: "Theme default" }).click();
  await expect(item(page, "news")).not.toHaveClass(/styled/);
});

test("in a narrow window the widgets stack and editing explains why it waits", async ({ preview: page }) => {
  await page.setViewportSize({ width: 820, height: 800 });
  await open(page);
  expect(await gridColumn(page, "news")).toBe("");
  await customize(page).click();
  await expect(page.getByText("Make the window wider to move and resize widgets.")).toBeVisible();
});
