'use strict';
/**
 * Measure cosmetics uploaded before 1.0.22 (docs/COSMETICS.md).
 *
 * Older cosmetics were stored as uploaded, with no measured metadata: no
 * attachment, animation roles, bounds or stats, so the launcher has to guess
 * and a creator cannot edit their roles. Their files are usually complete
 * (a .glb with textures inside, or a .gltf with everything in data: URIs), so
 * the server can measure them where they are instead of asking for a re-upload.
 *
 * This decides what to do for one cosmetic; scripts/backfill-cosmetics.cjs runs
 * it over the database. Nothing here touches storage or the database itself:
 * the caller passes readAsset, and applies the returned plan.
 */

const path = require('path');
const { prepareCosmeticModel, CosmeticAssetError } = require('./cosmeticAsset');

/** The storage path of a model served from this API's /assets, or null. */
function storedPath(modelUrl) {
    const at = String(modelUrl || '').indexOf('/assets/');
    if (at < 0) return null;
    try {
        return decodeURIComponent(String(modelUrl).slice(at + '/assets/'.length).split('?')[0]);
    } catch {
        return null;
    }
}

/**
 * What to do for one cosmetic row.
 *
 * Returns { status: 'skipped' | 'failed', reason } or
 * { status: 'measured', files: [{ rel, bytes }], update, reason }, where files
 * are to be written (the measured GLB, and a copy of an original .glb it
 * replaces) and update is the row patch.
 */
async function planBackfill(row, { readAsset }) {
    const meta = row && row.metadata && typeof row.metadata === 'object' ? row.metadata : {};
    if (meta.spec >= 1) return { status: 'skipped', reason: 'already measured' };
    if (!row.model_url) return { status: 'skipped', reason: 'no model' };
    const rel = storedPath(row.model_url);
    if (!rel || !/\.(glb|gltf)$/i.test(rel)) return { status: 'failed', reason: 'the model is not stored on this API' };
    const bytes = readAsset(rel);
    if (!bytes) return { status: 'failed', reason: `the stored file is missing (${rel})` };

    const file = { originalname: path.posix.basename(rel), buffer: bytes };
    const measure = (animationRoles) => prepareCosmeticModel([file], { slot: row.slot, animationRoles });
    let prepared;
    try {
        // The old idle_animation column was typed by hand; keep it when it
        // names a real clip, and otherwise let the names decide.
        try {
            prepared = await measure(row.idle_animation ? { idle: row.idle_animation } : undefined);
        } catch (e) {
            if (!(e instanceof CosmeticAssetError) || !row.idle_animation || !/no animation called/.test(e.message)) throw e;
            prepared = await measure(undefined);
        }
    } catch (e) {
        if (e instanceof CosmeticAssetError) return { status: 'failed', reason: e.message };
        throw e;
    }

    const glbRel = rel.replace(/\.gltf$/i, '.glb');
    const files = [];
    // A .glb is replaced in place, so keep what was there. Only once: a second
    // run would otherwise save the measured file as if it were the original,
    // and the creator's upload would be gone.
    if (/\.glb$/i.test(rel)) {
        const originalRel = rel.replace(/\.glb$/i, '.original.glb');
        if (!readAsset(originalRel)) files.push({ rel: originalRel, bytes: Buffer.from(bytes) });
    }
    files.push({ rel: glbRel, bytes: prepared.glb });

    const roles = prepared.metadata.animations.roles;
    // A hand-typed random_animations list is the creator's choice. Keep it when
    // every entry still names a clip in the model; replace it only when it has
    // gone stale, in which case the discovered extras are better than nothing.
    const clips = prepared.metadata.animations.clips.map((c) => (typeof c === 'string' ? c : c.name));
    const chosen = Array.isArray(row.random_animations) ? row.random_animations.filter(Boolean) : [];
    const keepChosen = chosen.length > 0 && chosen.every((name) => clips.includes(name));
    const update = {
        metadata: { ...meta, ...prepared.metadata },
        idle_animation: roles.idle || null,
        random_animations: keepChosen ? chosen : prepared.metadata.animations.extras,
    };
    if (glbRel !== rel) update.model_url = String(row.model_url).replace(/\.gltf(\?|$)/i, '.glb$1');
    return {
        status: 'measured',
        files,
        update,
        reason: `${prepared.metadata.stats.triangles} triangles, ${prepared.metadata.animations.clips.length} clips, ${prepared.metadata.attachment}`,
    };
}

module.exports = { planBackfill, storedPath };
