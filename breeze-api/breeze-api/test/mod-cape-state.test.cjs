'use strict';
/**
 * The mod's cape state, against rows stored the way production stores them.
 *
 * Production, read on 2026-10-03: every users.uuid and user_capes.user_uuid is
 * undashed, the users table has no equipped_cape_id column, and every
 * capes.image_url is "http://api.breezeclient.net/...". The mod asks with a
 * dashed uuid. /cosmetics/state looked the player up dashed and selected the
 * missing column, so it answered "not a Breeze account, no cape" for everyone
 * and no Breeze cape showed in game. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const DASHED = '71111111-1111-4111-8111-111111111111';
const PLAYER = DASHED.replace(/-/g, '');
const BARE_DASHED = '72222222-2222-4222-8222-222222222222';
const BARE = BARE_DASHED.replace(/-/g, '');
const CAPE = 'c1111111-1111-4111-8111-111111111111';
const SPARE = 'c2222222-2222-4222-8222-222222222222';
const IMAGE = 'http://api.test.invalid/assets/capes/marketplace/wind.png';
// Personal capes (the player's own upload, users.cape_url with no user_capes
// row) are kept in Supabase Storage, which the mod does not fetch from.
const OWN_A_DASHED = '74444444-4444-4444-8444-444444444444';
const OWN_A = OWN_A_DASHED.replace(/-/g, '');
const OWN_B_DASHED = '75555555-5555-4555-8555-555555555555';
const OWN_B = OWN_B_DASHED.replace(/-/g, '');
const storageUrl = (u) => `https://stub.invalid/storage/v1/object/public/capes/personal/${u}_cape.png`;

let api;
test.before(async () => {
  api = await startApi({
    name: 'mod-cape-state',
    seed: {
      users: [
        { uuid: PLAYER, username: 'Caped', role: 'user', cape_url: IMAGE },
        { uuid: BARE, username: 'Bare', role: 'user', cape_url: null },
        { uuid: OWN_A, username: 'OwnA', role: 'owner', cape_url: storageUrl(OWN_A) },
        { uuid: OWN_B, username: 'OwnB', role: 'user', cape_url: storageUrl(OWN_B) },
      ],
      capes: [
        { id: CAPE, name: 'Wind', image_url: IMAGE, rarity: 'rare', is_public: true, is_animated: false, animation_frames: [] },
        { id: SPARE, name: 'Spare', image_url: 'http://api.test.invalid/assets/capes/marketplace/spare.png', rarity: 'common', is_public: true, is_animated: false, animation_frames: [] },
      ],
      user_capes: [
        { id: 1, user_uuid: PLAYER, cape_id: CAPE, equipped: true },
        { id: 2, user_uuid: PLAYER, cape_id: SPARE, equipped: false },
        { id: 3, user_uuid: BARE, cape_id: SPARE, equipped: false },
      ],
      tags: [],
      user_tags: [],
    },
  });
});
test.after(async () => api && api.stop());

test('the state finds a player stored undashed, with the cape they have equipped', async () => {
  for (const asked of [DASHED, PLAYER]) {
    const res = await api.get(`/cosmetics/state/${asked}`);
    assert.equal(res.status, 200, res.text.slice(0, 200));
    assert.equal(res.body.username, 'Caped', `asked as ${asked}`);
    assert.ok(res.body.cape, 'the equipped cape must be in the state');
    assert.equal(res.body.cape.id, CAPE);
    assert.equal(res.body.ownedCapes.length, 2);
  }
});

test('the cape image comes back over https, which is what the mod fetches', async () => {
  const res = await api.get(`/cosmetics/state/${DASHED}`);
  assert.equal(res.body.cape.imageUrl, 'https://api.test.invalid/assets/capes/marketplace/wind.png');
});

test('a player who owns capes but wears none has an account and no cape', async () => {
  const res = await api.get(`/cosmetics/state/${BARE_DASHED}`);
  assert.equal(res.status, 200);
  assert.equal(res.body.username, 'Bare');
  assert.equal(res.body.cape, null);
  assert.equal(res.body.ownedCapes.length, 1);
});

test('someone who never used Breeze is answered as no account', async () => {
  const res = await api.get('/cosmetics/state/73333333-3333-4333-8333-333333333333');
  assert.equal(res.status, 200);
  assert.equal(res.body.username, null);
  assert.equal(res.body.cape, null);
});

test('the older routes the mod falls back to agree on the cape', async () => {
  const selected = await api.get(`/selected/${DASHED}`);
  assert.equal(selected.status, 200);
  assert.equal(selected.text.trim(), CAPE);
  const none = await api.get(`/selected/${BARE_DASHED}`);
  assert.equal(none.status, 404);
});

test('a personal cape is served through this API, under an id of its own', async () => {
  const a = await api.get(`/cosmetics/state/${OWN_A_DASHED}`);
  const b = await api.get(`/cosmetics/state/${OWN_B}`);
  assert.equal(a.status, 200, a.text.slice(0, 200));
  assert.equal(a.body.username, 'OwnA');
  assert.ok(a.body.cape, 'a personal cape is a cape');
  // The mod fetches images from the API's own host only.
  assert.equal(a.body.cape.imageUrl, `https://api.test.invalid/cape/${OWN_A_DASHED}`);
  assert.equal(b.body.cape.imageUrl, `https://api.test.invalid/cape/${OWN_B_DASHED}`);
  // The mod caches textures by id: two players' uploads must not share one.
  assert.match(a.body.cape.id, new RegExp(`^personal-${OWN_A}-[0-9a-f]{8}$`));
  assert.notEqual(a.body.cape.id, b.body.cape.id);
  assert.equal(a.body.cape.animated, false);
});

test('the older selection route names a personal cape as "personal"', async () => {
  const res = await api.get(`/selected/${OWN_A_DASHED}`);
  assert.equal(res.status, 200);
  assert.equal(res.text.trim(), 'personal');
});

test('the revision changes when the equipped cape changes', async () => {
  const a = await api.get(`/cosmetics/state/${DASHED}`);
  const b = await api.get(`/cosmetics/state/${BARE_DASHED}`);
  assert.ok(a.body.revision && b.body.revision);
  assert.notEqual(a.body.revision, b.body.revision);
});
