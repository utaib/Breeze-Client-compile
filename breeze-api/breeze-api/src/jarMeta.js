'use strict';
/**
 * Read a Fabric mod jar's own declaration of what it supports.
 *
 * The launcher has been shipping jars whose fabric.mod.json says
 * "minecraft": ">=1.20 <1.20.2" while the file is named 1.20.4.jar. Fabric
 * refuses to load those, so the player launches with no Breeze and nothing
 * explains why. Rather than trusting a filename or a hand-kept table, the API
 * reads the jar and reports what the jar itself claims.
 *
 * Jars are zip files and fabric.mod.json is a few hundred bytes, so a minimal
 * central-directory reader is enough and avoids adding a dependency for it.
 */

const fs = require('fs');
const zlib = require('zlib');

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_SIGNATURE = 0x02014b50;
const MAX_COMMENT = 0xffff;

/** Locate the end-of-central-directory record, which sits at the end of the file. */
function findEndOfCentralDirectory(buffer) {
    const earliest = Math.max(0, buffer.length - (MAX_COMMENT + 22));
    for (let i = buffer.length - 22; i >= earliest; i--) {
        if (buffer.readUInt32LE(i) === EOCD_SIGNATURE) return i;
    }
    return -1;
}

/** Read one named entry out of a zip buffer, or null when it is not there. */
function readZipEntry(buffer, wantedName) {
    const eocd = findEndOfCentralDirectory(buffer);
    if (eocd < 0) return null;
    const entryCount = buffer.readUInt16LE(eocd + 10);
    let offset = buffer.readUInt32LE(eocd + 16);

    for (let i = 0; i < entryCount; i++) {
        if (offset + 46 > buffer.length) return null;
        if (buffer.readUInt32LE(offset) !== CENTRAL_SIGNATURE) return null;
        const method = buffer.readUInt16LE(offset + 10);
        const compressedSize = buffer.readUInt32LE(offset + 20);
        const nameLength = buffer.readUInt16LE(offset + 28);
        const extraLength = buffer.readUInt16LE(offset + 30);
        const commentLength = buffer.readUInt16LE(offset + 32);
        const localOffset = buffer.readUInt32LE(offset + 42);
        const name = buffer.toString('utf8', offset + 46, offset + 46 + nameLength);

        if (name === wantedName) {
            // The local header repeats the name and extra fields, and its extra
            // length can differ from the central one, so read it from there.
            const localNameLength = buffer.readUInt16LE(localOffset + 26);
            const localExtraLength = buffer.readUInt16LE(localOffset + 28);
            const dataStart = localOffset + 30 + localNameLength + localExtraLength;
            const raw = buffer.subarray(dataStart, dataStart + compressedSize);
            if (method === 0) return raw;
            if (method === 8) return zlib.inflateRawSync(raw);
            return null; // some other compression method; not worth supporting
        }
        offset += 46 + nameLength + extraLength + commentLength;
    }
    return null;
}

const metaCache = new Map();

/**
 * Metadata for a Breeze mod jar: which Minecraft versions and which Fabric
 * loader it declares. Cached on (path, size, mtime).
 */
function readModJarMetadata(absolutePath) {
    let stat;
    try {
        stat = fs.statSync(absolutePath);
    } catch {
        return null;
    }
    const key = `${absolutePath}:${stat.size}:${stat.mtimeMs}`;
    if (metaCache.has(key)) return metaCache.get(key);

    let meta = null;
    try {
        const entry = readZipEntry(fs.readFileSync(absolutePath), 'fabric.mod.json');
        if (entry) {
            const parsed = JSON.parse(entry.toString('utf8').replace(/^﻿/, ''));
            const depends = parsed.depends || {};
            meta = {
                modVersion: parsed.version || null,
                minecraft: depends.minecraft ?? null,
                fabricLoader: depends.fabricloader ?? null,
                fabricApi: depends['fabric-api'] ?? null,
                entrypoints: Object.keys(parsed.entrypoints || {}),
            };
        }
    } catch {
        meta = null;
    }

    if (metaCache.size >= 64) metaCache.delete(metaCache.keys().next().value);
    metaCache.set(key, meta);
    return meta;
}

/** Split "1.20.4" into comparable numbers. Non-numeric tails sort lowest. */
function parseVersion(value) {
    const cleaned = String(value || '').trim();
    const core = (cleaned.match(/^\d+(?:\.\d+)*/) || [''])[0];
    if (!core) return null;
    return core.split('.').map((part) => parseInt(part, 10));
}

function compareVersions(a, b) {
    const left = parseVersion(a);
    const right = parseVersion(b);
    if (!left || !right) return null;
    const length = Math.max(left.length, right.length);
    for (let i = 0; i < length; i++) {
        const l = left[i] ?? 0;
        const r = right[i] ?? 0;
        if (l !== r) return l < r ? -1 : 1;
    }
    return 0;
}

/**
 * Does `version` satisfy a Fabric dependency range?
 *
 * Supports the forms Breeze actually uses: "*", "1.20.1", ">=1.20 <1.20.2",
 * "~1.20.3", "^1.21", and "a || b" alternatives. Anything else returns null,
 * meaning "unknown", because a confident wrong answer here would tell a player
 * a build works when it cannot load.
 */
function versionSatisfies(version, range) {
    if (range === '*' || range === null || range === undefined) return true;
    const target = parseVersion(version);
    if (!target) return null;

    for (const alternative of String(range).split('||')) {
        const clauses = alternative.trim().split(/\s+/).filter(Boolean);
        if (!clauses.length) continue;
        let all = true;
        for (const clause of clauses) {
            const result = satisfiesClause(version, clause);
            if (result === null) return null;
            if (!result) {
                all = false;
                break;
            }
        }
        if (all) return true;
    }
    return false;
}

function satisfiesClause(version, clause) {
    const match = clause.match(/^(>=|<=|>|<|=|\^|~)?\s*(.+)$/);
    if (!match) return null;
    const [, operator = '=', bound] = match;
    if (!parseVersion(bound)) return null;
    const cmp = compareVersions(version, bound);
    if (cmp === null) return null;

    switch (operator) {
        case '>=':
            return cmp >= 0;
        case '>':
            return cmp > 0;
        case '<=':
            return cmp <= 0;
        case '<':
            return cmp < 0;
        case '=':
            return cmp === 0;
        case '~': {
            // ~1.20.3 allows 1.20.x at or above 1.20.3
            const parts = parseVersion(bound);
            if (cmp < 0) return false;
            const upper = [...parts];
            if (upper.length >= 2) {
                upper[1] += 1;
                upper.length = 2;
            } else {
                upper[0] += 1;
            }
            return compareVersions(version, upper.join('.')) < 0;
        }
        case '^': {
            // ^1.21 allows 1.x at or above 1.21
            const parts = parseVersion(bound);
            if (cmp < 0) return false;
            const upper = [parts[0] + 1];
            return compareVersions(version, upper.join('.')) < 0;
        }
        default:
            return null;
    }
}

module.exports = { readModJarMetadata, versionSatisfies, compareVersions, readZipEntry };
