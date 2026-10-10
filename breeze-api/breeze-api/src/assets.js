'use strict';

/**
 * API-local asset storage
 * ========================
 * Capes, cosmetics, previews, and any other large binaries are stored on
 * the API server's own disk under storage/ and served at /assets/..., * NOT in Supabase storage. Supabase stays relational-only; the API box
 * has the storage headroom and serving files from Express is one less
 * external dependency, one less bill, and zero egress limits.
 *
 * Layout:
 *   storage/
 *     capes/            marketplace cape textures + animation frames
 *     capes/pending/    paid personal capes awaiting webhook settlement
 *     capes/personal/   applied personal capes
 *     cosmetics/        cosmetic .glb models + thumbnails
 *     spotify/          spotify account links (managed by src/spotify.js)
 *
 * Existing rows that still point at Supabase-storage URLs keep working: * rows store absolute URLs, so old assets serve from Supabase until they
 * are re-uploaded, while everything new serves from the API.
 */

const fs = require('fs');
const path = require('path');

// BREEZE_STORAGE_DIR lets a test run (or a deployment that keeps data off the
// code volume) store assets elsewhere. Unset, it is the API's own storage/.
const STORAGE_ROOT = path.resolve(process.env.BREEZE_STORAGE_DIR || path.join(__dirname, '..', 'storage'));
const SUBDIRS = ['capes', path.join('capes', 'pending'), path.join('capes', 'personal'), 'cosmetics'];

function sanitizeRelPath(rel) {
    const normalized = path.posix.normalize(String(rel || '').replace(/\\/g, '/'));
    if (!normalized || normalized.startsWith('..') || path.isAbsolute(normalized)) return null;
    return normalized;
}

/** Write a buffer under storage/ and return its relative asset path. */
function storeAsset(relPath, buffer) {
    const safe = sanitizeRelPath(relPath);
    if (!safe) throw new Error(`Refusing unsafe asset path: ${relPath}`);
    const abs = path.join(STORAGE_ROOT, safe);
    fs.mkdirSync(path.dirname(abs), { recursive: true });
    fs.writeFileSync(abs, buffer);
    return safe;
}

function readAsset(relPath) {
    const safe = sanitizeRelPath(relPath);
    if (!safe) return null;
    const abs = path.join(STORAGE_ROOT, safe);
    return fs.existsSync(abs) ? fs.readFileSync(abs) : null;
}

function deleteAsset(relPath) {
    const safe = sanitizeRelPath(relPath);
    if (!safe) return;
    const abs = path.join(STORAGE_ROOT, safe);
    try { if (fs.existsSync(abs)) fs.unlinkSync(abs); } catch {}
}

function moveAsset(fromRel, toRel) {
    const from = sanitizeRelPath(fromRel);
    const to = sanitizeRelPath(toRel);
    if (!from || !to) return false;
    const absFrom = path.join(STORAGE_ROOT, from);
    const absTo = path.join(STORAGE_ROOT, to);
    if (!fs.existsSync(absFrom)) return false;
    fs.mkdirSync(path.dirname(absTo), { recursive: true });
    fs.renameSync(absFrom, absTo);
    return true;
}

/** Absolute public URL for an asset, based on the deployment's public base. */
function assetUrl(baseUrl, relPath) {
    const safe = sanitizeRelPath(relPath);
    return `${baseUrl}/assets/${safe.split('/').map(encodeURIComponent).join('/')}`;
}

module.exports = function registerAssets(app, ctx) {
    const express = require('express');
    for (const dir of SUBDIRS) fs.mkdirSync(path.join(STORAGE_ROOT, dir), { recursive: true });

    // Pending personal capes are private until paid, everything else is public.
    //
    // helmet() answers every response with Cross-Origin-Resource-Policy:
    // same-origin. On these files that made browsers refuse them as plain
    // images on any other origin, breezeclient.net included, so the website
    // store showed no capes (net::ERR_BLOCKED_BY_RESPONSE.NotSameOrigin).
    // They are public by design, so say so.
    app.use('/assets/capes/pending', (req, res) => res.status(404).end());
    app.use('/assets', express.static(STORAGE_ROOT, {
        maxAge: '7d',
        setHeaders(res) {
            res.setHeader('Access-Control-Allow-Origin', '*');
            res.setHeader('Cross-Origin-Resource-Policy', 'cross-origin');
        },
    }));

    ctx.assets = { storeAsset, readAsset, deleteAsset, moveAsset, assetUrl, STORAGE_ROOT };
    return ctx.assets;
};

module.exports.storeAsset = storeAsset;
module.exports.readAsset = readAsset;
module.exports.deleteAsset = deleteAsset;
module.exports.moveAsset = moveAsset;
module.exports.assetUrl = assetUrl;
