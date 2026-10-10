'use strict';
/**
 * Launcher download counts (2026-10-09).
 *
 * Every public installer download is one launcher_downloads row: system, file,
 * source (website, updater or direct) and whether the whole file went out.
 * Link previews, HEAD requests, resumed ranges, missing files and test builds
 * are not downloads and are not counted.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startApi } = require('./support/harness.cjs');
const { osOf, versionOf } = require('../src/downloadStats');

const OWNER = { uuid: 'd1111111111111111111111111111111', username: 'Owner', role: 'owner' };
const PLAYER = { uuid: 'd2222222222222222222222222222222', username: 'Player', role: 'user' };

const FILES = {
  windows: 'windows/Breeze-Client-1.0.30-x86_64.exe',
  macos: 'macos/Breeze-Client-1.0.30-universal.dmg',
  linux: 'linux/Breeze-Client-1.0.30-x86_64.AppImage',
  testing: 'testing/pre-beta/windows/Breeze-Client-1.0.31.exe',
};
const BODY = 'MZ not really an installer, but it is a file';
const BROWSER_A = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) A';
const BROWSER_B = 'Mozilla/5.0 (X11; Linux x86_64) B';

let api;
const as = (user) => api.token({ uuid: user.uuid, username: user.username, role: user.role });
const download = (file, headers = {}, query = '') =>
  api.get(`/versions/launcher/${file}${query}`, { headers });
const stats = async () => (await api.get('/admin/downloads?days=7', { token: as(OWNER) })).body;

/** Rows are written when the response closes, so wait for them to land. */
async function settle(expected) {
  let last;
  for (let i = 0; i < 50; i++) {
    last = await stats();
    if (last.total.downloads + last.total.updates >= expected) return last;
    await new Promise((r) => setTimeout(r, 50));
  }
  return last;
}

test.before(async () => {
  api = await startApi({ name: 'download-stats', seed: { users: [OWNER, PLAYER] } });
  const root = path.join(api.dataDir, 'versions', 'launcher');
  for (const rel of Object.values(FILES)) {
    fs.mkdirSync(path.dirname(path.join(root, rel)), { recursive: true });
    fs.writeFileSync(path.join(root, rel), BODY);
  }
});

test.after(async () => {
  if (api) await api.stop();
});

test('system and version are read from the installer path', () => {
  assert.equal(osOf(FILES.windows), 'windows');
  assert.equal(osOf(FILES.macos), 'macos');
  assert.equal(osOf('linux/breeze-client_1.0.30_amd64.deb'), 'linux');
  assert.equal(osOf('Breeze-Client-1.0.21.exe'), 'windows', 'an old build at the top level');
  assert.equal(osOf('windows/latest.json'), null, 'not an installer');
  assert.equal(versionOf(FILES.windows), '1.0.30');
});

test('only admins and owners can read the counts', async () => {
  const res = await api.get('/admin/downloads', { token: as(PLAYER) });
  assert.equal(res.status, 403);
  const none = await api.get('/admin/downloads');
  assert.equal(none.status, 401);
});

test('downloads are counted by system and source; previews, retries and test builds are not', async () => {
  // Counted.
  assert.equal((await download(FILES.windows, { 'User-Agent': BROWSER_A }, '?src=site')).text, BODY);
  assert.equal((await download(FILES.windows, { 'User-Agent': BROWSER_A }, '?src=site')).status, 200);
  assert.equal((await download(FILES.linux, { 'User-Agent': BROWSER_B })).status, 200);
  assert.equal((await download(FILES.macos, { 'User-Agent': 'BreezeLauncher' })).status, 200);
  assert.equal((await download(FILES.windows, { 'User-Agent': BROWSER_B, Range: 'bytes=0-' }, '?src=site')).status, 206);

  // Not counted.
  assert.equal((await download(FILES.windows, { 'User-Agent': 'Mozilla/5.0 (compatible; Discordbot/2.0)' })).status, 200);
  assert.equal((await download(FILES.windows, { 'User-Agent': BROWSER_A, Range: 'bytes=10-' })).status, 206);
  assert.equal((await download('windows/Breeze-Client-9.9.9.exe', { 'User-Agent': BROWSER_A })).status, 404);
  const head = await fetch(`${api.base}/versions/launcher/${FILES.windows}`, { method: 'HEAD' });
  assert.equal(head.status, 200);
  const linkless = await download(FILES.testing, { 'User-Agent': BROWSER_A });
  assert.equal(linkless.status, 401);
  const tester = await api.get(`/versions/launcher/${FILES.testing}`, { token: as(OWNER) });
  assert.equal(tester.status, 200, 'a test build downloads, it just is not counted');

  const s = await settle(5);
  await new Promise((r) => setTimeout(r, 200));
  const final = await stats();
  assert.deepEqual(final.total, s.total, 'nothing else arrived late');

  assert.equal(s.total.downloads, 4, 'two website clicks, one direct link, one ranged website download');
  assert.equal(s.total.website, 3);
  assert.equal(s.total.direct, 1);
  assert.equal(s.total.completed, 4);
  assert.equal(s.total.updates, 1, 'the launcher updating itself is kept apart');
  assert.equal(s.total.people, 2, 'browser A twice and browser B twice today are two people');

  assert.equal(s.byOs.windows.downloads, 3);
  assert.equal(s.byOs.windows.people, 2);
  assert.equal(s.byOs.linux.downloads, 1);
  assert.equal(s.byOs.macos.downloads, 0);
  assert.equal(s.byOs.macos.updates, 1);

  assert.deepEqual(s.versions, [{ version: '1.0.30', downloads: 4, updates: 1 }]);
  assert.equal(s.daily.length, 7);
  const today = s.daily[s.daily.length - 1];
  assert.equal(today.date, new Date().toISOString().slice(0, 10));
  assert.deepEqual([today.windows, today.macos, today.linux, today.updates], [3, 0, 1, 1]);
  assert.ok(s.since, 'first download time');
});
