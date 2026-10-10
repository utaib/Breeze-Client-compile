'use strict';
/**
 * Tests for reading a Fabric jar's own compatibility declaration.
 *
 * The bug these guard against is real: jars named 1.20.2.jar and 1.20.4.jar
 * shipped declaring "minecraft": ">=1.20 <1.20.2", so Fabric refused to load
 * them and players got no Breeze and no explanation.
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const { readModJarMetadata, versionSatisfies, compareVersions } = require('../src/jarMeta.js');

/** Build a minimal zip containing one file, stored or deflated. */
function makeZip(name, contents, { deflate = false } = {}) {
    const nameBuf = Buffer.from(name, 'utf8');
    const raw = Buffer.from(contents, 'utf8');
    const data = deflate ? zlib.deflateRawSync(raw) : raw;
    const method = deflate ? 8 : 0;
    const crc = zlib.crc32 ? zlib.crc32(raw) : 0;

    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(method, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    local.writeUInt16LE(0, 28);

    const localRecord = Buffer.concat([local, nameBuf, data]);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt16LE(0, 8);
    central.writeUInt16LE(method, 10);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(raw.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(0, 42);
    const centralRecord = Buffer.concat([central, nameBuf]);

    const eocd = Buffer.alloc(22);
    eocd.writeUInt32LE(0x06054b50, 0);
    eocd.writeUInt16LE(1, 8);
    eocd.writeUInt16LE(1, 10);
    eocd.writeUInt32LE(centralRecord.length, 12);
    eocd.writeUInt32LE(localRecord.length, 16);

    return Buffer.concat([localRecord, centralRecord, eocd]);
}

function writeJar(contents, options) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'breeze-jarmeta-'));
    const file = path.join(dir, 'test.jar');
    fs.writeFileSync(file, makeZip('fabric.mod.json', JSON.stringify(contents), options));
    return file;
}

test('reads the declaration out of a stored jar entry', () => {
    const jar = writeJar({
        version: '1.0.0',
        depends: { minecraft: '>=1.20 <1.20.2', fabricloader: '>=0.15.0', 'fabric-api': '*' },
        entrypoints: { client: ['dev.breeze.BreezeClient'] },
    });
    const meta = readModJarMetadata(jar);
    assert.equal(meta.modVersion, '1.0.0');
    assert.equal(meta.minecraft, '>=1.20 <1.20.2');
    assert.equal(meta.fabricLoader, '>=0.15.0');
    assert.deepEqual(meta.entrypoints, ['client']);
});

test('reads a deflated jar entry too', () => {
    const jar = writeJar(
        { version: '2.0.0', depends: { minecraft: '~1.21.4', fabricloader: '>=0.18.4' } },
        { deflate: true },
    );
    const meta = readModJarMetadata(jar);
    assert.equal(meta.modVersion, '2.0.0');
    assert.equal(meta.minecraft, '~1.21.4');
});

test('a jar with no fabric.mod.json reports nothing rather than guessing', () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'breeze-jarmeta-'));
    const file = path.join(dir, 'empty.jar');
    fs.writeFileSync(file, makeZip('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'));
    assert.equal(readModJarMetadata(file), null);
});

test('the shipped mismatch is caught: a 1.20.4 request against a <1.20.2 jar', () => {
    assert.equal(versionSatisfies('1.20.4', '>=1.20 <1.20.2'), false);
    assert.equal(versionSatisfies('1.20.1', '>=1.20 <1.20.2'), true);
    assert.equal(versionSatisfies('1.20', '>=1.20 <1.20.2'), true);
});

test('range forms Breeze actually publishes', () => {
    assert.equal(versionSatisfies('1.20.3', '~1.20.3'), true);
    assert.equal(versionSatisfies('1.20.9', '~1.20.3'), true);
    assert.equal(versionSatisfies('1.21.0', '~1.20.3'), false);
    assert.equal(versionSatisfies('1.21.11', '>=1.21.9 <1.22'), true);
    assert.equal(versionSatisfies('1.21.8', '>=1.21.9 <1.22'), false);
    assert.equal(versionSatisfies('26.1.20', '>=26.1 <26.2'), true);
    assert.equal(versionSatisfies('26.2', '>=26.1 <26.2'), false);
    assert.equal(versionSatisfies('1.20.1', '*'), true);
    assert.equal(versionSatisfies('1.21.4', '1.21.4 || 1.21.5'), true);
});

test('an unparseable range is unknown, never a confident yes or no', () => {
    assert.equal(versionSatisfies('1.20.1', 'something-odd'), null);
    assert.equal(compareVersions('not-a-version', '1.0.0'), null);
});

test('version comparison handles differing segment counts', () => {
    assert.equal(compareVersions('1.20', '1.20.0'), 0);
    assert.equal(compareVersions('1.20.1', '1.20'), 1);
    assert.equal(compareVersions('1.9.0', '1.10.0'), -1);
});
