'use strict';
/**
 * Public asset files can be shown on other sites.
 *
 * helmet() answers every response with Cross-Origin-Resource-Policy:
 * same-origin. On /assets that made browsers refuse cape pictures as plain
 * images anywhere but api.breezeclient.net itself, so the website store on
 * breezeclient.net showed no capes (net::ERR_BLOCKED_BY_RESPONSE.NotSameOrigin).
 * /assets is public by design and now says cross-origin; every other route
 * keeps helmet's same-origin, and unpaid personal capes stay hidden.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startApi } = require('./support/harness.cjs');

// The smallest valid PNG: a 1x1 transparent pixel.
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
);

let api;
test.before(async () => {
  api = await startApi({ name: 'assets', seed: { users: [] } });
  const storage = path.join(api.dataDir, 'storage');
  for (const rel of ['capes/marketplace/test-cape.png', 'capes/pending/unpaid.png']) {
    fs.mkdirSync(path.dirname(path.join(storage, rel)), { recursive: true });
    fs.writeFileSync(path.join(storage, rel), PNG);
  }
});
test.after(async () => {
  if (api) await api.stop();
});

test('a cape picture may be shown as an image on another site', async () => {
  const res = await api.get('/assets/capes/marketplace/test-cape.png', { headers: { Origin: 'https://breezeclient.net' } });
  assert.equal(res.status, 200);
  assert.match(res.headers.get('content-type') || '', /^image\/png/);
  assert.equal(res.headers.get('cross-origin-resource-policy'), 'cross-origin');
  assert.equal(res.headers.get('access-control-allow-origin'), '*');
});

test('other routes keep the same-origin default', async () => {
  const res = await api.get('/health');
  assert.equal(res.headers.get('cross-origin-resource-policy'), 'same-origin');
});

test('an unpaid personal cape is still not served', async () => {
  const res = await api.get('/assets/capes/pending/unpaid.png');
  assert.equal(res.status, 404);
});
