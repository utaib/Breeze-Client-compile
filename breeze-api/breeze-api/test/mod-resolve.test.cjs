'use strict';
/**
 * Which Breeze jar a Minecraft version gets.
 *
 * Breeze 2.x ships one jar per Minecraft version (1.20.jar, 1.20.1.jar, ...),
 * each declaring only its own version. The family table sent 1.20 to
 * 1.20.1.jar, 1.21.9 and 1.21.10 to 1.21.11.jar and 26.1 to 26.1.2.jar, so
 * once those jars were published the runtime route would have refused all of
 * them (409): the jar it picked declares another version. A jar named after
 * the exact version now comes first; the family row stays the fallback, so
 * today's family jars are served exactly as before.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const { startApi } = require('./support/harness.cjs');

const PLAYER = { uuid: 'e3333333333333333333333333333333', username: 'Player', role: 'user' };

/** A minimal stored zip holding one fabric.mod.json, as Fabric reads it. */
function jarDeclaring(minecraft) {
  const nameBuf = Buffer.from('fabric.mod.json', 'utf8');
  const raw = Buffer.from(
    JSON.stringify({
      schemaVersion: 1,
      id: 'breeze',
      version: '2.9.2',
      depends: { minecraft, fabricloader: '>=0.15.0', 'fabric-api': '*' },
    }),
    'utf8',
  );
  const crc = zlib.crc32 ? zlib.crc32(raw) : 0;

  const local = Buffer.alloc(30);
  local.writeUInt32LE(0x04034b50, 0);
  local.writeUInt16LE(20, 4);
  local.writeUInt32LE(crc, 14);
  local.writeUInt32LE(raw.length, 18);
  local.writeUInt32LE(raw.length, 22);
  local.writeUInt16LE(nameBuf.length, 26);
  const localRecord = Buffer.concat([local, nameBuf, raw]);

  const central = Buffer.alloc(46);
  central.writeUInt32LE(0x02014b50, 0);
  central.writeUInt16LE(20, 4);
  central.writeUInt16LE(20, 6);
  central.writeUInt32LE(crc, 16);
  central.writeUInt32LE(raw.length, 20);
  central.writeUInt32LE(raw.length, 24);
  central.writeUInt16LE(nameBuf.length, 28);
  const centralRecord = Buffer.concat([central, nameBuf]);

  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(1, 8);
  eocd.writeUInt16LE(1, 10);
  eocd.writeUInt32LE(centralRecord.length, 12);
  eocd.writeUInt32LE(localRecord.length, 16);
  return Buffer.concat([localRecord, centralRecord, eocd]);
}

let api;
let modDir;

function publish(file, minecraft) {
  fs.writeFileSync(path.join(modDir, file), jarDeclaring(minecraft));
}

test.before(async () => {
  api = await startApi({ name: 'mod-resolve', seed: { users: [PLAYER] } });
  modDir = path.join(api.dataDir, 'versions', 'mods');
  fs.mkdirSync(modDir, { recursive: true });
  // A family jar as published today: one file for 1.21.9 to 1.21.11.
  publish('1.21.11.jar', '>=1.21.9 <=1.21.11');
  // Breeze 2.x: one jar per version, each declaring only itself.
  publish('1.20.jar', '1.20');
  publish('1.20.1.jar', '1.20.1');
  publish('26.1.jar', '26.1');
});

test.after(async () => {
  if (api) await api.stop();
});

test('a version with a jar of its own gets that jar, not its family jar', async () => {
  const res = await api.get('/versions/mod/resolve?mc=1.20');
  assert.equal(res.status, 200, res.text.slice(0, 200));
  assert.equal(res.body.file, '1.20.jar');
  assert.equal(res.body.supportStatus, 'supported');

  const newest = await api.get('/versions/mod/resolve?mc=26.1');
  assert.equal(newest.body.file, '26.1.jar');
  assert.equal(newest.body.supportStatus, 'supported');
});

test('the family jar still serves the versions that have none of their own', async () => {
  const res = await api.get('/versions/mod/resolve?mc=1.21.10');
  assert.equal(res.body.file, '1.21.11.jar');
  assert.equal(res.body.supportStatus, 'supported');

  const sibling = await api.get('/versions/mod/resolve?mc=26.1.1');
  assert.equal(sibling.body.file, '26.1.2.jar', 'the 26.1.x row, unchanged');
  assert.equal(sibling.body.available, false);
});

test('the runtime download hands over the exact jar', async () => {
  const token = api.token(PLAYER);
  const exact = await api.get('/mod/runtime/1.20', { token });
  assert.equal(exact.status, 200, exact.text.slice(0, 200));
  assert.equal(exact.headers.get('x-breeze-mod-file'), '1.20.jar');

  const family = await api.get('/mod/runtime/1.21.9', { token });
  assert.equal(family.status, 200, family.text.slice(0, 200));
  assert.equal(family.headers.get('x-breeze-mod-file'), '1.21.11.jar');
});

test('a name that is not a plain version never reaches the exact-file lookup', async () => {
  const token = api.token(PLAYER);
  const res = await api.get('/mod/runtime/..%2F1.20', { token });
  assert.notEqual(res.status, 200);
});
