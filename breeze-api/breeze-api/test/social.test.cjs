'use strict';
/**
 * Friends, search and direct messages (1.0.23).
 *
 * Production had never recorded a single friendship or message. Searching for
 * a real player answered "This user has not registered with Breeze Client yet",
 * which players read as "Breeze cannot see my friend because they are offline".
 * Being offline was never the reason: the username was handed to ilike as a
 * pattern, so the ten production names containing "_" matched the wrong rows or
 * none, and any database error was reported as the same "not registered".
 *
 * These run the real routes against the stub database. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const ME = { uuid: '11111111111111111111111111111111', username: 'Phantomxdz', role: 'user' };
const UNDERSCORE = { uuid: '22222222222222222222222222222222', username: 'bushpig_', role: 'user' };
const DECOY = { uuid: '33333333333333333333333333333333', username: 'bushpigs', role: 'user' };
const OFFLINE = { uuid: '44444444444444444444444444444444', username: 'Firatic', role: 'creator' };
const STRANGER = { uuid: '55555555555555555555555555555555', username: 'Nobody', role: 'user' };

// last_seen is deliberately old for everyone but me: being offline must change
// nothing about being findable, addable or messageable.
const LONG_AGO = '2026-01-01T00:00:00.000Z';

const seed = {
  users: [
    { ...ME, email: 'me@example.com', paypal_email: 'me@paypal.example', last_seen: new Date().toISOString() },
    { ...UNDERSCORE, email: 'pig@example.com', paypal_email: 'pig@paypal.example', last_seen: LONG_AGO },
    { ...DECOY, email: 'decoy@example.com', last_seen: LONG_AGO },
    { ...OFFLINE, email: 'fir@example.com', paypal_email: 'fir@paypal.example', last_seen: LONG_AGO },
    { ...STRANGER, last_seen: LONG_AGO },
    // Staff routes check the role in the database, not the token's claim.
    { uuid: '99999999999999999999999999999999', username: 'Owner', role: 'owner', last_seen: LONG_AGO },
  ],
  friendships: [],
  messages: [],
  notifications: [],
};

let api;
const meToken = () => api.token(ME);

test.before(async () => {
  api = await startApi({ name: 'social', seed });
});
test.after(async () => {
  if (api) await api.stop();
});

test('a username containing an underscore finds that exact player, not a lookalike', async () => {
  const res = await api.get('/social/search?q=bushpig_', { token: meToken() });
  assert.equal(res.status, 200, res.text.slice(0, 200));
  assert.equal(res.body.message, null, 'a registered player is never reported as unregistered');
  assert.equal(res.body.users[0].username, 'bushpig_', 'the exact name comes first');
  assert.equal(res.body.users[0].uuid, UNDERSCORE.uuid);
});

test('an offline player is found like any other', async () => {
  const res = await api.get('/social/search?q=Firatic', { token: meToken() });
  assert.equal(res.body.users.length, 1);
  assert.equal(res.body.users[0].uuid, OFFLINE.uuid);
  assert.equal(res.body.message, null);
});

test('search never hands out an email or a PayPal address', async () => {
  const res = await api.get('/social/search?q=bushpig', { token: meToken() });
  assert.ok(res.body.users.length >= 1);
  for (const u of res.body.users) {
    assert.equal(u.email, undefined, 'email must not leave the account it belongs to');
    assert.equal(u.paypalEmail, undefined, 'a payout address is not public');
  }
  assert.ok(!res.text.includes('paypal.example'), 'no payout address anywhere in the response');
});

test('a name nobody has is reported as unregistered, and only then', async () => {
  const res = await api.get('/social/search?q=NoSuchPlayerHere', { token: meToken() });
  assert.equal(res.body.users.length, 0);
  assert.match(res.body.message, /has not registered/);
});

test('a friend request sent from a search result uses the uuid, and reaches an offline player', async () => {
  const res = await api.post('/social/friend-requests', {
    token: meToken(),
    body: { target_uuid: OFFLINE.uuid },
  });
  assert.equal(res.status, 201, res.text.slice(0, 200));
  assert.equal(res.body.request.addressee_uuid, OFFLINE.uuid);
  assert.equal(res.body.request.status, 'pending');

  const listed = await api.get('/social/friends', { token: api.token(OFFLINE) });
  assert.equal(listed.body.requests.length, 1, 'it is waiting for them when they next sign in');
  assert.equal(listed.body.requests[0].user.username, ME.username);
  assert.equal(listed.body.requests[0].user.email, undefined, 'the friends list is not a contact list');
});

test('a request by name reaches the player whose name it is', async () => {
  const res = await api.post('/social/friend-requests', {
    token: meToken(),
    body: { username: 'bushpig_' },
  });
  assert.equal(res.status, 201, res.text.slice(0, 200));
  assert.equal(res.body.request.addressee_uuid, UNDERSCORE.uuid, 'not the lookalike account');
});

test('asking back is the same as saying yes', async () => {
  const theirs = await api.post('/social/friend-requests', {
    token: api.token(STRANGER),
    body: { target_uuid: ME.uuid },
  });
  assert.equal(theirs.status, 201);

  const mine = await api.post('/social/friend-requests', {
    token: meToken(),
    body: { target_uuid: STRANGER.uuid },
  });
  assert.equal(mine.status, 200, mine.text.slice(0, 200));
  assert.equal(mine.body.request.status, 'accepted');
  assert.match(mine.body.message, /now friends/);
});

test('messages only travel between friends, and reading them marks them read', async () => {
  // STRANGER and ME became friends in the previous test.
  const sent = await api.post('/social/messages', {
    token: api.token(STRANGER),
    body: { recipient_uuid: ME.uuid, body: 'hello there' },
  });
  assert.equal(sent.status, 201, sent.text.slice(0, 200));

  const unreadList = await api.get('/social/friends', { token: meToken() });
  const row = unreadList.body.friends.find((f) => f.uuid === STRANGER.uuid);
  assert.ok(row, 'the friendship is listed');
  assert.equal(row.unread, 1, 'the friends list says where the conversation is');

  const convo = await api.get(`/social/messages/${STRANGER.uuid}`, { token: meToken() });
  assert.equal(convo.body.messages.length, 1);
  assert.equal(convo.body.messages[0].body, 'hello there');
  assert.ok(convo.body.messages[0].read_at, 'opening the conversation marks it read');

  const after = await api.get('/social/friends', { token: meToken() });
  assert.equal(after.body.friends.find((f) => f.uuid === STRANGER.uuid).unread, 0);

  // Polling asks only for what is new.
  const since = await api.get(
    `/social/messages/${STRANGER.uuid}?since=${encodeURIComponent(convo.body.messages[0].created_at)}`,
    { token: meToken() },
  );
  assert.equal(since.body.messages.length, 0, 'nothing new yet');
});

test('a stranger cannot message you', async () => {
  const res = await api.post('/social/messages', {
    token: api.token(OFFLINE),
    body: { recipient_uuid: ME.uuid, body: 'unsolicited' },
  });
  assert.equal(res.status, 403, res.text.slice(0, 200));
  assert.match(res.body.error, /only message your friends/);
});

test('the conversation route refuses anything that is not a uuid', async () => {
  // The value is interpolated into a PostgREST or() expression, where a crafted
  // one would rewrite the filter and return other people's conversations.
  const res = await api.get('/social/messages/x.eq.1,sender_uuid.neq.zzz', { token: meToken() });
  assert.equal(res.status, 400);
  assert.match(res.body.error, /Invalid UUID/);
});

test('unfriending and blocking', async () => {
  const removed = await api.post(`/social/friends/${STRANGER.uuid}/remove`, { token: meToken(), body: {} });
  assert.equal(removed.status, 200, removed.text.slice(0, 200));
  const afterRemove = await api.get('/social/friends', { token: meToken() });
  assert.equal(afterRemove.body.friends.length, 0);

  // Block, then they cannot ask again, and they are not told why.
  await api.post('/social/friend-requests', { token: meToken(), body: { target_uuid: STRANGER.uuid } });
  const blocked = await api.post(`/social/friends/${STRANGER.uuid}/remove`, { token: meToken(), body: { block: true } });
  assert.equal(blocked.status, 200, blocked.text.slice(0, 200));

  const theirAttempt = await api.post('/social/friend-requests', {
    token: api.token(STRANGER),
    body: { target_uuid: ME.uuid },
  });
  assert.equal(theirAttempt.status, 403);
  assert.doesNotMatch(theirAttempt.body.error, /block/i, 'blocking is invisible to the person blocked');

  const lifted = await api.post(`/social/friends/${STRANGER.uuid}/unblock`, { token: meToken() });
  assert.equal(lifted.status, 200, lifted.text.slice(0, 200));
});

test('Social can be switched off for players and stay open for staff', async () => {
  // The flag used to draw a "under maintenance" screen in the launcher while
  // every /social route stayed open to anything that spoke to the API directly.
  const OWNER = { uuid: '99999999999999999999999999999999', username: 'Owner', role: 'owner' };
  const off = await api.patch('/admin/feature-flags', { token: api.token(OWNER), body: { chat_disabled: true } });
  assert.equal(off.status, 200, off.text.slice(0, 200));

  const player = await api.get('/social/search?q=Firatic', { token: meToken() });
  assert.equal(player.status, 503, 'the endpoint is closed, not just the screen');
  const staff = await api.get('/social/search?q=Firatic', { token: api.token(OWNER) });
  assert.equal(staff.status, 200, 'staff can still finish the feature while it is dark');

  // Presence keeps working, so "last seen" stays honest while Social is off.
  const presence = await api.patch('/social/presence', { token: meToken(), body: {} });
  assert.equal(presence.status, 200);

  const on = await api.patch('/admin/feature-flags', { token: api.token(OWNER), body: { chat_disabled: false } });
  assert.equal(on.status, 200);
});
