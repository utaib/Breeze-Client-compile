'use strict';
/* ============================================================
   audius.js, Breeze FM music via Audius
   ------------------------------------------------------------
   Audius has a free, open, official API: no API key, no OAuth,
   no "premium" requirement (unlike Spotify). We discover a
   content node, proxy search/trending, and hand the launcher a
   direct stream URL it can play in an <audio> element.
   Docs: https://docs.audius.org/developers/api/
   ============================================================ */
const axios = require('axios');

const APP_NAME = 'BreezeClient';
const HOST_TTL_MS = 30 * 60 * 1000;
let cachedHost = null;
let hostFetchedAt = 0;

// Discover a healthy Audius content node (the API is decentralised).
async function getHost() {
    if (cachedHost && Date.now() - hostFetchedAt < HOST_TTL_MS) return cachedHost;
    const r = await axios.get('https://api.audius.co', { timeout: 8000 });
    const hosts = (r.data && r.data.data) || [];
    if (!hosts.length) throw new Error('No Audius hosts available');
    // A middle host tends to be less overloaded than the first.
    cachedHost = hosts[Math.floor(hosts.length / 2)];
    hostFetchedAt = Date.now();
    return cachedHost;
}

function shapeTrack(t, host) {
    return {
        id: t.id,
        title: t.title,
        artist: (t.user && (t.user.name || t.user.handle)) || 'Unknown artist',
        artwork: (t.artwork && (t.artwork['480x480'] || t.artwork['150x150'])) || null,
        duration: t.duration || 0,
        // Direct, playable stream URL: no auth, follows a redirect to audio.
        streamUrl: `${host}/v1/tracks/${encodeURIComponent(t.id)}/stream?app_name=${APP_NAME}`,
    };
}

module.exports = function registerAudius(app, ctx) {
    const { ok, fail, log } = ctx;

    app.get('/audius/search', async (req, res) => {
        try {
            const q = String(req.query.q || '').trim();
            if (!q) return fail(res, 'A search query is required');
            const host = await getHost();
            const r = await axios.get(`${host}/v1/tracks/search`, {
                params: { query: q, app_name: APP_NAME, limit: 30 },
                timeout: 10000,
            });
            const tracks = (r.data && r.data.data ? r.data.data : [])
                .filter((t) => t && t.is_streamable !== false)
                .map((t) => shapeTrack(t, host));
            return ok(res, { tracks });
        } catch (e) {
            log.warn('Audius', 'search failed: ' + e.message);
            return fail(res, 'Music search is unavailable right now', 502);
        }
    });

    app.get('/audius/trending', async (req, res) => {
        try {
            const host = await getHost();
            const r = await axios.get(`${host}/v1/tracks/trending`, {
                params: { app_name: APP_NAME, limit: 30 },
                timeout: 10000,
            });
            const tracks = (r.data && r.data.data ? r.data.data : []).map((t) => shapeTrack(t, host));
            return ok(res, { tracks });
        } catch (e) {
            log.warn('Audius', 'trending failed: ' + e.message);
            return fail(res, 'Trending music is unavailable right now', 502);
        }
    });

    // Resolve a fresh stream URL for a track id (hosts can rotate).
    app.get('/audius/stream/:id', async (req, res) => {
        try {
            const host = await getHost();
            return ok(res, {
                url: `${host}/v1/tracks/${encodeURIComponent(req.params.id)}/stream?app_name=${APP_NAME}`,
            });
        } catch (e) {
            return fail(res, 'Stream unavailable', 502);
        }
    });

    log.info('Audius', 'Breeze FM (Audius) endpoints registered');
};
