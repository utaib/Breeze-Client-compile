'use strict';
/**
 * Announcements from the admin panel (2026-10-09).
 *
 * The launcher polls GET /notifications and shows an unread count on its
 * bell, so an announcement is one notification per account. These tests send
 * one as the owner and read it back as players, the way the launcher does.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');

const OWNER = { uuid: 'a1111111111111111111111111111111', username: 'Phantomxdz', role: 'owner' };
const ADMIN = { uuid: 'a2222222222222222222222222222222', username: 'T1Suub', role: 'admin' };
const PLAYER = { uuid: 'a3333333333333333333333333333333', username: 'Player', role: 'user' };
const OTHER = { uuid: 'a4444444444444444444444444444444', username: 'Other', role: 'user' };

const FRIEND_NOTE = {
  id: '11111111-1111-4111-8111-111111111111',
  user_uuid: PLAYER.uuid,
  type: 'friend_request',
  title: 'New friend request',
  body: null,
  data: { from: OTHER.uuid },
  read_at: null,
  created_at: '2026-01-01T00:00:00.000Z',
};

let api;
const as = (user) => api.token({ uuid: user.uuid, username: user.username, role: user.role });
const inbox = async (user) => (await api.get('/notifications', { token: as(user) })).body.notifications;

test.before(async () => {
  api = await startApi({
    name: 'announcements',
    seed: { users: [OWNER, ADMIN, PLAYER, OTHER], notifications: [FRIEND_NOTE] },
  });
});

test.after(async () => {
  if (api) await api.stop();
});

let sentId;

test('a player cannot send an announcement', async () => {
  const res = await api.post('/admin/announcements', { token: as(PLAYER), body: { title: 'Hi', body: 'x' } });
  assert.equal(res.status, 403);
});

test('an announcement needs a title and stays within the limits', async () => {
  const empty = await api.post('/admin/announcements', { token: as(OWNER), body: { title: '   ', body: 'x' } });
  assert.equal(empty.status, 400);
  const long = await api.post('/admin/announcements', { token: as(OWNER), body: { title: 'x'.repeat(121) } });
  assert.equal(long.status, 400);
});

test('the owner sends one announcement and every account receives it, naming the sender', async () => {
  const res = await api.post('/admin/announcements', {
    token: as(OWNER),
    body: { title: '  Maintenance  tonight ', body: 'The API restarts at 22:00 UTC.\nBack in five minutes.' },
  });
  assert.equal(res.status, 200, res.text);
  assert.equal(res.body.recipients, 4, 'every account, the sender included');
  assert.equal(res.body.type, 'Announcement from Phantomxdz (Owner)');
  sentId = res.body.id;

  for (const user of [OWNER, ADMIN, PLAYER, OTHER]) {
    const notes = (await inbox(user)).filter((n) => n.data?.announcement_id === sentId);
    assert.equal(notes.length, 1, `${user.username} has it once`);
    assert.equal(notes[0].type, 'Announcement from Phantomxdz (Owner)', 'the launcher prints the type above the title');
    assert.equal(notes[0].title, 'Maintenance tonight', 'spaces tidied');
    assert.equal(notes[0].body, 'The API restarts at 22:00 UTC.\nBack in five minutes.');
    assert.equal(notes[0].read_at, null, 'unread, so the bell counts it');
  }
});

test('a double click does not send it twice', async () => {
  const again = await api.post('/admin/announcements', {
    token: as(OWNER),
    body: { title: 'Maintenance tonight', body: 'The API restarts at 22:00 UTC.\nBack in five minutes.' },
  });
  assert.equal(again.status, 409);
  const notes = (await inbox(PLAYER)).filter((n) => n.data?.kind === 'announcement');
  assert.equal(notes.length, 1);
});

test('the history lists what was sent', async () => {
  const res = await api.get('/admin/announcements', { token: as(ADMIN) });
  assert.equal(res.status, 200, res.text);
  const row = res.body.announcements.find((a) => a.id === sentId);
  assert.ok(row, 'listed');
  assert.equal(row.recipients, 4);
  assert.equal(row.author_name, 'Phantomxdz');
  const refused = await api.get('/admin/announcements', { token: as(PLAYER) });
  assert.equal(refused.status, 403);
});

test('an admin can send too, under their own name', async () => {
  const res = await api.post('/admin/announcements', { token: as(ADMIN), body: { title: 'Event this weekend' } });
  assert.equal(res.status, 200, res.text);
  assert.equal(res.body.type, 'Announcement from T1Suub (Admin)');
  const note = (await inbox(OTHER)).find((n) => n.data?.announcement_id === res.body.id);
  assert.ok(note);
  assert.equal(note.body, null, 'a title alone is enough');
});

test('retracting removes it from every account and leaves other notifications alone', async () => {
  const res = await api.delete(`/admin/announcements/${sentId}`, { token: as(OWNER) });
  assert.equal(res.status, 200, res.text);
  assert.equal(res.body.removed, 4);
  for (const user of [OWNER, ADMIN, PLAYER, OTHER]) {
    const notes = (await inbox(user)).filter((n) => n.data?.announcement_id === sentId);
    assert.equal(notes.length, 0, `${user.username} no longer has it`);
  }
  const player = await inbox(PLAYER);
  assert.ok(player.some((n) => n.type === 'friend_request'), 'the friend request is still there');
  assert.ok(player.some((n) => n.type === 'Announcement from T1Suub (Admin)'), 'the other announcement is still there');

  const history = await api.get('/admin/announcements', { token: as(OWNER) });
  assert.ok(history.body.announcements.find((a) => a.id === sentId).retracted_at, 'marked retracted');
});

test('a player cannot retract, and a malformed id is refused', async () => {
  const refused = await api.delete(`/admin/announcements/${sentId}`, { token: as(PLAYER) });
  assert.equal(refused.status, 403);
  const bad = await api.delete('/admin/announcements/not-an-id', { token: as(OWNER) });
  assert.equal(bad.status, 400);
});
