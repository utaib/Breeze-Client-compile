import { test, expect } from "./fixtures";

/**
 * News shows the team's real announcements and nothing built into the
 * launcher (1.1.0). No announcements reads as such; an API that cannot be
 * reached says so and offers a retry, so the two never look alike.
 */
const panel = (page) => page.locator("section.news-panel");

test("with no announcements the panel says so, and shows nothing invented", async ({ preview: page }) => {
  await page.route("**/announcements", (r) => r.fulfill({ json: { success: true, announcements: [] } }));
  await page.goto("/preview.html?nosplash=1");
  await expect(panel(page).getByText("No announcements yet.")).toBeVisible();
  await expect(panel(page).locator(".news-item")).toHaveCount(0);
  await expect(panel(page)).not.toContainText("What's new");
});

test("published announcements are listed, newest first as the API sends them", async ({ preview: page }) => {
  await page.route("**/announcements", (r) => r.fulfill({ json: { success: true, announcements: [
    { id: "a2", title: "Server maintenance tonight", body: "Back at 10pm.", from: "Utaib", role: "Owner", created_at: new Date().toISOString() },
    { id: "a1", title: "Welcome to Breeze", body: null, from: "Utaib", role: "Owner", created_at: new Date(Date.now() - 3 * 864e5).toISOString() },
  ] } }));
  await page.goto("/preview.html?nosplash=1");
  await expect(panel(page).locator(".news-title")).toHaveText(["Server maintenance tonight", "Welcome to Breeze"]);
  await expect(panel(page).getByText("No announcements yet.")).toHaveCount(0);
});

test("an API that cannot be reached is reported, not shown as empty, and Try again recovers", async ({ preview: page }) => {
  let up = false;
  await page.route("**/announcements", (r) => (up
    ? r.fulfill({ json: { success: true, announcements: [{ id: "a1", title: "Back online", body: null, from: "Breeze", role: "Team", created_at: new Date().toISOString() }] } })
    : r.abort("connectionrefused")));
  await page.goto("/preview.html?nosplash=1");
  await expect(panel(page).getByText("Breeze news could not be reached.")).toBeVisible();
  await expect(panel(page).getByText("No announcements yet.")).toHaveCount(0);
  up = true;
  await panel(page).getByRole("button", { name: "Try again" }).click();
  await expect(panel(page).locator(".news-title")).toHaveText(["Back online"]);
  await expect(panel(page).getByText("could not be reached")).toHaveCount(0);
});

test("an API older than 1.0.38 falls back to the announcements on the bell", async ({ preview: page }) => {
  await page.route("**/announcements", (r) => r.fulfill({ status: 404, json: { success: false, error: "Endpoint not found" } }));
  await page.route("**/notifications", (r) => r.fulfill({ json: { success: true, notifications: [
    { id: "n1", type: "Announcement from Utaib (Owner)", title: "Patch notes are up", body: "Read them on the site.", data: { kind: "announcement" }, created_at: new Date().toISOString() },
  ] } }));
  await page.goto("/preview.html?nosplash=1");
  await expect(panel(page).locator(".news-title")).toHaveText(["Patch notes are up"]);
});
