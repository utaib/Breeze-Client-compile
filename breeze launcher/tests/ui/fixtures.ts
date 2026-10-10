import { test as base, expect, type Page } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

/**
 * The preview with a fixed world: a signed-in sample account, three sample
 * capes (the API's own test textures), six installed mods, and stubbed answers
 * for every outside service, so a run never touches production.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const CAPES = path.resolve(here, "../../../breeze-api/breeze-api/mod_capes");
const VERSIONS = ["26.3", "1.21.11", "1.21.4", "1.21.1", "1.20.1"];

const capes = [
  { id: "p1", name: "Sample cape one", rarity: "rare", price_wc: 128, price_usd: 2, image_url: "https://api.breezeclient.net/preview/testcape.png", is_public: true, creator_name: "Preview" },
  { id: "p2", name: "Sample cape two", rarity: "common", price_wc: 0, price_usd: 0, image_url: "https://api.breezeclient.net/preview/onemorecape.png", is_public: true, creator_name: "Preview" },
  { id: "p3", name: "Sample cape three", rarity: "epic", price_wc: 320, price_usd: 5, image_url: "https://api.breezeclient.net/preview/skinmc-custom-cape.png", is_public: true, creator_name: "Preview" },
];
const user = { uuid: "00000000-0000-4000-8000-000000000001", username: "PreviewPlayer", role: "user" };

const API: Record<string, unknown> = {
  "/versions/mod/list": { versions: VERSIONS },
  "/feature-flags": { success: true, flags: {} },
  "/wallet": { success: true, wind_charges: 0 },
  "/notifications": { success: true, notifications: [] },
  "/capes": { success: true, capes },
  "/capes/owned": { success: true, capes: [{ cape_id: "p1", equipped: true, cape: capes[0] }, { cape_id: "p2", equipped: false, cape: capes[1] }] },
  "/cosmetics": { success: true, cosmetics: [] },
  "/cosmetics/owned": { success: true, owned: [], equipped: {}, placements: {} },
  "/auth/login": { success: true, token: "preview-token", user },
  "/users/me": { success: true, user },
  "/versions": { success: true, launcher: { latestVersion: "1.0.28" } },
  "/system/version": { success: true },
  "/social/friends": { success: true, friends: [] },
  "/social/friend-requests": { success: true, incoming: [], outgoing: [] },
  "/gifts/pending": { success: true, gifts: [] },
  "/tags/mine": { success: true, tags: [] },
};

const MODRINTH = [
  ["AANobbMI", "sodium", "Sodium"], ["gvQqBUqZ", "lithium", "Lithium"], ["uXXizFIs", "ferrite-core", "FerriteCore"],
  ["NNAgCjsB", "entityculling", "EntityCulling"], ["5ZwdcRci", "immediatelyfast", "ImmediatelyFast"], ["mOgUt4GM", "modmenu", "Mod Menu"],
].map(([id, slug, title]) => ({ project_id: id, id, slug, title, description: `${title}, sample entry`, icon_url: null, categories: ["fabric"], downloads: 1000 }));

export async function stubNetwork(page: Page) {
  await page.route("https://piston-meta.mojang.com/**", (r) => r.fulfill({ json: { latest: { release: VERSIONS[0], snapshot: VERSIONS[0] }, versions: VERSIONS.map((id, i) => ({ id, type: "release", releaseTime: new Date(Date.UTC(2026, 8, 30) - i * 864e5 * 30).toISOString() })) } }));
  await page.route("https://meta.fabricmc.net/v2/versions/game", (r) => r.fulfill({ json: VERSIONS.map((v) => ({ version: v, stable: true })) }));
  await page.route("https://meta.fabricmc.net/v2/versions/loader/**", (r) => r.fulfill({ json: [{ loader: { version: "0.19.5", stable: true } }] }));
  await page.route("https://api.breezeclient.net/**", (r) => {
    const p = new URL(r.request().url()).pathname;
    if (p.startsWith("/preview/")) return r.fulfill({ contentType: "image/png", body: fs.readFileSync(path.join(CAPES, path.basename(p))) });
    return r.fulfill({ json: API[p] ?? { success: true } });
  });
  await page.route("https://api.modrinth.com/**", (r) => r.fulfill({ json: r.request().url().includes("/search") ? { hits: MODRINTH, offset: 0, limit: 20, total_hits: MODRINTH.length } : MODRINTH }));
  await page.route(/textures\.minecraft\.net|crafatar|mc-heads|minotar|sessionserver|googlesyndication|doubleclick|audius|spotify/, (r) => r.abort());
}

export const test = base.extend<{ preview: Page }>({
  preview: async ({ page }, use) => {
    await stubNetwork(page);
    await use(page);
  },
});
export { expect };
