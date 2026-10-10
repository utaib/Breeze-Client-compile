'use strict';
/**
 * Ad boxes (2026-10-10): the owner puts an image of their own in a box in the
 * launcher or on the website, with AdSense as the default (src/adBoxes.js).
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const sharp = require('sharp');
const { startApi } = require('./support/harness.cjs');

const OWNER = { uuid: 'b1111111111111111111111111111111', username: 'Phantomxdz', role: 'owner' };
const PLAYER = { uuid: 'b3333333333333333333333333333333', username: 'Player', role: 'user' };

let api;
const as = (user) => api.token({ uuid: user.uuid, username: user.username, role: user.role });

async function save(box, fields, { token = as(OWNER), image } = {}) {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields || {})) form.append(k, v);
  if (image) form.append('image', new Blob([image.bytes], { type: image.type }), image.name);
  const res = await fetch(`${api.base}/admin/ads/${box}`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  return { status: res.status, body: await res.json().catch(() => null) };
}

test.before(async () => {
  api = await startApi({ name: 'ad-boxes', seed: { users: [OWNER, PLAYER] } });
});

test.after(async () => {
  if (api) await api.stop();
});

test('every box starts on AdSense, with no image', async () => {
  const res = await api.get('/ads/boxes');
  assert.equal(res.status, 200);
  for (const id of ['launcher-play', 'launcher-store', 'site-home', 'site-docs']) {
    assert.equal(res.body.boxes[id].mode, 'adsense', id);
    assert.equal(res.body.boxes[id].image_url, null, id);
  }
});

test('a player cannot change a box', async () => {
  const res = await save('launcher-play', { link: 'https://breezeclient.net' }, { token: as(PLAYER) });
  assert.equal(res.status, 403);
});

test('the owner uploads an image, the box shows it with its link, and AdSense comes back on request', async () => {
  const png = await sharp({ create: { width: 728, height: 90, channels: 4, background: '#3366ff' } }).png().toBuffer();
  const up = await save('launcher-play', { link: 'https://breezeclient.net/store', alt: 'Breeze store' }, { image: { bytes: png, type: 'image/png', name: 'banner.png' } });
  assert.equal(up.status, 200, JSON.stringify(up.body));
  assert.equal(up.body.box.mode, 'image');

  const pub = (await api.get('/ads/boxes')).body.boxes['launcher-play'];
  assert.equal(pub.mode, 'image');
  assert.equal(pub.link, 'https://breezeclient.net/store');
  assert.equal(pub.alt, 'Breeze store');
  const img = await fetch(pub.image_url.replace(/^https?:\/\/[^/]+/, api.base));
  assert.equal(img.status, 200, 'the image is served from /assets');

  const back = await save('launcher-play', { mode: 'adsense' });
  assert.equal(back.body.box.mode, 'adsense');
  assert.equal((await api.get('/ads/boxes')).body.boxes['launcher-play'].mode, 'adsense');
  const again = await save('launcher-play', { mode: 'image' });
  assert.equal(again.body.box.mode, 'image', 'the image is kept, so it can be switched back on');

  const del = await api.delete('/admin/ads/launcher-play/image', { token: as(OWNER) });
  assert.equal(del.status, 200);
  assert.equal(del.body.box.mode, 'adsense');
  assert.equal(del.body.box.image_url, null);
});

test('bad input is refused', async () => {
  assert.equal((await save('nowhere', { link: '' })).status, 404);
  assert.equal((await save('site-home', { link: 'javascript:alert(1)' })).status, 400);
  assert.equal((await save('site-home', { link: 'http://example.com' })).status, 400, 'https only');
  assert.equal((await save('site-home', { adsense_slot: '12ab' })).status, 400);
  assert.equal((await save('site-home', { mode: 'image' })).status, 400, 'no image uploaded yet');
  const fake = await save('site-home', {}, { image: { bytes: Buffer.from('not an image'), type: 'image/png', name: 'x.png' } });
  assert.equal(fake.status, 400);
  const ok = await save('site-home', { adsense_slot: '1234567890' });
  assert.equal(ok.body.box.adsense_slot, '1234567890');
});
