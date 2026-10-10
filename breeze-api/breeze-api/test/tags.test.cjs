'use strict';
/**
 * Role-based tags (v1.0.22).
 *
 * The custom-name tag system is retired and each role reads as one colour:
 * blue users, yellow creators, purple developers, red owners. The one choice
 * left is a creator recolouring their Creator tag from the wind charge colours.
 * These run the real routes against the stub database. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { startApi } = require('./support/harness.cjs');

const TAG = {
  owner: 'a0000000-0000-4000-8000-000000000001',
  developer: 'a0000000-0000-4000-8000-000000000002',
  admin: 'a0000000-0000-4000-8000-000000000003',
  creator: 'a0000000-0000-4000-8000-000000000004',
  breeze: 'a0000000-0000-4000-8000-000000000005',
};

const OWNER = { uuid: '51111111-1111-4111-8111-111111111111', username: 'Owner', role: 'owner' };
const DEV = { uuid: '52222222-2222-4222-8222-222222222222', username: 'Dev', role: 'developer' };
const CREATOR = { uuid: '53333333-3333-4333-8333-333333333333', username: 'Maker', role: 'creator' };
const STALE = { uuid: '54444444-4444-4444-8444-444444444444', username: 'OldMaker', role: 'creator' };
const USER = { uuid: '55555555-5555-4555-8555-555555555555', username: 'Player', role: 'user' };

const seed = {
  tags: [
    { id: TAG.owner, slug: 'owner', name: 'Owner', color: '#FF5555', priority_weight: 100, auto_role: 'owner', is_role_badge: true },
    { id: TAG.developer, slug: 'developer', name: 'Developer', color: '#A56EFF', priority_weight: 90, auto_role: 'developer', is_role_badge: true },
    { id: TAG.admin, slug: 'admin', name: 'Admin', color: '#800080', priority_weight: 80, auto_role: 'admin', is_role_badge: true },
    { id: TAG.creator, slug: 'creator', name: 'Creator', color: '#FFD23F', priority_weight: 50, auto_role: 'creator', is_role_badge: true },
    { id: TAG.breeze, slug: 'breeze', name: 'Breeze', color: '#55C8FF', priority_weight: 10, auto_role: null, is_role_badge: true },
  ],
  user_tags: [],
  users: [
    { ...OWNER, custom_tag_text: null, custom_tag_color: null },
    { ...DEV, custom_tag_text: null, custom_tag_color: null },
    { ...CREATOR, custom_tag_text: null, custom_tag_color: null },
    // Set with the retired editor: free text and a colour that is not an option.
    { ...STALE, custom_tag_text: 'Owner', custom_tag_color: '#123456' },
    { ...USER, custom_tag_text: null, custom_tag_color: null },
  ],
};

let api;

test.before(async () => {
  api = await startApi({ name: 'tags', seed });
});

test.after(async () => {
  if (api) await api.stop();
});

async function roster() {
  const res = await api.get('/tag', { token: api.token(USER) });
  assert.equal(res.status, 200, res.text.slice(0, 200));
  return res.body;
}

test('each role reads as its own colour', async () => {
  const { names, roles } = await roster();
  assert.deepEqual(names.owner, { text: '[Owner]', color: '#FF5555', badge: 'owner' });
  assert.deepEqual(names.dev, { text: '[Developer]', color: '#A56EFF', badge: 'developer' });
  assert.deepEqual(names.maker, { text: '[Creator]', color: '#FFD23F', badge: 'creator' });
  assert.equal(roles.breeze.color, '#55C8FF', 'plain users are blue');
  // Plain users need no entry of their own; the blue default covers them.
  assert.equal(names.player, undefined);
});

test('custom tag text no longer renders anywhere', async () => {
  const { names } = await roster();
  assert.equal(names.oldmaker.custom, undefined, 'the roster must not carry custom text');
  assert.equal(names.oldmaker.color, '#FFD23F', 'a stale free-form colour must not render');

  const state = await api.get(`/cosmetics/state/${STALE.uuid}`);
  assert.equal(state.status, 200, state.text.slice(0, 200));
  assert.equal(state.body.customTag, null);
  assert.equal(state.body.canCustomTag, false);
});

test('the custom tag route is retired, not silently accepted', async () => {
  const res = await api.post('/tags/custom', { token: api.token(CREATOR), body: { text: 'Owner', color: '#FF5555' } });
  assert.equal(res.status, 410);
  const users = await api.get('/tag', { token: api.token(USER) });
  assert.equal(users.body.names.maker.custom, undefined);
});

test('a creator is offered the wind charge colours, and only a creator', async () => {
  const mine = await api.get('/tags/mine', { token: api.token(CREATOR) });
  assert.equal(mine.status, 200, mine.text.slice(0, 200));
  assert.equal(mine.body.canChooseColor, true);
  assert.equal(mine.body.tagColor, null, 'nothing chosen yet');
  assert.deepEqual(mine.body.colorOptions.map((o) => o.color), ['#FFD23F', '#55C8FF', '#FF78C8', '#C8EBF5']);
  assert.equal(mine.body.customTag, undefined, 'the custom tag fields are gone');

  for (const who of [USER, DEV, OWNER]) {
    const other = await api.get('/tags/mine', { token: api.token(who) });
    assert.equal(other.body.canChooseColor, false, `${who.role} must not get the colour choice`);
    assert.deepEqual(other.body.colorOptions, []);
  }
});

test('a creator can recolour their tag, and it shows everywhere', async () => {
  const set = await api.post('/tags/color', { token: api.token(CREATOR), body: { color: '#ff78c8' } });
  assert.equal(set.status, 200, set.text.slice(0, 200));
  assert.equal(set.body.tagColor, '#FF78C8');

  const { names, roles } = await roster();
  assert.equal(names.maker.color, '#FF78C8');
  assert.equal(names.maker.badge, 'creator', 'the badge still says Creator');
  assert.equal(roles.creator.color, '#FFD23F', 'the role itself stays yellow for everyone else');

  const mine = await api.get('/tags/mine', { token: api.token(CREATOR) });
  assert.equal(mine.body.tagColor, '#FF78C8');
  assert.equal(mine.body.tags.find((t) => t.slug === 'creator').color, '#FF78C8');
  assert.equal(mine.body.badge.color, '#FFD23F', 'the official badge keeps the role colour');

  const state = await api.get(`/cosmetics/state/${CREATOR.uuid}`);
  assert.equal(state.body.tag.color, '#FF78C8');
  assert.equal(state.body.badge.color, '#FFD23F');

  const reset = await api.post('/tags/color', { token: api.token(CREATOR), body: { color: null } });
  assert.equal(reset.status, 200);
  assert.equal((await roster()).names.maker.color, '#FFD23F', 'reset returns to yellow');
});

test('staff colours and made-up colours are refused', async () => {
  for (const color of ['#FF5555', '#A56EFF', '#123456', 'pink', '<b>']) {
    const res = await api.post('/tags/color', { token: api.token(CREATOR), body: { color } });
    assert.equal(res.status, 400, `${color} must be refused, got ${res.status}`);
  }
  assert.equal((await roster()).names.maker.color, '#FFD23F', 'nothing was stored');
});

test('nobody but a creator can set a tag colour', async () => {
  for (const who of [USER, DEV, OWNER]) {
    const res = await api.post('/tags/color', { token: api.token(who), body: { color: '#55C8FF' } });
    assert.equal(res.status, 403, `${who.role} must be refused, got ${res.status}`);
  }
  const anonymous = await api.post('/tags/color', { body: { color: '#55C8FF' } });
  assert.equal(anonymous.status, 401);
});

test('schema.sql sets the role colours and the hierarchy', () => {
  const sql = fs.readFileSync(path.join(__dirname, '..', 'schema.sql'), 'utf8');
  for (const [slug, color] of [['breeze', '#55C8FF'], ['creator', '#FFD23F'], ['developer', '#A56EFF'], ['owner', '#FF5555']]) {
    assert.ok(sql.includes(`SET color = '${color}' WHERE slug = '${slug}'`), `${slug} must be set to ${color}`);
  }
  const weight = (slug) => Number(new RegExp(`SET priority_weight = (\\d+)\\s+WHERE slug = '${slug}'`).exec(sql)[1]);
  assert.ok(weight('owner') > weight('developer'));
  assert.ok(weight('developer') > weight('creator'));
  assert.ok(weight('creator') > weight('breeze'));
});

test('developers get creator tools under creator rules, and no admin powers', async () => {
  const dev = api.token(DEV);
  const builds = await api.get('/creator/test-builds', { token: dev });
  assert.equal(builds.status, 200, `a developer must reach test builds, got ${builds.status}`);
  const earnings = await api.get('/creator/wallet', { token: dev });
  assert.equal(earnings.status, 200, `a developer must see creator earnings, got ${earnings.status}`);
  const wallet = await api.get('/wallet', { token: dev });
  assert.equal(wallet.status, 200, wallet.text.slice(0, 200));
  assert.equal(wallet.body.is_creator, true, 'developers hold Creator Passes');
  assert.equal(wallet.body.creator_passes, 6400, 'and get the standard grant');

  const admin = await api.get('/admin/tags', { token: dev });
  assert.equal(admin.status, 403, 'a developer is not an admin');

  const user = api.token(USER);
  assert.equal((await api.get('/creator/test-builds', { token: user })).status, 403);
  assert.equal((await api.get('/wallet', { token: user })).body.is_creator, false);
});

test('an owner can make someone a developer', async () => {
  const res = await api.patch(`/users/${USER.uuid}/role`, { token: api.token(OWNER), body: { role: 'developer' } });
  assert.equal(res.status, 200, res.text.slice(0, 200));
  const mine = await api.get('/tags/mine', { token: api.token({ ...USER, role: 'developer' }) });
  assert.equal(mine.body.badge.slug, 'developer');
  assert.equal(mine.body.badge.color, '#A56EFF');
  // Put Player back so test order cannot matter.
  await api.patch(`/users/${USER.uuid}/role`, { token: api.token(OWNER), body: { role: 'user' } });
});

test('the old hand-written tag.txt no longer adds anyone a tag', async () => {
  // It carried arbitrary per-player names, the system v1.0.22 removes.
  fs.writeFileSync(path.join(api.dataDir, 'data', 'tag.txt'), '[Breeze]\n#55FFFF\nPlayer [SneakyName] #FF00FF\n55555555-5555-4555-8555-555555555555 [Other] #00FF00\n');
  const { names, players, color } = await roster();
  assert.equal(names.player, undefined, 'a name listed only in tag.txt gets nothing');
  assert.equal(players['55555555-5555-4555-8555-555555555555'], undefined);
  assert.equal(color, '#55C8FF', 'the default is the Breeze role colour from the database');
});
