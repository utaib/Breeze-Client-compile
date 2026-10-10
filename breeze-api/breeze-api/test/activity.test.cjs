'use strict';
/**
 * Player activity for the admin panel (2026-10-10, src/activityStats.js):
 * who is online now, players per day, and accounts.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const os = require('os');
const fs = require('fs');
const path = require('path');
const { startApi } = require('./support/harness.cjs');
const { createActivity } = require('../src/activityStats');

const OWNER = { uuid: 'c1111111111111111111111111111111', username: 'Phantomxdz', role: 'owner', created_at: new Date().toISOString() };
const A = { uuid: 'c2222222222222222222222222222222', username: 'PlayerA', role: 'user', created_at: new Date().toISOString() };
const B = { uuid: 'c3333333333333333333333333333333', username: 'PlayerB', role: 'user', created_at: '2026-01-01T00:00:00.000Z' };

let api;
const as = (user) => api.token({ uuid: user.uuid, username: user.username, role: user.role });

test.before(async () => {
  api = await startApi({ name: 'activity', seed: { users: [OWNER, A, B] } });
});

test.after(async () => {
  if (api) await api.stop();
});

test('only the owner and admins see activity', async () => {
  assert.equal((await api.get('/admin/activity', { token: as(A) })).status, 403);
  assert.equal((await api.get('/admin/activity')).status, 401);
});

test('a player with the launcher open counts as online now and as active today', async () => {
  const beat = await api.patch('/social/presence', { token: as(A), body: {} });
  assert.equal(beat.status, 200, beat.text);
  await api.patch('/social/presence', { token: as(A), body: {} });
  const res = await api.get('/admin/activity?days=7', { token: as(OWNER) });
  assert.equal(res.status, 200, res.text);
  assert.equal(res.body.now.launcher, 1);
  assert.equal(res.body.now.total, 1);
  assert.equal(res.body.today.active, 1, 'two heartbeats are one player');
  assert.equal(res.body.daily.length, 7);
  const today = res.body.daily[res.body.daily.length - 1];
  assert.equal(today.active, 1);
  assert.equal(today.newAccounts, 2, 'two accounts were created today');
  assert.equal(res.body.accounts.total, 3);
});

test('days roll over: yesterday keeps its numbers, today starts again, and it survives a restart', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'breeze-activity-'));
  let t = Date.parse('2026-10-10T23:50:00Z');
  const a = createActivity({ dir, now: () => t });
  a.seen('c2222222-2222-2222-2222-222222222222', 'launcher');
  a.seen('c3333333333333333333333333333333', 'game');
  a.seen('c2222222222222222222222222222222', 'game');
  a.sample({ total: 2, launcher: 1, game: 2 });
  assert.deepEqual({ ...a.today(), peakAt: null }, { active: 2, launcher: 1, game: 2, peak: 2, peakAt: null });
  a.flush();

  t = Date.parse('2026-10-11T00:10:00Z');
  const b = createActivity({ dir, now: () => t });
  assert.equal(b.today().active, 0, 'a new day starts empty');
  const days = b.daily(2);
  assert.equal(days[0].date, '2026-10-10');
  assert.equal(days[0].active, 2);
  assert.equal(days[0].peak, 2);
  assert.equal(days[1].date, '2026-10-11');
  b.seen('not-a-uuid', 'launcher');
  assert.equal(b.today().active, 0, 'junk ids are ignored');
  fs.rmSync(dir, { recursive: true, force: true });
});
