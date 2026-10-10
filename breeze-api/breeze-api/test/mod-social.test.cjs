'use strict';
/**
 * In game and in the launcher, one friends list (1.0.23).
 *
 * The mod used to keep its own friends, requests and messages in flat files
 * whose only record of a player was an /announce sent while they were in a
 * world. A player who had an account but was not online was "unknown player",
 * and a friend added in the launcher did not exist in game. Both now read and
 * write the same friendships and messages tables.
 *
 * The mod's wire format is unchanged: text in, the same JSON out, dashed uuids.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const ME = { uuid: 'd1111111111111111111111111111111', username: 'InGame', role: 'user' };
const FRIEND = { uuid: 'd2222222222222222222222222222222', username: 'Offline_One', role: 'user' };
const dashed = (u) => `${u.slice(0, 8)}-${u.slice(8, 12)}-${u.slice(12, 16)}-${u.slice(16, 20)}-${u.slice(20)}`;

const seed = {
  users: [
    { ...ME, last_seen: new Date().toISOString() },
    // Never announced in game, and offline: the case that used to fail.
    { ...FRIEND, last_seen: '2026-01-01T00:00:00.000Z' },
  ],
  friendships: [],
  messages: [],
  notifications: [],
};

let api;
test.before(async () => {
  api = await startApi({ name: 'mod-social', seed });
});
test.after(async () => {
  if (api) await api.stop();
});

// The mod proves who it is with the same Breeze token, and sends plain text.
const modPost = (path, who, text) =>
  api.post(path, { token: api.token(who), body: text, headers: { 'Content-Type': 'text/plain' } });

test('a player who has never been seen in game can still be added from in game', async () => {
  const res = await modPost(`/friends/request/${dashed(ME.uuid)}`, ME, 'Offline_One');
  assert.equal(res.status, 200, res.text.slice(0, 200));
  assert.match(res.text, /request sent to Offline_One/);
});

test('the request is the same one the launcher shows', async () => {
  const launcher = await api.get('/social/friends', { token: api.token(FRIEND) });
  assert.equal(launcher.status, 200, launcher.text.slice(0, 200));
  assert.equal(launcher.body.requests.length, 1, 'the in-game request is in the launcher');
  assert.equal(launcher.body.requests[0].user.username, ME.username);
});

test('accepting in the launcher makes them friends in game', async () => {
  const launcher = await api.get('/social/friends', { token: api.token(FRIEND) });
  const id = launcher.body.requests[0].id;
  const accept = await api.post(`/social/friend-requests/${id}/accept`, { token: api.token(FRIEND) });
  assert.equal(accept.status, 200, accept.text.slice(0, 200));

  const inGame = await api.get(`/friends/list/${dashed(ME.uuid)}`, { token: api.token(ME) });
  assert.equal(inGame.status, 200, inGame.text.slice(0, 200));
  assert.equal(inGame.body.friends.length, 1);
  assert.equal(inGame.body.friends[0].name, FRIEND.username);
  assert.equal(inGame.body.friends[0].uuid, dashed(FRIEND.uuid), 'the mod is answered in dashed uuids');
});

test('a message sent in game is the same conversation the launcher shows', async () => {
  const sent = await modPost(`/dm/${dashed(ME.uuid)}`, ME, `${dashed(FRIEND.uuid)} see you on the server`);
  assert.equal(sent.status, 200, sent.text.slice(0, 200));

  const launcher = await api.get(`/social/messages/${FRIEND.uuid}`, { token: api.token(ME) });
  assert.equal(launcher.body.messages.length, 1);
  assert.equal(launcher.body.messages[0].body, 'see you on the server');

  const inGame = await api.get(`/dm/${dashed(FRIEND.uuid)}`, { token: api.token(FRIEND) });
  assert.equal(inGame.status, 200);
  assert.equal(inGame.body.length, 1);
  assert.equal(inGame.body[0].fromName, ME.username);
  assert.equal(inGame.body[0].from, dashed(ME.uuid));
});

test('in game you still cannot message someone who is not your friend', async () => {
  const STRANGER = { uuid: 'd3333333333333333333333333333333', username: 'Stranger', role: 'user' };
  const res = await modPost(`/dm/${dashed(STRANGER.uuid)}`, STRANGER, `${dashed(ME.uuid)} hello`);
  assert.equal(res.status, 403, res.text.slice(0, 200));
  assert.match(res.text, /not friends/);
});

test('removing in game removes the same friendship', async () => {
  const removed = await modPost(`/friends/remove/${dashed(ME.uuid)}`, ME, dashed(FRIEND.uuid));
  assert.equal(removed.status, 200, removed.text.slice(0, 200));
  const launcher = await api.get('/social/friends', { token: api.token(ME) });
  assert.equal(launcher.body.friends.length, 0, 'gone from the launcher too');
});
