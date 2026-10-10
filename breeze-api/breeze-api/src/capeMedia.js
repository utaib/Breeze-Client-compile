'use strict';
/* ============================================================
   capeMedia.js, server-side animated-image handling for capes
   ------------------------------------------------------------
   Creators upload a single GIF or animated PNG; this splits it into
   ordered frame buffers using sharp (which natively decodes animated
   GIF + APNG) and derives the frame rate from the source delays.
   No client-side canvas scraping, no manual frame preparation.
   ============================================================ */
const sharp = require('sharp');

const MAX_FRAMES = 60;

/**
 * Inspect an image buffer and, if it is animated, split it into per-frame PNG
 * buffers. Returns { animated, frames: Buffer[], fps }.
 *   - Static image  → { animated:false, frames:[png], fps:0 }
 *   - Animated GIF/APNG → { animated:true,  frames:[png,…], fps:<derived> }
 * Never throws for a normal image; falls back to treating input as static.
 */
async function splitAnimation(buffer) {
    let meta = null;
    try {
        meta = await sharp(buffer, { animated: true }).metadata();
    } catch {
        meta = null;
    }
    const pages = meta && Number.isFinite(meta.pages) ? meta.pages : 1;

    // Not animated (or undecodable as animated) → one static PNG frame.
    if (!meta || pages <= 1) {
        const png = await sharp(buffer).png().toBuffer();
        return { animated: false, frames: [png], fps: 0 };
    }

    const count = Math.min(pages, MAX_FRAMES);
    const frames = [];
    for (let i = 0; i < count; i++) {
        // Extract a single page/frame and flatten to a standalone PNG.
        const frame = await sharp(buffer, { page: i, pages: 1 }).png().toBuffer();
        frames.push(frame);
    }

    // Derive fps from the source's per-frame delays (ms). GIFs commonly use
    // 100ms → 10fps. Clamp to a sane 1-30fps range.
    const delays = Array.isArray(meta.delay) ? meta.delay.filter((d) => d > 0) : [];
    const avgDelay = delays.length ? delays.reduce((a, b) => a + b, 0) / delays.length : 100;
    const fps = Math.min(30, Math.max(1, Math.round(1000 / avgDelay)));

    return { animated: true, frames, fps };
}

/** True if the uploaded mimetype/extension suggests an animated container. */
function looksAnimated(mimetype, filename) {
    const m = (mimetype || '').toLowerCase();
    const f = (filename || '').toLowerCase();
    return m.includes('gif') || m.includes('apng') || f.endsWith('.gif') || f.endsWith('.apng');
}

module.exports = { splitAnimation, looksAnimated, MAX_FRAMES };
