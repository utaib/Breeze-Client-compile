/**
 * Spotify integration for Breeze.
 *
 * Kept in its own file and mounted with a single call from server.js, so it
 * cannot disturb anything already working there. Every route lives under
 * /spotify and touches only the breeze_spotify_accounts table.
 *
 * Environment (all required for Spotify to be offered at all):
 *   SPOTIFY_CLIENT_ID
 *   SPOTIFY_CLIENT_SECRET
 *   SPOTIFY_REDIRECT_URI     must match the Spotify dashboard exactly,
 *                            e.g. https://api.breezeclient.net/spotify/callback
 *
 * When those are absent every route answers with a clear "not configured"
 * rather than throwing, so a deployment without them behaves like a feature
 * that is switched off instead of a broken one.
 *
 * Premium: Spotify's Web API only permits playback control on Premium
 * accounts. Free accounts can still connect, and everything that reads state
 * keeps working; the control routes answer 403 with reason PREMIUM_REQUIRED so
 * the launcher can explain the situation rather than showing a raw error.
 */
const axios = require('axios');
const crypto = require('crypto');

const AUTH_BASE = 'https://accounts.spotify.com';
const API_BASE = 'https://api.spotify.com/v1';

/** Everything Breeze asks for, and nothing it does not use. */
const SCOPES = [
    'user-read-email',
    'user-read-private',
    'user-read-playback-state',
    'user-modify-playback-state',
    'user-read-currently-playing',
    'streaming',
].join(' ');

module.exports = function mountSpotify(app, deps) {
    const { supabase, requireAuth, ok, fail, log } = deps;

    const CLIENT_ID = process.env.SPOTIFY_CLIENT_ID || '';
    const CLIENT_SECRET = process.env.SPOTIFY_CLIENT_SECRET || '';
    const REDIRECT_URI = process.env.SPOTIFY_REDIRECT_URI || '';
    const configured = Boolean(CLIENT_ID && CLIENT_SECRET && REDIRECT_URI);

    /**
     * Which variables are absent, by name only.
     *
     * Reported to the client because "Spotify is not configured" gives whoever
     * is deploying nothing to act on: the usual cause is one of the three being
     * missing or misnamed, and without this they have to guess which. Names
     * only, never values.
     */
    const missingEnv = () => {
        const missing = [];
        if (!CLIENT_ID) missing.push('SPOTIFY_CLIENT_ID');
        if (!CLIENT_SECRET) missing.push('SPOTIFY_CLIENT_SECRET');
        if (!REDIRECT_URI) missing.push('SPOTIFY_REDIRECT_URI');
        return missing;
    };

    if (!configured) {
        log.warn('Spotify', `Spotify disabled, missing: ${missingEnv().join(', ')}`);
    }

    const notConfigured = (res) =>
        res.status(503).json({
            success: false,
            error: `Spotify is not configured on this server yet. Missing: ${missingEnv().join(', ')}`,
            missing: missingEnv(),
        });

    /**
     * Short-lived signed state for the OAuth round trip.
     *
     * The uuid has to survive the redirect, and putting it in the URL plainly
     * would let anyone link their own Spotify account to someone else's Breeze
     * account by editing it. HMAC over uuid+timestamp means a tampered state is
     * rejected and an old one expires.
     */
    const STATE_TTL_MS = 10 * 60 * 1000;
    const stateSecret = () => CLIENT_SECRET || 'breeze-spotify';

    function makeState(uuid) {
        const payload = `${uuid}.${Date.now()}`;
        const sig = crypto.createHmac('sha256', stateSecret()).update(payload).digest('hex').slice(0, 32);
        return Buffer.from(`${payload}.${sig}`).toString('base64url');
    }

    function readState(state) {
        try {
            const raw = Buffer.from(String(state), 'base64url').toString('utf8');
            const [uuid, ts, sig] = raw.split('.');
            if (!uuid || !ts || !sig) return null;
            const expect = crypto.createHmac('sha256', stateSecret()).update(`${uuid}.${ts}`).digest('hex').slice(0, 32);
            // Length-checked before timingSafeEqual, which throws on a mismatch.
            if (sig.length !== expect.length) return null;
            if (!crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expect))) return null;
            if (Date.now() - Number(ts) > STATE_TTL_MS) return null;
            return uuid;
        } catch {
            return null;
        }
    }

    // ── storage ─────────────────────────────────────────────────────────────

    async function readAccount(uuid) {
        const { data } = await supabase
            .from('breeze_spotify_accounts')
            .select('*')
            .eq('user_uuid', uuid)
            .maybeSingle();
        return data || null;
    }

    async function writeAccount(uuid, patch) {
        const row = { user_uuid: uuid, ...patch, updated_at: new Date().toISOString() };
        const { error } = await supabase
            .from('breeze_spotify_accounts')
            .upsert(row, { onConflict: 'user_uuid' });
        if (error) throw new Error(error.message);
    }

    // ── tokens ──────────────────────────────────────────────────────────────

    function basicAuth() {
        return Buffer.from(`${CLIENT_ID}:${CLIENT_SECRET}`).toString('base64');
    }

    /**
     * A refresh that did not produce a token.
     *
     * `transient` separates "Spotify was unreachable or unhappy for a moment"
     * from "this refresh token is dead": only the second one means the user has
     * to authorize again.
     */
    class SpotifyTokenError extends Error {
        constructor(message, transient) {
            super(message);
            this.name = 'SpotifyTokenError';
            this.transient = transient;
        }
    }

    /**
     * A usable access token, refreshing when it is close to expiry.
     *
     * Refreshed 60 seconds early rather than on expiry: a token that expires
     * mid-request produces a 401 the user sees as "Spotify broke".
     */
    async function accessTokenFor(uuid) {
        const acct = await readAccount(uuid);
        if (!acct || !acct.refresh_token) return null;

        const expiresAt = acct.expires_at ? new Date(acct.expires_at).getTime() : 0;
        if (acct.access_token && expiresAt - 60_000 > Date.now()) return acct.access_token;

        const body = new URLSearchParams({
            grant_type: 'refresh_token',
            refresh_token: acct.refresh_token,
        });
        // This call had no catch. Express 4 does not handle a rejected async
        // handler, so on Node 20 a refusal from Spotify's token endpoint, or a
        // DNS blip, took the whole API process down: /spotify/now-playing,
        // /spotify/:action and /spotify/search all await this with nothing
        // around them.
        let res;
        try {
            res = await axios.post(`${AUTH_BASE}/api/token`, body.toString(), {
                headers: {
                    Authorization: `Basic ${basicAuth()}`,
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
                timeout: 12_000,
            });
        } catch (e) {
            // invalid_grant is the one answer that means the refresh token is
            // genuinely dead and reconnecting is the right advice. A 429, a 5xx
            // or a network failure says nothing about the connection, and
            // telling someone to reconnect because Spotify had a bad minute is
            // how a working link reads as permanently broken.
            const reason = e.response?.data?.error;
            const dead = e.response?.status === 400 && reason === 'invalid_grant';
            throw new SpotifyTokenError(reason || e.message, !dead);
        }

        const next = {
            access_token: res.data.access_token,
            expires_at: new Date(Date.now() + (res.data.expires_in || 3600) * 1000).toISOString(),
        };
        // Spotify only returns a new refresh token sometimes; keep the old one
        // when it does not, or the connection silently dies after an hour.
        if (res.data.refresh_token) next.refresh_token = res.data.refresh_token;
        await writeAccount(uuid, next);
        return next.access_token;
    }

    async function spotify(uuid, method, path, { params, data } = {}) {
        // Every Spotify route funnels through here, so this is the one place
        // that has to guarantee a value rather than a rejected promise.
        let token;
        try {
            token = await accessTokenFor(uuid);
        } catch (e) {
            const transient = !(e instanceof SpotifyTokenError) || e.transient;
            log.warn('Spotify/Token', `${transient ? 'temporary' : 'dead'}: ${e.message}`);
            // 503 is not a Spotify status, it is this API saying "ask again".
            // Callers must not read it as a connection that needs rebuilding.
            return { status: transient ? 503 : 401, data: null };
        }
        if (!token) return { status: 401, data: null };
        try {
            const res = await axios({
                method,
                url: `${API_BASE}${path}`,
                params,
                data,
                headers: { Authorization: `Bearer ${token}` },
                timeout: 12_000,
                validateStatus: () => true,
            });
            return { status: res.status, data: res.data };
        } catch (e) {
            return { status: 502, data: { message: e.message } };
        }
    }

    // ── routes ──────────────────────────────────────────────────────────────

    /** Where to send the user to authorise Breeze. */
    app.get('/spotify/login', requireAuth, async (req, res) => {
        if (!configured) return notConfigured(res);
        const url = new URL(`${AUTH_BASE}/authorize`);
        url.searchParams.set('client_id', CLIENT_ID);
        url.searchParams.set('response_type', 'code');
        url.searchParams.set('redirect_uri', REDIRECT_URI);
        url.searchParams.set('scope', SCOPES);
        url.searchParams.set('state', makeState(req.user.uuid));
        // Always show the consent screen, so switching Spotify accounts works
        // instead of silently reusing the browser's existing session.
        url.searchParams.set('show_dialog', 'true');
        return ok(res, { url: url.toString() });
    });

    /**
     * OAuth return leg.
     *
     * Not authenticated: the browser arrives here from Spotify with no Breeze
     * token. The signed state carries the identity instead.
     */
    app.get('/spotify/callback', async (req, res) => {
        const CTX = 'Spotify/Callback';
        if (!configured) return res.status(503).send('Spotify is not configured on this server.');

        const { code, state, error } = req.query;
        if (error) return res.status(400).send(`Spotify sign-in was cancelled (${error}). You can close this window.`);
        const uuid = readState(state);
        if (!code || !uuid) return res.status(400).send('That Spotify link expired or was invalid. Try connecting again from Breeze.');

        try {
            const body = new URLSearchParams({
                grant_type: 'authorization_code',
                code: String(code),
                redirect_uri: REDIRECT_URI,
            });
            const tok = await axios.post(`${AUTH_BASE}/api/token`, body.toString(), {
                headers: {
                    Authorization: `Basic ${basicAuth()}`,
                    'Content-Type': 'application/x-www-form-urlencoded',
                },
                timeout: 12_000,
            });

            await writeAccount(uuid, {
                access_token: tok.data.access_token,
                refresh_token: tok.data.refresh_token,
                expires_at: new Date(Date.now() + (tok.data.expires_in || 3600) * 1000).toISOString(),
            });

            // Record the profile so the launcher can show who is connected and
            // whether playback control will be permitted, without a second call.
            const me = await spotify(uuid, 'get', '/me');
            if (me.status === 200 && me.data) {
                await writeAccount(uuid, {
                    spotify_user_id: me.data.id || null,
                    display_name: me.data.display_name || me.data.id || null,
                    product: me.data.product || null,
                });
            }

            log.info(CTX, `Spotify connected for ${uuid}`);
            return res.send(
                '<!doctype html><meta charset="utf-8"><title>Spotify connected</title>' +
                '<body style="font:15px system-ui;background:#0E1017;color:#E7E9EE;display:grid;place-items:center;height:100vh;margin:0">' +
                '<div style="text-align:center"><div style="font-size:19px;font-weight:700">Spotify connected</div>' +
                '<div style="margin-top:8px;color:#A6ADBA">You can close this window and go back to Breeze.</div></div>',
            );
        } catch (e) {
            log.error(CTX, e.message);
            return res.status(500).send('Could not finish connecting Spotify. Try again from Breeze.');
        }
    });

    /** Whether this user has Spotify linked, and whether it can control playback. */
    app.get('/spotify/status', requireAuth, async (req, res) => {
        if (!configured) return ok(res, { configured: false, connected: false, premium: false, missing: missingEnv() });
        try {
            const acct = await readAccount(req.user.uuid);
            if (!acct || !acct.refresh_token) {
                return ok(res, { configured: true, connected: false, premium: false });
            }
            // Re-read the product on every status call. A user can upgrade or
            // downgrade at any time and a cached value would tell them the
            // wrong thing about why playback is refused.
            const me = await spotify(req.user.uuid, 'get', '/me');
            if (me.status === 200 && me.data) {
                await writeAccount(req.user.uuid, {
                    product: me.data.product || null,
                    display_name: me.data.display_name || me.data.id || null,
                });
                return ok(res, {
                    configured: true,
                    connected: true,
                    premium: me.data.product === 'premium',
                    displayName: me.data.display_name || me.data.id || null,
                });
            }
            // Only a 401 means the authorization is gone. Anything else is
            // Spotify being unavailable, and the account row still holds a
            // refresh token, so the connection is intact and the launcher must
            // not offer to rebuild it. Reporting every non-200 as needsReconnect
            // is what made a working link read as permanently expired.
            if (me.status === 401) {
                return ok(res, { configured: true, connected: false, premium: false, needsReconnect: true });
            }
            return ok(res, {
                configured: true,
                connected: true,
                premium: acct.product === 'premium',
                displayName: acct.display_name || null,
                temporarilyUnavailable: true,
            });
        } catch (e) {
            log.error('Spotify/Status', e.message);
            return fail(res, 'Could not check Spotify.', 500);
        }
    });

    app.post('/spotify/disconnect', requireAuth, async (req, res) => {
        try {
            await supabase.from('breeze_spotify_accounts').delete().eq('user_uuid', req.user.uuid);
            return ok(res, { connected: false });
        } catch (e) {
            log.error('Spotify/Disconnect', e.message);
            return fail(res, 'Could not disconnect Spotify.', 500);
        }
    });

    /** What is playing right now. Works on free accounts too. */
    app.get('/spotify/now-playing', requireAuth, async (req, res) => {
        if (!configured) return notConfigured(res);
        const r = await spotify(req.user.uuid, 'get', '/me/player');
        if (r.status === 401) return fail(res, 'Spotify needs to be reconnected.', 401);
        // 503 is this API saying the token could not be refreshed for a reason
        // that says nothing about the connection. Falling through would report
        // an outage as "nothing is playing", or as a play command that worked.
        if (r.status === 503) return fail(res, 'Spotify is not responding right now, try again in a moment.', 503);
        // 204 means nothing is playing, which is a normal state and not an error.
        if (r.status === 204 || !r.data) return ok(res, { playing: false, track: null });
        const item = r.data.item || null;
        return ok(res, {
            playing: Boolean(r.data.is_playing),
            progressMs: r.data.progress_ms ?? 0,
            device: r.data.device ? { name: r.data.device.name, type: r.data.device.type } : null,
            track: item
                ? {
                    id: item.id,
                    title: item.name,
                    artist: (item.artists || []).map((a) => a.name).join(', '),
                    album: item.album?.name || null,
                    artwork: item.album?.images?.[0]?.url || null,
                    durationMs: item.duration_ms ?? 0,
                    url: item.external_urls?.spotify || null,
                }
                : null,
        });
    });

    /**
     * Playback control. Premium only, per Spotify's own restriction.
     *
     * The 403 is answered with an explicit reason so the launcher can say what
     * is actually wrong instead of surfacing "Forbidden".
     */
    const CONTROLS = {
        play: ['put', '/me/player/play'],
        pause: ['put', '/me/player/pause'],
        next: ['post', '/me/player/next'],
        previous: ['post', '/me/player/previous'],
    };

    // A plain param with an explicit whitelist rather than
    // ':action(play|pause|next|previous)'. The inline-regex form only works on
    // Express 4; this behaves identically and survives an Express 5 upgrade,
    // which would otherwise silently stop matching and take Spotify with it.
    app.post('/spotify/:action', requireAuth, async (req, res) => {
        if (!configured) return notConfigured(res);
        if (!Object.prototype.hasOwnProperty.call(CONTROLS, req.params.action)) {
            return fail(res, 'Unknown Spotify action.', 404);
        }
        const [method, path] = CONTROLS[req.params.action];

        const acct = await readAccount(req.user.uuid);
        if (!acct || !acct.refresh_token) return fail(res, 'Connect Spotify first.', 400);
        if (acct.product && acct.product !== 'premium') {
            return res.status(403).json({
                success: false,
                reason: 'PREMIUM_REQUIRED',
                error: 'Spotify Premium is required to play music through Breeze.',
            });
        }

        const r = await spotify(req.user.uuid, method, path);
        if (r.status === 401) return fail(res, 'Spotify needs to be reconnected.', 401);
        // 503 is this API saying the token could not be refreshed for a reason
        // that says nothing about the connection. Falling through would report
        // an outage as "nothing is playing", or as a play command that worked.
        if (r.status === 503) return fail(res, 'Spotify is not responding right now, try again in a moment.', 503);
        if (r.status === 403) {
            // Spotify also returns 403 when the account is not Premium, even if
            // our cached product said otherwise (it can change mid-session).
            return res.status(403).json({
                success: false,
                reason: 'PREMIUM_REQUIRED',
                error: 'Spotify Premium is required to play music through Breeze.',
            });
        }
        if (r.status === 404) {
            return fail(res, 'No active Spotify device. Open Spotify on any device, then try again.', 404);
        }
        if (r.status >= 400) return fail(res, 'Spotify refused that request.', r.status);
        return ok(res, { ok: true });
    });

    app.get('/spotify/search', requireAuth, async (req, res) => {
        if (!configured) return notConfigured(res);
        const q = String(req.query.q || '').trim();
        if (!q) return ok(res, { tracks: [] });
        const r = await spotify(req.user.uuid, 'get', '/search', {
            params: { q, type: 'track', limit: 20 },
        });
        if (r.status === 401) return fail(res, 'Spotify needs to be reconnected.', 401);
        // 503 is this API saying the token could not be refreshed for a reason
        // that says nothing about the connection. Falling through would report
        // an outage as "nothing is playing", or as a play command that worked.
        if (r.status === 503) return fail(res, 'Spotify is not responding right now, try again in a moment.', 503);
        if (r.status >= 400) return fail(res, 'Spotify search failed.', r.status);
        const items = r.data?.tracks?.items || [];
        return ok(res, {
            tracks: items.map((t) => ({
                id: t.id,
                uri: t.uri,
                title: t.name,
                artist: (t.artists || []).map((a) => a.name).join(', '),
                artwork: t.album?.images?.[t.album.images.length - 1]?.url || null,
                durationMs: t.duration_ms ?? 0,
            })),
        });
    });

    log.info('Spotify', configured ? 'Spotify routes mounted' : 'Spotify routes mounted (unconfigured)');
};
