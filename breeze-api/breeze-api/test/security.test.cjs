'use strict';
/**
 * Security regression tests for the v1.0.22 fixes.
 *
 * Every test here corresponds to a real finding from the 2026-09-15 audit and
 * asserts the FIXED behavior, so a regression fails the suite rather than
 * quietly reopening a hole. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startApi } = require('./support/harness.cjs');

const ALICE = { uuid: '11111111-1111-1111-1111-111111111111', username: 'Alice', role: 'user' };
const BOB = { uuid: '22222222-2222-2222-2222-222222222222', username: 'Bob', role: 'user' };
const ADMIN = { uuid: '33333333-3333-3333-3333-333333333333', username: 'Admin', role: 'admin' };

const seed = {
  users: [
    { uuid: ALICE.uuid, username: 'Alice', role: 'user', wind_charges: 0 },
    { uuid: BOB.uuid, username: 'Bob', role: 'user', wind_charges: 0 },
    { uuid: ADMIN.uuid, username: 'Admin', role: 'admin', wind_charges: 0 },
    { uuid: '44444444-4444-4444-4444-444444444444', username: 'Creator', role: 'creator', wind_charges: 0 },
  ],
};

let api;

test.before(async () => {
  api = await startApi({
    name: 'security',
    seed,
    // Configured on purpose: the removed legacy OAuth routes used to come alive
    // when this was set, so the test proves they are gone rather than disabled.
    env: { AZURE_CLIENT_ID: 'legacy-client-id-for-tests' },
  });
  // A stand-in Breeze mod jar so the distribution tests have something to ask for.
  fs.writeFileSync(path.join(api.dataDir, 'versions', 'mods', '1.20.1.jar'), 'PK not a real jar');
  fs.writeFileSync(
    path.join(api.dataDir, 'versions', 'launcher', 'testing', 'pre-beta', 'windows', 'Breeze-Client-9.9.9.exe'),
    'MZ fake installer',
  );
});

test.after(async () => {
  if (api) await api.stop();
});

test('mod player data is not readable without a token', async (t) => {
  const paths = [`/dm/${ALICE.uuid}`, `/friends/list/${ALICE.uuid}`, `/host/invites/${ALICE.uuid}`];
  for (const p of paths) {
    const res = await api.get(p);
    assert.equal(res.status, 401, `${p} must require authentication, got ${res.status} ${res.text.slice(0, 80)}`);
  }
});

test('a token for one player cannot read another player data', async () => {
  const aliceToken = api.token(ALICE);
  const res = await api.get(`/dm/${BOB.uuid}`, { token: aliceToken });
  assert.equal(res.status, 403, `Alice must not read Bob's DMs, got ${res.status}`);

  const friends = await api.get(`/friends/list/${BOB.uuid}`, { token: aliceToken });
  assert.equal(friends.status, 403);
});

test('a player can read their own data', async () => {
  const res = await api.get(`/dm/${ALICE.uuid}`, { token: api.token(ALICE) });
  assert.equal(res.status, 200, `Alice must read her own DMs, got ${res.status} ${res.text.slice(0, 120)}`);
});

test('writes to mod player data require the matching identity', async () => {
  const res = await api.post(`/dm/${BOB.uuid}`, {
    token: api.token(ALICE),
    body: 'hello',
    headers: { 'Content-Type': 'text/plain' },
  });
  assert.ok(res.status === 401 || res.status === 403, `posting as another player must be refused, got ${res.status}`);

  const anonymous = await api.post(`/dm/${ALICE.uuid}`, { body: 'hello', headers: { 'Content-Type': 'text/plain' } });
  assert.equal(anonymous.status, 401);
});

test('forged and expired tokens are rejected', async () => {
  const forged = api.tokenSignedWith('not-the-real-secret', ALICE);
  const forgedRes = await api.get(`/dm/${ALICE.uuid}`, { token: forged });
  assert.ok(forgedRes.status === 401 || forgedRes.status === 403, `forged token accepted with ${forgedRes.status}`);

  const expired = api.expiredToken(ALICE);
  const expiredRes = await api.get(`/dm/${ALICE.uuid}`, { token: expired });
  assert.ok(expiredRes.status === 401 || expiredRes.status === 403, `expired token accepted with ${expiredRes.status}`);
});

test('the Breeze mod jar is not a public download', async () => {
  const anonymous = await api.get('/versions/mod/1.20.1.jar');
  assert.ok(
    anonymous.status === 401 || anonymous.status === 403 || anonymous.status === 404,
    `mod jar must not be publicly downloadable, got ${anonymous.status}`,
  );
});

test('an authorized launcher can fetch the compatible mod jar', async () => {
  const res = await api.get('/mod/runtime/1.20.1', { token: api.token(ALICE) });
  assert.equal(res.status, 200, `authorized mod download failed: ${res.status} ${res.text.slice(0, 120)}`);
  const digest = res.headers.get('x-breeze-sha256');
  assert.match(digest || '', /^[a-f0-9]{64}$/, 'the response must carry the artifact SHA-256');
});

test('mod runtime requests cannot escape the versions directory', async () => {
  const token = api.token(ALICE);
  for (const attempt of ['../../server.js', '..%2f..%2fserver.js', '/etc/passwd', '1.20.1.jar/../../server.js']) {
    const res = await api.get(`/mod/runtime/${encodeURIComponent(attempt)}`, { token });
    assert.ok(res.status >= 400, `traversal attempt "${attempt}" returned ${res.status}`);
    assert.ok(!res.text.includes('express'), `traversal attempt "${attempt}" leaked file contents`);
  }
});

test('unreleased test builds are not downloadable anonymously', async () => {
  const res = await api.get('/versions/launcher/testing/pre-beta/windows/Breeze-Client-9.9.9.exe');
  assert.ok(
    res.status === 401 || res.status === 403,
    `test builds must require creator access, got ${res.status}`,
  );
});

test('a normal user cannot reach admin routes', async () => {
  const anonymous = await api.get('/admin/users');
  assert.equal(anonymous.status, 401);

  const asUser = await api.get('/admin/users', { token: api.token(ALICE) });
  assert.equal(asUser.status, 403, `a user role must not reach /admin/users, got ${asUser.status}`);
});

test('the update manifest never advertises a build it cannot serve', async () => {
  const res = await api.get('/versions/check?current=1.0.0&platform=windows');
  assert.equal(res.status, 200);
  const data = res.body.data || res.body;
  if (data.update) {
    assert.match(data.update.url || '', /^https:\/\//, 'update URLs must be https');
    assert.match(data.update.sha256 || '', /^[a-f0-9]{64}$/, 'an offered update must carry a sha256');
  }
  assert.equal(data.stable.available, false, 'no installer exists in this fixture, so nothing may be advertised');
});

test('a missing manifest does not invent a version', async () => {
  const res = await api.get('/versions');
  assert.equal(res.status, 200);
  const data = res.body.data || res.body;
  assert.notEqual(data.launcher.latestVersion, '0.1.0-beta', 'the API must not fabricate a placeholder version');
  for (const [platform, spec] of Object.entries(data.launcher.platforms || {})) {
    if (spec.available) {
      assert.match(spec.downloadUrl || '', /^https:\/\//, `${platform} download URL must be https`);
      assert.match(spec.sha256 || '', /^[a-f0-9]{64}$/, `${platform} must publish a sha256`);
    }
  }
});

test('a game session token proves identity to the mod but cannot touch the account', async () => {
    const issued = await api.post('/auth/game-session', { token: api.token(ALICE) });
    assert.equal(issued.status, 200, `could not mint a game session token: ${issued.text.slice(0, 160)}`);
    const gameToken = (issued.body.data || issued.body).token;
    assert.ok(gameToken, 'the response must contain a token');

    const mine = await api.get(`/dm/${ALICE.uuid}`, { token: gameToken });
    assert.equal(mine.status, 200, 'a game token must satisfy the mod identity check');

    const other = await api.get(`/dm/${BOB.uuid}`, { token: gameToken });
    assert.equal(other.status, 403, 'a game token is still only that one player');

    const account = await api.get('/users/me', { token: gameToken });
    assert.equal(account.status, 403, 'a game token must not reach account routes');
});

test('a game session token cannot mint another game session token', async () => {
    const issued = await api.post('/auth/game-session', { token: api.token(ALICE) });
    const gameToken = (issued.body.data || issued.body).token;
    const again = await api.post('/auth/game-session', { token: gameToken });
    assert.equal(again.status, 403);
});

test('an approved creator can download a test build', async () => {
    const creator = { uuid: '44444444-4444-4444-4444-444444444444', username: 'Creator', role: 'creator' };
    const res = await api.get('/versions/launcher/testing/pre-beta/windows/Breeze-Client-9.9.9.exe', {
        token: api.token(creator),
    });
    assert.equal(res.status, 200, `a creator must still get test builds, got ${res.status}`);
});

test('the player roster is not readable by strangers', async () => {
    // Both of these return a list covering everybody, not data about one
    // player: who is online, and every user's name, uuid and tag.
    for (const route of ['/users', '/tag']) {
        const anonymous = await api.get(route);
        assert.equal(anonymous.status, 401, `${route} handed out the roster without a token`);
        const authorized = await api.get(route, { token: api.token(ALICE) });
        assert.equal(authorized.status, 200, `${route} must still work for a signed-in client`);
    }
});

test('the legacy OAuth redirect flow cannot hand a session token to another site', async () => {
  // It redirected to any return URL with a 30-day session token in the
  // fragment. Sign-in uses the device-code flow, so the routes are gone.
  const evil = 'https://evil.example/steal';
  const start = await api.get(`/auth/ms/start?return=${encodeURIComponent(evil)}`, { redirect: 'manual' });
  assert.equal(start.status, 404, `/auth/ms/start must not exist, got ${start.status}`);
  assert.equal(start.headers.get('location'), null);

  const state = Buffer.from(JSON.stringify({ return: evil, ts: 1 })).toString('base64url');
  const callback = await api.get(`/auth/ms/callback?code=x&state=${state}`, { redirect: 'manual' });
  assert.equal(callback.status, 404, `/auth/ms/callback must not exist, got ${callback.status}`);
  assert.equal(callback.headers.get('location'), null);
});

test('error responses do not leak internals in production mode', async () => {
  const res = await api.get('/__no_such_route__');
  assert.equal(res.status, 404);
  assert.ok(!res.text.includes('at Object.'), 'stack traces must never reach clients');
});
