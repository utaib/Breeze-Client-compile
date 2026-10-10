'use strict';
/**
 * Role checks follow the database, not the role written into a sign-in token.
 *
 * A token's role claim lives for 30 days. When the owner takes a creator back
 * to user, every creator route (test builds, their download links, uploads,
 * earnings) must refuse that player at once, not when the old token runs out.
 * The reverse already held: a player promoted in the database is let in
 * before they sign in again.
 *
 *   node --test test/roles.test.cjs
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const DEMOTED = { uuid: 'a5555555555555555555555555555551', username: 'Demoted', role: 'user' };
const CREATOR = { uuid: 'a5555555555555555555555555555552', username: 'Maker', role: 'creator' };
const PROMOTED = { uuid: 'a5555555555555555555555555555553', username: 'Promoted', role: 'owner' };

test('a creator taken back to user is refused at once, whatever the old token says', async () => {
  const api = await startApi({ name: 'roles-live', seed: { users: [DEMOTED, CREATOR, PROMOTED] } });
  try {
    // Signed while they were still a creator.
    const stale = api.token({ uuid: DEMOTED.uuid, username: DEMOTED.username, role: 'creator' });
    const list = await api.get('/creator/test-builds', { token: stale });
    assert.equal(list.status, 403, `test build list: ${list.text.slice(0, 200)}`);
    const link = await api.post('/creator/test-builds/link', {
      token: stale,
      body: { path: 'testing/pre-beta/windows/Breeze-Client-1.0.27.exe' },
    });
    assert.equal(link.status, 403, `download link: ${link.text.slice(0, 200)}`);

    // A creator in the database is still let in.
    const maker = await api.get('/creator/test-builds', { token: api.token(CREATOR) });
    assert.equal(maker.status, 200, maker.text.slice(0, 200));

    // Promoted in the database since signing in: let in, as before.
    const old = api.token({ uuid: PROMOTED.uuid, username: PROMOTED.username, role: 'user' });
    const promoted = await api.get('/creator/test-builds', { token: old });
    assert.equal(promoted.status, 200, promoted.text.slice(0, 200));

    // A token for an account the database does not have is not trusted either.
    const ghost = api.token({ uuid: 'a5555555555555555555555555555559', username: 'Ghost', role: 'owner' });
    const gone = await api.get('/creator/test-builds', { token: ghost });
    assert.equal(gone.status, 403, gone.text.slice(0, 200));
  } finally {
    await api.stop();
  }
});
