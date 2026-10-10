'use strict';

/**
 * Website & Admin sign-in, Microsoft Device Code Flow
 * =====================================================
 * The launcher signs in with Microsoft's PUBLIC client id (00000000402b5328,
 * the same one the vanilla Minecraft launcher uses): no Azure app, no client
 * secret, no redirect URI to register. This module gives the website and admin
 * panel the EXACT same capability from a browser, driven by the API:
 *
 *   1. Browser calls  POST /auth/device/start
 *      → API asks Microsoft for a device code and returns a short user code +
 *        a verification URL (microsoft.com/link) to show the user.
 *   2. User opens that URL, enters the code, approves in their browser.
 *   3. Browser polls POST /auth/device/poll { session }
 *      → API polls Microsoft; once approved it runs the Xbox → XSTS →
 *        Minecraft token chain (identical to the launcher's Rust code) and
 *        mints the same Breeze JWT via issueBreezeSessionForMcToken().
 *
 * No secrets ever reach the browser: the device_code stays server-side, keyed
 * by an opaque session id. This is the most maintainable, deploy-anywhere
 * option: nothing to configure in Azure, ever.
 *
 * This is the only browser sign-in. The old /auth/ms/start redirect flow was
 * removed in v1.0.22: it sent a session token to whatever return URL it was
 * given.
 */

const crypto = require('crypto');

const MS_CLIENT_ID = '00000000402b5328'; // Microsoft/Mojang public client, no registration
const MS_SCOPE = 'XboxLive.signin offline_access';
// This is a legacy Live (MSA) app, it is NOT known to the modern
// login.microsoftonline.com/consumers endpoints (they return unauthorized_client).
// The legacy login.live.com device endpoints DO accept it (verified live), same as
// the desktop launcher which authenticates against login.live.com.
const DEVICE_CODE_URL = 'https://login.live.com/oauth20_connect.srf';
const TOKEN_URL = 'https://login.live.com/oauth20_token.srf';
const XBOX_AUTH_URL = 'https://user.auth.xboxlive.com/user/authenticate';
const XSTS_AUTH_URL = 'https://xsts.auth.xboxlive.com/xsts/authorize';
const MC_XBOX_LOGIN_URL = 'https://api.minecraftservices.com/authentication/login_with_xbox';

// In-memory pending device sessions (single Node instance on Pterodactyl).
// Each entry: { device_code, interval, expiresAt, done, result }
const sessions = new Map();
const SESSION_TTL_MS = 15 * 60 * 1000;

function sweep() {
    const now = Date.now();
    for (const [id, s] of sessions) if (s.expiresAt < now) sessions.delete(id);
}

async function form(url, params) {
    const r = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams(params),
    });
    const text = await r.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch {}
    return { status: r.status, ok: r.ok, data };
}

/** Xbox → XSTS → Minecraft, same chain as the launcher's Rust auth. */
async function microsoftToMinecraftToken(msAccessToken) {
    const xbox = await (await fetch(XBOX_AUTH_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
            Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msAccessToken}` },
            RelyingParty: 'http://auth.xboxlive.com',
            TokenType: 'JWT',
        }),
    })).json();
    const xblToken = xbox.Token;
    const uhs = xbox.DisplayClaims?.xui?.[0]?.uhs;
    if (!xblToken || !uhs) throw new Error('Xbox Live authentication failed');

    const xsts = await (await fetch(XSTS_AUTH_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({
            Properties: { SandboxId: 'RETAIL', UserTokens: [xblToken] },
            RelyingParty: 'rp://api.minecraftservices.com/',
            TokenType: 'JWT',
        }),
    })).json();
    const xstsToken = xsts.Token;
    const xstsUhs = xsts.DisplayClaims?.xui?.[0]?.uhs || uhs;
    if (!xstsToken) throw new Error('This Microsoft account does not own Minecraft Java Edition');

    const mc = await (await fetch(MC_XBOX_LOGIN_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
        body: JSON.stringify({ identityToken: `XBL3.0 x=${xstsUhs};${xstsToken}` }),
    })).json();
    if (!mc.access_token) throw new Error('Minecraft services rejected the sign-in');
    return mc.access_token;
}

module.exports = function registerMsAuth(app, ctx) {
    const { ok, fail, log, issueBreezeSessionForMcToken } = ctx;

    app.post('/auth/device/start', async (req, res) => {
        try {
            sweep();
            const { status, data } = await form(DEVICE_CODE_URL, {
                client_id: MS_CLIENT_ID,
                scope: MS_SCOPE,
                response_type: 'device_code',
            });
            if (status !== 200 || !data?.device_code) {
                log.error('Auth/Device', 'devicecode request failed', { status, err: data?.error });
                return fail(res, 'Could not start Microsoft sign-in. Try again shortly.', 502);
            }
            const session = crypto.randomBytes(18).toString('hex');
            sessions.set(session, {
                device_code: data.device_code,
                interval: Math.max(3, Number(data.interval) || 5),
                expiresAt: Date.now() + Math.min(SESSION_TTL_MS, (Number(data.expires_in) || 900) * 1000),
                done: false,
                result: null,
            });
            // verification_uri is microsoft.com/link (or /devicelogin); code is short.
            return ok(res, {
                session,
                user_code: data.user_code,
                verification_uri: data.verification_uri || 'https://microsoft.com/link',
                interval: Math.max(3, Number(data.interval) || 5),
                expires_in: Number(data.expires_in) || 900,
            });
        } catch (err) {
            log.error('Auth/Device', 'start error', { msg: err.message });
            return fail(res, 'Could not start Microsoft sign-in', 500);
        }
    });

    app.post('/auth/device/poll', async (req, res) => {
        const session = String(req.body?.session || '');
        const entry = sessions.get(session);
        if (!entry) return fail(res, 'Sign-in session expired, start again.', 410);
        if (Date.now() > entry.expiresAt) { sessions.delete(session); return fail(res, 'Sign-in timed out, start again.', 410); }
        if (entry.done) { sessions.delete(session); return ok(res, entry.result); }
        try {
            const { data } = await form(TOKEN_URL, {
                grant_type: 'urn:ietf:params:oauth:grant-type:device_code',
                client_id: MS_CLIENT_ID,
                device_code: entry.device_code,
            });
            if (data?.error === 'authorization_pending') return ok(res, { pending: true, interval: entry.interval });
            if (data?.error === 'slow_down') { entry.interval += 2; return ok(res, { pending: true, interval: entry.interval }); }
            if (data?.error === 'authorization_declined') { sessions.delete(session); return fail(res, 'Sign-in was declined.', 401); }
            if (data?.error === 'expired_token' || data?.error === 'code_expired') { sessions.delete(session); return fail(res, 'Code expired, start again.', 410); }
            if (!data?.access_token) return ok(res, { pending: true, interval: entry.interval });

            // Approved, run the Minecraft token chain and mint the Breeze JWT.
            const mcToken = await microsoftToMinecraftToken(data.access_token);
            const result = await issueBreezeSessionForMcToken(mcToken, {});
            if (result.error) { sessions.delete(session); return fail(res, result.error, result.status || 400); }
            entry.done = true;
            entry.result = { token: result.token, user: result.user };
            sessions.delete(session);
            return ok(res, entry.result);
        } catch (err) {
            log.error('Auth/Device', 'poll error', { msg: err.message });
            // Most chain failures mean "doesn't own Minecraft", surface plainly.
            sessions.delete(session);
            return fail(res, err.message || 'Sign-in failed', 401);
        }
    });
};
