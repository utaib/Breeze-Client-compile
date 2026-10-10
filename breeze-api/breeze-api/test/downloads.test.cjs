'use strict';
/**
 * Downloading a launcher build (1.0.23).
 *
 * The admin dashboard rendered each test build as a plain link to the file
 * route. A download is a navigation, and a navigation carries no Authorization
 * header, so the API answered {"success":false,"error":"Authentication
 * required"} and the browser displayed that JSON as a page. The gate was right;
 * the browser simply could not pass it.
 *
 * A build is now fetched through a short-lived link that names one artifact and
 * one account. Public releases stay public, test builds stay gated.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startApi } = require('./support/harness.cjs');

const OWNER = { uuid: 'e1111111111111111111111111111111', username: 'Owner', role: 'owner' };
const PLAYER = { uuid: 'e2222222222222222222222222222222', username: 'Player', role: 'user' };

const TEST_BUILD = 'testing/pre-beta/windows/Breeze-Client-1.0.23.exe';
const PUBLIC_BUILD = 'windows/Breeze-Client-1.0.21.exe';
const BODY = 'MZ not really an installer, but it is a file';

let api;

test.before(async () => {
  api = await startApi({
    name: 'downloads',
    seed: { users: [OWNER, PLAYER] },
  });
  // Put one gated build and one public build where the API serves them from.
  const root = path.join(api.dataDir, 'versions', 'launcher');
  for (const rel of [TEST_BUILD, PUBLIC_BUILD]) {
    const full = path.join(root, rel);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, BODY);
  }
});

test.after(async () => {
  if (api) await api.stop();
});

test('a public release needs no token', async () => {
  const res = await api.get(`/versions/launcher/${PUBLIC_BUILD}`);
  assert.equal(res.status, 200, res.text.slice(0, 120));
  assert.equal(res.text, BODY, 'the file itself, not JSON');
});

test('a test build is refused without a token, and the refusal is honest', async () => {
  const res = await api.get(`/versions/launcher/${TEST_BUILD}`);
  assert.equal(res.status, 401);
  assert.match(res.body.error, /Authentication required/);
});

test('an admin gets a link, and the link downloads the actual file', async () => {
  const link = await api.post('/creator/test-builds/link', {
    token: api.token(OWNER),
    body: { path: TEST_BUILD },
  });
  assert.equal(link.status, 200, link.text.slice(0, 200));
  assert.match(link.body.url, /\/versions\/launcher\/testing\/pre-beta\/windows\/Breeze-Client-1\.0\.23\.exe\?t=/);
  assert.ok(link.body.expiresIn > 0 && link.body.expiresIn <= 900, 'short lived');

  const url = new URL(link.body.url);
  const res = await api.get(url.pathname + url.search);
  assert.equal(res.status, 200, res.text.slice(0, 200));
  assert.equal(res.text, BODY, 'the installer, not an API response');
});

test('the link is for one file only', async () => {
  const link = await api.post('/creator/test-builds/link', {
    token: api.token(OWNER),
    body: { path: TEST_BUILD },
  });
  const token = new URL(link.body.url).searchParams.get('t');
  const other = await api.get(`/versions/launcher/testing/pre-beta/windows/Breeze-Client-9.9.9.exe?t=${encodeURIComponent(token)}`);
  assert.equal(other.status, 403, other.text.slice(0, 120));
});

test('a player cannot get a link, and a made-up one is refused', async () => {
  const denied = await api.post('/creator/test-builds/link', {
    token: api.token(PLAYER),
    body: { path: TEST_BUILD },
  });
  assert.equal(denied.status, 403, denied.text.slice(0, 200));

  const forged = await api.get(`/versions/launcher/${TEST_BUILD}?t=not-a-token`);
  assert.equal(forged.status, 403);
});

test('a link is refused for a path that is not a test build, or does not exist', async () => {
  const notTesting = await api.post('/creator/test-builds/link', {
    token: api.token(OWNER),
    body: { path: PUBLIC_BUILD },
  });
  assert.equal(notTesting.status, 400, notTesting.text.slice(0, 120));

  const missing = await api.post('/creator/test-builds/link', {
    token: api.token(OWNER),
    body: { path: 'testing/pre-beta/windows/nothing-here.exe' },
  });
  assert.equal(missing.status, 404);

  const escape = await api.post('/creator/test-builds/link', {
    token: api.token(OWNER),
    body: { path: 'testing/../../../../etc/passwd' },
  });
  assert.ok([400, 404].includes(escape.status), `escaping the tree is refused, saw ${escape.status}`);
});

test('the listing tells the dashboard the path and checksum of each build', async () => {
  const res = await api.get('/creator/test-builds', { token: api.token(OWNER) });
  assert.equal(res.status, 200, res.text.slice(0, 200));
  const builds = (res.body.channels || []).flatMap((c) => c.builds || []);
  const build = builds.find((b) => b.file === 'Breeze-Client-1.0.23.exe');
  assert.ok(build, 'the build is listed');
  assert.equal(build.path, TEST_BUILD, 'the path a download link is issued for');
  assert.match(build.sha256 || '', /^[0-9a-f]{64}$/, 'a checksum the dashboard can show');
  assert.match(build.downloadUrl, /^https?:\/\//);
});

test('a download link is not a bearer token for the account', async () => {
  // A download link travels in a URL: browser history, proxy logs, a referrer
  // header. requireAuth used to name the audiences it refused, and the download
  // audience was added later without anyone coming back here, so a link handed
  // out for one artifact was accepted as a full account token.
  const link = await api.post('/creator/test-builds/link', {
    body: { path: TEST_BUILD },
    token: api.token(OWNER),
  });
  assert.equal(link.status, 200, 'the owner can still get a link');
  const issued = new URL(link.body.url).searchParams.get('t');
  assert.ok(issued, 'the link carries a token');

  // It still does the one job it was minted for.
  const file = await api.get(`/versions/launcher/${TEST_BUILD}?t=${encodeURIComponent(issued)}`);
  assert.equal(file.status, 200, 'the token downloads its own artifact');

  // And nothing else. Every one of these answered 200 before.
  for (const route of ['/users/me', '/wallet', '/admin/users']) {
    const res = await api.get(route, { token: issued });
    assert.equal(res.status, 403, `${route} must refuse a download token`);
    assert.match(res.body.error, /cannot be used for account operations/);
  }
  const game = await api.post('/auth/game-session', { body: {}, token: issued });
  assert.equal(game.status, 403, 'a download token cannot mint a game-session token');
});

test('every Linux format in one folder is published, not just the AppImage', async () => {
  // The owner uploaded an AppImage, a .deb and a .rpm together into the
  // channel's linux/ folder. Only the AppImage offered a download; the other two
  // read "not published". Each non-primary format looked exclusively inside its
  // own subfolder, which nobody had created, and only the primary format got a
  // fallback to the flat folder. Dropping the three files in one place is the
  // obvious thing to do, and the release workflow emits them that way.
  const linux = path.join(api.dataDir, 'versions', 'launcher', 'testing', 'pre-beta', 'linux');
  fs.mkdirSync(linux, { recursive: true });
  const dropped = {
    appimage: 'Breeze-Client-1.0.24-x86_64.AppImage',
    deb: 'Breeze-Client-1.0.24-x86_64.deb',
    rpm: 'Breeze-Client-1.0.24-x86_64.rpm',
  };
  for (const name of Object.values(dropped)) {
    fs.writeFileSync(path.join(linux, name), `bytes of ${name}`);
  }

  const res = await api.get('/creator/test-builds', { token: api.token(OWNER) });
  assert.equal(res.status, 200);
  const channel = res.body.channels.find((c) => c.id === 'pre-beta');
  const formats = channel.platforms.linux.formats;

  for (const [formatId, name] of Object.entries(dropped)) {
    assert.equal(formats[formatId].available, true, `${formatId} must be published`);
    assert.equal(formats[formatId].latest.file, name, `${formatId} points at its own file`);
    assert.equal(formats[formatId].latest.version, '1.0.24', `${formatId} version read from the name`);
    assert.equal(formats[formatId].latest.arch, 'x86_64', `${formatId} arch read from the name`);
    // And the download link the dashboard asks for actually resolves.
    const link = await api.post('/creator/test-builds/link', {
      body: { path: formats[formatId].latest.path },
      token: api.token(OWNER),
    });
    assert.equal(link.status, 200, `${formatId} can be linked`);
    const file = await api.get(new URL(link.body.url).pathname + new URL(link.body.url).search);
    assert.equal(file.status, 200, `${formatId} downloads`);
    assert.equal(file.text, `bytes of ${name}`, `${formatId} serves its own bytes`);
  }

  // A file must not be claimed by a format it does not belong to.
  assert.notEqual(formats.deb.latest.file, dropped.appimage);
  assert.notEqual(formats.appimage.latest.file, dropped.deb);
});
