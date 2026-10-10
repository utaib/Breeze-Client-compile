'use strict';
/**
 * Equipping 3D cosmetics from inside the game.
 *
 * The game holds a game-session token (audience "breeze-game"), which the
 * launcher's /cosmetics/owned, /cosmetics/equip and /cosmetics/unequip refuse
 * by design. The mod's own routes take a token for exactly the player in the
 * path, and share the launcher's code, so ownership is checked the same way.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const PLAYER = { uuid: '63333333-3333-4333-8333-333333333333', username: 'Wearer', role: 'user' };
const OTHER = { uuid: '64444444-4444-4444-8444-444444444444', username: 'Someone', role: 'user' };

let api;
test.before(async () => {
  api = await startApi({
    name: 'mod-cosmetics',
    env: { BREEZE_MOD_SECRET: 'shared-mod-secret-for-the-test' },
    seed: {
      users: [{ ...PLAYER }, { ...OTHER }],
      cosmetics: [
        { id: 'hat-owned', slot: 'hat', name: 'Owned Hat', model_url: 'https://example.invalid/hat.glb', is_public: true },
        { id: 'hat-other', slot: 'hat', name: 'Other Hat', model_url: 'https://example.invalid/hat2.glb', is_public: true },
        { id: 'pet-owned', slot: 'pet', name: 'Owned Pet', model_url: 'https://example.invalid/pet.glb', is_public: true },
      ],
      user_cosmetics: [
        { user_uuid: PLAYER.uuid, cosmetic_id: 'hat-owned' },
        { user_uuid: PLAYER.uuid, cosmetic_id: 'pet-owned' },
        { user_uuid: OTHER.uuid, cosmetic_id: 'hat-other' },
      ],
      user_equipped_cosmetics: [],
    },
  });
});
test.after(async () => { if (api) await api.stop(); });

const game = (who) => api.token({ uuid: who.uuid, username: who.username, role: who.role, aud: 'breeze-game' });
const account = (who) => api.token({ uuid: who.uuid, username: who.username, role: who.role });
const wearing = async (who) => (await api.get(`/cosmetics/equipped/${who.uuid}`)).body.equipped.map((r) => r.cosmetic_id).sort();

test('the game lists what the player owns and wears with its game token', async () => {
  const res = await api.get(`/cosmetics/owned/${PLAYER.uuid}`, { token: game(PLAYER) });
  assert.equal(res.status, 200, res.text);
  assert.deepEqual(res.body.owned.map((o) => o.cosmetic_id).sort(), ['hat-owned', 'pet-owned']);
  assert.equal(res.body.owned[0].cosmetic.model_url.startsWith('https://'), true, 'the model is there to draw');
  assert.deepEqual(res.body.equipped, {});
});

test('equip and unequip from the game land in the same rows other players read', async () => {
  const on = await api.post(`/cosmetics/equip/${PLAYER.uuid}`, { token: game(PLAYER), body: { cosmetic_id: 'hat-owned' } });
  assert.equal(on.status, 200, on.text);
  assert.deepEqual(on.body, { success: true, slot: 'hat', cosmetic_id: 'hat-owned' });
  assert.deepEqual(await wearing(PLAYER), ['hat-owned']);

  const pet = await api.post(`/cosmetics/equip/${PLAYER.uuid}`, { token: game(PLAYER), body: { cosmetic_id: 'pet-owned' } });
  assert.equal(pet.status, 200, pet.text);
  assert.deepEqual(await wearing(PLAYER), ['hat-owned', 'pet-owned']);

  const owned = await api.get(`/cosmetics/owned/${PLAYER.uuid}`, { token: game(PLAYER) });
  assert.deepEqual(owned.body.equipped, { hat: 'hat-owned', pet: 'pet-owned' });

  const off = await api.post(`/cosmetics/unequip/${PLAYER.uuid}`, { token: game(PLAYER), body: { slot: 'hat' } });
  assert.equal(off.status, 200, off.text);
  assert.deepEqual(await wearing(PLAYER), ['pet-owned']);
});

test('the launcher sees what the game equipped, and the other way round', async () => {
  const fromLauncher = await api.post('/cosmetics/equip', { token: account(PLAYER), body: { cosmetic_id: 'hat-owned' } });
  assert.equal(fromLauncher.status, 200, fromLauncher.text);
  const inGame = await api.get(`/cosmetics/owned/${PLAYER.uuid}`, { token: game(PLAYER) });
  assert.equal(inGame.body.equipped.hat, 'hat-owned');

  await api.post(`/cosmetics/unequip/${PLAYER.uuid}`, { token: game(PLAYER), body: { slot: 'hat' } });
  const inLauncher = await api.get('/cosmetics/owned', { token: account(PLAYER) });
  assert.equal(inLauncher.body.equipped.hat, undefined);
});

test('a cosmetic the player does not own cannot be equipped from the game', async () => {
  const res = await api.post(`/cosmetics/equip/${PLAYER.uuid}`, { token: game(PLAYER), body: { cosmetic_id: 'hat-other' } });
  assert.equal(res.status, 403);
  assert.match(res.body.error, /do not own/);
  assert.equal((await wearing(PLAYER)).includes('hat-other'), false);
});

test('bad requests are clear 400s and 404s', async () => {
  const none = await api.post(`/cosmetics/equip/${PLAYER.uuid}`, { token: game(PLAYER), body: {} });
  assert.equal(none.status, 400);
  const missing = await api.post(`/cosmetics/equip/${PLAYER.uuid}`, { token: game(PLAYER), body: { cosmetic_id: 'no-such-thing' } });
  assert.equal(missing.status, 404);
  const slot = await api.post(`/cosmetics/unequip/${PLAYER.uuid}`, { token: game(PLAYER), body: { slot: 'not-a-slot' } });
  assert.equal(slot.status, 400);
});

test('a token only ever acts for its own player', async () => {
  for (const [method, path, body] of [
    ['get', `/cosmetics/owned/${PLAYER.uuid}`, undefined],
    ['post', `/cosmetics/equip/${PLAYER.uuid}`, { cosmetic_id: 'hat-owned' }],
    ['post', `/cosmetics/unequip/${PLAYER.uuid}`, { slot: 'pet' }],
  ]) {
    const nobody = await api[method](path, { body });
    assert.equal(nobody.status, 401, `${path} without a token`);

    const someoneElse = await api[method](path, { token: game(OTHER), body });
    assert.equal(someoneElse.status, 403, `${path} with another player's game token`);

    const expired = await api[method](path, { token: api.expiredToken({ uuid: PLAYER.uuid, aud: 'breeze-game' }), body });
    assert.equal(expired.status, 403, `${path} with an expired token`);

    const forged = await api[method](path, { token: api.tokenSignedWith('not-the-server-secret', { uuid: PLAYER.uuid, aud: 'breeze-game' }), body });
    assert.equal(forged.status, 403, `${path} with a token the server did not sign`);

    // A token minted for one job (a download link) is not a sign-in.
    const scoped = await api[method](path, { token: api.token({ uuid: PLAYER.uuid, aud: 'breeze-download' }), body });
    assert.equal(scoped.status, 403, `${path} with a download token`);

    // The shared mod secret names the mod, not a player: not enough to change what someone wears.
    const secretOnly = await api[method](path, { headers: { 'x-breeze-mod-key': 'shared-mod-secret-for-the-test' }, body });
    assert.equal(secretOnly.status, 401, `${path} with only the shared mod secret`);
  }
  assert.deepEqual(await wearing(PLAYER), ['pet-owned'], 'nothing above changed what the player wears');
});

test('the uuid in the path may be written with or without dashes', async () => {
  const undashed = PLAYER.uuid.replace(/-/g, '');
  const res = await api.get(`/cosmetics/owned/${undashed}`, { token: game(PLAYER) });
  assert.equal(res.status, 200, res.text);
});

test("the launcher's routes still refuse a game token", async () => {
  const res = await api.post('/cosmetics/equip', { token: game(PLAYER), body: { cosmetic_id: 'hat-owned' } });
  assert.equal(res.status, 403);
  const owned = await api.get('/cosmetics/owned', { token: game(PLAYER) });
  assert.equal(owned.status, 403);
});
