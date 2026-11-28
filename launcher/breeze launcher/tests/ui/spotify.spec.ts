import { test, expect } from "./fixtures";

/**
 * Spotify with an app of the player's own (1.1.2). Spotify lets Breeze's app
 * serve only the few accounts its owner adds, so a player with Premium can
 * make an app in their own Spotify account and give Breeze its Client ID. The
 * panel walks through it and only ever asks for the Client ID.
 */
const REDIRECT = "https://api.breezeclient.net/spotify/callback";
const CLIENT_ID = "0123456789abcdef0123456789abcdef";

type Account = { ownApp: boolean; connected: boolean; blocked?: string };

const openFm = async (page, account: Account, seen: { posts: unknown[]; deletes: number }) => {
  await page.route("https://api.breezeclient.net/spotify/**", async (r) => {
    const req = r.request();
    const path = new URL(req.url()).pathname;
    if (path === "/spotify/status") {
      return r.fulfill({ json: {
        success: true, configured: true, connected: account.connected, premium: account.connected ? null : false,
        ownApp: account.ownApp, ownAppAvailable: true, redirectUri: REDIRECT,
        ...(account.blocked ? { blocked: account.blocked } : {}),
      } });
    }
    if (path === "/spotify/app" && req.method() === "POST") {
      const body = req.postDataJSON();
      seen.posts.push(body);
      if (!/^[0-9a-f]{32}$/.test(String(body?.clientId))) {
        return r.fulfill({ status: 400, json: { success: false, error: "That is not a Spotify Client ID." } });
      }
      account.ownApp = true;
      account.connected = false;
      delete account.blocked;
      return r.fulfill({ json: { success: true, ownApp: true, ownAppAvailable: true, redirectUri: REDIRECT, clientIdEnd: "cdef", connected: false } });
    }
    if (path === "/spotify/app" && req.method() === "DELETE") {
      seen.deletes += 1;
      account.ownApp = false;
      account.connected = false;
      return r.fulfill({ json: { success: true, ownApp: false, ownAppAvailable: true, connected: false } });
    }
    if (path === "/spotify/now-playing") return r.fulfill({ json: { success: true, playing: false, track: null } });
    return r.fulfill({ json: { success: true } });
  });
  await page.goto("/preview.html?nosplash=1");
  // The top bar's Breeze FM button opens the small player, which leads to the page.
  await page.getByRole("button", { name: "Breeze FM" }).click();
  await page.getByRole("button", { name: "Browse all music" }).click();
  return page.locator(".spotify-panel");
};

test("a player can save the Client ID of their own Spotify app, and only that is asked for", async ({ preview: page }) => {
  const account: Account = { ownApp: false, connected: false };
  const seen = { posts: [] as unknown[], deletes: 0 };
  const panel = await openFm(page, account, seen);
  await expect(panel.getByRole("button", { name: "Connect Spotify" })).toBeVisible();
  await panel.getByRole("button", { name: "Use your own Spotify app" }).click();

  const steps = panel.getByRole("group", { name: "Use your own Spotify app" });
  // The address the player's app must allow, exactly as the API gave it.
  await expect(steps.locator("code")).toHaveText(REDIRECT);
  await expect(steps.getByText(/not the secret/)).toBeVisible();
  await expect(steps.locator("input")).toHaveCount(1);

  const save = steps.getByRole("button", { name: "Save Client ID" });
  await expect(save).toBeDisabled();
  // A wrong paste is caught before anything is sent.
  await steps.getByLabel("Client ID").fill("not-a-client-id");
  await expect(steps.getByText(/That is not a Client ID/)).toBeVisible();
  await expect(save).toBeDisabled();
  expect(seen.posts).toHaveLength(0);

  await steps.getByLabel("Client ID").fill(`  ${CLIENT_ID}  `);
  await expect(save).toBeEnabled();
  await save.click();
  await expect(panel.getByText("Your Spotify app is saved. Now connect Spotify.")).toBeVisible();
  expect(seen.posts).toEqual([{ clientId: CLIENT_ID }]);
  // The steps are gone; the way back to Breeze's app is offered.
  await expect(panel.getByRole("group", { name: "Use your own Spotify app" })).toHaveCount(0);
  await panel.getByRole("button", { name: "Use Breeze's app instead" }).click();
  await expect(panel.getByRole("button", { name: "Use your own Spotify app" })).toBeVisible();
  expect(seen.deletes).toBe(1);
});

test("an account Breeze's app cannot serve is offered an app of its own", async ({ preview: page }) => {
  const account: Account = { ownApp: false, connected: true, blocked: "NOT_REGISTERED" };
  const panel = await openFm(page, account, { posts: [], deletes: 0 });
  await expect(panel.getByText("Spotify has not allowed this account yet")).toBeVisible();
  await expect(panel.getByText(/you can use your own app instead/)).toBeVisible();
  await panel.getByRole("button", { name: "Use your own Spotify app" }).click();
  await expect(panel.getByRole("group", { name: "Use your own Spotify app" }).getByLabel("Client ID")).toBeVisible();
  await expect(page.getByText(/not implemented|coming soon/i)).toHaveCount(0);
});

test("an own app that refuses the account says where to add it", async ({ preview: page }) => {
  const account: Account = { ownApp: true, connected: true, blocked: "NOT_REGISTERED" };
  const panel = await openFm(page, account, { posts: [], deletes: 0 });
  await expect(panel.getByText("Your Spotify app has not allowed this account yet")).toBeVisible();
  await expect(panel.getByText(/add your Spotify email under User Management/)).toBeVisible();
  await expect(panel.getByRole("button", { name: "Check again" })).toBeVisible();
});
