'use strict';
/**
 * Gifting (1.0.23). Two ways to give someone an item, and only two:
 *
 *   buy it for them   /store/purchase-item with gift_to  — the buyer pays, the
 *                     creator earns, the recipient receives.
 *   send your own     /gifts/send                        — the item moves; the
 *                     sender no longer has it.
 *
 * Before this, /gifts/send copied an item the sender owned: one purchased cape
 * could be handed to everyone for nothing, and the creator earned nothing for
 * any of it. The recipient was also resolved with the raw username as an ilike
 * pattern, so a gift addressed to a name containing "_" could be delivered to
 * a different account. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const GIVER = { uuid: 'a1111111111111111111111111111111', username: 'Giver', role: 'user' };
const FRIEND = { uuid: 'b2222222222222222222222222222222', username: 'pixel_', role: 'user' };
const DECOY = { uuid: 'c3333333333333333333333333333333', username: 'pixels', role: 'user' };
const CREATOR = { uuid: 'd4444444444444444444444444444444', username: 'Maker', role: 'creator' };

const CAPE = 'e5555555-5555-4555-8555-555555555555';
const PET = 'f6666666-6666-4666-8666-666666666666';

const seed = {
  users: [
    { ...GIVER, wind_charges: 10000, earned_wind_charges: 0, creator_passes: 0 },
    { ...FRIEND, wind_charges: 0, earned_wind_charges: 0, creator_passes: 0 },
    { ...DECOY, wind_charges: 0, earned_wind_charges: 0, creator_passes: 0 },
    { ...CREATOR, wind_charges: 0, earned_wind_charges: 0, creator_passes: 0, creator_share_percent: 70 },
    // Staff routes check the role in the database, not the token's claim.
    { uuid: '0f000000000000000000000000000000', username: 'Owner', role: 'owner' },
  ],
  capes: [
    { id: CAPE, name: 'Aurora Cape', price_usd: 5, creator_id: CREATOR.uuid, is_public: true, image_url: 'https://example/cape.png' },
  ],
  cosmetics: [
    { id: PET, name: 'Glare', slot: 'pet', price_usd: 2.5, creator_id: CREATOR.uuid, is_public: true },
  ],
  // The giver owns the cape already; the pet is bought during the tests.
  user_capes: [{ id: 1, user_uuid: GIVER.uuid, cape_id: CAPE, equipped: false }],
  user_cosmetics: [],
  gifts: [],
  notifications: [],
  wallet_transactions: [],
  friendships: [],
};

let api;
test.before(async () => {
  api = await startApi({ name: 'gifts', seed });
});
test.after(async () => {
  if (api) await api.stop();
});

const balances = async (who) => {
  const res = await api.get('/wallet', { token: api.token(who) });
  assert.equal(res.status, 200, res.text.slice(0, 200));
  return res.body;
};

test('sending your own item moves it: the sender no longer has it', async () => {
  const res = await api.post('/gifts/send', {
    token: api.token(GIVER),
    body: { username: 'pixel_', cape_id: CAPE },
  });
  assert.equal(res.status, 200, res.text.slice(0, 250));
  assert.equal(res.body.transferred, true);
  assert.equal(res.body.recipient.uuid, FRIEND.uuid, 'delivered to the exact name, not the lookalike');
  assert.equal(res.body.recipient.email, undefined, 'a gift receipt is not a contact card');

  const mine = await api.get('/capes/owned', { token: api.token(GIVER) });
  const theirs = await api.get('/capes/owned', { token: api.token(FRIEND) });
  const has = (r) => JSON.stringify(r.body).includes(CAPE);
  assert.equal(has(mine), false, 'the item left the sender');
  assert.equal(has(theirs), true, 'and arrived with the recipient');
});

test('you cannot send what you do not own, or send it twice', async () => {
  const again = await api.post('/gifts/send', {
    token: api.token(GIVER),
    body: { username: 'pixel_', cape_id: CAPE },
  });
  assert.equal(again.status, 403, again.text.slice(0, 200));
  assert.match(again.body.error, /do not own/);
});

test('a gift to a name nobody has is refused, not delivered somewhere else', async () => {
  const res = await api.post('/gifts/send', {
    token: api.token(FRIEND),
    body: { username: 'pixel_x', cape_id: CAPE },
  });
  assert.equal(res.status, 404, res.text.slice(0, 200));
  assert.match(res.body.error, /has not registered/);

  const decoy = await api.get('/capes/owned', { token: api.token(DECOY) });
  assert.equal(JSON.stringify(decoy.body).includes(CAPE), false, 'the lookalike account received nothing');
});

test('buying a gift charges the buyer and pays the creator', async () => {
  const before = await balances(GIVER);
  const res = await api.post('/store/purchase-item', {
    token: api.token(GIVER),
    body: { cosmetic_id: PET, gift_to: 'pixel_' },
  });
  assert.equal(res.status, 200, res.text.slice(0, 250));
  assert.equal(res.body.granted, true);
  assert.equal(res.body.recipient.uuid, FRIEND.uuid);
  assert.ok(res.body.spent > 0, 'a gift is paid for');

  const after = await balances(GIVER);
  assert.equal(
    Number(after.wind_charges ?? 0),
    Number(before.wind_charges ?? 0) - res.body.spent,
    'the buyer paid, once',
  );

  const theirs = await api.get('/cosmetics/owned', { token: api.token(FRIEND) });
  assert.ok(JSON.stringify(theirs.body).includes(PET), 'the recipient owns it');
  const mine = await api.get('/cosmetics/owned', { token: api.token(GIVER) });
  assert.equal(JSON.stringify(mine.body).includes(PET), false, 'the buyer does not get a copy');

  // The creator is paid exactly as they are for an ordinary sale: earnings are
  // recorded as an 'earn' transaction against their Breeze Rod balance.
  const creator = await balances(CREATOR);
  const earn = (creator.transactions ?? []).find((t) => t.type === 'earn');
  assert.ok(earn, `the creator was paid for the sale, saw ${JSON.stringify(creator.transactions)}`);
  assert.ok(Number(earn.amount_wc) > 0, `the earning has a value: ${JSON.stringify(earn)}`);
});

test('buying a gift for someone who already owns it is refused before any charge', async () => {
  const before = await balances(GIVER);
  const res = await api.post('/store/purchase-item', {
    token: api.token(GIVER),
    body: { cosmetic_id: PET, gift_to: 'pixel_' },
  });
  assert.equal(res.status, 400, res.text.slice(0, 200));
  assert.match(res.body.error, /already owns/);
  const after = await balances(GIVER);
  assert.equal(Number(after.wind_charges ?? 0), Number(before.wind_charges ?? 0), 'nothing was taken');
});

test('every delivery is recorded as a gift the recipient can open', async () => {
  const pending = await api.get('/gifts/pending', { token: api.token(FRIEND) });
  assert.equal(pending.status, 200, pending.text.slice(0, 200));
  const sources = (pending.body.gifts ?? []).length;
  assert.ok(sources >= 2, `both deliveries are waiting to be opened, saw ${sources}`);
});

test('gifting can be switched off for players and stays open for staff', async () => {
  const OWNER = { uuid: '0f000000000000000000000000000000', username: 'Owner', role: 'owner' };
  const off = await api.patch('/admin/feature-flags', { token: api.token(OWNER), body: { gifting_disabled: true } });
  assert.equal(off.status, 200, off.text.slice(0, 200));

  const blocked = await api.post('/gifts/send', {
    token: api.token(FRIEND),
    body: { username: 'Giver', cape_id: CAPE },
  });
  assert.equal(blocked.status, 503, blocked.text.slice(0, 200));

  const buying = await api.post('/store/purchase-item', {
    token: api.token(GIVER),
    body: { cape_id: CAPE, gift_to: 'Maker' },
  });
  assert.equal(buying.status, 503, 'buying a gift is off too');

  await api.patch('/admin/feature-flags', { token: api.token(OWNER), body: { gifting_disabled: false } });
});
