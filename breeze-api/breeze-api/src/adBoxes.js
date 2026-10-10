'use strict';
/**
 * Ad boxes: places in the launcher and on the website where the owner can put
 * an image of their own, with AdSense as the default.
 *
 *   GET    /ads/boxes                 public: what every box shows
 *   GET    /admin/ads                 owner/admin: the boxes and their settings
 *   POST   /admin/ads/:box            owner/admin: multipart, all optional:
 *                                       image (PNG, JPEG, GIF or WebP, 2 MB),
 *                                       mode ("image" or "adsense"),
 *                                       link (https), alt, adsense_slot (digits)
 *   DELETE /admin/ads/:box/image      owner/admin: drop the image, back to AdSense
 *
 * A box in "image" mode shows the uploaded image (linking to `link` if set).
 * In "adsense" mode the website shows the AdSense unit `adsense_slot`, and
 * the launcher shows nothing: AdSense does not allow its ads in desktop apps.
 *
 * Nothing here needs a database table: the settings are one JSON file and the
 * images sit next to it in the API's own storage (src/assets.js), served from
 * /assets/ads/.
 */
const sharp = require('sharp');

const BOXES = [
    { id: 'launcher-play', label: 'Launcher: Play page, beside Quick Actions', where: 'launcher', size: '300 x 250' },
    { id: 'launcher-store', label: 'Launcher: top of the Store', where: 'launcher', size: '728 x 90' },
    { id: 'site-home', label: 'Website: home page', where: 'website', size: '970 x 250' },
    { id: 'site-docs', label: 'Website: docs pages', where: 'website', size: '728 x 90' },
];
const CONFIG = 'ads/boxes.json';
const TYPES = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/jpg': 'jpg', 'image/gif': 'gif', 'image/webp': 'webp' };
const MAX_WIDTH = 1940; // twice the widest box, for sharp screens

function cleanLink(value) {
    const text = String(value ?? '').trim();
    if (!text) return '';
    let url;
    try {
        url = new URL(text);
    } catch {
        return null;
    }
    if (url.protocol !== 'https:' || text.length > 500) return null;
    return url.toString();
}

function cleanText(value, max) {
    return String(value ?? '').replace(/[\u0000-\u001F\u007F]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
}

module.exports = function registerAdBoxes(app, ctx) {
    const { requireAuth, requireRole, ROLES, ok, fail, log, upload, getRequestBaseUrl } = ctx;
    const assets = ctx.assets;
    const adminOnly = [requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER)];

    function readConfig() {
        try {
            const buf = assets.readAsset(CONFIG);
            const parsed = buf ? JSON.parse(buf.toString('utf8')) : {};
            return parsed && typeof parsed === 'object' ? parsed : {};
        } catch {
            return {};
        }
    }

    function writeConfig(config) {
        assets.storeAsset(CONFIG, Buffer.from(JSON.stringify(config, null, 2)));
    }

    /** One box as the launcher and the website read it. */
    function shape(box, saved, base) {
        const s = saved || {};
        const imageUrl = s.image ? assets.assetUrl(base, s.image) : null;
        const mode = s.mode === 'image' && imageUrl ? 'image' : 'adsense';
        return {
            id: box.id,
            label: box.label,
            where: box.where,
            size: box.size,
            mode,
            image_url: imageUrl,
            link: s.link || null,
            alt: s.alt || '',
            adsense_slot: s.adsense_slot || null,
            updated_at: s.updated_at || null,
        };
    }

    app.get('/ads/boxes', (req, res) => {
        const config = readConfig();
        const base = getRequestBaseUrl(req);
        const boxes = {};
        for (const box of BOXES) {
            const { label, where, size, updated_at, ...pub } = shape(box, config[box.id], base);
            boxes[box.id] = pub;
        }
        res.setHeader('Cache-Control', 'public, max-age=60');
        return ok(res, { boxes });
    });

    app.get('/admin/ads', ...adminOnly, (req, res) => {
        const config = readConfig();
        const base = getRequestBaseUrl(req);
        return ok(res, { boxes: BOXES.map((box) => shape(box, config[box.id], base)) });
    });

    app.post('/admin/ads/:box', ...adminOnly, upload.single('image'), async (req, res) => {
        const CTX = 'Ads/Save';
        const box = BOXES.find((b) => b.id === req.params.box);
        if (!box) return fail(res, 'There is no ad box with that name', 404);
        const config = readConfig();
        const saved = { ...(config[box.id] || {}) };
        const body = req.body || {};

        if (body.link !== undefined) {
            const link = cleanLink(body.link);
            if (link === null) return fail(res, 'The link must be a full https:// address');
            saved.link = link;
        }
        if (body.alt !== undefined) saved.alt = cleanText(body.alt, 120);
        if (body.adsense_slot !== undefined) {
            const slot = String(body.adsense_slot).trim();
            if (slot && !/^\d{1,20}$/.test(slot)) return fail(res, 'An AdSense unit id is digits only');
            saved.adsense_slot = slot;
        }

        if (req.file) {
            const ext = TYPES[req.file.mimetype];
            if (!ext) return fail(res, 'Use a PNG, JPEG, GIF or WebP image');
            let bytes;
            try {
                const meta = await sharp(req.file.buffer, { animated: true }).metadata();
                if (!meta.width || !meta.height) throw new Error('no size');
                // Animated images are kept as they are (re-encoding would drop
                // frames or bloat them); still ones are scaled down if huge.
                bytes = (meta.pages || 1) > 1 || ext === 'gif'
                    ? req.file.buffer
                    : await sharp(req.file.buffer).resize({ width: MAX_WIDTH, withoutEnlargement: true }).toBuffer();
            } catch (err) {
                return fail(res, 'That file is not an image the server can read');
            }
            const rel = `ads/${box.id}-${Date.now()}.${ext}`;
            try {
                assets.storeAsset(rel, bytes);
            } catch (err) {
                log.error(CTX, 'Storage error', { msg: err.message });
                return fail(res, 'Could not store the image', 500);
            }
            if (saved.image && saved.image !== rel) {
                try { assets.deleteAsset(saved.image); } catch { /* an old file left behind is harmless */ }
            }
            saved.image = rel;
            saved.mode = 'image';
        }

        if (body.mode !== undefined) {
            if (body.mode !== 'image' && body.mode !== 'adsense') return fail(res, 'Mode is image or adsense');
            if (body.mode === 'image' && !saved.image) return fail(res, 'Upload an image for this box first');
            saved.mode = body.mode;
        }
        saved.updated_at = new Date().toISOString();
        config[box.id] = saved;
        try {
            writeConfig(config);
        } catch (err) {
            log.error(CTX, 'Could not save the ad boxes', { msg: err.message });
            return fail(res, 'Could not save the ad box', 500);
        }
        log.info(CTX, `${box.id} now ${saved.mode || 'adsense'} (by ${req.user.uuid})`);
        return ok(res, { box: shape(box, saved, getRequestBaseUrl(req)) });
    });

    app.delete('/admin/ads/:box/image', ...adminOnly, (req, res) => {
        const box = BOXES.find((b) => b.id === req.params.box);
        if (!box) return fail(res, 'There is no ad box with that name', 404);
        const config = readConfig();
        const saved = { ...(config[box.id] || {}) };
        if (saved.image) {
            try { assets.deleteAsset(saved.image); } catch { /* already gone */ }
        }
        delete saved.image;
        saved.mode = 'adsense';
        saved.updated_at = new Date().toISOString();
        config[box.id] = saved;
        try {
            writeConfig(config);
        } catch (err) {
            return fail(res, 'Could not save the ad box', 500);
        }
        return ok(res, { box: shape(box, saved, getRequestBaseUrl(req)) });
    });
};

module.exports.BOXES = BOXES;
