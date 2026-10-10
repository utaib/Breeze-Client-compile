/**
 * Required ENV variables:
 *   SUPABASE_URL, SUPABASE_KEY
 *   JWT_SECRET
 *   PAYPAL_CLIENT_ID, PAYPAL_CLIENT_SECRET, PAYPAL_WEBHOOK_ID
 *   PAYPAL_MODE              (sandbox | live)
 *   SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASS
 *   REPORT_EMAIL             (recipient for monthly earnings emails)
 *   PAYPAL_FEE_PERCENT       (default 3.49)
 *   PAYPAL_FEE_FIXED         (default 0.49  in USD)
 *   CREATOR_SHARE_PERCENT    (default 70, global fallback when DB value is null)
 *   COOWNER_SHARE_PERCENT    (default 3, % of the remainder after creator cut)
 *   COOWNER_UUID             (UUID of the co-owner user in the DB)
 *   DEVELOPER_ONE_UUID       (UUID of developer one in the DB)
 *   DEVELOPER_TWO_UUID       (UUID of developer two in the DB)
 *   OWNER_UUID               (UUID of the owner user in the DB)
 *   FRONTEND_URL             (default https://breezeclient.com)
 *   PORT                     (default 25583)
 *   NODE_ENV                 (development | production)
 */

'use strict';

// --- Environment loading (robust) -------------------------------------------
// dotenv only auto-loads a file literally named ".env". Some hosts/uploads end
// up with the file named "env" (no dot) or ".env.local". Load whichever exists,
// in priority order, so a rename mishap can never silently take the API down.
{
    const dotenv = require('dotenv');
    const fsBoot = require('fs');
    const pathBoot = require('path');
    const candidates = ['.env', '.env.local', 'env', '.env.production'];
    let loadedFrom = null;
    for (const name of candidates) {
        const p = pathBoot.join(__dirname, name);
        if (fsBoot.existsSync(p)) {
            dotenv.config({ path: p });
            loadedFrom = name;
            break;
        }
    }
    // Also honour a plain process-level .env (e.g. Pterodactyl/Docker env vars)
    // that may already be in process.env, dotenv never overwrites those.
    if (!loadedFrom) dotenv.config();
    if (loadedFrom && loadedFrom !== '.env') {
        console.info(`[Breeze] Loaded environment from "${loadedFrom}" (no .env found).`);
    }
}
const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const multer = require('multer');
const sharp = require('sharp');
// Used for the cosmetic-state revision hash. Named nodeCrypto because 'crypto'
// is also a global in modern Node and shadowing it here would be confusing.
const nodeCrypto = require('crypto');
const jwt = require('jsonwebtoken');
const axios = require('axios');
const nodemailer = require('nodemailer');
const { v4: uuidv4 } = require('uuid');
const rateLimit = require('express-rate-limit');
const { createClient } = require('@supabase/supabase-js');
const fs = require('fs');
const path = require('path');
// API-local asset storage (storage/ served at /assets), see src/assets.js
const breezeAssets = require('./src/assets');
// Reads what a Breeze mod jar declares it supports, so compatibility comes from
// the artifact rather than from its filename.
const { readModJarMetadata, versionSatisfies } = require('./src/jarMeta');
// Server-side animated-cape splitting (GIF/APNG → frames), see src/capeMedia.js
const capeMedia = require('./src/capeMedia');
const cosmeticAsset = require('./src/cosmeticAsset');
// Counts public launcher downloads and serves the totals to the admin panel.
const { createDownloadCounter, registerDownloadStats } = require('./src/downloadStats');
const { createActivity, registerActivity } = require('./src/activityStats');
if (typeof globalThis.WebSocket === 'undefined') {
    try {
        const wsLib = require('ws');
        globalThis.WebSocket = wsLib.WebSocket || wsLib;
        console.info('[Breeze] WebSocket polyfill installed via ws@%s', wsLib.version || 'unknown');
    } catch (e) {
        console.warn(
            '[Breeze] `ws` package not installed; ' +
                'Supabase realtime disabled but REST endpoints will work. ' +
                'Add "ws": "^8" to package.json to silence this warning.',
        );
        globalThis.WebSocket = function () {
            throw new Error('Breeze: realtime WebSocket disabled, install ws package');
        };
        globalThis.WebSocket.CONNECTING = 0;
        globalThis.WebSocket.OPEN = 1;
        globalThis.WebSocket.CLOSING = 2;
        globalThis.WebSocket.CLOSED = 3;
    }
}
if (!process.env.SUPABASE_KEY && process.env.SUPABASE_SERVICE_KEY) {
    process.env.SUPABASE_KEY = process.env.SUPABASE_SERVICE_KEY;
}
const REQUIRED_ENV = ['SUPABASE_URL', 'SUPABASE_KEY', 'JWT_SECRET'];
const missingEnv = REQUIRED_ENV.filter((key) => !process.env[key]);
if (missingEnv.length) {
    console.error(
        '\n❌  Breeze API cannot start, missing required environment variable(s):\n' +
            missingEnv.map((k) => `      • ${k}`).join('\n') +
            '\n\n   Fix: create a ".env" file in the API folder (or set these in your\n' +
            '   host\'s environment panel). SUPABASE_KEY is the service-role key.\n' +
            '   See DEPLOY.md for the full list. The API will now exit.\n',
    );
    process.exit(1);
}
const PORT = parseInt(process.env.PORT ?? '25609', 10);
const JWT_SECRET = process.env.JWT_SECRET;
const NODE_ENV = process.env.NODE_ENV ?? 'development';
const PAYPAL_MODE = process.env.PAYPAL_MODE ?? 'sandbox';
const PAYPAL_CLIENT_ID = process.env.PAYPAL_CLIENT_ID ?? '';
const PAYPAL_CLIENT_SECRET = process.env.PAYPAL_CLIENT_SECRET ?? '';
const PAYPAL_WEBHOOK_ID = process.env.PAYPAL_WEBHOOK_ID ?? '';
// PAYPAL_API_BASE may point sandbox mode at a stand-in PayPal (the payment
// tests do this). Live mode always talks to PayPal itself.
const PAYPAL_API_BASE =
    PAYPAL_MODE === 'live'
        ? 'https://api-m.paypal.com'
        : process.env.PAYPAL_API_BASE || 'https://api-m.sandbox.paypal.com';
// Opt-in escape hatch for local testing without PayPal credentials. See
// paypalVerifyWebhook: with this off (the default) an event whose signature
// cannot be checked is rejected rather than trusted.
const PAYPAL_ALLOW_UNVERIFIED_WEBHOOKS =
    process.env.PAYPAL_ALLOW_UNVERIFIED_WEBHOOKS === 'true';
const PAYPAL_FEE_PERCENT = parseFloat(process.env.PAYPAL_FEE_PERCENT ?? '3.49');
const PAYPAL_FEE_FIXED = parseFloat(process.env.PAYPAL_FEE_FIXED ?? '0.49');
const CREATOR_SHARE_PERCENT = parseFloat(process.env.CREATOR_SHARE_PERCENT ?? '70');
const COOWNER_SHARE_PERCENT = parseFloat(process.env.COOWNER_SHARE_PERCENT ?? '3');
const DEVELOPER_ONE_SHARE_PERCENT = parseFloat(process.env.DEVELOPER_ONE_SHARE_PERCENT ?? '10');
const DEVELOPER_TWO_SHARE_PERCENT = parseFloat(process.env.DEVELOPER_TWO_SHARE_PERCENT ?? '10');
const SMTP_HOST = process.env.SMTP_HOST ?? '';
const SMTP_PORT = parseInt(process.env.SMTP_PORT ?? '587', 10);
const SMTP_USER = process.env.SMTP_USER ?? '';
const SMTP_PASS = process.env.SMTP_PASS ?? '';
const EMAIL_NOREPLY = process.env.EMAIL_NOREPLY || 'Breeze Client <noreply@breezeclient.net>';
const EMAIL_SUPPORT = process.env.EMAIL_SUPPORT || 'Breeze Support <support@breezeclient.net>';
const EMAIL_INFO = process.env.EMAIL_INFO || 'Breeze Client <info@breezeclient.net>';
const EMAIL_SECURITY = process.env.EMAIL_SECURITY || 'Breeze Security <security@breezeclient.net>';
const EMAIL_ADMIN = process.env.EMAIL_ADMIN || 'Breeze Admin <admin@breezeclient.net>';
const REPORT_EMAIL = process.env.REPORT_EMAIL ?? '';
const STARTUP_EMAIL_ENABLED = process.env.STARTUP_EMAIL_ENABLED === 'true';
const STARTUP_EMAIL_TO = process.env.STARTUP_EMAIL_TO || REPORT_EMAIL;
// Where PayPal sends a buyer back to. The site is breezeclient.net; the old
// default pointed at .com, which this project does not serve.
const FRONTEND_URL = process.env.FRONTEND_URL ?? 'https://breezeclient.net';
const API_PUBLIC_BASE_URL = (
    process.env.API_PUBLIC_BASE_URL ||
    process.env.PUBLIC_API_URL ||
    ''
).replace(/\/$/, '');
const VERSIONS_ROOT = path.resolve(
    process.env.BREEZE_VERSIONS_DIR || path.join(__dirname, 'versions'),
);
const VERSIONS_MANIFEST_PATH = path.resolve(
    process.env.BREEZE_VERSIONS_MANIFEST || path.join(VERSIONS_ROOT, 'versions.js'),
);
const LAUNCHER_VERSION_DIR = path.resolve(
    process.env.BREEZE_LAUNCHER_VERSIONS_DIR || path.join(VERSIONS_ROOT, 'launcher'),
);
const LEGACY_MOD_VERSION_DIR = path.join(VERSIONS_ROOT, 'mod');
const MOD_VERSION_DIR = path.resolve(
    process.env.BREEZE_MOD_VERSIONS_DIR ||
        (fs.existsSync(LEGACY_MOD_VERSION_DIR)
            ? LEGACY_MOD_VERSION_DIR
            : path.join(VERSIONS_ROOT, 'mods')),
);
// Where the flat mod data files live (names.txt, friends.txt, dms.txt, ...).
// These hold real player data and used to sit in the deploy directory itself,
// which is why copies ended up committed to git. Pointing this at a directory
// outside the deployment keeps player data out of the source tree, and lets a
// test run write to a temp folder instead of the repository copies.
const MOD_DATA_DIR = path.resolve(process.env.BREEZE_MOD_DATA_DIR || __dirname);
try {
    fs.mkdirSync(MOD_DATA_DIR, { recursive: true });
} catch {
    /* falls back to the API directory, which always exists */
}
const ROLES = Object.freeze({
    USER: 'user',
    CREATOR: 'creator',
    ADMIN: 'admin',
    // Carries the Developer badge and creator access (see CREATOR_TIER).
    DEVELOPER: 'developer',
    OWNER: 'owner',
});

// The role ladder is owner > developer > creator > user. A developer can do
// everything a creator can, under the same rules: the same upload limits, and
// editing or deleting only what they made themselves. Admin sits outside the
// ladder and keeps what it had. Owners can do everything.
const CREATOR_TIER = Object.freeze([ROLES.CREATOR, ROLES.DEVELOPER]);
const CREATOR_ACCESS_ROLES = Object.freeze([...CREATOR_TIER, ROLES.ADMIN, ROLES.OWNER]);
/** Creator rules apply: upload limits, own items only. */
const isCreatorTier = (role) => CREATOR_TIER.includes(role);
/** May use creator features at all. */
const hasCreatorAccess = (role) => CREATOR_ACCESS_ROLES.includes(role);

// ─── Mod identity (v1.0.22) ──────────────────────────────────────────────────
// The in-game mod endpoints below (/select, /dm, /friends, /host, /cosmetics/tag)
// address a player by the UUID in the URL. A Minecraft UUID is public
// information, so trusting it let anyone act as any player: read their DMs,
// change their cape, send friend requests as them. The 2026-09-15 audit
// confirmed this was live in production.
//
// Identity is now REQUIRED by default. A caller proves who it is with a Breeze
// token whose uuid matches the path:
//
//   - a game-session token (aud "breeze-game"), which the launcher fetches from
//     POST /auth/game-session and hands to the mod for the length of a play
//     session. This is the intended path.
//   - a normal Breeze account token, for the launcher's own calls.
//   - optionally BREEZE_MOD_SECRET, presented as x-breeze-mod-key, for
//     server-to-server callers. Never ship this inside the mod jar: anyone can
//     unzip a jar, so an embedded secret is not a secret.
//
// BREEZE_MOD_AUTH_REQUIRED=false reopens the old, unauthenticated behaviour.
// It exists only as a deployment-window rollback, it is logged loudly on every
// boot, and it must never be left on.
const MOD_SHARED_SECRET = process.env.BREEZE_MOD_SECRET || '';
const MOD_AUTH_REQUIRED = process.env.BREEZE_MOD_AUTH_REQUIRED !== 'false';
// Audience marker for tokens that are handed to the game client. They can prove
// "I am this player" to the mod endpoints, but they are refused by requireAuth,
// so a token pulled out of a game instance cannot spend Wind Charges, change an
// email address or reach any other account route.
const GAME_TOKEN_AUDIENCE = 'breeze-game';
// A link the browser can follow to one artifact, instead of a header it cannot
// send. Minutes long, one path, one person.
const DOWNLOAD_TOKEN_AUDIENCE = 'breeze-download';
const DOWNLOAD_TOKEN_TTL_SECONDS = 300;
const GAME_TOKEN_TTL = process.env.BREEZE_GAME_TOKEN_TTL || '12h';
// Hard cap on the in-memory tables the unauthenticated mod endpoints populate.
// Without a ceiling, a script hitting /announce with random UUIDs grows these
// Maps (and the .txt files written from them) until the process runs out of
// memory or the disk fills.
const MOD_MAX_TRACKED_USERS = parseInt(process.env.BREEZE_MOD_MAX_USERS ?? '50000', 10);
const supabase = createClient(process.env.SUPABASE_URL, process.env.SUPABASE_KEY, {
    realtime: {
        params: {
            eventsPerSecond: 0,
        },
    },
    auth: {
        autoRefreshToken: false,
        persistSession: false,
        detectSessionInUrl: false,
    },
});

// Datastore abstraction (Section 18). `db` is a pass-through to the client
// above, so `db.from(x)` and `supabase.from(x)` are interchangeable today. New
// code should use `db`; existing call sites move over one at a time. The MySQL
// migration is NOT active. See MYSQL_MIGRATION.md.
const { createDb, DB_DRIVER } = require('./src/db');
const { createRepositories } = require('./src/db/repositories');
const db = createDb(supabase);
const repos = createRepositories(db);

const mailer = nodemailer.createTransport({
    host: SMTP_HOST,
    port: SMTP_PORT,
    secure: SMTP_PORT === 465,
    auth: {
        user: SMTP_USER,
        pass: SMTP_PASS,
    },
});
const app = express();
// Behind a reverse proxy (Pterodactyl/NGINX/Cloudflare) the client IP arrives in
// X-Forwarded-For. Without this, express-rate-limit throws ERR_ERL_UNEXPECTED_X_FORWARDED_FOR.
app.set('trust proxy', 1);
app.use(helmet());
const ALLOWED_ORIGINS = [
    'https://breezeclient.net',
    'https://www.breezeclient.net',
    'https://breezeclient.pages.dev',
    'https://admin.breezeclient.net',
    'http://localhost:1420',
    'http://localhost:5173',
    'tauri://localhost',
    'https://tauri.localhost',
    'http://tauri.localhost', // Tauri v2 webview origin on Windows
];
// The Tauri webview origin differs per OS/scheme:
//   Windows → http://tauri.localhost   macOS/Linux → tauri://localhost
// plus the Vite dev origin http://localhost:<port>. Match them all.
const TAURI_ORIGIN_RE = /^(https?|tauri):\/\/(tauri\.localhost|localhost)(:\d+)?$/;
app.use(
    cors({
        origin: (origin, callback) => {
            if (!origin) return callback(null, true);
            if (ALLOWED_ORIGINS.includes(origin)) return callback(null, true);
            if (TAURI_ORIGIN_RE.test(origin)) return callback(null, true);
            if (/^https?:\/\/([a-z0-9-]+\.)*breezeclient\.net$/.test(origin))
                return callback(null, true);
            // Deny cleanly (no CORS headers) instead of throwing, a thrown error
            // just spams the logs with an unhandled-error stack for every request.
            log.warn('CORS', `Blocked origin: ${origin}`);
            return callback(null, false);
        },
        credentials: true,
    }),
);
app.use(
    '/payments/webhook',
    express.raw({
        type: 'application/json',
    }),
);
// Express defaults to 100kb; keeping that as an explicit value documents the
// ceiling instead of leaving it implicit. No route needs more: binary uploads
// go through multer with its own 2 MB limit, and every JSON payload here is
// small metadata.
app.use(express.json({ limit: '100kb' }));

// ─── Rate limiting ───────────────────────────────────────────────────────────
// Only /auth and /purchases were limited before, which left every other route
// (including the unauthenticated mod and lookup endpoints) free to be hammered.
// This global limit is deliberately generous: a normal launcher or mod client
// polls a handful of endpoints every few seconds and stays far below it, while
// a scraper enumerating UUIDs hits it almost immediately.
// Sized for the mod's real traffic shape, not for a browser's. A client polls
// /cosmetics/state per visible player, so one player on a busy server can
// legitimately make a few hundred lookups a minute, and several players can
// share one household or campus IP. Enumerating UUIDs at this rate is still
// hopeless, which is what the limit is for. Watch for 429s after deploying and
// raise BREEZE_RATE_LIMIT_MAX / BREEZE_LOOKUP_LIMIT if legitimate clients trip.
const GLOBAL_RATE_LIMIT = parseInt(process.env.BREEZE_RATE_LIMIT_MAX ?? '1200', 10);
const globalLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: GLOBAL_RATE_LIMIT,
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many requests, slow down.' },
    // PayPal's webhook retries must never be throttled: a dropped capture event
    // is a customer who paid and did not get their item. It is signature-checked
    // rather than rate-checked.
    skip: (req) => (req.originalUrl || '').split('?')[0] === '/payments/webhook',
});
app.use(globalLimiter);

// Tighter limit for the endpoints that WRITE state while identifying the player
// only by a UUID in the URL. Even once the shared secret is enforced, this caps
// the damage a leaked secret or a misbehaving client can do.
const modWriteLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: parseInt(process.env.BREEZE_MOD_WRITE_LIMIT ?? '60', 10),
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many requests, slow down.' },
});

// Lookup endpoints that take a user identifier and are not authenticated. These
// are the ones worth scraping (profiles, batch cape lookups, cosmetic state), so
// they get their own budget rather than sharing the global one.
const lookupLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: parseInt(process.env.BREEZE_LOOKUP_LIMIT ?? '300', 10),
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many lookups, slow down.' },
});

const authLimiter = rateLimit({
    windowMs: 15 * 60 * 1000,
    max: 20,
    message: {
        success: false,
        error: 'Too many auth requests, slow down.',
    },
    // The device-code poll fires every few seconds while the user approves in
    // their browser, it must not count against the auth limit. originalUrl is
    // never stripped by the mount, so this matches regardless of how the
    // limiter is attached.
    skip: (req) => (req.originalUrl || '').split('?')[0].endsWith('/auth/device/poll'),
});
const purchaseLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: 10,
    message: {
        success: false,
        error: 'Too many purchase requests, slow down.',
    },
});
app.use('/auth/', authLimiter);
app.use('/purchases/', purchaseLimiter);
const upload = multer({
    storage: multer.memoryStorage(),
    limits: {
        fileSize: 2 * 1024 * 1024,
        files: 50,
    },
});
const log = {
    info: (ctx, msg, meta = {}) =>
        console.log(`[INFO]  [${ctx}] ${msg}`, Object.keys(meta).length ? meta : ''),
    warn: (ctx, msg, meta = {}) =>
        console.warn(`[WARN]  [${ctx}] ${msg}`, Object.keys(meta).length ? meta : ''),
    error: (ctx, msg, meta = {}) =>
        console.error(`[ERROR] [${ctx}] ${msg}`, Object.keys(meta).length ? meta : ''),
};
// Players online and daily active players for the admin panel (src/activityStats.js).
const breezeActivity = createActivity({ log });
function hasMailConfig() {
    return Boolean(SMTP_HOST && SMTP_USER && SMTP_PASS);
}
async function sendSystemEmail({ to = REPORT_EMAIL, from = EMAIL_ADMIN, subject, text, html }) {
    if (!hasMailConfig() || !to) {
        log.warn('Email', 'SMTP not configured - skipping system email', {
            subject,
        });
        return false;
    }
    try {
        await mailer.sendMail({
            from,
            to,
            subject,
            text,
            html,
        });
        log.info('Email', 'System email sent', {
            to,
            subject,
        });
        return true;
    } catch (error) {
        log.error('Email', 'System email failed', {
            msg: error.message,
            subject,
        });
        return false;
    }
}
const RADIO_FALLBACK_TRACKS = [
    {
        id: 'focus-lights',
        title: 'Focus Lights',
        artist: 'Breeze FM',
        album: 'Launcher Focus',
        mood: 'lofi',
        length: '2:48',
        provider: 'fallback',
    },
    {
        id: 'deep-caves',
        title: 'Deep Caves',
        artist: 'Breeze FM',
        album: 'Launcher Focus',
        mood: 'ambient',
        length: '3:16',
        provider: 'fallback',
    },
    {
        id: 'nether-night',
        title: 'Nether Night Drive',
        artist: 'Breeze FM',
        album: 'Launcher Focus',
        mood: 'chill',
        length: '2:59',
        provider: 'fallback',
    },
    {
        id: 'skybase',
        title: 'Skybase',
        artist: 'Breeze FM',
        album: 'Launcher Focus',
        mood: 'study',
        length: '3:32',
        provider: 'fallback',
    },
];
function msToTrackLength(ms) {
    if (!Number.isFinite(ms)) return null;
    const totalSeconds = Math.max(0, Math.round(ms / 1000));
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = String(totalSeconds % 60).padStart(2, '0');
    return `${minutes}:${seconds}`;
}
// Every successful JSON response passes through here, which makes it the one
// place to guarantee no asset URL ever leaves the API over plain http. See
// httpsifyAssetUrls for why that matters (the launcher's CSP blocks http: images).
const ok = (res, data = {}, status = 200) =>
    res.status(status).json(
        httpsifyAssetUrls({
            success: true,
            ...data,
        }),
    );
const fail = (res, message, status = 400) =>
    res.status(status).json({
        success: false,
        error: message,
    });
function getRequestBaseUrl(req) {
    if (API_PUBLIC_BASE_URL) return forcePublicHttps(API_PUBLIC_BASE_URL);
    const proto = String(req.headers['x-forwarded-proto'] || req.protocol || 'http')
        .split(',')[0]
        .trim();
    const host = String(req.headers['x-forwarded-host'] || req.headers.host || '')
        .split(',')[0]
        .trim();
    return host ? forcePublicHttps(`${proto}://${host}`) : `http://localhost:${PORT}`;
}

/**
 * Never hand out an http:// URL for our own public host.
 *
 * The proxy in front of production terminates TLS and does not always forward
 * x-forwarded-proto, so this built "http://api.breezeclient.net". Every URL
 * derived from it, including launcher download links and the update manifest,
 * then pointed at http: a browser on the https admin panel blocks that as mixed
 * content, and the launcher's CSP allows https only. Local development keeps
 * http, because that is what it actually serves.
 */
function forcePublicHttps(url) {
    return String(url).replace(/^http:\/\/(?!localhost|127\.0\.0\.1|0\.0\.0\.0|\[)/i, 'https://');
}
/**
 * Force our own asset URLs onto https.
 *
 * Cape image_url values are baked into the database at upload time from the
 * request host. Behind a TLS-terminating proxy that does not forward
 * x-forwarded-proto, that produced "http://api.breezeclient.net/...". The
 * launcher's webview CSP allows `img-src https:` but not `http:`, so every one
 * of those textures was silently blocked: the cape record loaded, the image
 * never did.
 *
 * Rewriting on the way out fixes every already-stored row without a migration,
 * and fixes clients that are already installed.
 */
function httpsifyAssetUrls(payload) {
    if (payload == null) return payload;
    if (typeof payload === 'string') {
        // Only our own non-local hosts. localhost / 127.0.0.1 must stay http
        // so local development keeps working.
        return payload.replace(/\bhttp:\/\/(?!localhost|127\.0\.0\.1|0\.0\.0\.0|\[)/gi, 'https://');
    }
    if (Array.isArray(payload)) return payload.map(httpsifyAssetUrls);
    if (typeof payload === 'object') {
        const out = {};
        for (const [key, value] of Object.entries(payload)) out[key] = httpsifyAssetUrls(value);
        return out;
    }
    return payload;
}

function safeFileName(value) {
    const fileName = path.basename(String(value || '').trim());
    if (!fileName || fileName === '.' || fileName === '..') return null;
    return fileName;
}
function safeArtifactPath(value) {
    const raw = String(value || '')
        .trim()
        .replace(/\\/g, '/');
    if (!raw || path.isAbsolute(raw)) return null;
    const normalized = path.posix.normalize(raw);
    if (!normalized || normalized === '.' || normalized === '..' || normalized.startsWith('../'))
        return null;
    return normalized;
}
function encodeArtifactPath(value) {
    return value
        .split('/')
        .map((part) => encodeURIComponent(part))
        .join('/');
}
function artifactUrl(req, kind, fileName, explicitUrl) {
    if (explicitUrl) return explicitUrl;
    const safe = safeArtifactPath(fileName);
    if (!safe) return null;
    return `${getRequestBaseUrl(req)}/versions/${kind}/${encodeArtifactPath(safe)}`;
}

/**
 * Is the file this manifest entry names actually here?
 *
 * The manifest is hand-edited on release, so it can name a build before anyone
 * uploads it. Advertising that URL is how an updater ends up fetching a 404 and
 * a player is told an update exists that cannot be installed.
 */
function artifactIsPresent(kind, fileName) {
    const safe = safeArtifactPath(fileName);
    if (!safe) return false;
    const root = kind === 'mod' ? MOD_VERSION_DIR : LAUNCHER_VERSION_DIR;
    const abs = path.resolve(root, ...safe.split('/'));
    const rel = path.relative(path.resolve(root), abs);
    if (rel.startsWith('..')) return false;
    try {
        return fs.statSync(abs).isFile();
    } catch {
        return false;
    }
}
function normalizeArtifact(req, kind, section = {}, envPrefix, defaults = {}) {
    const versions = Array.isArray(section.versions) ? section.versions : [];
    const latest =
        section.latest ||
        section.latestVersion ||
        process.env[`${envPrefix}_VERSION`] ||
        defaults.latestVersion;
    const selected =
        versions.find((item) => item.version === latest || item.latestVersion === latest) ||
        (versions.length ? versions[0] : {});
    const fileName = safeArtifactPath(
        selected.fileName ||
            selected.file ||
            selected.path ||
            selected.relativePath ||
            section.fileName ||
            section.file ||
            section.path ||
            section.relativePath ||
            process.env[`${envPrefix}_FILE`] ||
            defaults.fileName,
    );
    // A website page is not an installer. defaults.downloadUrl is the human
    // page and deliberately does NOT feed this: with it here, every manifest
    // that had no artifact URL configured advertised breezeclient.net/downloads
    // as the thing to download, so a launcher fetched an HTML document and
    // compared it against an installer's sha256.
    const explicitUrl =
        selected.downloadUrl || section.downloadUrl || process.env[`${envPrefix}_URL`] || null;
    return {
        latestVersion:
            selected.version || selected.latestVersion || latest || defaults.latestVersion,
        version: selected.version || selected.latestVersion || latest || defaults.latestVersion,
        // Only ever a real artifact URL, and only when the file is really here.
        // This used to fall back to a website page, so a launcher with nothing
        // to download fetched an HTML document and reported "Downloaded update
        // looks incomplete"; and it advertised a named build before it was
        // uploaded, which is a 404 for whoever follows it. Nothing published
        // means null, which every caller already understands.
        downloadUrl: explicitUrl || (artifactIsPresent(kind, fileName) ? artifactUrl(req, kind, fileName, null) : null),
        // Where a person should be sent instead. The website's download button
        // uses this; the updater must not.
        websiteUrl:
            defaults.downloadUrl ||
            `${FRONTEND_URL}/downloads/${kind === 'mod' ? 'mod/latest' : 'latest'}`,
        fileName,
        sha256: selected.sha256 || section.sha256 || process.env[`${envPrefix}_SHA256`] || null,
        mandatory: Boolean(
            selected.mandatory ??
            section.mandatory ??
            process.env[`${envPrefix}_MANDATORY`] === 'true',
        ),
        changelog:
            selected.changelog ||
            section.changelog ||
            process.env[`${envPrefix}_CHANGELOG`] ||
            defaults.changelog ||
            '',
        notesUrl:
            selected.notesUrl || section.notesUrl || process.env[`${envPrefix}_NOTES_URL`] || null,
        channel: selected.channel || section.channel || defaults.channel || 'stable',
        releasedAt: selected.releasedAt || section.releasedAt || null,
    };
}
function readVersionsManifestFile() {
    if (!fs.existsSync(VERSIONS_MANIFEST_PATH)) return {};
    try {
        delete require.cache[require.resolve(VERSIONS_MANIFEST_PATH)];
        const loaded = require(VERSIONS_MANIFEST_PATH);
        return loaded.default || loaded;
    } catch (jsError) {
        try {
            return JSON.parse(fs.readFileSync(VERSIONS_MANIFEST_PATH, 'utf8'));
        } catch (jsonError) {
            log.warn('Versions', 'Could not read versions manifest', {
                path: VERSIONS_MANIFEST_PATH,
                msg: jsError.message || jsonError.message,
            });
            return {};
        }
    }
}
// Cross-platform launcher downloads. For each OS the manifest declares, resolve
// the public download URL, report whether the installer is actually uploaded
// yet, and its size, so the website + launcher can present real per-OS options.
const PLATFORM_META = {
    windows: { label: 'Windows 10 / 11 (64-bit)', ext: 'exe', kind: 'nsis', arch: 'x64' },
    linux: { label: 'Linux (AppImage, 64-bit)', ext: 'AppImage', kind: 'appimage', arch: 'x86_64' },
    macos: { label: 'macOS 12+ (Universal)', ext: 'dmg', kind: 'dmg', arch: 'universal' },
};
/**
 * Installer formats per operating system.
 *
 * Linux genuinely needs several: an AppImage runs anywhere, .deb suits
 * Debian/Ubuntu, .rpm suits Fedora/RHEL, and Flatpak is sandboxed and the route
 * to Flathub. A single "linux" download forces users to guess whether it fits
 * their distribution, so each format is published, listed and updated
 * independently (Section 14.2).
 *
 * `primary` is the format the auto-updater uses for that OS and the one older
 * clients see in the flat downloadUrl/sha256 fields.
 */
const LAUNCHER_FORMATS = {
    windows: {
        primary: 'nsis',
        formats: {
            nsis: { ext: 'exe', label: 'Windows Installer', hint: 'Windows 10 and 11, 64-bit' },
        },
    },
    macos: {
        primary: 'dmg',
        formats: {
            dmg: { ext: 'dmg', label: 'macOS Disk Image', hint: 'macOS 12+, Intel and Apple silicon' },
        },
    },
    linux: {
        primary: 'appimage',
        formats: {
            appimage: { ext: 'AppImage', label: 'AppImage', hint: 'Runs on any distribution, no install needed' },
            deb: { ext: 'deb', label: 'Debian package', hint: 'Debian, Ubuntu, Mint, Pop!_OS' },
            rpm: { ext: 'rpm', label: 'RPM package', hint: 'Fedora, RHEL, openSUSE' },
            flatpak: { ext: 'flatpak', label: 'Flatpak bundle', hint: 'Sandboxed, any distribution with Flatpak' },
        },
    },
};

/**
 * Every installer actually sitting in a platform's folder, identified by what
 * it is rather than by the name someone hoped it would have.
 *
 * Publishing used to mean writing a filename into versions.js and then naming
 * the uploaded file to match exactly: "linux/deb/Breeze-Client-1.0.23.deb".
 * Real build output is named "breeze-client_1.0.23_amd64.deb", so dropping the
 * artifacts in a folder found nothing, and Linux appeared to have only an
 * AppImage because that was the one name the convention happened to match.
 *
 * Now one folder per platform is enough: any file whose extension names a
 * format we support is discovered, and its version and architecture are read
 * from the filename. Format subfolders still work, so nothing already uploaded
 * moves.
 */
const ARCH_PATTERNS = [
    [/\b(x86[_-]?64|amd64|x64)\b/i, 'x86_64'],
    [/\b(aarch64|arm64)\b/i, 'aarch64'],
    [/\b(universal)\b/i, 'universal'],
    [/\b(i686|i386|x86)\b/i, 'i686'],
];
function describeArtifactName(fileName, meta) {
    const stem = fileName.slice(0, fileName.length - (meta.ext.length + 1));
    // A pre-release suffix is a word: "1.0.7-beta.1". An rpm's release number
    // and architecture are not: "breeze-client-1.0.23-1.x86_64" is version
    // 1.0.23, and a greedy match read it as "1.0.23-1.x86".
    const version =
        (stem.match(/(\d+\.\d+\.\d+(?:[.-](?:alpha|beta|rc|pre|dev)[0-9A-Za-z.]*)?)/i) || [])[1] || null;
    const arch = (ARCH_PATTERNS.find(([re]) => re.test(stem)) || [])[1] || null;
    return { version, arch };
}
const artifactScanCache = new Map();
function discoverPlatformArtifacts(os) {
    const spec = LAUNCHER_FORMATS[os];
    if (!spec) return {};
    const platformDir = path.resolve(LAUNCHER_VERSION_DIR, os);
    // Cheap staleness check: the newest mtime across the folders we read.
    let stamp = '';
    const dirs = [[platformDir, os]];
    for (const formatId of Object.keys(spec.formats)) {
        dirs.push([path.resolve(platformDir, formatId), `${os}/${formatId}`]);
    }
    for (const [dir] of dirs) {
        try { stamp += `${dir}:${fs.statSync(dir).mtimeMs};`; } catch { /* absent is fine */ }
    }
    const cached = artifactScanCache.get(os);
    if (cached && cached.stamp === stamp) return cached.found;

    const found = {};
    for (const [dir, relPrefix] of dirs) {
        let entries = [];
        try { entries = fs.readdirSync(dir); } catch { continue; }
        for (const name of entries) {
            if (name.startsWith('.') || /^readme/i.test(name)) continue;
            const lower = name.toLowerCase();
            // Longest extension first, so ".tar.gz" style names cannot be
            // claimed by a shorter one.
            const match = Object.entries(spec.formats)
                .filter(([, meta]) => lower.endsWith(`.${meta.ext.toLowerCase()}`))
                .sort((a, b) => b[1].ext.length - a[1].ext.length)[0];
            if (!match) continue;
            const [formatId, meta] = match;
            const abs = path.join(dir, name);
            let st;
            try { st = fs.statSync(abs); } catch { continue; }
            if (!st.isFile()) continue;
            const { version, arch } = describeArtifactName(name, meta);
            (found[formatId] ||= []).push({
                file: `${relPrefix}/${name}`,
                name,
                version,
                arch,
                size: st.size,
                mtime: st.mtimeMs,
            });
        }
    }
    // Newest version first, then newest file, so "the latest" is unambiguous.
    for (const list of Object.values(found)) {
        list.sort((a, b) => cmpVersions(b.version || '0.0.0', a.version || '0.0.0') || b.mtime - a.mtime);
    }
    if (artifactScanCache.size >= 8) artifactScanCache.delete(artifactScanCache.keys().next().value);
    artifactScanCache.set(os, { stamp, found });
    return found;
}

/** Resolve one installer format to a concrete published file, if it exists. */
/**
 * SHA-256 of a published artifact, read from the file itself.
 *
 * The manifest used to publish whatever hash a human typed into versions.js or
 * an environment variable. The 2026-09-15 audit found the advertised top-level
 * hash belonged to the 1.0.10 installer while the file on offer was 1.0.12, so
 * any client that checked would have rejected a perfectly good download. A hash
 * the API computes from the bytes it is about to serve cannot drift.
 *
 * Hashing is chunked so a 100 MB AppImage never lands in memory at once, and
 * cached on (path, size, mtime) so repeated manifest builds cost nothing.
 */
const artifactHashCache = new Map();
function artifactSha256(absolutePath) {
    let stat;
    try {
        stat = fs.statSync(absolutePath);
    } catch {
        return null;
    }
    const key = `${absolutePath}:${stat.size}:${stat.mtimeMs}`;
    const cached = artifactHashCache.get(key);
    if (cached) return cached;

    let fd;
    try {
        fd = fs.openSync(absolutePath, 'r');
        const hash = nodeCrypto.createHash('sha256');
        const buffer = Buffer.allocUnsafe(1024 * 1024);
        let read = 0;
        while ((read = fs.readSync(fd, buffer, 0, buffer.length, null)) > 0) {
            hash.update(buffer.subarray(0, read));
        }
        const digest = hash.digest('hex');
        // Bounded so a folder full of old releases cannot grow this forever.
        if (artifactHashCache.size >= 64) {
            artifactHashCache.delete(artifactHashCache.keys().next().value);
        }
        artifactHashCache.set(key, digest);
        return digest;
    } catch {
        return null;
    } finally {
        if (fd !== undefined) {
            try {
                fs.closeSync(fd);
            } catch {
                /* already gone */
            }
        }
    }
}

function resolveLauncherFormat(req, os, formatId, meta, declared, version, releasedAt) {
    const envKey = `LATEST_LAUNCHER_${os.toUpperCase()}_${formatId.toUpperCase()}`;
    // Explicit declaration wins, then env override, then the naming convention.
    // The convention lets a new format be published by dropping the file in the
    // right folder with no manifest edit at all.
    const conventional =
        os === 'linux'
            ? `linux/${formatId}/Breeze-Client-${version}.${meta.ext}`
            : `${os}/Breeze-Client-${version}.${meta.ext}`;
    // Whatever is really in the folder, preferring this release's version.
    const discovered = discoverPlatformArtifacts(os)[formatId] || [];
    const forThisVersion = discovered.filter((a) => a.version === version);
    const candidates = [
        declared.file,
        declared.fileName,
        process.env[`${envKey}_FILE`],
        conventional,
        // Older layout: Linux artifacts sat directly in linux/ with no format
        // subfolder. Still honoured so existing uploads keep working.
        os === 'linux' ? `linux/Breeze-Client-${version}.${meta.ext}` : null,
        // Then anything discovered: this version first, then the newest build
        // of this format, so an artifact named the way the build system names
        // it is published without anyone editing a manifest.
        ...forThisVersion.map((a) => a.file),
        ...discovered.filter((a) => a.version !== version).map((a) => a.file),
    ].filter(Boolean);

    for (const candidate of candidates) {
        const file = safeArtifactPath(candidate);
        if (!file) continue;
        const abs = path.resolve(LAUNCHER_VERSION_DIR, ...file.split('/'));
        const rel = path.relative(path.resolve(LAUNCHER_VERSION_DIR), abs);
        if (rel.startsWith('..') || !fs.existsSync(abs)) continue;
        let size = null;
        try { size = fs.statSync(abs).size; } catch { /* size is cosmetic */ }
        // What the file itself says, when the folder scan found it: a build
        // named breeze-client_1.0.23_amd64.deb knows its own version and arch.
        const scanned = discovered.find((a) => a.file === file);
        return {
            format: formatId,
            ext: meta.ext,
            label: meta.label,
            hint: meta.hint,
            arch: scanned?.arch || PLATFORM_META[os]?.arch || null,
            version: scanned?.version || version,
            releasedAt: declared.releasedAt || releasedAt || null,
            downloadUrl: artifactUrl(req, 'launcher', file),
            fileName: file,
            // Computed from the file on disk, so it always describes the bytes
            // this URL serves. A declared hash is only a fallback for the case
            // where the file cannot be read.
            sha256: artifactSha256(abs) || declared.sha256 || process.env[`${envKey}_SHA256`] || null,
            available: true,
            size,
        };
    }

    // Nothing published yet. Report it so the website can say "coming soon"
    // rather than offering a link that 404s.
    return {
        format: formatId,
        ext: meta.ext,
        label: meta.label,
        hint: meta.hint,
        version,
        releasedAt: declared.releasedAt || releasedAt || null,
        downloadUrl: null,
        fileName: null,
        sha256: null,
        available: false,
        size: null,
    };
}

function buildLauncherPlatforms(req, launcherSection = {}, latestVersion, releasedAt) {
    const declared = launcherSection.platforms || {};
    const out = {};
    for (const os of ['windows', 'linux', 'macos']) {
        const p = declared[os] || {};
        const spec = LAUNCHER_FORMATS[os];
        const version = p.version || latestVersion;

        const formats = {};
        for (const [formatId, meta] of Object.entries(spec.formats)) {
            // A format may be declared either nested under platforms[os].formats
            // or, for the primary format, directly on platforms[os] (the old shape).
            const perFormat =
                (p.formats && p.formats[formatId]) || (formatId === spec.primary ? p : {});
            formats[formatId] = resolveLauncherFormat(req, os, formatId, meta, perFormat, version, releasedAt);
        }

        // Flat fields describe the primary format, so clients written against
        // the previous manifest shape keep working unchanged.
        const primary = formats[spec.primary];
        const anyAvailable = Object.values(formats).some((f) => f.available);
        out[os] = {
            ...PLATFORM_META[os],
            version,
            // Every other flat field describes the primary format, so the
            // architecture has to as well. Leaving the static default here made
            // one .exe read as "x64" at this level and "x86_64" inside formats,
            // which is the same file described two ways.
            arch: primary.arch || PLATFORM_META[os]?.arch || null,
            releasedAt: primary.releasedAt,
            downloadUrl: primary.downloadUrl,
            fileName: primary.fileName,
            sha256: primary.sha256,
            available: primary.available,
            size: primary.size,
            // New: every installer format for this OS, independently published.
            primaryFormat: spec.primary,
            formats,
            anyFormatAvailable: anyAvailable,
        };
    }
    return out;
}
function buildVersionsManifest(req) {
    const raw = readVersionsManifestFile();
    // No placeholder version. A missing manifest used to make the API announce
    // "0.1.0-beta", which is not a build anyone can install: clients then
    // compared against a version that does not exist. With null the manifest
    // says "nothing published", which is the truth in that situation.
    const launcher = normalizeArtifact(req, 'launcher', raw.launcher, 'LATEST_LAUNCHER', {
        latestVersion: null,
        changelog: '',
        downloadUrl: `${FRONTEND_URL}/downloads/latest`,
    });
    const mod = normalizeArtifact(req, 'mod', raw.mod || raw.breezeMod, 'LATEST_MOD', {
        latestVersion: null,
        downloadUrl: `${FRONTEND_URL}/downloads/mod/latest`,
    });
    // Attach per-OS builds so the launcher/website can pick the right installer.
    launcher.platforms = buildLauncherPlatforms(
        req,
        raw.launcher,
        launcher.latestVersion,
        launcher.releasedAt,
    );
    return {
        apiBaseUrl: getRequestBaseUrl(req),
        manifestSource: fs.existsSync(VERSIONS_MANIFEST_PATH)
            ? VERSIONS_MANIFEST_PATH
            : 'environment',
        launcher,
        mod,
        latestLauncher: launcher,
        latestMod: mod,
        generatedAt: new Date().toISOString(),
    };
}
// Every token this API issues is HS256 (jwt.sign with a string secret). Saying
// so explicitly stops the verifier from ever being talked into honouring a
// different algorithm by the token's own header, which is the classic JWT
// confusion attack. Without this the header chooses, and the header is
// attacker-controlled.
const JWT_VERIFY_OPTS = Object.freeze({ algorithms: ['HS256'] });

/**
 * Pull the bearer token out of an Authorization header.
 *
 * A bare token with no "Bearer " prefix is still accepted, because the previous
 * `header.split(' ')[1]` tolerated enough shapes that some client may rely on
 * it, and tightening that is not what this change is for.
 */
function bearerToken(header) {
    if (!header) return null;
    const parts = String(header).trim().split(/\s+/);
    // A lone "Bearer" is a scheme with no credential, not a credential.
    if (parts.length === 1) return /^bearer$/i.test(parts[0]) ? null : parts[0] || null;
    return /^bearer$/i.test(parts[0]) ? parts[1] || null : null;
}

const requireAuth = (req, res, next) => {
    const token = bearerToken(req.headers.authorization);
    if (!token) return fail(res, 'Authentication required', 401);
    jwt.verify(token, JWT_SECRET, JWT_VERIFY_OPTS, (err, payload) => {
        if (err) return fail(res, 'Invalid or expired token', 403);
        // Only a sign-in token may act for an account, and a sign-in token is the
        // one kind this API issues with no audience. Everything else is scoped to
        // a single job: a game-session token proves identity to the mod endpoints
        // from inside a running Minecraft, and a download token names one artifact
        // for one person and travels in a URL, so it ends up in browser history,
        // proxy logs and referrers.
        //
        // This used to name the audiences it refused. That let the download token
        // through, because it was added later and nobody came back here, and a
        // link handed out for one file was accepted as a full bearer token:
        // /admin/users, /admin/feature-flags and /auth/game-session all answered
        // 200 to it. Listing what is allowed instead means the next scoped token
        // is refused by default rather than by remembering.
        if (payload?.aud !== undefined) {
            return fail(res, 'This token cannot be used for account operations', 403);
        }
        req.user = payload;
        next();
    });
};
const optionalAuth = (req, res, next) => {
    const token = bearerToken(req.headers.authorization);
    if (!token) return next();
    jwt.verify(token, JWT_SECRET, JWT_VERIFY_OPTS, (err, payload) => {
        if (!err) req.user = payload;
        next();
    });
};

/**
 * Constant-time string comparison.
 *
 * A plain `===` on a secret leaks its contents through timing: it returns as
 * soon as two bytes differ, so an attacker can recover the secret one character
 * at a time by measuring response latency.
 */
function safeEqual(a, b) {
    const bufA = Buffer.from(String(a ?? ''), 'utf8');
    const bufB = Buffer.from(String(b ?? ''), 'utf8');
    if (bufA.length !== bufB.length) return false;
    return nodeCrypto.timingSafeEqual(bufA, bufB);
}

/**
 * Gate for the uuid-addressed mod endpoints.
 *
 * Accepts either proof the caller holds the shared mod secret, or a Breeze
 * bearer token whose uuid matches the one in the path. Returns true when the
 * request may proceed. See the BREEZE_MOD_SECRET notes at the top of the file
 * for the staged-rollout story: with nothing configured this returns true, which
 * is the pre-existing behaviour, so deploying this file changes no client.
 */
function modCallerIdentity(req, pathUuid) {
    if (MOD_SHARED_SECRET) {
        const presented = req.get('x-breeze-mod-key');
        if (presented && safeEqual(presented, MOD_SHARED_SECRET)) {
            return { allowed: true, reason: 'shared-secret' };
        }
    }
    const token = bearerToken(req.headers.authorization);
    if (!token) {
        // Nothing was presented at all. That is a missing credential (401),
        // not a rejected one (403), and clients tell the two apart.
        return { allowed: !MOD_AUTH_REQUIRED, reason: 'no-credential', status: 401 };
    }
    let payload;
    try {
        payload = jwt.verify(token, JWT_SECRET, JWT_VERIFY_OPTS);
    } catch {
        return { allowed: !MOD_AUTH_REQUIRED, reason: 'invalid-token', status: 401 };
    }
    const claimed = breezeDashUuid(payload?.uuid);
    const wanted = breezeDashUuid(pathUuid);
    // A token only ever speaks for its own player. Staff included: this is an
    // identity check, not a permission check, so there is no role that lets one
    // account read another account's messages through this route.
    if (claimed && wanted && claimed === wanted) {
        return { allowed: true, reason: payload?.aud === GAME_TOKEN_AUDIENCE ? 'game-token' : 'account-token', uuid: claimed };
    }
    return { allowed: !MOD_AUTH_REQUIRED, reason: 'uuid-mismatch', status: 403 };
}

/** Back-compat boolean wrapper for the routes that check inline. */
function modCallerAllowed(req, pathUuid) {
    return modCallerIdentity(req, pathUuid).allowed;
}

/**
 * Requires any valid Breeze token, without tying it to a particular player.
 *
 * For the routes that return a list covering everybody rather than data about
 * one player: who is online, and the tag roster. Those were readable by anyone,
 * which made them a free roster of every Breeze player's UUID and username to
 * anyone who asked. They are not private to one account, so the self-scoped
 * check does not apply, but they should not be handed to strangers either.
 */
function requireAnyBreezeToken(req, res, next) {
    if (!MOD_AUTH_REQUIRED) return next();
    const token = bearerToken(req.headers.authorization);
    if (!token) {
        res.set('WWW-Authenticate', 'Bearer');
        return fail(res, 'Authentication required', 401);
    }
    try {
        req.breezeToken = jwt.verify(token, JWT_SECRET, JWT_VERIFY_OPTS);
        return next();
    } catch {
        return fail(res, 'Invalid or expired token', 403);
    }
}

/**
 * Express middleware form, for routes with :modUuid.
 *
 * Refusals are logged with the reason but never with the token, so an operator
 * can see who is being turned away without the log becoming a credential store.
 */
function requireModIdentity(req, res, next) {
    const verdict = modCallerIdentity(req, req.params.modUuid);
    if (verdict.allowed) {
        if (!MOD_AUTH_REQUIRED && verdict.reason !== 'shared-secret' && verdict.status) {
            log.warn('BreezeMod', `Unauthenticated ${req.method} ${req.path} allowed: BREEZE_MOD_AUTH_REQUIRED is off`);
        }
        req.modUuid = verdict.uuid || breezeDashUuid(req.params.modUuid);
        return next();
    }
    log.warn('BreezeMod', `Refused ${req.method} ${req.path} (${verdict.reason})`);
    const status = verdict.status || 403;
    return res
        .status(status)
        .json({ success: false, error: status === 401 ? 'Authentication required' : 'This token cannot act for that player' });
}
/**
 * A Breeze token for exactly the player in the path, and nothing less.
 *
 * For the mod routes that change what an account owns or wears. Unlike
 * requireModIdentity it has no fallbacks: the shared mod secret names the mod,
 * not a player, and BREEZE_MOD_AUTH_REQUIRED=false is a staged-rollout escape
 * hatch, so neither is enough here. Only a sign-in token or a game-session
 * token is accepted; a token scoped to anything else (a download link) is not.
 * req.modUuid is the uuid exactly as the token carries it, the same value the
 * account routes use, so both write the same rows.
 */
function requirePlayerToken(req, res, next) {
    const token = bearerToken(req.headers.authorization);
    if (!token) {
        res.set('WWW-Authenticate', 'Bearer');
        return fail(res, 'Authentication required', 401);
    }
    let payload;
    try {
        payload = jwt.verify(token, JWT_SECRET, JWT_VERIFY_OPTS);
    } catch {
        return fail(res, 'Invalid or expired token', 403);
    }
    const scoped = payload?.aud !== undefined && payload.aud !== GAME_TOKEN_AUDIENCE;
    const claimed = breezeDashUuid(payload?.uuid);
    const wanted = breezeDashUuid(req.params.modUuid);
    if (scoped || !claimed || !wanted || claimed !== wanted) {
        log.warn('BreezeMod', `Refused ${req.method} ${req.path} (${scoped ? 'scoped-token' : 'uuid-mismatch'})`);
        return fail(res, 'This token cannot act for that player', 403);
    }
    req.modUuid = payload.uuid;
    return next();
}

/**
 * Issue a short-lived identity token for a running game instance.
 *
 * The launcher calls this with its own account token just before starting
 * Minecraft, and hands the result to the Breeze mod. The mod then proves which
 * player it is on every call, so no endpoint has to trust a UUID in a URL.
 *
 * The token is deliberately weak on purpose: it expires in hours, it carries
 * the "breeze-game" audience so requireAuth refuses it, and the worst an
 * attacker who extracts one from a game process can do is act as that player
 * in the mod's own features until it expires.
 */
app.post('/auth/game-session', authLimiter, requireAuth, (req, res) => {
    const CTX = 'Auth/GameSession';
    try {
        const token = jwt.sign(
            {
                uuid: req.user.uuid,
                username: req.user.username,
                role: req.user.role,
                aud: GAME_TOKEN_AUDIENCE,
            },
            JWT_SECRET,
            { expiresIn: GAME_TOKEN_TTL, algorithm: 'HS256' },
        );
        const decoded = jwt.decode(token);
        // Never log the token itself, only who it was issued for.
        log.info(CTX, `Issued a game session token for ${req.user.username}`);
        return ok(res, {
            token,
            audience: GAME_TOKEN_AUDIENCE,
            expiresAt: decoded?.exp ? new Date(decoded.exp * 1000).toISOString() : null,
        });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not issue a game session token', 500);
    }
});

const requireRole =
    (...allowed) =>
    async (req, res, next) => {
        if (!req.user) return fail(res, 'Authentication required', 401);
        // JWT role claims live for 30 days, so the claim can be stale either
        // way: a player promoted in the DB would stay locked out, and a
        // creator taken back to user would keep test builds, uploads and
        // earnings until the token ran out. The live DB role decides; an
        // account the DB cannot confirm is refused.
        let dbUser = null;
        try {
            dbUser = await dbGetUser(req.user.uuid);
        } catch {
            /* refused below */
        }
        if (dbUser && allowed.includes(dbUser.role)) {
            req.user.role = dbUser.role;
            return next();
        }
        return fail(res, `Requires one of: ${allowed.join(', ')}`, 403);
    };
const CAPE_SIZES = [
    {
        w: 64,
        h: 32,
    },
    {
        w: 128,
        h: 64,
    },
    {
        w: 256,
        h: 128,
    },
    {
        w: 512,
        h: 256,
    },
    {
        w: 1024,
        h: 512,
    },
    {
        w: 2048,
        h: 1024,
    },
];
function pickCapeSize(srcW, srcH) {
    const longSide = Math.max(srcW, (srcH || 0) * 2);
    for (const s of CAPE_SIZES) {
        if (s.w >= longSide) return s;
    }
    return CAPE_SIZES[CAPE_SIZES.length - 1];
}
async function fitCapeBuffer(srcBuffer) {
    const img = sharp(srcBuffer);
    const meta = await img.metadata();
    const sw = meta.width || 64;
    const sh = meta.height || 32;
    const target = pickCapeSize(sw, sh);
    const aspect = sw / sh;
    const isCapeAspect = Math.abs(aspect - 2.0) < 0.05;
    let pipeline = sharp(srcBuffer).png();
    if (isCapeAspect) {
        pipeline = pipeline.resize(target.w, target.h, {
            fit: 'fill',
            kernel: 'lanczos3',
        });
    } else {
        const hasAlpha = !!meta.hasAlpha;
        const bg = hasAlpha
            ? {
                  r: 0,
                  g: 0,
                  b: 0,
                  alpha: 0,
              }
            : {
                  r: 0,
                  g: 0,
                  b: 0,
                  alpha: 1,
              };
        pipeline = pipeline.resize(target.w, target.h, {
            fit: 'contain',
            background: bg,
            kernel: 'lanczos3',
        });
    }
    const buffer = await pipeline.toBuffer();
    return {
        buffer,
        width: target.w,
        height: target.h,
    };
}
async function dbGetUser(uuid) {
    const { data, error } = await supabase.from('users').select('*').eq('uuid', uuid).single();
    if (error) {
        log.error('DB', 'fetchUser failed', {
            uuid,
            msg: error.message,
        });
        return null;
    }
    return data;
}

// ─── Creator Passes ──────────────────────────────────────────────────────────
// A gold test currency that spends like Wind Charges but never generates
// earnings, commission or payouts. Owners, admins, developers and creators hold passes;
// nobody else does. Keep this list as the single source of truth so every
// gate (API, launcher, admin dashboard) agrees on who qualifies.
const PASS_ROLES = Object.freeze([ROLES.OWNER, ROLES.ADMIN, ROLES.DEVELOPER, ROLES.CREATOR]);
const CREATOR_PASS_GRANT = parseInt(process.env.CREATOR_PASS_GRANT ?? '6400', 10);
const hasCreatorPasses = (role) => PASS_ROLES.includes(role);

/**
 * Top a user's Creator Pass balance up to the standard grant when they enter a
 * pass-holding role, and zero it out when they leave one. Never lowers a
 * balance that is already above the grant, and never re-grants passes the user
 * has legitimately spent while staying in the same role tier.
 * Best-effort: an un-migrated DB (no creator_passes column) must not break the
 * role change itself.
 */
async function syncCreatorPasses(uuid, nextRole, previousRole = null) {
    const nowEligible = hasCreatorPasses(nextRole);
    const wasEligible = previousRole ? hasCreatorPasses(previousRole) : false;
    if (nowEligible === wasEligible) return;
    const passes = nowEligible ? CREATOR_PASS_GRANT : 0;
    const { error } = await supabase.from('users').update({ creator_passes: passes }).eq('uuid', uuid);
    if (error) {
        log.warn('Economy', `creator_passes not synced for ${uuid} (run schema.sql to add the column): ${error.message}`);
        return;
    }
    log.info('Economy', `Creator Passes ${nowEligible ? `granted (${passes})` : 'revoked'} for ${uuid} → ${nextRole}`);
}
function shapeUser(row, equippedCapeId = null) {
    return {
        uuid: row.uuid,
        username: row.username,
        role: row.role ?? ROLES.USER,
        creator_share_percent: row.creator_share_percent ?? null,
        capeUrl: row.cape_url ?? null,
        email: row.email ?? null,
        paypalEmail: row.paypal_email ?? null,
        avatarUrl: row.avatar_url ?? null,
        equippedCapeId: equippedCapeId ?? null,
        lastSeen: row.last_seen ?? null,
    };
}
/**
 * A player as other players may see them.
 *
 * shapeUser carries the account's email and PayPal address. Those belong to the
 * account's own screens and to staff; a friends list, a search result or a gift
 * receipt handed them to anyone holding any valid token.
 */
function publicUser(row, equippedCapeId = null) {
    const { email, paypalEmail, ...rest } = shapeUser(row, equippedCapeId);
    return rest;
}

/**
 * Make a username safe to pass to ilike, which reads its argument as a pattern.
 *
 * "_" is a single-character wildcard and PostgREST also turns "*" into "%". Ten
 * of the usernames in production contain "_", so "bushpig_" matched "bushpigs"
 * as readily as the real account: a friend request or a gift could land on a
 * stranger, and a name that matched two rows came back as "not registered".
 */
const likePattern = (s) => String(s).replace(/([\\%_*])/g, '\\$1');

/** UUIDs are stored here without dashes. Accept either form from a client. */
const undash = (s) => String(s || '').replace(/-/g, '').toLowerCase();
const looksLikeUuid = (s) => /^[0-9a-f]{32}$/.test(undash(s));

/**
 * Find the player someone named.
 *
 * Exact name first, case-insensitively. If that finds nobody, ask Mojang and
 * look the player up by UUID: a player who changed their Minecraft name keeps
 * their UUID, and our copy of the name is only refreshed when they next sign in.
 * Whether they are online has nothing to do with any of this.
 *
 * Returns { user, reason } where reason is 'ok', 'unknown' (no such Breeze
 * account), 'lookup_failed' (Mojang unreachable) or 'db' (our database failed).
 * The last two must never be reported to a player as "not registered".
 */
async function findUserByName(name) {
    const wanted = String(name || '').trim();
    if (!wanted) return { user: null, reason: 'unknown' };

    const { data, error } = await supabase
        .from('users')
        .select('*')
        .ilike('username', likePattern(wanted))
        .limit(5);
    if (error) return { user: null, reason: 'db', error };
    // Verified in JS as well: whatever the pattern matched, only an exact name
    // is the person who was asked for.
    const exact = (data ?? []).find((u) => String(u.username).toLowerCase() === wanted.toLowerCase());
    if (exact) return { user: exact, reason: 'ok' };

    let profile = null;
    try {
        profile = await cachedMojangProfile(wanted);
    } catch {
        return { user: null, reason: 'lookup_failed' };
    }
    if (!profile) return { user: null, reason: 'unknown' };

    const { data: byUuid, error: uuidErr } = await supabase
        .from('users')
        .select('*')
        .eq('uuid', undash(profile.uuid))
        .maybeSingle();
    if (uuidErr) return { user: null, reason: 'db', error: uuidErr };
    if (!byUuid) return { user: null, reason: 'unknown' };
    if (byUuid.username !== profile.name) {
        await supabase.from('users').update({ username: profile.name }).eq('uuid', byUuid.uuid);
        byUuid.username = profile.name;
    }
    return { user: byUuid, reason: 'ok' };
}

/** The same, from whichever of uuid or username a client sent. */
async function findUserByNameOrUuid({ uuid, username }) {
    const id = undash(uuid);
    if (id && looksLikeUuid(id)) {
        const { data, error } = await supabase.from('users').select('*').eq('uuid', id).maybeSingle();
        if (error) return { user: null, reason: 'db', error };
        if (data) return { user: data, reason: 'ok' };
    }
    return findUserByName(username);
}

/** What to tell a player when a lookup found nobody. */
function lookupFailure(res, reason) {
    if (reason === 'db') return fail(res, 'Player lookup is not ready yet, try again in a moment', 503);
    if (reason === 'lookup_failed')
        return fail(res, 'Could not check that username right now. Check your connection and try again.', 503);
    return fail(res, 'This user has not registered with Breeze Client yet.', 404);
}

const PRESENCE_ONLINE_WINDOW_MINUTES = 5;
function calcNetRevenue(grossUsd) {
    const fee = grossUsd * (PAYPAL_FEE_PERCENT / 100) + PAYPAL_FEE_FIXED;
    return Math.max(0, parseFloat((grossUsd - fee).toFixed(4)));
}
function calcEarningsSplit(netUsd, hasCreator, creatorSharePercent = null) {
    const creatorPct = hasCreator
        ? creatorSharePercent !== null && creatorSharePercent !== undefined
            ? parseFloat(creatorSharePercent)
            : CREATOR_SHARE_PERCENT
        : 0;
    const creator = parseFloat(((netUsd * creatorPct) / 100).toFixed(4));
    const remaining = parseFloat((netUsd - creator).toFixed(4));
    const coowner = parseFloat(((remaining * COOWNER_SHARE_PERCENT) / 100).toFixed(4));
    const developerOne = parseFloat(((remaining * DEVELOPER_ONE_SHARE_PERCENT) / 100).toFixed(4));
    const developerTwo = parseFloat(((remaining * DEVELOPER_TWO_SHARE_PERCENT) / 100).toFixed(4));
    const owner = parseFloat((remaining - coowner - developerOne - developerTwo).toFixed(4));
    return {
        creator,
        coowner,
        developerOne,
        developerTwo,
        owner: Math.max(0, owner),
    };
}
async function paypalGetAccessToken() {
    if (!PAYPAL_CLIENT_ID || !PAYPAL_CLIENT_SECRET)
        throw new Error('PayPal credentials not configured');
    const resp = await axios.post(
        `${PAYPAL_API_BASE}/v1/oauth2/token`,
        'grant_type=client_credentials',
        {
            auth: {
                username: PAYPAL_CLIENT_ID,
                password: PAYPAL_CLIENT_SECRET,
            },
            headers: {
                'Content-Type': 'application/x-www-form-urlencoded',
            },
            timeout: 8000,
        },
    );
    return resp.data.access_token;
}
async function paypalCreateOrder(amountUsd, orderId, description) {
    const accessToken = await paypalGetAccessToken();
    const resp = await axios.post(
        `${PAYPAL_API_BASE}/v2/checkout/orders`,
        {
            intent: 'CAPTURE',
            purchase_units: [
                {
                    reference_id: orderId,
                    description,
                    amount: {
                        currency_code: 'USD',
                        value: amountUsd.toFixed(2),
                    },
                },
            ],
            application_context: {
                brand_name: 'Breeze Client',
                user_action: 'PAY_NOW',
                return_url: `${FRONTEND_URL}/purchase/success`,
                cancel_url: `${FRONTEND_URL}/purchase/cancel`,
            },
        },
        {
            headers: {
                Authorization: `Bearer ${accessToken}`,
                'Content-Type': 'application/json',
            },
            timeout: 10000,
        },
    );
    const approveUrl = resp.data.links?.find((l) => l.rel === 'approve')?.href ?? null;
    return {
        id: resp.data.id,
        status: resp.data.status,
        approve_url: approveUrl,
    };
}
// An order the buyer approved is only an authorisation: no money moves, and
// PayPal sends no PAYMENT.CAPTURE.COMPLETED (the event that credits Wind
// Charges and grants items) until the merchant captures it. Nothing used to
// call this, so every approved payment stopped there.
//
// The PayPal-Request-Id makes the call idempotent: the webhook and the return
// page may both capture the same order, and PayPal answers the second with
// the first result instead of capturing twice.
//
// Returns the order's status and its capture (id, status, amount), which is
// PayPal's own answer to this server's authenticated call and so is enough
// to deliver the purchase (settleCapturedOrder).
async function paypalCaptureOrder(paypalOrderId) {
    const accessToken = await paypalGetAccessToken();
    try {
        const resp = await axios.post(
            `${PAYPAL_API_BASE}/v2/checkout/orders/${encodeURIComponent(paypalOrderId)}/capture`,
            {},
            {
                headers: {
                    Authorization: `Bearer ${accessToken}`,
                    'Content-Type': 'application/json',
                    'PayPal-Request-Id': `capture-${paypalOrderId}`,
                },
                timeout: 15000,
            },
        );
        const capture = paypalCaptureOf(resp.data);
        // A capture PayPal is holding (PENDING) is answered the same way to
        // every repeat of this request id, so ask for the order's current state.
        if (capture && capture.status !== 'COMPLETED') return paypalGetOrder(paypalOrderId);
        return { status: resp.data?.status ?? 'UNKNOWN', capture };
    } catch (err) {
        const issue = err.response?.data?.details?.[0]?.issue;
        // Captured already (by the other path): read what that capture was.
        if (issue === 'ORDER_ALREADY_CAPTURED') return paypalGetOrder(paypalOrderId);
        // Not approved yet: the buyer has not finished on PayPal's page.
        if (issue === 'ORDER_NOT_APPROVED') return { status: 'NOT_APPROVED', capture: null };
        if (!err.paypalStep) err.paypalStep = 'capture';
        throw err;
    }
}
async function paypalGetOrder(paypalOrderId) {
    const accessToken = await paypalGetAccessToken();
    try {
        const resp = await axios.get(`${PAYPAL_API_BASE}/v2/checkout/orders/${encodeURIComponent(paypalOrderId)}`, {
            headers: { Authorization: `Bearer ${accessToken}` },
            timeout: 10000,
        });
        return { status: resp.data?.status ?? 'UNKNOWN', capture: paypalCaptureOf(resp.data) };
    } catch (err) {
        err.paypalStep = 'read order';
        throw err;
    }
}
/** What PayPal said when a call failed, for the log. No keys or tokens. */
function paypalErrorDetail(err) {
    const data = err.response?.data;
    const detail = data?.details?.[0];
    return {
        msg: err.message,
        status: err.response?.status,
        step: err.paypalStep,
        name: data?.name,
        issue: detail?.issue,
        description: detail?.description,
        debug_id: data?.debug_id,
    };
}
/** The first capture in a PayPal order body, or null before it is captured. */
function paypalCaptureOf(data) {
    const c = data?.purchase_units?.[0]?.payments?.captures?.[0];
    if (!c) return null;
    return {
        id: c.id ?? null,
        status: c.status ?? 'UNKNOWN',
        value: c.amount?.value ?? null,
        currency: c.amount?.currency_code ?? null,
        reason: c.status_details?.reason ?? null,
    };
}
async function paypalVerifyWebhook(headers, rawBody) {
    if (!PAYPAL_WEBHOOK_ID || !PAYPAL_CLIENT_ID || !PAYPAL_CLIENT_SECRET) {
        // This used to return `NODE_ENV === 'development'`, and NODE_ENV
        // defaults to 'development' when the host does not set it. On a
        // production box that forgot the variable, that combination accepted
        // ANY unsigned POST to /payments/webhook as a real PayPal event:
        // free capes, free Wind Charges, fabricated creator earnings.
        //
        // Trusting an unverified payment event is now an explicit, deliberate
        // opt-in that nobody can arrive at by omission.
        if (PAYPAL_ALLOW_UNVERIFIED_WEBHOOKS) {
            log.warn('PayPal', 'Accepting UNVERIFIED webhook, PAYPAL_ALLOW_UNVERIFIED_WEBHOOKS is on (never use in production)');
            return true;
        }
        log.error('PayPal', 'Webhook REJECTED, credentials not configured so the signature cannot be verified');
        return false;
    }
    try {
        const bodyBuffer = Buffer.isBuffer(rawBody)
            ? rawBody
            : Buffer.from(typeof rawBody === 'string' ? rawBody : JSON.stringify(rawBody ?? {}));
        const webhookEvent = JSON.parse(bodyBuffer.toString('utf8'));
        const accessToken = await paypalGetAccessToken();
        const resp = await axios.post(
            `${PAYPAL_API_BASE}/v1/notifications/verify-webhook-signature`,
            {
                auth_algo: headers['paypal-auth-algo'],
                cert_url: headers['paypal-cert-url'],
                client_id: PAYPAL_CLIENT_ID,
                transmission_id: headers['paypal-transmission-id'],
                transmission_sig: headers['paypal-transmission-sig'],
                transmission_time: headers['paypal-transmission-time'],
                webhook_id: PAYPAL_WEBHOOK_ID,
                webhook_event: webhookEvent,
            },
            {
                headers: {
                    Authorization: `Bearer ${accessToken}`,
                    'Content-Type': 'application/json',
                },
                timeout: 8000,
            },
        );
        return resp.data.verification_status === 'SUCCESS';
    } catch (err) {
        // PayPal's reason, so a refused check can be fixed: a 400 is usually a
        // PAYPAL_WEBHOOK_ID from another app or from the other mode (sandbox
        // or live) than this server's credentials.
        const detail = err.response?.data?.details?.[0];
        log.error('PayPal', 'Webhook verification error', {
            msg: err.message,
            name: err.response?.data?.name,
            issue: detail?.issue,
            field: detail?.field,
            description: detail?.description,
            debug_id: err.response?.data?.debug_id,
        });
        return false;
    }
}
async function sendMonthlyEarningsReport(month, year, rows) {
    if (!SMTP_HOST || !REPORT_EMAIL) {
        log.warn('Email', 'SMTP not configured, skipping report email');
        return;
    }
    const total = rows.reduce((acc, r) => acc + r.total_earnings_usd, 0).toFixed(2);
    const tableRows = rows
        .map(
            (r) => `<tr>
          <td style="padding:6px 12px">${r.username}</td>
          <td style="padding:6px 12px">${r.role}</td>
          <td style="padding:6px 12px; text-align:right">$${r.total_earnings_usd.toFixed(2)}</td>
          <td style="padding:6px 12px; text-align:right">${r.transactions}</td>
        </tr>`,
        )
        .join('');
    const html = `
    <html><body style="font-family:sans-serif; color:#222">
      <h2>Breeze Client, Monthly Earnings Report</h2>
      <p><strong>Period:</strong> ${month}/${year}</p>
      <p><strong>Generated:</strong> ${new Date().toISOString()}</p>
      <hr/>
      <table border="0" cellspacing="0" cellpadding="0"
             style="border-collapse:collapse; width:100%; min-width:400px">
        <thead style="background:#f3f3f3">
          <tr>
            <th style="padding:6px 12px; text-align:left">User</th>
            <th style="padding:6px 12px; text-align:left">Role</th>
            <th style="padding:6px 12px; text-align:right">Earnings (USD)</th>
            <th style="padding:6px 12px; text-align:right">Transactions</th>
          </tr>
        </thead>
        <tbody>${tableRows}</tbody>
        <tfoot>
          <tr style="font-weight:bold; background:#eef">
            <td colspan="2" style="padding:6px 12px">TOTAL</td>
            <td style="padding:6px 12px; text-align:right">$${total}</td>
            <td></td>
          </tr>
        </tfoot>
      </table>
      <hr/>
      <p style="font-size:11px; color:#888">
        This is an automated report. Earnings are net of PayPal fees.
        All amounts are pending payout, no transfers have been made automatically.
      </p>
    </body></html>`;
    await mailer.sendMail({
        from: EMAIL_ADMIN,
        to: REPORT_EMAIL,
        subject: `Breeze Earnings Report, ${month}/${year}`,
        html,
    });
    log.info('Email', `Monthly report sent for ${month}/${year}`);
}
function shortCodePrefix(username) {
    return (
        String(username || 'BREEZE')
            .toUpperCase()
            .replace(/[^A-Z0-9]/g, '')
            .slice(0, 6) || 'BREEZE'
    );
}
function calcRewardDiscount(adsWatched) {
    if (adsWatched >= 6) return 20;
    if (adsWatched >= 4) return 10;
    if (adsWatched >= 2) return 5;
    return 0;
}
function safeJson(value) {
    if (!value || typeof value !== 'object') return {};
    return value;
}
async function createNotification(userUuid, type, title, body, data = {}) {
    if (!userUuid) return;
    const { error } = await supabase.from('notifications').insert({
        user_uuid: userUuid,
        type,
        title,
        body,
        data: safeJson(data),
        read_at: null,
        created_at: new Date().toISOString(),
    });
    if (error)
        log.warn('Notifications', 'Insert skipped', {
            msg: error.message,
        });
}
async function getUserEmail(uuid) {
    if (!uuid) return null;
    const { data, error } = await supabase.from('users').select('*').eq('uuid', uuid).maybeSingle();
    if (error) return null;
    return data?.email || data?.paypal_email || null;
}
async function sendBreezeEmail(to, subject, heading, bodyHtml, action = null) {
    if (!SMTP_HOST || !to) return;
    const actionHtml = action?.url
        ? `<a href="${action.url}" style="display:inline-block;margin-top:18px;padding:11px 16px;border-radius:10px;background:#2563eb;color:#fff;text-decoration:none;font-weight:700">${action.label || 'Open Breeze'}</a>`
        : '';
    const html = `<!doctype html><html><body style="margin:0;background:#f5f7fb;font-family:Inter,Segoe UI,Arial,sans-serif;color:#162033">
      <div style="max-width:620px;margin:0 auto;padding:28px 18px">
        <div style="background:#07111f;border-radius:18px;padding:26px;color:#eaf2ff;box-shadow:0 18px 45px rgba(15,23,42,.18)">
          <div style="font-size:13px;letter-spacing:.14em;text-transform:uppercase;color:#8ab8ff;font-weight:800">Breeze Client</div>
          <h1 style="margin:12px 0 10px;font-size:25px;line-height:1.25">${heading}</h1>
          <div style="font-size:15px;line-height:1.7;color:#c8d7ee">${bodyHtml}</div>
          ${actionHtml}
        </div>
        <p style="margin:16px 6px 0;color:#6b7280;font-size:12px;line-height:1.5">This security and account message was sent by Breeze Client. If this was not you, secure your Microsoft account and contact Breeze support.</p>
      </div>
    </body></html>`;
    try {
        await mailer.sendMail({
            from: EMAIL_NOREPLY,
            to,
            subject,
            html,
        });
    } catch (err) {
        log.warn('Email', 'Send failed', {
            to,
            subject,
            msg: err.message,
        });
    }
}
async function expirePromoIfOneUse(promoId) {
    if (!promoId) return;
    const { data: promo } = await supabase
        .from('promo_codes')
        .select('id, usage_limit, times_used')
        .eq('id', promoId)
        .maybeSingle();
    if (promo?.usage_limit === 1 && promo.times_used >= 1) {
        await supabase
            .from('promo_codes')
            .update({
                is_active: false,
            })
            .eq('id', promoId);
    }
}
/** Grants a cape or cosmetic to a user (idempotent). Used by purchases,
 *  the owner bypass, 100%-discount grants, and the payment webhook so all
 *  item types flow through identical ownership logic. */
async function grantItemToUser(userUuid, { capeId = null, cosmeticId = null }) {
    const now = new Date().toISOString();
    if (capeId) {
        const { error } = await supabase.from('user_capes').upsert(
            {
                user_uuid: userUuid,
                cape_id: capeId,
                equipped: false,
                acquired_at: now,
            },
            {
                onConflict: 'user_uuid,cape_id',
                ignoreDuplicates: true,
            },
        );
        if (error) return error;
    }
    if (cosmeticId) {
        const { error } = await supabase.from('user_cosmetics').upsert(
            {
                user_uuid: userUuid,
                cosmetic_id: cosmeticId,
                acquired_at: now,
            },
            {
                onConflict: 'user_uuid,cosmetic_id',
                ignoreDuplicates: true,
            },
        );
        if (error) return error;
    }
    return null;
}
async function maybeSyncContact(uuid, body = {}) {
    const patch = {};
    const email = String(body.email || '')
        .trim()
        .toLowerCase();
    const paypalEmail = String(body.paypalEmail || body.paypal_email || '')
        .trim()
        .toLowerCase();
    const avatarUrl = String(body.avatarUrl || body.avatar_url || '').trim();
    if (email && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email)) patch.email = email;
    if (paypalEmail && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(paypalEmail))
        patch.paypal_email = paypalEmail;
    if (avatarUrl && /^https?:\/\//i.test(avatarUrl)) patch.avatar_url = avatarUrl;
    if (!Object.keys(patch).length) return;
    const { error } = await supabase.from('users').update(patch).eq('uuid', uuid);
    if (error)
        log.warn('Users', 'Contact sync skipped', {
            uuid,
            msg: error.message,
        });
}
/**
 * Shared login core: given a verified Minecraft access token, upsert the user
 * and mint a 30-day Breeze JWT. Used by the launcher (/auth/login) AND the
 * website/admin device-code flow (src/auth-ms.js), so every surface signs in
 * through one identical, auditable path.
 * @returns {{ token, user }} on success, or { error, status } on failure.
 */
async function issueBreezeSessionForMcToken(mcAccessToken, contact = {}) {
    const CTX = 'Auth/Session';
    if (!mcAccessToken) return { error: 'Missing Minecraft access token', status: 400 };
    let profile;
    try {
        const r = await axios.get('https://api.minecraftservices.com/minecraft/profile', {
            headers: { Authorization: `Bearer ${mcAccessToken}` },
            timeout: 8000,
        });
        profile = r.data;
    } catch {
        return { error: 'Invalid or expired Minecraft token', status: 401 };
    }
    const { id: uuid, name: username } = profile;
    if (!uuid || !username) return { error: 'Could not verify Minecraft profile', status: 401 };
    const { data: existing, error: selectErr } = await supabase
        .from('users')
        .select('uuid')
        .eq('uuid', uuid)
        .maybeSingle();
    if (selectErr) {
        log.error(CTX, 'DB select error', { msg: selectErr.message });
        return { error: 'Database error during login', status: 500 };
    }
    if (!existing) {
        const { error: insertErr } = await supabase.from('users').insert({
            uuid,
            username,
            role: ROLES.USER,
            creator_share_percent: null,
            cape_url: null,
        });
        if (insertErr) {
            log.error(CTX, 'Insert error', { msg: insertErr.message });
            return { error: 'Failed to create user profile', status: 500 };
        }
        log.info(CTX, `New user created: ${username} (${uuid})`);
    } else {
        await supabase.from('users').update({ username }).eq('uuid', uuid);
    }
    await maybeSyncContact(uuid, contact);
    const user = await dbGetUser(uuid);
    if (!user) return { error: 'Failed to load user profile', status: 500 };
    const token = jwt.sign(
        { uuid: user.uuid, username: user.username, role: user.role },
        JWT_SECRET,
        { expiresIn: '30d' },
    );
    // Welcome messaging fires exactly once, at account creation.
    //
    // This previously ran on EVERY login: each launcher open sent a "sign-in
    // confirmed" email and created an account notification. With a 3,000 email
    // quota that alone could exhaust the month, and it is also why the
    // notification centre filled with dozens of undismissable sign-in entries.
    // Routine sign-ins are not noteworthy and generate nothing now.
    if (!existing) {
        await createNotification(
            uuid,
            'account',
            'Welcome to Breeze Client',
            'Your Breeze account is ready and linked to your Minecraft profile.',
            { username },
        );
    }

    // The flag is a safeguard against two near-simultaneous first logins both
    // passing the !existing check before either has written a row. Claim it
    // atomically: the update only matches while the flag is still false, so
    // exactly one caller can win.
    if (!existing && user.email && user.email_notifications !== false) {
        let claimed = false;
        try {
            const { data: claim, error: claimErr } = await supabase
                .from('users')
                .update({ welcome_email_sent: true })
                .eq('uuid', uuid)
                .or('welcome_email_sent.is.null,welcome_email_sent.eq.false')
                .select('uuid');
            if (claimErr) {
                // Column missing on an un-migrated database. Fall back to the
                // !existing check alone rather than skipping the email forever.
                claimed = /welcome_email_sent/.test(claimErr.message);
                if (claimed) log.warn(CTX, 'welcome_email_sent column missing, run schema.sql');
            } else {
                claimed = Array.isArray(claim) && claim.length > 0;
            }
        } catch (e) {
            log.warn(CTX, `welcome email claim failed: ${e.message}`);
        }
        if (claimed) {
            await sendBreezeEmail(
                user.email,
                'Welcome to Breeze Client',
                'Welcome to Breeze Client',
                `<p>Hi ${user.username}, your Breeze Client account has been created and linked to your Minecraft profile. Your account is protected by Microsoft authentication.</p>`,
            );
            log.info(CTX, `Welcome email sent (first login) → ${user.username}`);
        }
    }
    log.info(CTX, `Login successful: ${username} (${uuid})`);
    return { token, user: shapeUser(user) };
}
app.post('/auth/login', async (req, res) => {
    try {
        const result = await issueBreezeSessionForMcToken(req.body?.mcAccessToken, req.body || {});
        if (result.error) return fail(res, result.error, result.status || 400);
        return ok(res, { token: result.token, user: result.user });
    } catch (err) {
        log.error('Auth/Login', 'Unexpected error', { msg: err.message });
        return fail(res, 'Internal server error', 500);
    }
});
app.get('/users/me', requireAuth, async (req, res) => {
    const CTX = 'Users/Me';
    try {
        const user = await dbGetUser(req.user.uuid);
        if (!user) return fail(res, 'User not found', 404);
        const { data: equippedRow } = await supabase
            .from('user_capes')
            .select('cape_id')
            .eq('user_uuid', req.user.uuid)
            .eq('equipped', true)
            .maybeSingle();
        return ok(res, {
            user: shapeUser(user, equippedRow?.cape_id),
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get('/users/:uuid', lookupLimiter, optionalAuth, async (req, res) => {
    const CTX = 'Users/Profile';
    try {
        const { uuid } = req.params;
        if (!uuid || uuid.length < 10) return fail(res, 'Invalid UUID');
        const user = await dbGetUser(uuid);
        if (!user) return fail(res, 'User not found', 404);
        const { data: equippedRow } = await supabase
            .from('user_capes')
            .select('cape_id')
            .eq('user_uuid', uuid)
            .eq('equipped', true)
            .maybeSingle();
        // This route is public (optionalAuth), but shapeUser carries the user's
        // email and PayPal address. Minecraft UUIDs are public information, so
        // returning those to any caller handed out a scrapeable list of every
        // Breeze user's contact and payout details. They are kept only for the
        // account's own owner and for staff; everyone else sees the cosmetic
        // profile, which is all a public profile view ever needed.
        const shaped = shapeUser(user, equippedRow?.cape_id);
        const isSelf = req.user?.uuid === user.uuid;
        const isStaff = [ROLES.ADMIN, ROLES.OWNER].includes(req.user?.role);
        if (!isSelf && !isStaff) {
            delete shaped.email;
            delete shaped.paypalEmail;
        }
        return ok(res, {
            user: shaped,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.post('/users/batch', lookupLimiter, async (req, res) => {
    const CTX = 'Users/Batch';
    try {
        const { uuids } = req.body;
        if (!Array.isArray(uuids) || uuids.length === 0)
            return fail(res, 'uuids must be a non-empty array');
        if (uuids.length > 100) return fail(res, 'Maximum 100 UUIDs per request');
        // Only strings that actually look like a Minecraft UUID reach the query.
        // Previously the raw array went straight into .in(), so a caller could
        // pass objects or crafted strings and let PostgREST interpret them.
        const clean = uuids
            .filter((u) => typeof u === 'string')
            .map((u) => breezeUndash(u))
            .filter((u) => /^[0-9a-f]{32}$/.test(u));
        if (!clean.length) return fail(res, 'No valid UUIDs supplied');
        const { data, error } = await supabase
            .from('users')
            .select('uuid, cape_url')
            .in('uuid', clean);
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Database error', 500);
        }
        return ok(res, {
            users: data ?? [],
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.patch('/users/:uuid/role', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'Users/SetRole';
    try {
        const caller = await dbGetUser(req.user.uuid);
        if (!caller || caller.role !== ROLES.OWNER) return fail(res, 'Owner access required', 403);
        const { uuid } = req.params;
        const { role } = req.body;
        if (!Object.values(ROLES).includes(role))
            return fail(res, `Invalid role. Valid values: ${Object.values(ROLES).join(', ')}`);
        const target = await dbGetUser(uuid);
        const { error } = await supabase
            .from('users')
            .update({
                role,
            })
            .eq('uuid', uuid);
        if (error) {
            log.error(CTX, 'Update error', {
                msg: error.message,
            });
            return fail(res, 'Failed to update role', 500);
        }
        // Owners, admins and creators all hold Creator Passes; promoting into
        // (or out of) that group adjusts the balance to match.
        await syncCreatorPasses(uuid, role, target?.role ?? null);
        log.info(CTX, `Role updated: ${uuid} → ${role} (by ${req.user.uuid})`);
        return ok(res, {
            message: `Role updated to ${role}`,
            creator_passes: hasCreatorPasses(role) ? CREATOR_PASS_GRANT : 0,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.patch('/users/:uuid/creator-share', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'Users/SetCreatorShare';
    try {
        const caller = await dbGetUser(req.user.uuid);
        if (!caller || caller.role !== ROLES.OWNER) return fail(res, 'Owner access required', 403);
        const { uuid } = req.params;
        const percent = parseFloat(req.body.creator_share_percent);
        if (isNaN(percent) || percent < 0 || percent > 100)
            return fail(res, 'creator_share_percent must be 0-100');
        const { error } = await supabase
            .from('users')
            .update({
                creator_share_percent: percent,
            })
            .eq('uuid', uuid);
        if (error) {
            log.error(CTX, 'Update error', {
                msg: error.message,
            });
            return fail(res, 'Failed to update creator share', 500);
        }
        log.info(CTX, `Creator share updated: ${uuid} → ${percent}% (by ${req.user.uuid})`);
        return ok(res, {
            message: `Creator share set to ${percent}%`,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});

// Resolve a Minecraft username to its canonical undashed UUID + name via the
// Mojang public API. Returns null for an unknown name, throws on network error.
async function resolveMinecraftProfile(username) {
    try {
        const r = await axios.get(
            `https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`,
            { timeout: 8000 },
        );
        if (r.data && r.data.id) return { uuid: String(r.data.id).replace(/-/g, ''), username: r.data.name };
        return null;
    } catch (e) {
        if (e.response && (e.response.status === 404 || e.response.status === 204)) return null;
        throw e;
    }
}

// Owner: create (or promote) a creator without touching Supabase by hand.
// Enter their Minecraft account + email + PayPal + share%; we resolve the MC
// UUID and upsert the users row with role=creator. It works whether or not the
// person has ever logged in, on their next Microsoft sign-in the existing row
// is matched by UUID and they get creator access immediately.
app.post('/admin/creators', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'Admin/CreateCreator';
    try {
        const b = req.body || {};
        const minecraft = String(b.minecraft_username || b.minecraft || '').trim();
        const rawUuid = String(b.uuid || b.minecraft_uuid || '').trim().replace(/-/g, '').toLowerCase();
        const email = String(b.email || '').trim().toLowerCase();
        const paypal = String(b.paypal_email || '').trim().toLowerCase();
        const displayName = String(b.display_name || '').trim();
        const emailRe = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
        let percent =
            b.creator_share_percent === undefined || b.creator_share_percent === null || b.creator_share_percent === ''
                ? null
                : parseFloat(b.creator_share_percent);

        if (!minecraft && !rawUuid) return fail(res, 'Minecraft username or UUID is required');
        if (rawUuid && !/^[0-9a-f]{32}$/.test(rawUuid))
            return fail(res, 'UUID must be 32 hex characters (dashes optional)');
        if (percent !== null && (isNaN(percent) || percent < 0 || percent > 100))
            return fail(res, 'Revenue share must be between 0 and 100');
        if (email && !emailRe.test(email)) return fail(res, 'Invalid email address');
        if (paypal && !emailRe.test(paypal)) return fail(res, 'Invalid PayPal email address');

        // Prefer an explicitly supplied UUID; otherwise resolve it from the
        // Minecraft username via Mojang.
        let uuid = rawUuid;
        let username = minecraft;
        if (!uuid) {
            let profile;
            try {
                profile = await resolveMinecraftProfile(minecraft);
            } catch (e) {
                log.error(CTX, 'Mojang lookup failed', { msg: e.message });
                return fail(res, 'Could not reach the Minecraft profile service, try again shortly', 502);
            }
            if (!profile) return fail(res, `No Minecraft account found for "${minecraft}"`, 404);
            uuid = profile.uuid;
            username = profile.username;
        } else if (!username) {
            // UUID given without a name: try to fill the name in, but don't fail.
            try {
                const r = await axios.get(`https://sessionserver.mojang.com/session/minecraft/profile/${uuid}`, { timeout: 8000 });
                if (r.data && r.data.name) username = r.data.name;
            } catch {
                username = `player_${uuid.slice(0, 8)}`;
            }
        }
        const core = { role: ROLES.CREATOR, creator_share_percent: percent };
        if (email) core.email = email;
        if (paypal) core.paypal_email = paypal;

        const { data: existing, error: selErr } = await supabase
            .from('users')
            .select('uuid, role')
            .eq('uuid', uuid)
            .maybeSingle();
        if (selErr) {
            log.error(CTX, 'Select error', { msg: selErr.message });
            return fail(res, 'Database error', 500);
        }
        if (existing) {
            const { error } = await supabase.from('users').update({ username, ...core }).eq('uuid', uuid);
            if (error) {
                log.error(CTX, 'Update error', { msg: error.message });
                return fail(res, 'Failed to update creator', 500);
            }
        } else {
            const { error } = await supabase.from('users').insert({ uuid, username, cape_url: null, ...core });
            if (error) {
                log.error(CTX, 'Insert error', { msg: error.message });
                return fail(res, 'Failed to create creator', 500);
            }
        }
        // display_name is an optional/newer column, set it best-effort so a
        // database that has not run the latest schema.sql still succeeds.
        if (displayName) {
            const { error: dnErr } = await supabase.from('users').update({ display_name: displayName }).eq('uuid', uuid);
            if (dnErr) log.warn(CTX, 'display_name not stored (run schema.sql to add the column): ' + dnErr.message);
        }

        // Grant Creator Passes (test-only currency) to a newly created or newly
        // promoted creator. Owners and admins already hold passes, so promoting
        // one of them to creator must not reset the balance they already have.
        await syncCreatorPasses(uuid, ROLES.CREATOR, existing?.role ?? null);

        await createNotification(
            uuid,
            'account',
            'You are now a Breeze creator',
            'An owner has enabled creator access on your account. Sign in to the launcher or admin panel to publish capes and track earnings.',
            { username },
        ).catch(() => {});

        // Welcome email, invite them into the Creator Portal with Microsoft.
        const welcomeTo = email || (await getUserEmail(uuid));
        if (welcomeTo) {
            const portal = `${process.env.ADMIN_URL || 'https://admin.breezeclient.net'}`;
            const sharePct = percent != null ? `${percent}%` : `${CREATOR_SHARE_PERCENT}%`;
            await sendBreezeEmail(
                welcomeTo,
                'Your Breeze creator account is ready',
                'Welcome to the Breeze Creator Portal',
                `<p>Hi ${username},</p>
                 <p>Your Breeze creator account has been created. You can publish capes and cosmetics, track sales, and get paid for what people buy.</p>
                 <p><strong>Getting started</strong></p>
                 <ol>
                   <li>Go to <a href="${portal}">${portal.replace(/^https?:\/\//, '')}</a></li>
                   <li>Sign in with the same <strong>Microsoft account</strong> you use for Minecraft (${username})</li>
                   <li>Add the <strong>PayPal email</strong> your payouts should go to, you can change it any time</li>
                 </ol>
                 <p>You keep <strong>${sharePct}</strong> of every sale. Your dashboard shows your earnings, sales, and payout history.</p>
                 <p>Welcome aboard.</p>`,
            ).catch(() => {});
            log.info(CTX, `Creator welcome email queued → ${welcomeTo}`);
        }

        const creator = await dbGetUser(uuid);
        log.info(CTX, `Creator ${existing ? 'updated' : 'created'}: ${username} (${uuid}) by ${req.user.uuid}`);
        return ok(res, { creator, existed: !!existing, message: `${username} is now a creator` });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});

// Owner/admin: list all creators for the management panel.
app.get('/admin/creators', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const CTX = 'Admin/ListCreators';
    try {
        const { data, error } = await supabase
            .from('users')
            .select('uuid, username, email, paypal_email, role, creator_share_percent')
            .eq('role', ROLES.CREATOR)
            .order('username', { ascending: true });
        if (error) {
            log.error(CTX, 'DB error', { msg: error.message });
            return fail(res, 'Failed to load creators', 500);
        }
        return ok(res, { creators: data || [] });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});

// Any signed-in user updates their OWN contact/profile fields. Creators use this
// to add/edit the PayPal email their payouts are sent to (stored on their user row).
app.patch('/users/me/profile', requireAuth, async (req, res) => {
    const CTX = 'Users/UpdateProfile';
    try {
        const b = req.body || {};
        const patch = {};
        const emailRe = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
        if (b.paypal_email !== undefined || b.paypalEmail !== undefined) {
            const pp = String(b.paypal_email ?? b.paypalEmail ?? '').trim().toLowerCase();
            if (pp && !emailRe.test(pp)) return fail(res, 'Invalid PayPal email address');
            patch.paypal_email = pp || null;
        }
        if (b.email !== undefined) {
            const em = String(b.email).trim().toLowerCase();
            if (em && !emailRe.test(em)) return fail(res, 'Invalid email address');
            patch.email = em || null;
        }
        let displayNameRequested = false;
        if (b.display_name !== undefined) {
            displayNameRequested = true;
            patch.display_name = String(b.display_name).trim().slice(0, 60) || null;
        }
        if (!Object.keys(patch).length) return fail(res, 'Nothing to update');

        let { error } = await supabase.from('users').update(patch).eq('uuid', req.user.uuid);
        // display_name column may be missing on an un-migrated DB, retry without it.
        if (error && displayNameRequested) {
            delete patch.display_name;
            if (Object.keys(patch).length) {
                ({ error } = await supabase.from('users').update(patch).eq('uuid', req.user.uuid));
            } else {
                error = null;
            }
            log.warn(CTX, 'display_name not stored (run schema.sql to add the column)');
        }
        if (error) {
            log.error(CTX, 'Update error', { msg: error.message });
            return fail(res, 'Failed to update profile', 500);
        }
        const user = await dbGetUser(req.user.uuid);
        return ok(res, { user });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});

app.get('/capes', async (req, res) => {
    const CTX = 'Capes/All';
    try {
        const { data, error } = await supabase
            .from('capes')
            .select(
                'id, name, description, price_usd, rarity, image_url, is_limited, is_animated, animation_fps, animation_frames, created_at, creator_id',
            )
            .eq('is_public', true)
            .order('created_at', {
                ascending: false,
            });
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch capes', 500);
        }
        return ok(res, {
            capes: data ?? [],
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get('/capes/owned', requireAuth, async (req, res) => {
    const CTX = 'Capes/Owned';
    try {
        const { data, error } = await supabase
            .from('user_capes')
            .select(
                'cape_id, equipped, acquired_at, cape:capes(id, name, image_url, rarity, price_usd, is_animated, animation_frames)',
            )
            .eq('user_uuid', req.user.uuid);
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch owned capes', 500);
        }
        return ok(res, {
            capes: data ?? [],
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get(
    '/capes/creator',
    requireAuth,
    requireRole(...CREATOR_ACCESS_ROLES),
    async (req, res) => {
        const CTX = 'Capes/Creator';
        try {
            const { data, error } = await supabase
                .from('capes')
                .select('*')
                .eq('creator_id', req.user.uuid)
                .order('created_at', {
                    ascending: false,
                });
            if (error) {
                log.error(CTX, 'DB error', {
                    msg: error.message,
                });
                return fail(res, 'Failed to fetch creator capes', 500);
            }
            return ok(res, {
                capes: data ?? [],
            });
        } catch (err) {
            log.error(CTX, 'Error', {
                msg: err.message,
            });
            return fail(res, 'Server error', 500);
        }
    },
);
app.post('/capes', requireAuth, upload.any(), async (req, res) => {
    const CTX = 'Capes/Upload';
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        if (!hasCreatorAccess(dbUser.role))
            return fail(res, 'Creator access required', 403);
        if (isCreatorTier(dbUser.role)) {
            const { count: existingCount, error: countErr } = await supabase
                .from('capes')
                .select('id', {
                    count: 'exact',
                    head: true,
                })
                .eq('creator_id', req.user.uuid);
            if (countErr) {
                log.error(CTX, 'Creator count query failed', {
                    msg: countErr.message,
                });
                return fail(res, 'Could not verify creator upload slots', 500);
            }
            // Env-configurable; the old hard limit of 2 blocked creators from
            // publishing more capes (which read as "my upload never appeared").
            const CREATOR_CAPE_LIMIT = parseInt(process.env.CREATOR_CAPE_LIMIT ?? '25', 10);
            if ((existingCount ?? 0) >= CREATOR_CAPE_LIMIT) {
                return fail(
                    res,
                    `You've reached your cape limit (${CREATOR_CAPE_LIMIT}). Delete an old cape or ask an owner to raise the limit.`,
                    403,
                );
            }
        }
        const framesFiles = (req.files || []).filter((f) => f.fieldname === 'frames');
        const capeFile = (req.files || []).find((f) => f.fieldname === 'cape');

        // Decide the frames. Three ways in, one way out (an array of frame buffers):
        //   1. Client pre-split frames[] (legacy path) → use as-is.
        //   2. A single `cape` file that is an animated GIF/APNG → auto-split
        //      server-side (creators just upload the GIF, no manual work).
        //   3. A single static image → one frame.
        let frameBuffers = [];
        let isAnimated = false;
        let derivedFps = null;
        if (framesFiles.length >= 2) {
            if (framesFiles.length > capeMedia.MAX_FRAMES)
                return fail(res, `Maximum ${capeMedia.MAX_FRAMES} frames per cape`);
            isAnimated = true;
            frameBuffers = framesFiles.map((f) => f.buffer);
        } else if (capeFile) {
            let media;
            try {
                media = await capeMedia.splitAnimation(capeFile.buffer);
            } catch (splitErr) {
                log.error(CTX, 'Animation split failed', { msg: splitErr.message });
                return fail(res, 'Could not process the uploaded image', 400);
            }
            isAnimated = media.animated;
            frameBuffers = media.frames;
            derivedFps = media.fps || null;
            if (isAnimated)
                log.info(CTX, `Auto-split animated upload → ${frameBuffers.length} frames @ ~${derivedFps}fps`);
        } else {
            return fail(res, 'No image provided');
        }
        if (isAnimated && frameBuffers.length < 2)
            return fail(res, 'Animated capes need at least 2 frames');
        const name = req.body.name?.trim();
        const price_usd = parseFloat(req.body.price_usd ?? '0');
        const rarityMap = {
            free: 'common',
            premium: 'rare',
            event: 'epic',
            limited: 'legendary',
        };
        const rarity = rarityMap[(req.body.rarity || '').toLowerCase()] || 'common';
        const description = req.body.description || null;
        const isPublic = req.body.is_public !== 'false';
        const animFps = isAnimated
            ? req.body.animation_fps
                ? parseInt(req.body.animation_fps, 10)
                : derivedFps || 8
            : null;
        if (!name) return fail(res, 'Cape name is required');
        if (isNaN(price_usd) || price_usd < 0) return fail(res, 'Invalid price_usd');
        const safeSlug = name.toLowerCase().replace(/[^a-z0-9]+/g, '_');
        const stamp = Date.now();
        const baseFolder = `marketplace/${req.user.uuid}_${safeSlug}_${stamp}`;
        let primaryImageUrl = null;
        let animationFramesUrls = null;
        if (isAnimated) {
            animationFramesUrls = [];
            const fitted = [];
            let chosenW = 0,
                chosenH = 0;
            for (const buf of frameBuffers) {
                const r = await fitCapeBuffer(buf);
                fitted.push(r);
                if (r.width > chosenW) {
                    chosenW = r.width;
                    chosenH = r.height;
                }
            }
            for (let i = 0; i < fitted.length; i++) {
                if (fitted[i].width !== chosenW) {
                    const r2 = await sharp(frameBuffers[i])
                        .resize(chosenW, chosenH, {
                            fit: 'fill',
                            kernel: 'lanczos3',
                        })
                        .png()
                        .toBuffer();
                    fitted[i] = {
                        buffer: r2,
                        width: chosenW,
                        height: chosenH,
                    };
                }
            }
            // Cape textures live on the API's own disk (storage/capes) and are
            // served at /assets/..., Supabase stays relational-only.
            for (let i = 0; i < fitted.length; i++) {
                const frameRel = `capes/${baseFolder}/frame_${String(i).padStart(3, '0')}.png`;
                try {
                    breezeAssets.storeAsset(frameRel, fitted[i].buffer);
                } catch (fErr) {
                    log.error(CTX, `Frame ${i} store error`, {
                        msg: fErr.message,
                    });
                    return fail(res, `Failed to store frame ${i + 1}`, 500);
                }
                animationFramesUrls.push(breezeAssets.assetUrl(getRequestBaseUrl(req), frameRel));
            }
            log.info(CTX, `Animated cape: ${fitted.length} frames @ ${chosenW}x${chosenH}`);
            primaryImageUrl = animationFramesUrls[0];
        } else {
            const fitted = await fitCapeBuffer(capeFile.buffer);
            const fileRel = `capes/${baseFolder}.png`;
            try {
                breezeAssets.storeAsset(fileRel, fitted.buffer);
            } catch (uploadErr) {
                log.error(CTX, 'Storage error', {
                    msg: uploadErr.message,
                });
                return fail(res, 'Failed to store cape image', 500);
            }
            primaryImageUrl = breezeAssets.assetUrl(getRequestBaseUrl(req), fileRel);
            log.info(CTX, `Static cape stored @ ${fitted.width}x${fitted.height}`);
        }
        const { data: newCape, error: insertErr } = await supabase
            .from('capes')
            .insert({
                name,
                price_usd,
                creator_id: req.user.uuid,
                image_url: primaryImageUrl,
                is_public: isPublic,
                is_limited: false,
                is_animated: isAnimated,
                rarity,
                description,
                animation_fps: animFps,
                animation_frames: animationFramesUrls,
            })
            .select()
            .single();
        if (insertErr) {
            log.error(CTX, 'DB insert error', {
                msg: insertErr.message,
            });
            return fail(res, 'Failed to create cape listing', 500);
        }
        log.info(CTX, `Cape created: ${newCape.id} "${name}" by ${req.user.uuid}`);
        return ok(
            res,
            {
                cape: newCape,
            },
            201,
        );
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Failed to upload cape', 500);
    }
});
app.patch('/capes/:id', requireAuth, async (req, res) => {
    const CTX = 'Capes/Update';
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        const { data: cape, error: capeErr } = await supabase
            .from('capes')
            .select('creator_id')
            .eq('id', req.params.id)
            .single();
        if (capeErr || !cape) return fail(res, 'Cape not found', 404);
        const isAdmin = [ROLES.ADMIN, ROLES.OWNER].includes(dbUser.role);
        const isCreator = isCreatorTier(dbUser.role) && cape.creator_id === req.user.uuid;
        if (!isAdmin && !isCreator) return fail(res, 'Not authorized to edit this cape', 403);
        const allowed = ['name', 'description', 'price_usd', 'rarity', 'is_public', 'is_limited'];
        const updates = {};
        for (const key of allowed) {
            if (req.body[key] !== undefined) updates[key] = req.body[key];
        }
        const { error } = await supabase.from('capes').update(updates).eq('id', req.params.id);
        if (error) {
            log.error(CTX, 'Update error', {
                msg: error.message,
            });
            return fail(res, 'Failed to update cape', 500);
        }
        log.info(CTX, `Cape updated: ${req.params.id} by ${req.user.uuid}`);
        return ok(res, {
            message: 'Cape updated',
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
// Delete a marketplace cape. Owners/admins can delete any; a creator can delete
// ONLY their own (creator_id === them). This is the ownership guard the admin
// "My Capes" delete button relies on.
app.delete('/capes/:id', requireAuth, async (req, res) => {
    const CTX = 'Capes/Delete';
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        const { data: cape, error: capeErr } = await supabase
            .from('capes')
            .select('id, creator_id')
            .eq('id', req.params.id)
            .single();
        if (capeErr || !cape) return fail(res, 'Cape not found', 404);
        // Only the Owner (any cape) or the cape's own Creator may delete. Admins
        // are intentionally NOT allowed to delete capes.
        const isOwner = dbUser.role === ROLES.OWNER;
        const isOwnerCreator = isCreatorTier(dbUser.role) && cape.creator_id === req.user.uuid;
        if (!isOwner && !isOwnerCreator)
            return fail(res, 'Only the owner or the cape\'s creator can delete it', 403);
        // Remove ownership rows first, then the cape.
        await supabase.from('user_capes').delete().eq('cape_id', req.params.id);
        const { error } = await supabase.from('capes').delete().eq('id', req.params.id);
        if (error) {
            log.error(CTX, 'Delete error', { msg: error.message });
            return fail(res, 'Failed to delete cape', 500);
        }
        log.info(CTX, `Cape deleted: ${req.params.id} by ${req.user.uuid} (${dbUser.role})`);
        return ok(res, { message: 'Cape deleted' });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});
app.post('/capes/equip', requireAuth, async (req, res) => {
    const CTX = 'Capes/Equip';
    try {
        const { cape_id } = req.body;
        if (!cape_id) return fail(res, 'cape_id required');
        const dbUser = await dbGetUser(req.user.uuid);
        const isOwner = dbUser?.role === ROLES.OWNER;
        if (!isOwner) {
            const { data: owned, error } = await supabase
                .from('user_capes')
                .select('cape_id')
                .eq('user_uuid', req.user.uuid)
                .eq('cape_id', cape_id)
                .maybeSingle();
            if (error) return fail(res, 'DB error checking ownership', 500);
            if (!owned) return fail(res, 'You do not own this cape', 403);
        }
        await supabase
            .from('user_capes')
            .update({
                equipped: false,
            })
            .eq('user_uuid', req.user.uuid);
        await supabase
            .from('user_capes')
            .update({
                equipped: true,
            })
            .eq('user_uuid', req.user.uuid)
            .eq('cape_id', cape_id);
        const { data: capeRow } = await supabase
            .from('capes')
            .select('image_url')
            .eq('id', cape_id)
            .single();
        if (capeRow?.image_url) {
            await supabase
                .from('users')
                .update({
                    cape_url: capeRow.image_url,
                })
                .eq('uuid', req.user.uuid);
        }
        log.info(CTX, `Cape equipped: ${cape_id} by ${req.user.uuid}`);
        return ok(res, {
            cape_id,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Failed to equip cape', 500);
    }
});
app.post('/capes/unequip', requireAuth, async (req, res) => {
    const CTX = 'Capes/Unequip';
    try {
        await supabase
            .from('user_capes')
            .update({
                equipped: false,
            })
            .eq('user_uuid', req.user.uuid);
        await supabase
            .from('users')
            .update({
                cape_url: null,
            })
            .eq('uuid', req.user.uuid);
        log.info(CTX, `Cape unequipped by ${req.user.uuid}`);
        return ok(res, {
            cape_id: null,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Failed to unequip cape', 500);
    }
});
app.post('/capes/upload-personal', requireAuth, upload.single('cape'), async (req, res) => {
    const CTX = 'Capes/UploadPersonal';
    try {
        if (!req.file) return fail(res, 'No image provided');
        if (!['image/png', 'image/jpeg', 'image/jpg'].includes(req.file.mimetype))
            return fail(res, 'Only PNG and JPEG images are accepted');
        const fitted = await fitCapeBuffer(req.file.buffer);
        const optimizedBuffer = fitted.buffer;
        log.info(CTX, `Personal cape sized to ${fitted.width}x${fitted.height}`);
        const fileRel = `capes/personal/${req.user.uuid}_cape.png`;
        try {
            breezeAssets.storeAsset(fileRel, optimizedBuffer);
        } catch (uploadErr) {
            log.error(CTX, 'Storage error', {
                msg: uploadErr.message,
            });
            return fail(res, 'Failed to upload cape', 500);
        }
        const capeUrl = breezeAssets.assetUrl(getRequestBaseUrl(req), fileRel);
        const { error: updateErr } = await supabase
            .from('users')
            .update({
                cape_url: capeUrl,
            })
            .eq('uuid', req.user.uuid);
        if (updateErr) {
            log.error(CTX, 'User update error', {
                msg: updateErr.message,
            });
            return fail(res, 'Failed to update user cape', 500);
        }
        log.info(CTX, `Personal cape uploaded for ${req.user.uuid}`);
        return ok(res, {
            cape_url: capeUrl,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Failed to process cape upload', 500);
    }
});
app.delete('/capes/personal', requireAuth, async (req, res) => {
    const CTX = 'Capes/DeletePersonal';
    try {
        breezeAssets.deleteAsset(`capes/personal/${req.user.uuid}_cape.png`);
        // Legacy personal capes may still live in Supabase storage.
        await supabase.storage.from('capes').remove([`personal/${req.user.uuid}_cape.png`]).catch(() => {});
        const { error } = await supabase
            .from('users')
            .update({
                cape_url: null,
            })
            .eq('uuid', req.user.uuid);
        if (error) {
            log.error(CTX, 'Update error', {
                msg: error.message,
            });
            return fail(res, 'Failed to remove cape', 500);
        }
        log.info(CTX, `Personal cape removed for ${req.user.uuid}`);
        return ok(res, {
            message: 'Cape removed',
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Failed to remove cape', 500);
    }
});
const PERSONAL_CAPE_PRICE_USD = parseFloat(process.env.PERSONAL_CAPE_PRICE_USD ?? '20');
app.post('/capes/personal-order', requireAuth, upload.single('cape'), async (req, res) => {
    const CTX = 'Capes/PersonalOrder';
    try {
        if (!req.file) return fail(res, 'No image provided');
        if (!['image/png', 'image/jpeg', 'image/jpg'].includes(req.file.mimetype))
            return fail(res, 'Only PNG and JPEG images are accepted');
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        const fitted = await fitCapeBuffer(req.file.buffer);
        const optimizedBuffer = fitted.buffer;
        if (dbUser.role === ROLES.OWNER) {
            const fileName = `personal/${req.user.uuid}_cape.png`;
            const { error: upErr } = await supabase.storage
                .from('capes')
                .upload(fileName, optimizedBuffer, {
                    contentType: 'image/png',
                    upsert: true,
                });
            if (upErr) {
                log.error(CTX, 'Owner bypass upload failed', {
                    msg: upErr.message,
                });
                return fail(res, 'Failed to upload personal cape', 500);
            }
            const { data: urlData } = supabase.storage.from('capes').getPublicUrl(fileName);
            await supabase
                .from('users')
                .update({
                    cape_url: urlData.publicUrl,
                })
                .eq('uuid', req.user.uuid);
            log.info(CTX, `Owner bypass: personal cape applied for ${req.user.uuid}`);
            return ok(res, {
                granted: true,
                cape_url: urlData.publicUrl,
                message: 'Personal cape applied (owner bypass)',
            });
        }
        const orderId = uuidv4();
        const pendingPath = `personal/pending/${req.user.uuid}/${orderId}.png`;
        const { error: stashErr } = await supabase.storage
            .from('capes')
            .upload(pendingPath, optimizedBuffer, {
                contentType: 'image/png',
                upsert: false,
            });
        if (stashErr) {
            log.error(CTX, 'Pending stash failed', {
                msg: stashErr.message,
            });
            return fail(res, 'Failed to stage personal cape', 500);
        }
        let finalPrice = PERSONAL_CAPE_PRICE_USD;
        let appliedPromoId = null;
        let discountPct = 0;
        const promo_code = req.body?.promo_code;
        if (promo_code) {
            const { data: promo, error: promoErr } = await supabase
                .from('promo_codes')
                .select('id, code, discount_percent, usage_limit, times_used, is_active')
                .eq('code', String(promo_code).toUpperCase().trim())
                .maybeSingle();
            if (promoErr) {
                log.error(CTX, 'Promo lookup failed', {
                    msg: promoErr.message,
                });
                return fail(res, 'Failed to validate promo code', 500);
            }
            if (!promo || !promo.is_active) return fail(res, 'Invalid promo code');
            if (promo.usage_limit !== null && promo.times_used >= promo.usage_limit)
                return fail(res, 'This promo code has reached its usage limit');
            const { data: alreadyUsed } = await supabase
                .from('promo_code_uses')
                .select('id')
                .eq('promo_code_id', promo.id)
                .eq('user_uuid', req.user.uuid)
                .maybeSingle();
            if (alreadyUsed) return fail(res, 'You have already used this promo code');
            discountPct = promo.discount_percent;
            finalPrice = parseFloat((finalPrice * (1 - discountPct / 100)).toFixed(2));
            appliedPromoId = promo.id;
        }
        const { error: orderInsertErr } = await supabase.from('orders').insert({
            id: orderId,
            user_uuid: req.user.uuid,
            cape_id: null,
            gross_amount_usd: finalPrice,
            discount_percent: discountPct,
            promo_code_id: appliedPromoId,
            status: 'pending',
            pending_cape_path: pendingPath,
            order_type: 'personal_cape',
            created_at: new Date().toISOString(),
        });
        if (orderInsertErr) {
            try {
                await supabase.storage.from('capes').remove([pendingPath]);
            } catch {}
            log.error(CTX, 'Order insert failed', {
                msg: orderInsertErr.message,
            });
            return fail(res, 'Failed to create order', 500);
        }
        if (finalPrice <= 0) {
            const liveFileName = `personal/${req.user.uuid}_cape.png`;
            try {
                const { data: blob } = await supabase.storage.from('capes').download(pendingPath);
                if (blob) {
                    const buf = Buffer.from(await blob.arrayBuffer());
                    await supabase.storage.from('capes').upload(liveFileName, buf, {
                        contentType: 'image/png',
                        upsert: true,
                    });
                }
                await supabase.storage.from('capes').remove([pendingPath]);
            } catch (promoteErr) {
                log.error(CTX, 'Promote-on-free failed', {
                    msg: promoteErr.message,
                });
            }
            const { data: urlData } = supabase.storage.from('capes').getPublicUrl(liveFileName);
            await supabase
                .from('users')
                .update({
                    cape_url: urlData.publicUrl,
                })
                .eq('uuid', req.user.uuid);
            await supabase
                .from('orders')
                .update({
                    status: 'completed',
                })
                .eq('id', orderId);
            try {
                await promotePersonalCapeIfAny(orderId);
            } catch (pErr) {
                log.error('Webhook', 'Promote call failed', {
                    msg: pErr.message,
                });
            }
            if (appliedPromoId) {
                await supabase.from('promo_code_uses').insert({
                    promo_code_id: appliedPromoId,
                    user_uuid: req.user.uuid,
                });
                await supabase
                    .rpc('increment_promo_uses', {
                        promo_id: appliedPromoId,
                    })
                    .then(({ error }) => {
                        if (error)
                            log.error(CTX, 'Promo increment failed', {
                                msg: error.message,
                            });
                    });
                await expirePromoIfOneUse(appliedPromoId);
            }
            return ok(res, {
                granted: true,
                cape_url: urlData.publicUrl,
                message: 'Personal cape applied (100% discount)',
            });
        }
        let paypalOrder;
        if (PAYPAL_CLIENT_ID && PAYPAL_CLIENT_SECRET) {
            paypalOrder = await paypalCreateOrder(finalPrice, orderId, 'Breeze Personal Cape');
        } else {
            log.warn(CTX, 'PayPal not configured, returning mock approve URL');
            paypalOrder = {
                id: `MOCK_${Date.now()}`,
                status: 'CREATED',
                approve_url: `https://sandbox.paypal.com/mock?order_id=${orderId}`,
            };
        }
        await supabase
            .from('orders')
            .update({
                paypal_order_id: paypalOrder.id,
            })
            .eq('id', orderId);
        log.info(
            CTX,
            `Personal-cape order created: ${orderId} | $${finalPrice} | user ${req.user.uuid}`,
        );
        return ok(
            res,
            {
                order_id: orderId,
                approve_url: paypalOrder.approve_url,
                amount_usd: finalPrice,
            },
            201,
        );
    } catch (err) {
        log.error(CTX, 'Unexpected error', {
            msg: err.message,
            stack: err.stack,
        });
        return fail(res, 'Failed to create personal-cape order', 500);
    }
});
app.post('/purchases/create-order', requireAuth, async (req, res) => {
    const CTX = 'Purchases/CreateOrder';
    try {
        // Capes and cosmetics share one purchase pipeline: same owner bypass,
        // promo validation, free-grant path, PayPal order, and webhook grant.
        const { cape_id, cosmetic_id, promo_code } = req.body;
        if (!cape_id && !cosmetic_id) return fail(res, 'cape_id or cosmetic_id is required');
        if (cape_id && cosmetic_id)
            return fail(res, 'Provide either cape_id or cosmetic_id, not both');
        const isCosmetic = Boolean(cosmetic_id);
        const itemId = isCosmetic ? cosmetic_id : cape_id;
        const itemLabel = isCosmetic ? 'cosmetic' : 'cape';
        const grantIds = isCosmetic ? { cosmeticId: itemId } : { capeId: itemId };
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        if (dbUser.role === ROLES.OWNER) {
            const grantErr = await grantItemToUser(req.user.uuid, grantIds);
            if (grantErr)
                log.error(CTX, 'Owner grant error', {
                    msg: grantErr.message,
                });
            log.info(CTX, `Owner access granted: ${itemLabel} ${itemId} → ${req.user.uuid}`);
            return ok(res, {
                granted: true,
                message: `${isCosmetic ? 'Cosmetic' : 'Cape'} granted (owner bypass)`,
            });
        }
        const ownershipTable = isCosmetic ? 'user_cosmetics' : 'user_capes';
        const ownershipColumn = isCosmetic ? 'cosmetic_id' : 'cape_id';
        const { data: alreadyOwned } = await supabase
            .from(ownershipTable)
            .select(ownershipColumn)
            .eq('user_uuid', req.user.uuid)
            .eq(ownershipColumn, itemId)
            .maybeSingle();
        if (alreadyOwned) return fail(res, `You already own this ${itemLabel}`);
        const { data: item, error: itemErr } = await supabase
            .from(isCosmetic ? 'cosmetics' : 'capes')
            .select('id, name, price_usd, creator_id, is_public')
            .eq('id', itemId)
            .single();
        if (itemErr || !item) return fail(res, `${isCosmetic ? 'Cosmetic' : 'Cape'} not found`, 404);
        if (!item.is_public)
            return fail(res, `This ${itemLabel} is not available for purchase`, 403);
        let finalPrice = parseFloat(item.price_usd);
        let appliedPromoId = null;
        let discountPct = 0;
        if (promo_code) {
            const { data: promo, error: promoErr } = await supabase
                .from('promo_codes')
                .select(
                    'id, code, discount_percent, usage_limit, times_used, is_active, owner_uuid',
                )
                .eq('code', promo_code.toUpperCase().trim())
                .maybeSingle();
            if (promoErr) {
                log.error(CTX, 'Promo lookup error', {
                    msg: promoErr.message,
                });
                return fail(res, 'Failed to validate promo code', 500);
            }
            if (!promo) return fail(res, 'Invalid promo code');
            if (!promo.is_active) return fail(res, 'This promo code is no longer active');
            if (promo.usage_limit !== null && promo.times_used >= promo.usage_limit)
                return fail(res, 'This promo code has reached its usage limit');
            const { data: alreadyUsed } = await supabase
                .from('promo_code_uses')
                .select('id')
                .eq('promo_code_id', promo.id)
                .eq('user_uuid', req.user.uuid)
                .maybeSingle();
            if (alreadyUsed) return fail(res, 'You have already used this promo code');
            discountPct = promo.discount_percent;
            finalPrice = parseFloat((finalPrice * (1 - discountPct / 100)).toFixed(2));
            appliedPromoId = promo.id;
            log.info(CTX, `Promo applied: ${promo_code} (${discountPct}% off) → $${finalPrice}`);
        }
        if (finalPrice <= 0) {
            const grantErr = await grantItemToUser(req.user.uuid, grantIds);
            if (grantErr) {
                log.error(CTX, 'Free grant error', {
                    msg: grantErr.message,
                });
                return fail(res, `Failed to grant ${itemLabel}`, 500);
            }
            if (appliedPromoId) {
                await supabase.from('promo_code_uses').insert({
                    promo_code_id: appliedPromoId,
                    user_uuid: req.user.uuid,
                });
                await supabase
                    .rpc('increment_promo_uses', {
                        promo_id: appliedPromoId,
                    })
                    .then(({ error }) => {
                        if (error)
                            log.error(CTX, 'Promo increment error', {
                                msg: error.message,
                            });
                    });
                await expirePromoIfOneUse(appliedPromoId);
            }
            log.info(
                CTX,
                `${isCosmetic ? 'Cosmetic' : 'Cape'} granted free after 100% discount: ${itemId} → ${req.user.uuid}`,
            );
            return ok(res, {
                granted: true,
                message: `${isCosmetic ? 'Cosmetic' : 'Cape'} granted (100% discount)`,
            });
        }
        const orderId = uuidv4();
        const { error: orderInsertErr } = await supabase.from('orders').insert({
            id: orderId,
            user_uuid: req.user.uuid,
            cape_id: isCosmetic ? null : item.id,
            cosmetic_id: isCosmetic ? item.id : null,
            gross_amount_usd: finalPrice,
            discount_percent: discountPct,
            promo_code_id: appliedPromoId,
            status: 'pending',
            created_at: new Date().toISOString(),
        });
        if (orderInsertErr) {
            log.error(CTX, 'Order insert error', {
                msg: orderInsertErr.message,
            });
            return fail(res, 'Failed to create order', 500);
        }
        let paypalOrder;
        if (PAYPAL_CLIENT_ID && PAYPAL_CLIENT_SECRET) {
            paypalOrder = await paypalCreateOrder(
                finalPrice,
                orderId,
                `Breeze ${isCosmetic ? 'Cosmetic' : 'Cape'}: ${item.name}`,
            );
        } else {
            log.warn(CTX, 'PayPal not configured, returning mock approve URL');
            paypalOrder = {
                id: `MOCK_${Date.now()}`,
                status: 'CREATED',
                approve_url: `https://sandbox.paypal.com/mock?order_id=${orderId}`,
            };
        }
        await supabase
            .from('orders')
            .update({
                paypal_order_id: paypalOrder.id,
            })
            .eq('id', orderId);
        log.info(
            CTX,
            `Order created: ${orderId} | ${itemLabel}: ${item.id} | $${finalPrice} | user: ${req.user.uuid}`,
        );
        return ok(
            res,
            {
                order_id: orderId,
                approve_url: paypalOrder.approve_url,
                amount_usd: finalPrice,
            },
            201,
        );
    } catch (err) {
        log.error(CTX, 'Unexpected error', {
            msg: err.message,
            stack: err.stack,
        });
        return fail(res, 'Failed to create order', 500);
    }
});
// The page PayPal sends the buyer back to (breezeclient.net/purchase/success)
// asks for the capture straight away, so a payment does not wait on the
// CHECKOUT.ORDER.APPROVED webhook. No sign-in: the buyer may have paid from
// the launcher, in a browser that is not signed in. That is safe, because
// capturing only completes a payment its buyer already approved on PayPal's
// own page, and what is delivered is decided by PayPal's answer to this
// server's own capture call (settleCapturedOrder), never by the caller.
// Calling it again for an order whose capture PayPal is still holding checks
// again. Rate limited by purchaseLimiter.
app.post('/purchases/capture', async (req, res) => {
    const CTX = 'Purchases/Capture';
    try {
        const token = String(req.body?.token ?? '');
        if (!/^[A-Za-z0-9-]{6,64}$/.test(token)) return fail(res, 'token is required');
        const { data: order } = await supabase
            .from('orders')
            .select('id, status')
            .eq('paypal_order_id', token)
            .maybeSingle();
        if (!order) return fail(res, 'Order not found', 404);
        if (order.status === 'completed') return ok(res, { status: 'completed' });
        if (order.status !== 'pending' && order.status !== 'approved')
            return ok(res, { status: order.status });
        if (!PAYPAL_CLIENT_ID || !PAYPAL_CLIENT_SECRET) return fail(res, 'Payments are not configured', 503);
        const { status, capture } = await paypalCaptureOrder(token);
        log.info(CTX, `Capture for order ${order.id}: ${status}${capture ? ` (payment ${capture.status})` : ''}`);
        if (status === 'NOT_APPROVED') return ok(res, { status: 'not_approved' });
        if (status !== 'COMPLETED') return ok(res, { status: 'processing' });
        const outcome = await settleCapturedOrder(token, capture, CTX);
        if (outcome === 'completed') return ok(res, { status: 'completed' });
        if (outcome === 'processing') return ok(res, { status: 'processing' });
        return fail(res, 'The payment went through but could not be delivered automatically. Support has been told.', 502);
    } catch (err) {
        log.error(CTX, 'Capture error', paypalErrorDetail(err));
        // PayPal answers 404 when the order was made with other PayPal keys
        // (another app, or live against sandbox) than the ones in the env now.
        if (err.response?.status === 404) {
            log.error(CTX, 'PayPal does not know this order with the current PAYPAL_CLIENT_ID; it was created with other PayPal keys or in the other mode');
        }
        return fail(res, 'Could not complete the payment', 502);
    }
});
app.get('/purchases/orders', requireAuth, async (req, res) => {
    const CTX = 'Purchases/Orders';
    try {
        // Item details are looked up separately instead of via PostgREST embeds.
        // orders has no declared foreign key to cosmetics, so the embedded form
        // failed outright and the whole purchase history came back as an error.
        const { data, error } = await supabase
            .from('orders')
            .select(
                'id, cape_id, cosmetic_id, order_type, wind_charges, gross_amount_usd, discount_percent, status, created_at',
            )
            .eq('user_uuid', req.user.uuid)
            .order('created_at', {
                ascending: false,
            });
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch orders', 500);
        }
        const rows = data ?? [];
        const uniq = (vals) => [...new Set(vals.filter(Boolean))];
        const capeIds = uniq(rows.map((o) => o.cape_id));
        const cosmeticIds = uniq(rows.map((o) => o.cosmetic_id));
        const [capeRes, cosmeticRes] = await Promise.all([
            capeIds.length
                ? supabase.from('capes').select('id, name, image_url').in('id', capeIds)
                : Promise.resolve({ data: [] }),
            cosmeticIds.length
                ? supabase.from('cosmetics').select('id, name, thumbnail_url').in('id', cosmeticIds)
                : Promise.resolve({ data: [] }),
        ]);
        const capeById = Object.fromEntries((capeRes.data || []).map((c) => [c.id, c]));
        const cosmeticById = Object.fromEntries((cosmeticRes.data || []).map((c) => [c.id, c]));

        // Shape to the field names the website account page expects.
        const orders = rows.map((row) => ({
            ...row,
            cape: row.cape_id ? capeById[row.cape_id] || null : null,
            cosmetic: row.cosmetic_id ? cosmeticById[row.cosmetic_id] || null : null,
        })).map((o) => ({
            order_id: o.id,
            order_type: o.order_type || 'item',
            wind_charges: o.wind_charges ?? null,
            item_name: o.cape?.name || o.cosmetic?.name || null,
            image_url: o.cape?.image_url || o.cosmetic?.thumbnail_url || null,
            amount_usd: o.gross_amount_usd,
            discount_percent: o.discount_percent,
            status: o.status,
            created_at: o.created_at,
        }));
        return ok(res, { orders });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
async function promotePersonalCapeIfAny(orderId) {
    const CTX = 'Capes/PromotePersonal';
    try {
        const { data: order } = await supabase
            .from('orders')
            .select('id, user_uuid, order_type, pending_cape_path, status')
            .eq('id', orderId)
            .maybeSingle();
        if (!order || order.order_type !== 'personal_cape' || !order.pending_cape_path) return;
        const liveFileName = `personal/${order.user_uuid}_cape.png`;
        const { data: blob, error: dlErr } = await supabase.storage
            .from('capes')
            .download(order.pending_cape_path);
        if (dlErr || !blob) {
            log.error(CTX, 'Download pending failed', {
                msg: dlErr?.message,
                path: order.pending_cape_path,
            });
            return;
        }
        const buf = Buffer.from(await blob.arrayBuffer());
        const { error: upErr } = await supabase.storage.from('capes').upload(liveFileName, buf, {
            contentType: 'image/png',
            upsert: true,
        });
        if (upErr) {
            log.error(CTX, 'Promote upload failed', {
                msg: upErr.message,
            });
            return;
        }
        try {
            await supabase.storage.from('capes').remove([order.pending_cape_path]);
        } catch {}
        const { data: urlData } = supabase.storage.from('capes').getPublicUrl(liveFileName);
        await supabase
            .from('users')
            .update({
                cape_url: urlData.publicUrl,
            })
            .eq('uuid', order.user_uuid);
        log.info(CTX, `Personal cape promoted for ${order.user_uuid} (order ${orderId})`);
    } catch (err) {
        log.error(CTX, 'Unexpected error', {
            msg: err.message,
        });
    }
}
app.post('/payments/webhook', async (req, res) => {
    const CTX = 'Payments/Webhook';
    res.status(200).json({
        received: true,
    });
    try {
        const rawBody = Buffer.isBuffer(req.body)
            ? req.body
            : Buffer.from(typeof req.body === 'string' ? req.body : JSON.stringify(req.body ?? {}));
        const isVerified = await paypalVerifyWebhook(req.headers, rawBody);
        if (!isVerified) {
            log.warn(CTX, 'Webhook signature verification FAILED, ignoring event');
            return;
        }
        const event = JSON.parse(rawBody.toString('utf8'));
        const { event_type, resource } = event;
        log.info(CTX, `Received verified PayPal event: ${event_type}`);
        if (event_type === 'CHECKOUT.ORDER.APPROVED') {
            await handlePayPalOrderApproved(resource);
        } else if (event_type === 'PAYMENT.CAPTURE.COMPLETED') {
            await handlePayPalCaptureCompleted(resource);
        } else if (
            event_type === 'PAYMENT.CAPTURE.REFUNDED' ||
            event_type === 'PAYMENT.CAPTURE.REVERSED'
        ) {
            await handlePayPalCaptureRefunded(resource);
        } else {
            log.info(CTX, `Unhandled event type: ${event_type}`);
        }
    } catch (err) {
        log.error(CTX, 'Webhook processing error', {
            msg: err.message,
        });
    }
});
async function handlePayPalOrderApproved(resource) {
    const CTX = 'Webhook/OrderApproved';
    const paypalOrderId = resource.id;
    const { data: order } = await supabase
        .from('orders')
        .select('id, status')
        .eq('paypal_order_id', paypalOrderId)
        .maybeSingle();
    if (!order) {
        log.warn(CTX, `No matching internal order for PayPal order ${paypalOrderId}`);
        return;
    }
    // 'approved' is retried too: if an earlier capture failed, PayPal's retry
    // of this event is the chance to take the payment.
    if (order.status !== 'pending' && order.status !== 'approved') {
        log.warn(CTX, `Order ${order.id} already in state: ${order.status}`);
        return;
    }
    if (order.status === 'pending') {
        await supabase
            .from('orders')
            .update({
                status: 'approved',
            })
            .eq('id', order.id);
        log.info(CTX, `Order approved: ${order.id}`);
    }
    // Take the money now, and deliver as soon as PayPal's answer says it moved.
    // PAYMENT.CAPTURE.COMPLETED does the same when it arrives (whichever is
    // first wins; fulfilPaidOrder delivers once).
    try {
        const { status, capture } = await paypalCaptureOrder(paypalOrderId);
        log.info(CTX, `Capture for order ${order.id}: ${status}${capture ? ` (payment ${capture.status})` : ''}`);
        if (status === 'COMPLETED') await settleCapturedOrder(paypalOrderId, capture, CTX);
    } catch (err) {
        log.error(CTX, `Capture failed for order ${order.id}`, paypalErrorDetail(err));
    }
}
async function handlePayPalCaptureCompleted(resource) {
    const paypalOrderId = resource.supplementary_data?.related_ids?.order_id ?? resource.id;
    await fulfilPaidOrder(paypalOrderId, 'Webhook/CaptureCompleted');
}
/**
 * Delivers a captured order from PayPal's answer to this server's own capture
 * call. Until 2026-10-09 only the verified PAYMENT.CAPTURE.COMPLETED webhook
 * delivered, so when the webhook check failed (a 400 from PayPal's verify
 * endpoint) the buyer paid and received nothing. The capture answer comes
 * from PayPal over this server's authenticated connection, which is as good
 * a proof as a verified webhook, as long as the money is released
 * (COMPLETED, not PENDING) and is exactly the order's amount in USD.
 * Returns 'completed', 'processing' (PayPal is holding it), 'mismatch' or
 * 'failed'.
 */
async function settleCapturedOrder(paypalOrderId, capture, CTX) {
    if (!capture || capture.status !== 'COMPLETED') {
        log.info(
            CTX,
            `PayPal has not released the payment for ${paypalOrderId} yet (${capture?.status || 'no capture'}${capture?.reason ? `, ${capture.reason}` : ''})`,
        );
        return 'processing';
    }
    const { data: order } = await supabase
        .from('orders')
        .select('id, user_uuid, gross_amount_usd')
        .eq('paypal_order_id', paypalOrderId)
        .maybeSingle();
    if (!order) return 'failed';
    const cents = (v) => Math.round(Number(v) * 100);
    if (capture.currency !== 'USD' || !Number.isFinite(cents(capture.value)) || cents(capture.value) !== cents(order.gross_amount_usd)) {
        log.error(CTX, `Order ${order.id} captured ${capture.value} ${capture.currency}, expected ${order.gross_amount_usd} USD; not delivered`);
        await sendSystemEmail({
            to: process.env.PAYOUT_MANAGER_EMAIL || REPORT_EMAIL,
            subject: `Breeze: payment amount does not match its order (${order.id})`,
            html: `<p>PayPal captured <strong>${capture.value} ${capture.currency}</strong> for order ${order.id}, which costs <strong>${order.gross_amount_usd} USD</strong>. Nothing was delivered. Check the payment in PayPal and deliver or refund it by hand.</p>`,
        });
        return 'mismatch';
    }
    return (await fulfilPaidOrder(paypalOrderId, CTX)) ? 'completed' : 'failed';
}
/** Credits or grants a paid order once. True when it is (now or already) delivered. */
async function fulfilPaidOrder(paypalOrderId, CTX) {
    const { data: order, error: orderErr } = await supabase
        .from('orders')
        .select('id, user_uuid, cape_id, cosmetic_id, gross_amount_usd, promo_code_id, status, order_type, wind_charges')
        .eq('paypal_order_id', paypalOrderId)
        .maybeSingle();
    if (orderErr || !order) {
        log.warn(CTX, 'No matching internal order for PayPal capture', {
            paypalOrderId,
        });
        return false;
    }
    if (order.status === 'completed') {
        log.info(CTX, `Order ${order.id} already completed`);
        return true;
    }
    // The return page, the approval webhook and the completion webhook can all
    // arrive at once. Whoever stamps completed_at first delivers; the others
    // stop here, so nothing is credited twice.
    const { data: claimed, error: claimErr } = await supabase
        .from('orders')
        .update({ completed_at: new Date().toISOString() })
        .eq('id', order.id)
        .is('completed_at', null)
        .select('id');
    if (claimErr) {
        log.error(CTX, `Could not claim order ${order.id}`, { msg: claimErr.message });
        return false;
    }
    if (!claimed || claimed.length === 0) {
        log.info(CTX, `Order ${order.id} is already being delivered`);
        return true;
    }
    try {
        return await deliverPaidOrder(order, CTX);
    } catch (err) {
        log.error(CTX, `Delivering order ${order.id} failed`, { msg: err.message });
        // Release the claim so a retry can deliver, unless it got as far as done.
        await supabase.from('orders').update({ completed_at: null }).eq('id', order.id).neq('status', 'completed');
        return false;
    }
}
async function deliverPaidOrder(order, CTX) {
    // Wind Charge purchases: credit the wallet and stop: no item grant, no
    // revenue split. Creator earnings happen when charges are SPENT.
    if (order.order_type === 'wind_charges') {
        const amount = Number(order.wind_charges || 0);
        const bal = breezeCtx.economy
            ? await breezeCtx.economy.creditWindCharges(order.user_uuid, amount, `order:${order.id}`, 'Wind Charge purchase')
            : null;
        const credited = bal !== null && bal !== false;
        if (!credited) {
            // Buyer PAID but the credit did not apply. Do NOT mark the order
            // completed, leaving it non-completed lets a webhook retry (or a
            // manual re-trigger) credit it, and alert the team so nobody is
            // silently shorted.
            log.error(CTX, `WC order ${order.id} PAID but credit FAILED for ${order.user_uuid} (+${amount} WC), needs manual review`);
            await sendSystemEmail({
                to: process.env.PAYOUT_MANAGER_EMAIL || REPORT_EMAIL,
                subject: `⚠ Breeze: paid Wind Charges NOT credited (order ${order.id})`,
                html: `<p>A PayPal payment captured but the Wind Charge credit failed.</p>
                       <p><strong>Order:</strong> ${order.id}<br><strong>User:</strong> ${order.user_uuid}<br><strong>Amount:</strong> ${amount.toLocaleString()} WC</p>
                       <p>The order was left un-completed so a retry can credit it. If it doesn't self-heal, credit the user manually.</p>`,
            });
            // Release the claim so a retry can credit it.
            await supabase.from('orders').update({ completed_at: null }).eq('id', order.id);
            return false;
        }
        await supabase
            .from('orders')
            .update({ status: 'completed', completed_at: new Date().toISOString() })
            .eq('id', order.id);
        await createNotification(
            order.user_uuid,
            'purchase',
            'Wind Charges added',
            `${amount.toLocaleString()} Wind Charges were added to your balance.`,
            { order_id: order.id, wind_charges: amount },
        );
        const wcBuyerEmail = await getUserEmail(order.user_uuid);
        await sendBreezeEmail(
            wcBuyerEmail,
            'Your Wind Charges are ready',
            'Wind Charges added',
            `<p><strong>${amount.toLocaleString()} Wind Charges</strong> were added to your Breeze balance. Spend them on capes and cosmetics in the launcher store.</p><p><strong>Order:</strong> ${order.id}</p>`,
        );
        // Notify the payout manager / co-owner that real money came in. Funds
        // land in the PayPal business account behind PAYPAL_CLIENT_ID.
        const grossUsd = Number(order.gross_amount_usd || 0);
        await sendSystemEmail({
            to: process.env.PAYOUT_MANAGER_EMAIL || REPORT_EMAIL,
            subject: `Breeze payment received, $${grossUsd.toFixed(2)} (${amount.toLocaleString()} WC)`,
            html: `<p>A Wind Charge purchase completed.</p>
                   <p><strong>Amount:</strong> $${grossUsd.toFixed(2)} → ${amount.toLocaleString()} Wind Charges<br>
                   <strong>Buyer:</strong> ${order.user_uuid}<br>
                   <strong>Order:</strong> ${order.id}</p>
                   <p>The funds are in the Breeze PayPal account.</p>`,
        });
        log.info(CTX, `WC order ${order.id} completed: +${amount} WC → ${order.user_uuid} (balance ${bal})`);
        return true;
    }
    const isCosmetic = Boolean(order.cosmetic_id);
    const itemId = isCosmetic ? order.cosmetic_id : order.cape_id;
    const itemLabel = isCosmetic ? 'Cosmetic' : 'Cape';
    const grossUsd = parseFloat(order.gross_amount_usd);
    const netUsd = calcNetRevenue(grossUsd);
    const { data: itemRow } = await supabase
        .from(isCosmetic ? 'cosmetics' : 'capes')
        .select('creator_id')
        .eq('id', itemId)
        .single();
    const hasCreator = !!itemRow?.creator_id;
    let creatorSharePercent = null;
    if (hasCreator) {
        const { data: creatorRow } = await supabase
            .from('users')
            .select('creator_share_percent')
            .eq('uuid', itemRow.creator_id)
            .single();
        creatorSharePercent = creatorRow?.creator_share_percent ?? null;
    }
    const split = calcEarningsSplit(netUsd, hasCreator, creatorSharePercent);
    log.info(CTX, `Revenue split for order ${order.id}`, {
        gross_usd: grossUsd,
        net_usd: netUsd,
        creator_share_percent: hasCreator ? (creatorSharePercent ?? CREATOR_SHARE_PERCENT) : 0,
        split,
    });
    const now = new Date().toISOString();
    const grantErr = await grantItemToUser(
        order.user_uuid,
        isCosmetic ? { cosmeticId: itemId } : { capeId: itemId },
    );
    if (grantErr)
        log.error(CTX, `${itemLabel} grant error`, {
            msg: grantErr.message,
        });
    else log.info(CTX, `${itemLabel} granted: ${itemId} → ${order.user_uuid}`);
    const earningsRows = [];
    if (hasCreator && split.creator > 0) {
        earningsRows.push({
            order_id: order.id,
            user_uuid: itemRow.creator_id,
            role: 'creator',
            amount_usd: split.creator,
            created_at: now,
        });
    }
    if (split.coowner > 0) {
        earningsRows.push({
            order_id: order.id,
            user_uuid: process.env.COOWNER_UUID ?? null,
            role: 'coowner',
            amount_usd: split.coowner,
            created_at: now,
        });
    }
    if (split.developerOne > 0) {
        earningsRows.push({
            order_id: order.id,
            user_uuid: process.env.DEVELOPER_ONE_UUID ?? null,
            role: 'developer_one',
            amount_usd: split.developerOne,
            created_at: now,
        });
    }
    if (split.developerTwo > 0) {
        earningsRows.push({
            order_id: order.id,
            user_uuid: process.env.DEVELOPER_TWO_UUID ?? null,
            role: 'developer_two',
            amount_usd: split.developerTwo,
            created_at: now,
        });
    }
    if (split.owner > 0) {
        earningsRows.push({
            order_id: order.id,
            user_uuid: process.env.OWNER_UUID ?? null,
            role: 'owner',
            amount_usd: split.owner,
            created_at: now,
        });
    }
    if (earningsRows.length > 0) {
        const { error: earningsErr } = await supabase.from('earnings').insert(earningsRows);
        if (earningsErr)
            log.error(CTX, 'Earnings insert error', {
                msg: earningsErr.message,
            });
        else log.info(CTX, `Earnings recorded for order ${order.id}: net=$${netUsd}`, split);
    }
    if (order.promo_code_id) {
        await supabase
            .from('promo_code_uses')
            .insert({
                promo_code_id: order.promo_code_id,
                user_uuid: order.user_uuid,
                used_at: now,
            })
            .then(({ error }) => {
                if (error)
                    log.error(CTX, 'Promo use record error', {
                        msg: error.message,
                    });
            });
        await supabase
            .rpc('increment_promo_uses', {
                promo_id: order.promo_code_id,
            })
            .then(({ error }) => {
                if (error)
                    log.error(CTX, 'Promo increment error', {
                        msg: error.message,
                    });
            });
        await expirePromoIfOneUse(order.promo_code_id);
    }
    await supabase
        .from('orders')
        .update({
            status: 'completed',
            net_amount_usd: netUsd,
            completed_at: now,
        })
        .eq('id', order.id);
    await createNotification(
        order.user_uuid,
        'purchase',
        'Purchase complete',
        'Your Breeze purchase has been delivered to your inventory.',
        {
            order_id: order.id,
            cape_id: order.cape_id,
            cosmetic_id: order.cosmetic_id,
            gross_usd: grossUsd,
        },
    );
    const buyerEmail = await getUserEmail(order.user_uuid);
    await sendBreezeEmail(
        buyerEmail,
        'Your Breeze purchase is complete',
        'Your cosmetic is ready',
        `<p>Your Breeze purchase has been confirmed and delivered to your launcher inventory.</p><p><strong>Order:</strong> ${order.id}<br><strong>Total:</strong> $${grossUsd.toFixed(2)}</p>`,
    );
    for (const row of earningsRows) {
        if (!row.user_uuid) continue;
        await createNotification(
            row.user_uuid,
            'payout',
            'New pending Breeze earnings',
            `$${Number(row.amount_usd).toFixed(2)} was added to your pending ${row.role} earnings.`,
            {
                order_id: order.id,
                role: row.role,
                amount_usd: row.amount_usd,
            },
        );
        const payoutEmail = await getUserEmail(row.user_uuid);
        await sendBreezeEmail(
            payoutEmail,
            'New pending Breeze earnings',
            'Your Breeze earnings were updated',
            `<p>A completed Breeze purchase added <strong>$${Number(row.amount_usd).toFixed(2)}</strong> to your pending ${row.role} earnings.</p><p>Payouts are tracked in the owner/creator dashboard and are not transferred automatically.</p>`,
        );
    }
    log.info(CTX, `Order completed: ${order.id} | gross=$${grossUsd} | net=$${netUsd}`);
    return true;
}
/** PayPal refund/reversal: mark the order refunded and, for Wind Charge
 *  purchases, pull the matching charges back out of the wallet (floored at
 *  zero, already-spent charges are an owner-side judgement call). */
async function handlePayPalCaptureRefunded(resource) {
    const CTX = 'Webhook/CaptureRefunded';
    const paypalOrderId = resource.supplementary_data?.related_ids?.order_id ?? null;
    if (!paypalOrderId) {
        log.warn(CTX, 'Refund event without an order id, resolve manually', {
            refund_id: resource.id,
        });
        return;
    }
    const { data: order } = await supabase
        .from('orders')
        .select('id, user_uuid, order_type, wind_charges, status')
        .eq('paypal_order_id', paypalOrderId)
        .maybeSingle();
    if (!order) {
        log.warn(CTX, `No internal order for refunded PayPal order ${paypalOrderId}`);
        return;
    }
    if (order.status === 'refunded') return;
    await supabase
        .from('orders')
        .update({ status: 'refunded', refunded_at: new Date().toISOString() })
        .eq('id', order.id);
    if (order.order_type === 'wind_charges' && breezeCtx.economy) {
        await breezeCtx.economy.debitWindChargesForRefund(
            order.user_uuid,
            Number(order.wind_charges || 0),
            `order:${order.id}`,
        );
    }
    await createNotification(
        order.user_uuid,
        'refund',
        'Payment refunded',
        'Your PayPal payment was refunded and the matching Wind Charges were removed from your balance.',
        { order_id: order.id },
    );
    const refundEmail = await getUserEmail(order.user_uuid);
    await sendBreezeEmail(
        refundEmail,
        'Your Breeze payment was refunded',
        'Refund processed',
        `<p>Your PayPal payment for order <strong>${order.id}</strong> was refunded. Any Wind Charges from that purchase were removed from your balance.</p>`,
    );
    log.info(CTX, `Order ${order.id} marked refunded`);
}
app.post('/promo-codes', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'PromoCodes/Create';
    try {
        const caller = await dbGetUser(req.user.uuid);
        if (!caller || caller.role !== ROLES.OWNER) return fail(res, 'Owner access required', 403);
        const { code, discount_percent, owner_uuid, usage_limit } = req.body;
        if (!code || typeof code !== 'string') return fail(res, 'code is required');
        const safeCode = code.toUpperCase().trim();
        if (!/^[A-Z0-9_-]{3,20}$/.test(safeCode))
            return fail(res, 'Code must be 3-20 alphanumeric characters (A-Z, 0-9, _ -)');
        const pct = parseFloat(discount_percent);
        if (isNaN(pct) || pct <= 0 || pct > 100) return fail(res, 'discount_percent must be 1-100');
        const { data: existing } = await supabase
            .from('promo_codes')
            .select('id')
            .eq('code', safeCode)
            .maybeSingle();
        if (existing) return fail(res, 'A promo code with that name already exists');
        const { data: promo, error } = await supabase
            .from('promo_codes')
            .insert({
                code: safeCode,
                discount_percent: pct,
                owner_uuid: owner_uuid ?? null,
                usage_limit: usage_limit ?? null,
                times_used: 0,
                is_active: true,
                created_at: new Date().toISOString(),
            })
            .select()
            .single();
        if (error) {
            log.error(CTX, 'Insert error', {
                msg: error.message,
            });
            return fail(res, 'Failed to create promo code', 500);
        }
        log.info(CTX, `Promo code created: ${safeCode} (${pct}%) by ${req.user.uuid}`);
        return ok(
            res,
            {
                promo,
            },
            201,
        );
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get('/promo-codes', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const CTX = 'PromoCodes/List';
    try {
        const { data, error } = await supabase.from('promo_codes').select('*').order('created_at', {
            ascending: false,
        });
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch promo codes', 500);
        }
        return ok(res, {
            promo_codes: data ?? [],
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.patch('/promo-codes/:id', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'PromoCodes/Update';
    try {
        const caller = await dbGetUser(req.user.uuid);
        if (!caller || caller.role !== ROLES.OWNER) return fail(res, 'Owner access required', 403);
        const allowed = ['discount_percent', 'usage_limit', 'is_active'];
        const updates = {};
        for (const key of allowed) {
            if (req.body[key] !== undefined) updates[key] = req.body[key];
        }
        const { error } = await supabase
            .from('promo_codes')
            .update(updates)
            .eq('id', req.params.id);
        if (error) {
            log.error(CTX, 'Update error', {
                msg: error.message,
            });
            return fail(res, 'Failed to update promo code', 500);
        }
        log.info(CTX, `Promo code ${req.params.id} updated`);
        return ok(res, {
            message: 'Promo code updated',
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.delete('/promo-codes/:id', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'PromoCodes/Delete';
    try {
        const caller = await dbGetUser(req.user.uuid);
        if (!caller || caller.role !== ROLES.OWNER) return fail(res, 'Owner access required', 403);
        const { error } = await supabase
            .from('promo_codes')
            .update({
                is_active: false,
            })
            .eq('id', req.params.id);
        if (error) {
            log.error(CTX, 'Deactivate error', {
                msg: error.message,
            });
            return fail(res, 'Failed to deactivate promo code', 500);
        }
        log.info(CTX, `Promo code ${req.params.id} deactivated`);
        return ok(res, {
            message: 'Promo code deactivated',
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.post('/promo-codes/validate', requireAuth, async (req, res) => {
    const CTX = 'PromoCodes/Validate';
    try {
        const { code } = req.body;
        if (!code) return fail(res, 'code is required');
        const { data: promo } = await supabase
            .from('promo_codes')
            .select('id, code, discount_percent, usage_limit, times_used, is_active')
            .eq('code', code.toUpperCase().trim())
            .maybeSingle();
        if (!promo || !promo.is_active) return fail(res, 'Invalid or inactive promo code', 404);
        if (promo.usage_limit !== null && promo.times_used >= promo.usage_limit)
            return fail(res, 'Promo code usage limit reached');
        const { data: alreadyUsed } = await supabase
            .from('promo_code_uses')
            .select('id')
            .eq('promo_code_id', promo.id)
            .eq('user_uuid', req.user.uuid)
            .maybeSingle();
        if (alreadyUsed) return fail(res, 'You have already used this promo code');
        return ok(res, {
            valid: true,
            discount_percent: promo.discount_percent,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get('/earnings/me', requireAuth, async (req, res) => {
    const CTX = 'Earnings/Me';
    try {
        const { data, error } = await supabase
            .from('earnings')
            .select('id, order_id, role, amount_usd, paid_out, created_at')
            .eq('user_uuid', req.user.uuid)
            .order('created_at', {
                ascending: false,
            });
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch earnings', 500);
        }
        const total = (data ?? []).reduce((s, r) => s + parseFloat(r.amount_usd), 0);
        const pending = (data ?? [])
            .filter((r) => !r.paid_out)
            .reduce((s, r) => s + parseFloat(r.amount_usd), 0);
        return ok(res, {
            earnings: data ?? [],
            total_usd: total.toFixed(4),
            pending_usd: pending.toFixed(4),
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get('/earnings/all', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const CTX = 'Earnings/All';
    try {
        const { data, error } = await supabase
            .from('earnings')
            .select('id, order_id, user_uuid, role, amount_usd, paid_out, created_at')
            .order('created_at', {
                ascending: false,
            });
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch earnings', 500);
        }
        return ok(res, {
            earnings: data ?? [],
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get(
    '/earnings/monthly-report',
    requireAuth,
    requireRole(ROLES.ADMIN, ROLES.OWNER),
    async (req, res) => {
        const CTX = 'Earnings/MonthlyReport';
        try {
            const month = parseInt(req.query.month ?? new Date().getMonth() + 1, 10);
            const year = parseInt(req.query.year ?? new Date().getFullYear(), 10);
            if (month < 1 || month > 12 || year < 2024)
                return fail(res, 'Invalid month/year parameters');
            const from = new Date(year, month - 1, 1).toISOString();
            const to = new Date(year, month, 1).toISOString();
            const { data: rows, error } = await supabase
                .from('earnings')
                .select('user_uuid, role, amount_usd, users(username)')
                .gte('created_at', from)
                .lt('created_at', to);
            if (error) {
                log.error(CTX, 'DB error', {
                    msg: error.message,
                });
                return fail(res, 'Failed to generate report', 500);
            }
            const aggregated = {};
            for (const row of rows ?? []) {
                const key = row.user_uuid;
                if (!aggregated[key]) {
                    aggregated[key] = {
                        user_uuid: key,
                        username: row.users?.username ?? 'Unknown',
                        role: row.role,
                        total_earnings_usd: 0,
                        transactions: 0,
                    };
                }
                aggregated[key].total_earnings_usd += parseFloat(row.amount_usd);
                aggregated[key].transactions += 1;
            }
            const report = Object.values(aggregated).map((r) => ({
                ...r,
                total_earnings_usd: parseFloat(r.total_earnings_usd.toFixed(4)),
            }));
            return ok(res, {
                month,
                year,
                report,
            });
        } catch (err) {
            log.error(CTX, 'Error', {
                msg: err.message,
            });
            return fail(res, 'Server error', 500);
        }
    },
);
app.post('/earnings/send-report', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const CTX = 'Earnings/SendReport';
    try {
        const month = parseInt(req.body.month ?? new Date().getMonth() + 1, 10);
        const year = parseInt(req.body.year ?? new Date().getFullYear(), 10);
        const from = new Date(year, month - 1, 1).toISOString();
        const to = new Date(year, month, 1).toISOString();
        const { data: rows, error } = await supabase
            .from('earnings')
            .select('user_uuid, role, amount_usd, users(username)')
            .gte('created_at', from)
            .lt('created_at', to);
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch earnings for report', 500);
        }
        const aggregated = {};
        for (const row of rows ?? []) {
            const key = row.user_uuid;
            if (!aggregated[key]) {
                aggregated[key] = {
                    user_uuid: key,
                    username: row.users?.username ?? 'Unknown',
                    role: row.role,
                    total_earnings_usd: 0,
                    transactions: 0,
                };
            }
            aggregated[key].total_earnings_usd += parseFloat(row.amount_usd);
            aggregated[key].transactions += 1;
        }
        await sendMonthlyEarningsReport(month, year, Object.values(aggregated));
        log.info(CTX, `Monthly report emailed for ${month}/${year}`);
        return ok(res, {
            message: `Report for ${month}/${year} sent to ${REPORT_EMAIL}`,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.get(
    '/creator/stats',
    requireAuth,
    requireRole(...CREATOR_ACCESS_ROLES),
    async (req, res) => {
        const CTX = 'Creator/Stats';
        try {
            const { data: capes } = await supabase
                .from('capes')
                .select('id')
                .eq('creator_id', req.user.uuid);
            const capeIds = (capes ?? []).map((c) => c.id);
            let total_sales = 0,
                total_gross_usd = 0,
                total_net_usd = 0,
                creator_earnings_usd = 0;
            if (capeIds.length > 0) {
                const { data: orders } = await supabase
                    .from('orders')
                    .select('gross_amount_usd, net_amount_usd')
                    .in('cape_id', capeIds)
                    .eq('status', 'completed');
                for (const o of orders ?? []) {
                    total_sales += 1;
                    total_gross_usd += parseFloat(o.gross_amount_usd ?? 0);
                    total_net_usd += parseFloat(o.net_amount_usd ?? 0);
                }
                const { data: myEarnings } = await supabase
                    .from('earnings')
                    .select('amount_usd')
                    .eq('user_uuid', req.user.uuid)
                    .eq('role', 'creator');
                creator_earnings_usd = (myEarnings ?? []).reduce(
                    (s, r) => s + parseFloat(r.amount_usd),
                    0,
                );
            }
            const dbUser = await dbGetUser(req.user.uuid);
            return ok(res, {
                capes_created: capeIds.length,
                total_sales,
                total_gross_usd: parseFloat(total_gross_usd.toFixed(4)),
                total_net_usd: parseFloat(total_net_usd.toFixed(4)),
                creator_earnings_usd: parseFloat(creator_earnings_usd.toFixed(4)),
                creator_share_percent: dbUser?.creator_share_percent ?? CREATOR_SHARE_PERCENT,
            });
        } catch (err) {
            log.error(CTX, 'Error', {
                msg: err.message,
            });
            return fail(res, 'Server error', 500);
        }
    },
);
app.get('/notifications', requireAuth, async (req, res) => {
    try {
        const { data, error } = await supabase
            .from('notifications')
            .select('*')
            .eq('user_uuid', req.user.uuid)
            .order('created_at', {
                ascending: false,
            })
            .limit(80);
        if (error) return fail(res, 'Notifications are not ready yet', 503);
        return ok(res, {
            notifications: data ?? [],
        });
    } catch (err) {
        log.error('Notifications/List', 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.patch('/notifications/:id/read', requireAuth, async (req, res) => {
    const { error } = await supabase
        .from('notifications')
        .update({
            read_at: new Date().toISOString(),
        })
        .eq('id', req.params.id)
        .eq('user_uuid', req.user.uuid);
    if (error) return fail(res, 'Failed to update notification', 500);
    return ok(res, {
        id: req.params.id,
    });
});
app.post('/notifications/read-all', requireAuth, async (req, res) => {
    const { error } = await supabase
        .from('notifications')
        .update({
            read_at: new Date().toISOString(),
        })
        .eq('user_uuid', req.user.uuid)
        .is('read_at', null);
    if (error) return fail(res, 'Failed to update notifications', 500);
    return ok(res);
});

// Dismiss a single notification. Marking as read was previously the only
// option, so routine sign-in notices accumulated forever with no way to clear
// them. Every delete is scoped to the caller's own uuid.
app.delete('/notifications/:id', requireAuth, async (req, res) => {
    const CTX = 'Notifications/Delete';
    const { error } = await supabase
        .from('notifications')
        .delete()
        .eq('id', req.params.id)
        .eq('user_uuid', req.user.uuid);
    if (error) {
        log.error(CTX, 'DB error', { msg: error.message });
        return fail(res, 'Failed to dismiss notification', 500);
    }
    return ok(res, { id: req.params.id });
});

/**
 * Bulk dismissal.
 *   { ids: [...] }   dismiss exactly those
 *   { scope: 'read' } dismiss everything already read
 *   { scope: 'all' }  clear the whole list
 */
app.post('/notifications/dismiss', requireAuth, async (req, res) => {
    const CTX = 'Notifications/Dismiss';
    const ids = Array.isArray(req.body?.ids) ? req.body.ids.filter(Boolean) : [];
    const scope = String(req.body?.scope || '').toLowerCase();

    let query = supabase.from('notifications').delete().eq('user_uuid', req.user.uuid);
    if (ids.length) {
        if (ids.length > 500) return fail(res, 'Too many notifications in one request (max 500)');
        query = query.in('id', ids);
    } else if (scope === 'read') {
        query = query.not('read_at', 'is', null);
    } else if (scope !== 'all') {
        return fail(res, 'Provide ids, or scope of "read" or "all"');
    }

    const { error } = await query;
    if (error) {
        log.error(CTX, 'DB error', { msg: error.message });
        return fail(res, 'Failed to dismiss notifications', 500);
    }
    log.info(CTX, `${req.user.uuid} dismissed ${ids.length ? ids.length + ' selected' : scope}`);
    return ok(res, { dismissed: ids.length || scope });
});
/**
 * Resolve a Minecraft username to its canonical undashed UUID via Mojang.
 *
 * Returns { uuid, name } on success, null when Mojang says no such player, and
 * throws only when the lookup itself failed. The three cases must stay
 * distinguishable so search can tell "no such player" from "Mojang is down".
 */
async function resolveMojangProfile(username) {
    const r = await axios.get(
        `https://api.mojang.com/users/profiles/minecraft/${encodeURIComponent(username)}`,
        { timeout: 6000, validateStatus: (s) => s === 200 || s === 204 || s === 404 },
    );
    if (r.status === 204 || r.status === 404 || !r.data?.id) return null;
    return { uuid: String(r.data.id).replace(/-/g, '').toLowerCase(), name: r.data.name };
}

// Mojang rate limits aggressively (429 within a handful of rapid lookups), so
// resolved profiles are cached briefly. Only misses cost a network call.
const mojangCache = new Map();
const MOJANG_CACHE_MS = 10 * 60 * 1000;

async function cachedMojangProfile(username) {
    const key = String(username).toLowerCase();
    const hit = mojangCache.get(key);
    if (hit && hit.at > Date.now() - MOJANG_CACHE_MS) return hit.profile;
    const profile = await resolveMojangProfile(username);
    mojangCache.set(key, { profile, at: Date.now() });
    if (mojangCache.size > 500) {
        // Cheap bound: drop the oldest half rather than tracking LRU precisely.
        const entries = [...mojangCache.entries()].sort((a, b) => a[1].at - b[1].at);
        for (const [k] of entries.slice(0, 250)) mojangCache.delete(k);
    }
    return profile;
}

/**
 * The maintenance switch for Social, enforced here rather than only in the
 * launcher's UI. Presence is left alone: recording that someone is online costs
 * nothing and keeps "last seen" honest while Social is dark.
 */
app.use('/social', requireAuth, async (req, res, next) => {
    if (req.path === '/presence') return next();
    if (await featureDisabled('chat_disabled', req.user)) {
        return fail(res, 'Social is unavailable right now', 503);
    }
    return next();
});

app.get('/social/search', requireAuth, async (req, res) => {
    const CTX = 'Social/Search';
    const query = String(req.query.q || '').trim();
    if (query.length < 2) return fail(res, 'Search needs at least 2 characters');

    const shape = (rows) =>
        (rows ?? [])
            .filter((u) => u.uuid !== req.user.uuid && !String(u.uuid).startsWith('__'))
            .map((u) => publicUser(u));

    // Local, case-insensitive match first. This is the common case, costs no
    // network call, and also powers partial-name browsing. The query is escaped:
    // "_" and "*" are wildcards to ilike, and a username may contain them.
    const { data, error } = await supabase
        .from('users')
        .select('*')
        .ilike('username', `%${likePattern(query)}%`)
        .limit(24);
    if (error) {
        log.error(CTX, 'DB error', { msg: error.message });
        return fail(res, 'Search is not ready yet', 503);
    }
    // Whoever the player typed exactly comes first, then the rest alphabetically.
    const users = shape(data).sort((a, b) => {
        const want = query.toLowerCase();
        const ax = a.username.toLowerCase() === want ? 0 : 1;
        const bx = b.username.toLowerCase() === want ? 0 : 1;
        return ax - bx || a.username.localeCompare(b.username);
    });
    if (users.length) return ok(res, { users, message: null });

    // Nothing locally. Before declaring the player unregistered, resolve the
    // name through Mojang and retry by UUID: a player who changed their
    // Minecraft name keeps the same UUID, but our stored username is only
    // refreshed on their next login. That is the most likely reason a genuinely
    // registered friend came back as "not registered".
    const { user, reason } = await findUserByName(query);
    if (user && user.uuid !== req.user.uuid) return ok(res, { users: shape([user]), message: null });

    return ok(res, {
        users: [],
        // A lookup that could not be completed must never be reported as
        // "not registered", and neither must a player who is simply offline.
        message:
            reason === 'lookup_failed'
                ? 'Could not check that username right now. Check your connection and try again.'
                : reason === 'db'
                  ? 'Search is not ready yet, try again in a moment.'
                  : 'This user has not registered with Breeze Client yet.',
    });
});
app.get('/social/friends', requireAuth, async (req, res) => {
    const { data, error } = await supabase
        .from('friendships')
        .select('*')
        .or(`requester_uuid.eq.${req.user.uuid},addressee_uuid.eq.${req.user.uuid}`)
        .order('created_at', {
            ascending: false,
        });
    if (error) return fail(res, 'Friends are not ready yet', 503);
    const rows = data ?? [];
    const otherUuids = [
        ...new Set(
            rows.map((r) =>
                r.requester_uuid === req.user.uuid ? r.addressee_uuid : r.requester_uuid,
            ),
        ),
    ];
    let profiles = [];
    if (otherUuids.length) {
        const result = await supabase.from('users').select('*').in('uuid', otherUuids);
        profiles = result.data ?? [];
    }
    const byUuid = new Map(profiles.map((u) => [u.uuid, publicUser(u)]));

    // Unread counts, so the friends list can say where the conversation is.
    // One query for every friend rather than one per friend.
    const unread = new Map();
    if (otherUuids.length) {
        const { data: pending } = await supabase
            .from('messages')
            .select('sender_uuid')
            .eq('recipient_uuid', req.user.uuid)
            .is('read_at', null)
            .in('sender_uuid', otherUuids);
        for (const m of pending ?? []) unread.set(m.sender_uuid, (unread.get(m.sender_uuid) || 0) + 1);
    }

    const other = (r) => (r.requester_uuid === req.user.uuid ? r.addressee_uuid : r.requester_uuid);
    // A profile row can be missing (a user deleted, or a friendship written by
    // an older release). The entry still lists the uuid so the row is never
    // rendered against the friendship's own id.
    const withUser = (r) => ({
        ...r,
        uuid: other(r),
        unread: unread.get(other(r)) || 0,
        user: byUuid.get(other(r)) || { uuid: other(r), username: null, lastSeen: null },
    });
    return ok(res, {
        friends: rows.filter((r) => r.status === 'accepted').map(withUser),
        requests: rows.filter((r) => r.status === 'pending').map(withUser),
        blocked: rows.filter((r) => r.status === 'blocked').map(withUser),
    });
});

/**
 * Stop being friends, or block someone.
 *
 * Unfriending removes the row: either side may ask again later. Blocking keeps
 * it, so a request cannot be sent back, and the block is invisible to the other
 * player. Only the player who blocked can lift it.
 */
app.post('/social/friends/:uuid/remove', requireAuth, async (req, res) => {
    const other = undash(req.params.uuid);
    if (!looksLikeUuid(other)) return fail(res, 'Invalid UUID');
    const block = req.body?.block === true;
    const { data: row } = await supabase
        .from('friendships')
        .select('*')
        .or(
            `and(requester_uuid.eq.${req.user.uuid},addressee_uuid.eq.${other}),and(requester_uuid.eq.${other},addressee_uuid.eq.${req.user.uuid})`,
        )
        .maybeSingle();
    if (!row) return fail(res, 'You are not friends with this player', 404);
    if (row.status === 'blocked' && row.requester_uuid !== req.user.uuid) {
        return fail(res, 'This user cannot be changed right now', 403);
    }
    if (block) {
        // The blocker becomes the requester so the row records who blocked whom.
        const { error } = await supabase
            .from('friendships')
            .update({ status: 'blocked', requester_uuid: req.user.uuid, addressee_uuid: other, accepted_at: null })
            .eq('id', row.id);
        if (error) return fail(res, 'Failed to block this player', 500);
        return ok(res, { blocked: true });
    }
    const { error } = await supabase.from('friendships').delete().eq('id', row.id);
    if (error) return fail(res, 'Failed to remove this friend', 500);
    return ok(res, { removed: true });
});

/** Lift a block this player made. */
app.post('/social/friends/:uuid/unblock', requireAuth, async (req, res) => {
    const other = undash(req.params.uuid);
    if (!looksLikeUuid(other)) return fail(res, 'Invalid UUID');
    const { data: row } = await supabase
        .from('friendships')
        .select('*')
        .eq('status', 'blocked')
        .eq('requester_uuid', req.user.uuid)
        .eq('addressee_uuid', other)
        .maybeSingle();
    if (!row) return fail(res, 'You have not blocked this player', 404);
    const { error } = await supabase.from('friendships').delete().eq('id', row.id);
    if (error) return fail(res, 'Failed to unblock this player', 500);
    return ok(res, { unblocked: true });
});
app.post('/social/friend-requests', requireAuth, async (req, res) => {
    // The search result already carries the uuid, so a request sent from it
    // never depends on the name at all. A typed name is resolved the same way
    // search resolves it, including for players who are offline or renamed.
    const { user: target, reason } = await findUserByNameOrUuid({
        uuid: req.body.target_uuid,
        username: req.body.username,
    });
    if (!target) return lookupFailure(res, reason);
    if (target.uuid === req.user.uuid) return fail(res, 'You cannot add yourself');
    const { data: existing } = await supabase
        .from('friendships')
        .select('*')
        .or(
            `and(requester_uuid.eq.${req.user.uuid},addressee_uuid.eq.${target.uuid}),and(requester_uuid.eq.${target.uuid},addressee_uuid.eq.${req.user.uuid})`,
        )
        .maybeSingle();
    if (existing?.status === 'blocked') {
        // Never say who blocked whom: that is the point of blocking.
        return fail(res, 'This user cannot be added right now', 403);
    }
    if (existing?.status === 'pending' && existing.addressee_uuid === req.user.uuid) {
        // They asked first. Sending a request back means yes.
        const { data: accepted, error: acceptErr } = await supabase
            .from('friendships')
            .update({ status: 'accepted', accepted_at: new Date().toISOString() })
            .eq('id', existing.id)
            .select()
            .single();
        if (acceptErr) return fail(res, 'Failed to accept friend request', 500);
        await createNotification(
            target.uuid,
            'friend_accept',
            'Friend request accepted',
            `${req.user.username} accepted your friend request.`,
            { request_id: existing.id },
        );
        return ok(res, { request: accepted, message: `You and ${target.username} are now friends` });
    }
    if (existing)
        return ok(res, {
            request: existing,
            message:
                existing.status === 'accepted'
                    ? 'Already friends'
                    : 'Friend request already pending',
        });
    const { data, error } = await supabase
        .from('friendships')
        .insert({
            requester_uuid: req.user.uuid,
            addressee_uuid: target.uuid,
            status: 'pending',
            created_at: new Date().toISOString(),
        })
        .select()
        .single();
    if (error) return fail(res, 'Failed to send friend request', 500);
    await createNotification(
        target.uuid,
        'friend_request',
        'New friend request',
        `${req.user.username} sent you a friend request.`,
        {
            request_id: data.id,
        },
    );
    return ok(
        res,
        {
            request: data,
        },
        201,
    );
});
app.post('/social/friend-requests/:id/accept', requireAuth, async (req, res) => {
    const { data: row } = await supabase
        .from('friendships')
        .select('*')
        .eq('id', req.params.id)
        .eq('addressee_uuid', req.user.uuid)
        .maybeSingle();
    if (!row) return fail(res, 'Friend request not found', 404);
    const { data, error } = await supabase
        .from('friendships')
        .update({
            status: 'accepted',
            accepted_at: new Date().toISOString(),
        })
        .eq('id', req.params.id)
        .select()
        .single();
    if (error) return fail(res, 'Failed to accept friend request', 500);
    await createNotification(
        row.requester_uuid,
        'friend_accept',
        'Friend request accepted',
        `${req.user.username} accepted your friend request.`,
        {
            request_id: row.id,
        },
    );
    return ok(res, {
        friendship: data,
    });
});
app.post('/social/friend-requests/:id/decline', requireAuth, async (req, res) => {
    const { data: row } = await supabase
        .from('friendships')
        .select('id, addressee_uuid')
        .eq('id', req.params.id)
        .eq('addressee_uuid', req.user.uuid)
        .maybeSingle();
    if (!row) return fail(res, 'Friend request not found', 404);
    const { error } = await supabase.from('friendships').delete().eq('id', req.params.id);
    if (error) return fail(res, 'Failed to decline friend request', 500);
    return ok(res, { declined: true });
});
/**
 * A conversation. `since` returns only what arrived after that timestamp, which
 * is what the launcher polls with, so an open chat costs one small query.
 *
 * The uuid is checked before it goes anywhere near the filter: it is
 * interpolated into a PostgREST `or()` expression, where a crafted value would
 * otherwise change the filter itself.
 */
app.get('/social/messages/:friendUuid', requireAuth, async (req, res) => {
    const friendUuid = undash(req.params.friendUuid);
    if (!looksLikeUuid(friendUuid)) return fail(res, 'Invalid UUID');
    const pair = `and(sender_uuid.eq.${req.user.uuid},recipient_uuid.eq.${friendUuid}),and(sender_uuid.eq.${friendUuid},recipient_uuid.eq.${req.user.uuid})`;
    let query = supabase.from('messages').select('*').or(pair);
    const since = String(req.query.since || '').trim();
    if (since) {
        const at = new Date(since);
        if (Number.isNaN(at.getTime())) return fail(res, 'Invalid since timestamp');
        query = query.gt('created_at', at.toISOString());
    }
    const { data, error } = await query.order('created_at', { ascending: true }).limit(200);
    if (error) return fail(res, 'Messages are not ready yet', 503);

    // Reading the conversation is what marks it read. Only their messages, and
    // only the ones actually returned, so nothing newer is silently cleared.
    const unreadIds = (data ?? [])
        .filter((m) => m.recipient_uuid === req.user.uuid && !m.read_at)
        .map((m) => m.id);
    let readAt = null;
    if (unreadIds.length) {
        readAt = new Date().toISOString();
        const { error: readErr } = await supabase.from('messages').update({ read_at: readAt }).in('id', unreadIds);
        if (readErr) log.warn('Social/Messages', `Could not mark read: ${readErr.message}`);
        else for (const m of data) if (unreadIds.includes(m.id)) m.read_at = readAt;
    }
    return ok(res, { messages: data ?? [], readAt });
});
app.post('/social/messages', requireAuth, async (req, res) => {
    const recipient = undash(req.body.recipient_uuid);
    const body = String(req.body.body || '').trim();
    const attachment = safeJson(req.body.attachment);
    if (!recipient) return fail(res, 'recipient_uuid is required');
    if (!looksLikeUuid(recipient)) return fail(res, 'Invalid UUID');
    if (recipient === req.user.uuid) return fail(res, 'You cannot message yourself');
    // Friends only. Without this, any account could message any player it knew
    // the uuid of, and a blocked player could keep writing.
    const { data: friendship } = await supabase
        .from('friendships')
        .select('status')
        .or(
            `and(requester_uuid.eq.${req.user.uuid},addressee_uuid.eq.${recipient}),and(requester_uuid.eq.${recipient},addressee_uuid.eq.${req.user.uuid})`,
        )
        .maybeSingle();
    if (friendship?.status !== 'accepted') return fail(res, 'You can only message your friends', 403);
    if (!body && !attachment.url) return fail(res, 'Message body or attachment is required');
    if (body.length > 4000) return fail(res, 'Message is too long');
    if (attachment.size && Number(attachment.size) > 1024 * 1024 * 1024)
        return fail(res, 'Attachments are limited to 1 GB');
    const { data, error } = await supabase
        .from('messages')
        .insert({
            sender_uuid: req.user.uuid,
            recipient_uuid: recipient,
            body,
            attachment,
            created_at: new Date().toISOString(),
        })
        .select()
        .single();
    if (error) return fail(res, 'Failed to send message', 500);
    await createNotification(
        recipient,
        'message',
        `Message from ${req.user.username}`,
        body || 'Sent an attachment.',
        {
            message_id: data.id,
        },
    );
    return ok(
        res,
        {
            message: data,
        },
        201,
    );
});
app.patch('/social/presence', requireAuth, async (req, res) => {
    const now = new Date().toISOString();
    const { error } = await supabase
        .from('users')
        .update({
            last_seen: now,
        })
        .eq('uuid', req.user.uuid);
    if (error) return fail(res, 'Failed to update presence', 500);
    breezeActivity.seen(req.user.uuid, 'launcher');
    return ok(res, {
        lastSeen: now,
    });
});
app.post('/ad-rewards/start', requireAuth, async (req, res) => {
    const { data, error } = await supabase
        .from('ad_reward_sessions')
        .insert({
            user_uuid: req.user.uuid,
            ads_watched: 0,
            status: 'active',
            created_at: new Date().toISOString(),
        })
        .select()
        .single();
    if (error) return fail(res, 'Ad rewards are not ready yet', 503);
    return ok(
        res,
        {
            session: data,
            discount_percent: 0,
        },
        201,
    );
});
app.post('/ad-rewards/:id/progress', requireAuth, async (req, res) => {
    const { data: session } = await supabase
        .from('ad_reward_sessions')
        .select('*')
        .eq('id', req.params.id)
        .eq('user_uuid', req.user.uuid)
        .maybeSingle();
    if (!session || session.status !== 'active') return fail(res, 'Reward session not found', 404);
    const adsWatched = Math.min(6, Number(session.ads_watched || 0) + 1);
    const discount = calcRewardDiscount(adsWatched);
    const { data, error } = await supabase
        .from('ad_reward_sessions')
        .update({
            ads_watched: adsWatched,
            discount_percent: discount,
            updated_at: new Date().toISOString(),
        })
        .eq('id', session.id)
        .select()
        .single();
    if (error) return fail(res, 'Failed to update reward progress', 500);
    return ok(res, {
        session: data,
        discount_percent: discount,
        max_ads: 6,
    });
});
app.post('/ad-rewards/:id/claim', requireAuth, async (req, res) => {
    const { data: session } = await supabase
        .from('ad_reward_sessions')
        .select('*')
        .eq('id', req.params.id)
        .eq('user_uuid', req.user.uuid)
        .maybeSingle();
    if (!session || session.status !== 'active') return fail(res, 'Reward session not found', 404);
    const discount = calcRewardDiscount(Number(session.ads_watched || 0));
    if (discount <= 0) return fail(res, 'Watch at least 2 ads before claiming a reward');
    const user = await dbGetUser(req.user.uuid);
    const code = `${shortCodePrefix(user?.username)}${Math.floor(1000 + Math.random() * 9000)}${uuidv4().slice(0, 4).toUpperCase()}`;
    const { data: promo, error } = await supabase
        .from('promo_codes')
        .insert({
            code,
            discount_percent: discount,
            usage_limit: 1,
            times_used: 0,
            is_active: true,
            owner_uuid: req.user.uuid,
            created_at: new Date().toISOString(),
        })
        .select()
        .single();
    if (error) return fail(res, 'Failed to create reward promo code', 500);
    await supabase
        .from('ad_reward_sessions')
        .update({
            status: 'claimed',
            promo_code_id: promo.id,
            claimed_at: new Date().toISOString(),
        })
        .eq('id', session.id);
    await createNotification(
        req.user.uuid,
        'ad_reward',
        'Reward promo code ready',
        `${discount}% off promo code ${code} is ready.`,
        {
            promo_code: code,
            discount_percent: discount,
        },
    );
    return ok(res, {
        promo,
        discount_percent: discount,
    });
});
app.get('/', (req, res) => {
    const versions = buildVersionsManifest(req);
    const uptime = process.uptime();
    const d = Math.floor(uptime / 86400);
    const h = Math.floor((uptime % 86400) / 3600);
    const m = Math.floor((uptime % 3600) / 60);
    res.json({
        service: 'Breeze Client API',
        version: '2.0.0',
        status: 'operational',
        uptime: `${d}d ${h}h ${m}m`,
        latestLauncher: versions.launcher.latestVersion,
        latestMod: versions.mod.latestVersion,
        timestamp: new Date().toISOString(),
        links: {
            health: '/health',
            docs: 'https://breezeclient.net/docs',
            support: 'support@breezeclient.net',
        },
    });
});
app.get('/health', async (req, res) => {
    const health = {
        status: 'ok',
        database: 'ok',
        storage: 'ok',
        payments: PAYPAL_CLIENT_ID && PAYPAL_CLIENT_SECRET ? PAYPAL_MODE : 'not_configured',
        email: hasMailConfig() ? 'configured' : 'not_configured',
        versions: {
            manifest: fs.existsSync(VERSIONS_MANIFEST_PATH) ? 'configured' : 'environment_fallback',
            launcherDir: fs.existsSync(LAUNCHER_VERSION_DIR),
            modDir: fs.existsSync(MOD_VERSION_DIR),
        },
        timestamp: new Date().toISOString(),
    };
    try {
        const { error } = await supabase.from('users').select('uuid').limit(1);
        if (error) throw error;
    } catch (e) {
        log.error('Health', 'DB check failed', {
            msg: e.message,
        });
        health.database = 'error';
        health.status = 'degraded';
    }
    try {
        const { error } = await supabase.storage.getBucket('capes');
        if (error && ![400, 404].includes(error.status)) throw error;
    } catch (e) {
        log.error('Health', 'Storage check failed', {
            msg: e.message,
        });
        health.storage = 'error';
        health.status = 'degraded';
    }
    res.status(health.status === 'ok' ? 200 : 503).json(health);
});
// Legacy alias kept so any older launcher build still gets music. Breeze FM
// runs on Audius now; see /audius/search and /audius/trending.
app.get('/radio/search', async (req, res) => {
    const query = String(req.query.q || 'lofi')
        .trim()
        .slice(0, 80);
    const q = query.toLowerCase();
    const tracks = RADIO_FALLBACK_TRACKS.filter(
        (track) =>
            !q ||
            track.title.toLowerCase().includes(q) ||
            track.artist.toLowerCase().includes(q) ||
            track.mood.toLowerCase().includes(q),
    );
    res.json({
        provider: 'fallback',
        configured: false,
        tracks: tracks.length ? tracks : RADIO_FALLBACK_TRACKS,
    });
});
app.get('/versions', (req, res) => {
    // This route answers with res.json directly rather than through ok(), which
    // is the wrapper that rewrites our own http:// URLs to https. Behind the
    // production proxy that made every URL in the update manifest http, and the
    // launcher refuses a non-https update before it downloads anything.
    res.json(httpsifyAssetUrls(buildVersionsManifest(req)));
});
function sendVersionFile(req, res, rootDir, label, requestedFile = req.params.file) {
    const fileName = safeArtifactPath(requestedFile);
    if (!fileName) return fail(res, 'Invalid version file', 400);
    const root = path.resolve(rootDir);
    const filePath = path.resolve(root, ...fileName.split('/'));
    const relative = path.relative(root, filePath);
    if (relative.startsWith('..') || path.isAbsolute(relative))
        return fail(res, 'Invalid version file', 400);
    if (!fs.existsSync(filePath)) return fail(res, `${label} artifact not found`, 404);
    return res.download(filePath, path.basename(fileName));
}
// Unreleased launcher builds that approved creators can download for testing
// and content creation. Each channel holds one folder per OS:
//   versions/launcher/testing/<channel>/<os>/Breeze-Client-<version>.<ext>
const TEST_CHANNELS = [
    { id: 'pre-beta', label: 'Pre-Beta', blurb: 'Earliest builds. Expect rough edges and breaking changes.' },
    { id: 'beta', label: 'Beta', blurb: 'Feature-complete builds still being tested.' },
    { id: 'pre-release', label: 'Pre-Release', blurb: 'Release candidates, final checks before public launch.' },
];

// One entry per OS the launcher ships on. `ext` is what the updater matches so
// a macOS client is never offered a .exe.
const BUILD_PLATFORMS = [
    { id: 'windows', label: 'Windows 10 / 11 (64-bit)', ext: '.exe', kind: 'nsis', arch: 'x64' },
    { id: 'macos', label: 'macOS 12+ (Universal)', ext: '.dmg', kind: 'dmg', arch: 'universal' },
    { id: 'linux', label: 'Linux (AppImage, 64-bit)', ext: '.AppImage', kind: 'appimage', arch: 'x86_64' },
];
const PLATFORM_IDS = BUILD_PLATFORMS.map((p) => p.id);

/** Normalise whatever the client calls its OS into one of PLATFORM_IDS. */
function normalizePlatform(raw) {
    const v = String(raw || '').trim().toLowerCase();
    if (!v) return null;
    if (['windows', 'win', 'win32', 'win64'].includes(v)) return 'windows';
    if (['macos', 'mac', 'darwin', 'osx'].includes(v)) return 'macos';
    if (['linux', 'ubuntu', 'debian'].includes(v)) return 'linux';
    return PLATFORM_IDS.includes(v) ? v : null;
}

/** Encode a relative artifact path without turning its slashes into %2F. */
function artifactPathUrl(...segments) {
    return segments
        .flatMap((s) => String(s).split('/'))
        .filter(Boolean)
        .map(encodeURIComponent)
        .join('/');
}

/**
 * Every build in one test channel/OS/format folder, newest first.
 *
 * The layout mirrors the public release tree exactly, so there is one thing to
 * learn rather than two:
 *
 *   testing/<channel>/windows/Breeze-Client-<v>.exe
 *   testing/<channel>/macos/Breeze-Client-<v>.dmg
 *   testing/<channel>/linux/appimage|deb|rpm|flatpak/Breeze-Client-<v>.<ext>
 *
 * Two older layouts are still read so nothing already uploaded disappears:
 * loose files directly in `linux/` are matched by their extension, and loose
 * files at the channel root are treated as Windows.
 */
function readChannelBuilds(base, channelId, platformId, formatId) {
    const spec = LAUNCHER_FORMATS[platformId];
    const meta = spec?.formats?.[formatId];
    if (!meta) return [];
    const ext = `.${meta.ext}`;

    // Single-format OSes keep their files directly under the OS folder; only
    // Linux nests by format, matching the release tree.
    const nests = platformId === 'linux';
    const dirs = nests
        ? [[path.join(LAUNCHER_VERSION_DIR, 'testing', channelId, platformId, formatId), `${platformId}/${formatId}`]]
        : [[path.join(LAUNCHER_VERSION_DIR, 'testing', channelId, platformId), platformId]];

    // Every Linux format also looks in the flat linux/ folder, not just the
    // primary one.
    //
    // Restricting this to AppImage is why a .deb and a .rpm uploaded next to an
    // AppImage in testing/<channel>/linux/ showed as "not published" while the
    // AppImage downloaded fine: each other format only ever looked inside its
    // own subfolder, which nobody had created. Dropping three files in one
    // folder is the obvious thing to do, and the release workflow emits them
    // that way.
    //
    // There is no ambiguity to protect against: the loop below only accepts a
    // file whose extension matches this format, so a .deb in the flat folder
    // can be claimed by the deb format and by nothing else.
    if (nests) {
        dirs.push([path.join(LAUNCHER_VERSION_DIR, 'testing', channelId, platformId), platformId]);
    }
    if (platformId === 'windows') {
        dirs.push([path.join(LAUNCHER_VERSION_DIR, 'testing', channelId), '']);
    }

    const out = [];
    const seen = new Set();
    for (const [fromDir, relPrefix] of dirs) {
        if (!fs.existsSync(fromDir)) continue;
        for (const f of fs.readdirSync(fromDir)) {
            if (f.startsWith('.') || /^readme/i.test(f)) continue;
            if (seen.has(f)) continue;
            let st;
            try { st = fs.statSync(path.join(fromDir, f)); } catch { continue; }
            if (!st.isFile()) continue;
            if (!f.toLowerCase().endsWith(ext.toLowerCase())) continue;
            seen.add(f);
            // Strip the extension before parsing: the pre-release suffix group
            // is greedy, so "1.0.7-beta.1.exe" would otherwise yield a version
            // of "1.0.7-beta.1.exe" and compare/display wrongly.
            // The same reading the release scan does, so a test build named by
            // the build system reports its version and architecture too.
            const { version, arch } = describeArtifactName(f, meta);
            out.push({
                file: f,
                version,
                arch,
                platform: platformId,
                format: formatId,
                size: st.size,
                // Test builds are installed by the same updater as stable ones,
                // so they need the same integrity data.
                sha256: artifactSha256(path.join(fromDir, f)),
                updatedAt: st.mtime.toISOString(),
                // The path is what a download link is issued for; the URL is
                // kept for anything that already reads it.
                path: ['testing', channelId, relPrefix, f].filter(Boolean).join('/'),
                downloadUrl: `${base}/versions/launcher/${artifactPathUrl('testing', channelId, relPrefix, f)}`,
            });
        }
    }
    return out.sort((a, b) => (cmpVersions(b.version || '0.0.0', a.version || '0.0.0') || (a.updatedAt < b.updatedAt ? 1 : -1)));
}
app.get(
    '/creator/test-builds',
    requireAuth,
    requireRole(...CREATOR_ACCESS_ROLES),
    (req, res) => {
        const CTX = 'Creator/TestBuilds';
        try {
            const base = getRequestBaseUrl(req);
            const channels = TEST_CHANNELS.map((c) => {
                // Per-OS buckets, each now split by installer format, so the
                // dashboard can show a Linux row with its four formats rather
                // than one ambiguous Linux entry.
                const platforms = {};
                let all = [];
                for (const p of BUILD_PLATFORMS) {
                    const spec = LAUNCHER_FORMATS[p.id];
                    const formats = {};
                    let osBuilds = [];
                    for (const formatId of Object.keys(spec?.formats || {})) {
                        let builds = [];
                        try {
                            builds = readChannelBuilds(base, c.id, p.id, formatId);
                        } catch (e) {
                            log.warn(CTX, `Could not read ${c.id}/${p.id}/${formatId}: ${e.message}`);
                        }
                        const fmeta = spec.formats[formatId];
                        formats[formatId] = {
                            label: fmeta.label,
                            hint: fmeta.hint,
                            ext: `.${fmeta.ext}`,
                            primary: formatId === spec.primary,
                            available: builds.length > 0,
                            latest: builds[0] || null,
                            builds,
                        };
                        osBuilds = osBuilds.concat(builds);
                    }
                    osBuilds.sort((a, b) => cmpVersions(b.version || '0.0.0', a.version || '0.0.0'));
                    platforms[p.id] = {
                        label: p.label,
                        ext: p.ext,
                        arch: p.arch,
                        primaryFormat: spec?.primary || null,
                        formats,
                        available: osBuilds.length > 0,
                        // Flat per-OS fields kept so an older dashboard build
                        // that knows nothing about formats keeps working.
                        latest: osBuilds[0] || null,
                        builds: osBuilds,
                    };
                    all = all.concat(osBuilds);
                }
                all.sort((a, b) => cmpVersions(b.version || '0.0.0', a.version || '0.0.0'));
                return {
                    ...c,
                    platforms,
                    latestVersion: all[0]?.version || null,
                    // Flat list kept so any older dashboard build keeps working.
                    builds: all,
                };
            });
            return ok(res, { channels, platforms: BUILD_PLATFORMS, formats: LAUNCHER_FORMATS });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Failed to list test builds', 500);
        }
    },
);
/**
 * The download URL for a gated test build, carrying its own proof.
 *
 * Both callers need it: the dashboard, whose click is a navigation, and the
 * updater, which downloads with no Authorization header. An update may be
 * started a while after the check that offered it, so this lives longer than a
 * dashboard click but is still measured in minutes.
 */
const UPDATE_LINK_TTL_SECONDS = 1800;
function testBuildDownloadUrl(req, build, uuid) {
    if (!build?.downloadUrl) return null;
    if (!uuid || !build.path) return build.downloadUrl;
    const token = jwt.sign({ uuid, path: build.path }, JWT_SECRET, {
        expiresIn: UPDATE_LINK_TTL_SECONDS,
        audience: DOWNLOAD_TOKEN_AUDIENCE,
    });
    return `${build.downloadUrl}?t=${encodeURIComponent(token)}`;
}

/**
 * A link the browser can actually follow to a test build.
 *
 * The dashboard holds a bearer token, but a download is a navigation and a
 * navigation sends no headers, so the file route answered "Authentication
 * required" and the browser displayed that JSON instead of downloading. This
 * exchanges the token for a link that carries its own short-lived proof, tied
 * to one artifact path and one account, and the role is checked again when the
 * file is served.
 */
app.post('/creator/test-builds/link', requireAuth, requireRole(...CREATOR_ACCESS_ROLES), (req, res) => {
    const safe = safeArtifactPath(String(req.body?.path || '').replace(/^\/+/, ''));
    if (!safe || !/^testing\//i.test(safe)) return fail(res, 'That is not a test build path');
    const full = path.join(LAUNCHER_VERSION_DIR, safe);
    if (!full.startsWith(LAUNCHER_VERSION_DIR) || !fs.existsSync(full)) return fail(res, 'That build is not here', 404);
    const token = jwt.sign(
        { uuid: req.user.uuid, path: safe },
        JWT_SECRET,
        { expiresIn: DOWNLOAD_TOKEN_TTL_SECONDS, audience: DOWNLOAD_TOKEN_AUDIENCE },
    );
    return ok(res, {
        url: `${getRequestBaseUrl(req)}/versions/launcher/${artifactPathUrl(safe)}?t=${encodeURIComponent(token)}`,
        expiresIn: DOWNLOAD_TOKEN_TTL_SECONDS,
    });
});

// One row per public installer download (src/downloadStats.js). Test builds
// are not counted: they are for creators, not players.
const countLauncherDownload = createDownloadCounter({ supabase, log });
app.get(/^\/versions\/launcher\/(.+)$/, async (req, res) => {
    const requested = req.params[0] || '';
    // Public releases stay public: the website links to them and the updater
    // downloads them. Unreleased test builds are a different thing. The listing
    // at /creator/test-builds was already role-gated, but the files themselves
    // were served to anyone who knew the path, so the gate only hid the menu.
    const safe = safeArtifactPath(requested) || '';
    if (/^testing(\/|$)/i.test(safe)) {
        // A browser download is a navigation, and a navigation cannot carry an
        // Authorization header. That is why clicking Download in the admin
        // panel showed {"success":false,"error":"Authentication required"} as a
        // page. A short-lived link issued by /creator/test-builds/link stands in
        // for the header: it names one artifact, for one person, for minutes.
        const linkToken = String(req.query.t || '').trim();
        if (linkToken) {
            let claim;
            try {
                claim = jwt.verify(linkToken, JWT_SECRET, { ...JWT_VERIFY_OPTS, audience: DOWNLOAD_TOKEN_AUDIENCE });
            } catch {
                return fail(res, 'This download link has expired, open the page again', 403);
            }
            if (claim?.path !== safe) return fail(res, 'This link is for a different file', 403);
            const holder = await dbGetUser(claim.uuid).catch(() => null);
            if (!hasCreatorAccess(holder?.role || 'user')) {
                return fail(res, 'Test builds are limited to approved creators', 403);
            }
            log.info('Versions/TestBuild', `${holder?.username || claim.uuid} downloading ${safe} by link`);
            return sendVersionFile(req, res, LAUNCHER_VERSION_DIR, 'Launcher', requested);
        }
        const token = bearerToken(req.headers.authorization);
        if (!token) return fail(res, 'Authentication required', 401);
        let payload;
        try {
            payload = jwt.verify(token, JWT_SECRET, JWT_VERIFY_OPTS);
        } catch {
            return fail(res, 'Invalid or expired token', 403);
        }
        if (payload?.aud === GAME_TOKEN_AUDIENCE) {
            return fail(res, 'This token cannot download builds', 403);
        }
        const user = await dbGetUser(payload.uuid).catch(() => null);
        const role = user?.role || 'user';
        if (!hasCreatorAccess(role)) {
            log.warn('Versions/TestBuild', `Refused ${payload.username || payload.uuid} (role ${role})`);
            return fail(res, 'Test builds are limited to approved creators', 403);
        }
        log.info('Versions/TestBuild', `${payload.username || payload.uuid} downloading ${safe}`);
    } else {
        countLauncherDownload(req, res, safe);
    }
    sendVersionFile(req, res, LAUNCHER_VERSION_DIR, 'Launcher', requested);
});
// ── Breeze mod version → jar mapping ─────────────────────────────────────────
// One jar covers a family of Minecraft versions. Files are named by version
// only (e.g. 1.20.1.jar), no "breeze" in the name. A selected MC version is
// resolved to its family jar here, so the launcher needs no per-version build
// and no client change when the table grows.
// One jar covers a family of Minecraft versions. `versions` is the explicit
// list; `prefix` additionally accepts any future release in that series so a
// newly published 26.1.x keeps working without a code change.
const MOD_VERSION_TABLE = [
    { file: '1.16.5.jar', versions: ['1.16.2', '1.16.3', '1.16.4', '1.16.5'] },
    { file: '1.17.1.jar', versions: ['1.17', '1.17.1'] },
    { file: '1.18.2.jar', versions: ['1.18', '1.18.1', '1.18.2'] },
    { file: '1.19.4.jar', versions: ['1.19.3', '1.19.4'] },
    { file: '1.20.1.jar', versions: ['1.20', '1.20.1'] },
    { file: '1.21.11.jar', versions: ['1.21.9', '1.21.10', '1.21.11'] },
    {
        file: '26.1.2.jar',
        // Enumerated so availability listing can report them; the prefix keeps
        // resolution working for any 26.1.x beyond this list.
        versions: Array.from({ length: 21 }, (_, i) => `26.1.${i}`),
        prefix: '26.1.',
    },
];

function resolveModFile(requested) {
    const v = String(requested || '').trim().replace(/\.jar$/i, '');
    if (!v) return null;
    // A jar named exactly after the requested version comes first. Breeze 2.x
    // ships one jar per Minecraft version, each declaring only that version,
    // so 1.20 has to get 1.20.jar: the family row below would hand it
    // 1.20.1.jar, which the runtime route then refuses (409). With no jar of
    // its own, a version still falls back to its family row as before.
    if (/^[0-9A-Za-z._-]{1,32}$/.test(v) && !v.includes('..') && modFileExists(`${v}.jar`)) {
        return `${v}.jar`;
    }
    for (const row of MOD_VERSION_TABLE) {
        if (row.versions && row.versions.includes(v)) return row.file;
        if (row.prefix && v.startsWith(row.prefix)) return row.file;
    }
    // Fall back to an exact filename so a directly-named jar still serves.
    return `${v}.jar`;
}

/** Is this jar actually published on disk? */
function modFileExists(file) {
    if (!file) return false;
    try {
        return fs.existsSync(path.resolve(MOD_VERSION_DIR, file));
    } catch {
        return false;
    }
}

/**
 * Every Minecraft version the API can actually serve a mod for, derived from
 * the SAME table the download path uses.
 *
 * Listing raw filenames was the bug: with only 26.1.2.jar on disk the launcher
 * reported 26.1.2 as supported and 26.1.1 as unsupported, even though both map
 * to that one jar. Availability and resolution must never diverge.
 */
function supportedModVersions() {
    const out = new Set();
    for (const row of MOD_VERSION_TABLE) {
        if (!modFileExists(row.file)) continue;
        for (const v of row.versions || []) out.add(v);
        // A jar named after a Minecraft version also supports that version.
        out.add(row.file.replace(/\.jar$/i, ''));
    }
    // Any jar dropped in that the table does not mention still counts.
    try {
        for (const name of fs.readdirSync(MOD_VERSION_DIR)) {
            if (name.toLowerCase().endsWith('.jar')) out.add(name.slice(0, -4));
        }
    } catch { /* directory may not exist yet */ }
    return [...out].sort();
}
// Compare two dotted versions (1.0.10 > 1.0.9, 1.1.0-beta.2 > 1.1.0-beta.1).
/**
 * Semver-aware comparison. Returns 1 / 0 / -1.
 *
 * The pre-release tag matters: 1.0.7-beta.1 must rank BELOW 1.0.7, otherwise a
 * beta tester is told they are up to date forever and never receives the stable
 * release that supersedes their build.
 */
function cmpVersions(a, b) {
    const split = (v) => {
        const s = String(v || '0').trim().replace(/^v/i, '');
        const [core, ...rest] = s.split('+')[0].split('-');
        return { core: core.split('.'), pre: rest.join('-') };
    };
    const A = split(a);
    const B = split(b);

    for (let i = 0; i < Math.max(A.core.length, B.core.length); i++) {
        const x = parseInt(A.core[i], 10) || 0;
        const y = parseInt(B.core[i], 10) || 0;
        if (x !== y) return x > y ? 1 : -1;
    }

    // Same release core: no pre-release tag wins (1.0.7 > 1.0.7-beta.1).
    if (!A.pre && !B.pre) return 0;
    if (!A.pre) return 1;
    if (!B.pre) return -1;

    // Both pre-release: compare dot-separated identifiers, numeric before
    // alphanumeric, per semver ordering (beta.2 > beta.1, rc > beta).
    const pa = A.pre.split('.');
    const pb = B.pre.split('.');
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const x = pa[i];
        const y = pb[i];
        if (x === undefined) return -1;
        if (y === undefined) return 1;
        const nx = /^\d+$/.test(x);
        const ny = /^\d+$/.test(y);
        if (nx && ny) {
            const d = parseInt(x, 10) - parseInt(y, 10);
            if (d !== 0) return d > 0 ? 1 : -1;
        } else if (nx !== ny) {
            return nx ? -1 : 1;
        } else if (x !== y) {
            return x > y ? 1 : -1;
        }
    }
    return 0;
}
// Role-aware update check. Normal users only ever see the Stable channel and are
// told they are up to date even if newer test builds exist. Creators/Admins get
// the Pre-Beta/Beta/Pre-Release testing channels; Owners get everything.
const CHANNEL_ORDER = ['pre-release', 'beta', 'pre-beta'];
app.get('/versions/check', optionalAuth, async (req, res) => {
    const CTX = 'Versions/Check';
    try {
        const current = String(req.query.current || '0.0.0').trim();
        // The client tells us its OS so a Mac is never handed a .exe. Defaults
        // to windows only when nothing usable was supplied.
        const platform = normalizePlatform(req.query.platform || req.query.os) || 'windows';
        // A Linux client installed from .deb can ask for .deb back. Anything
        // unrecognised falls to the platform's primary format, which is what
        // the release manifest's primaryFormat already promises the updater
        // uses (AppImage on Linux, because it needs no root).
        const platformSpec = LAUNCHER_FORMATS[platform] || {};
        const requestedFormat = String(req.query.format || '').trim().toLowerCase();
        const updateFormat = platformSpec.formats?.[requestedFormat]
            ? requestedFormat
            : platformSpec.primary;
        const manifest = buildVersionsManifest(req);
        const plat = (manifest.launcher.platforms && manifest.launcher.platforms[platform]) || {};
        // Answer in the format that was asked for. The flat platform fields
        // describe the primary format only, so a Linux client installed from a
        // .deb was told its format was "deb" and then handed the AppImage.
        const chosen = (plat.formats && plat.formats[updateFormat]) || plat;
        const stable = {
            channel: 'stable',
            platform,
            format: chosen.format || updateFormat,
            version: chosen.version || plat.version || manifest.launcher.latestVersion,
            // A platform with no published build must not advertise a URL.
            url: chosen.available ? chosen.downloadUrl || null : null,
            available: !!chosen.available,
            // The updater verifies what it downloads, so the check response has
            // to carry the hash. Without these fields the launcher had nothing
            // to compare against and installed whatever arrived.
            fileName: chosen.available ? chosen.fileName || null : null,
            sha256: chosen.available ? chosen.sha256 || null : null,
            size: chosen.available ? chosen.size ?? null : null,
            changelog: manifest.launcher.changelog || null,
            mandatory: !!manifest.launcher.mandatory,
        };

        // Which testing channels may this user see?
        let role = 'user';
        if (req.user) {
            const u = await dbGetUser(req.user.uuid).catch(() => null);
            role = u?.role || 'user';
        }
        const canTest = hasCreatorAccess(role);
        const allowed = canTest ? CHANNEL_ORDER : [];

        // Newest build for THIS platform across stable + any channel the user
        // is allowed to see. A release counts only once its installer is in
        // the release folder: LATEST_LAUNCHER_VERSION is raised before that,
        // and a declared release with no file used to outrank a test build
        // of the same number, so testers were offered nothing at all.
        let best = stable.url ? { ...stable } : null;
        const base = getRequestBaseUrl(req);
        const channelMeta = Object.fromEntries(TEST_CHANNELS.map((c) => [c.id, c]));
        for (const ch of allowed) {
            for (const build of readChannelBuilds(base, ch, platform, updateFormat)) {
                if (!build.version) continue;
                if (!best || cmpVersions(build.version, best.version) > 0) {
                    best = {
                        channel: ch,
                        platform,
                        format: updateFormat,
                        version: build.version,
                        // A test build is behind the creator gate, and the
                        // updater downloads with no Authorization header, so
                        // the URL carries its own short-lived proof. Without
                        // this every testing update offered here answered 401
                        // when the launcher tried to fetch it, and no installed
                        // build can be changed after the fact.
                        url: testBuildDownloadUrl(req, build, req.user?.uuid),
                        available: true,
                        size: build.size,
                        fileName: build.file,
                        sha256: build.sha256 || null,
                        changelog: channelMeta[ch]?.blurb || `${ch.replace('-', ' ')} build`,
                        mandatory: false,
                    };
                }
            }
        }

        // Never offer an "update" we cannot actually hand the user a file for.
        const updateAvailable = !!best && !!best.url && cmpVersions(best.version, current) > 0;
        return ok(res, {
            current,
            platform,
            format: updateFormat,
            role,
            channels: allowed,
            stable,
            update: updateAvailable ? best : null,
            upToDate: !updateAvailable,
        });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Update check failed', 500);
    }
});
app.get('/versions/mod/list', (req, res) => {
    // Every Minecraft version that resolves to a published jar, not just the
    // jar filenames. The launcher tests membership of this list to decide
    // whether to show "Breeze Mod ready", so it has to agree with the resolver.
    const versions = supportedModVersions();
    let files = [];
    try {
        files = fs
            .readdirSync(MOD_VERSION_DIR)
            .filter((name) => name.toLowerCase().endsWith('.jar'))
            .map((name) => name.slice(0, -4))
            .sort();
    } catch { /* directory may not exist yet */ }
    return ok(res, {
        versions,
        count: versions.length,
        files,
        table: MOD_VERSION_TABLE,
    });
});
// Resolve a Minecraft version to its mod jar (+ whether it is published). The
// launcher can call this before launch to know if a mod exists for the version.
app.get('/versions/mod/resolve', (req, res) => {
    const mc = String(req.query.mc || req.query.version || '').trim();
    if (!mc) return fail(res, 'mc version required');
    const file = resolveModFile(mc);
    const abs = file ? path.resolve(MOD_VERSION_DIR, file) : null;
    const available = !!abs && fs.existsSync(abs);
    // Read what the jar itself declares rather than trusting its filename. Jars
    // have shipped named 1.20.4.jar while declaring "minecraft": ">=1.20 <1.20.2",
    // which Fabric refuses to load, leaving the player with no Breeze and no
    // explanation. The launcher uses this to build its compatibility chain.
    const meta = available ? readModJarMetadata(abs) : null;
    const declaresSupport = meta ? versionSatisfies(mc, meta.minecraft) : null;
    const supportStatus = !available
        ? 'unavailable'
        : declaresSupport === false
          ? 'incompatible'
          : declaresSupport === true
            ? 'supported'
            : 'unknown';
    return ok(res, {
        mc,
        file,
        available,
        // The jar is fetched through the authorized runtime endpoint, not from a
        // browsable path. This URL needs a Breeze token.
        url: available ? `${getRequestBaseUrl(req)}/mod/runtime/${encodeURIComponent(mc)}` : null,
        requiresAuth: true,
        sha256: available ? artifactSha256(abs) : null,
        size: available ? fs.statSync(abs).size : null,
        modVersion: meta?.modVersion ?? null,
        minecraftRange: meta?.minecraft ?? null,
        minLoader: meta?.fabricLoader ?? null,
        requiresFabricApi: meta ? meta.fabricApi !== null && meta.fabricApi !== undefined : null,
        declaresSupport,
        supportStatus,
    });
});

// ─── Breeze mod runtime distribution (v1.0.22) ───────────────────────────────
// The Breeze mod jar is a runtime dependency the launcher fetches while
// preparing a Minecraft instance, not a public download. It used to be served
// from a browsable path, so anyone who guessed the URL could take any build.
//
// Retrieval now needs a Breeze account token, and the filename is never taken
// from the request: the requested Minecraft version is looked up in the
// compatibility table and the table decides which file is served. That makes
// path traversal structurally impossible rather than filtered.
const MOD_PUBLIC_LEGACY = process.env.BREEZE_MOD_PUBLIC_LEGACY === 'true';
const modRuntimeLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: parseInt(process.env.BREEZE_MOD_RUNTIME_LIMIT ?? '30', 10),
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many mod downloads, slow down.' },
});
app.get('/mod/runtime/:mc', modRuntimeLimiter, requireAuth, (req, res) => {
    const CTX = 'Mod/Runtime';
    const requested = String(req.params.mc || '').trim();
    if (!/^[0-9A-Za-z._-]{1,32}$/.test(requested)) {
        return fail(res, 'Invalid Minecraft version', 400);
    }
    const file = resolveModFile(requested);
    if (!file || !/^[0-9A-Za-z._-]+\.jar$/.test(file)) {
        return fail(res, 'No Breeze build is published for that Minecraft version', 404);
    }
    const root = path.resolve(MOD_VERSION_DIR);
    const abs = path.resolve(root, file);
    const rel = path.relative(root, abs);
    if (rel.startsWith('..') || path.isAbsolute(rel) || !fs.existsSync(abs)) {
        return fail(res, 'No Breeze build is published for that Minecraft version', 404);
    }
    // Refuse to hand over a build the jar itself says cannot run on the
    // requested version. Fabric would reject it at load time anyway, and the
    // player would see Breeze silently missing instead of an explanation.
    // Only an explicit "no" blocks the download; an unparseable range does not.
    const meta = readModJarMetadata(abs);
    const declaresSupport = meta ? versionSatisfies(requested, meta.minecraft) : null;
    if (declaresSupport === false) {
        log.warn(
            CTX,
            `Refused ${file} for Minecraft ${requested}: the jar declares "${meta.minecraft}"`,
        );
        return fail(
            res,
            `The published Breeze build declares support for "${meta.minecraft}", not ${requested}`,
            409,
        );
    }

    const digest = artifactSha256(abs);
    if (digest) res.setHeader('X-Breeze-Sha256', digest);
    res.setHeader('X-Breeze-Mod-File', file);
    if (meta?.modVersion) res.setHeader('X-Breeze-Mod-Version', meta.modVersion);
    if (meta?.fabricLoader) res.setHeader('X-Breeze-Min-Loader', String(meta.fabricLoader));
    // Who fetched what, never the token that authorized it.
    log.info(CTX, `${req.user.username || req.user.uuid} fetched ${file} for Minecraft ${requested}`);
    return res.download(abs, file);
});

app.get(/^\/versions\/mod\/(.+)$/, (req, res) => {
    // Legacy public path. Off by default; BREEZE_MOD_PUBLIC_LEGACY=true reopens
    // it for the deployment window while launchers older than 1.0.22 are still
    // in use, since those clients only know this URL.
    if (!MOD_PUBLIC_LEGACY) {
        // Visible in the log so an operator can tell "players lost Breeze"
        // apart from "nobody is using the old path any more", and knows when it
        // is safe to leave BREEZE_MOD_PUBLIC_LEGACY off for good.
        log.warn(
            'Mod/Legacy',
            `Refused the old public jar path for "${String(req.params[0] || '').slice(0, 32)}" (launcher older than 1.0.22)`,
        );
        return fail(res, 'Fetch the Breeze mod through GET /mod/runtime/:minecraftVersion', 404);
    }
    const resolved = resolveModFile(req.params[0]);
    sendVersionFile(req, res, MOD_VERSION_DIR, 'Breeze mod', resolved);
});
app.get('/versions/launcher/:file', (req, res) => {
    sendVersionFile(req, res, LAUNCHER_VERSION_DIR, 'Launcher');
});
app.get('/system/version', (req, res) => {
    const manifest = buildVersionsManifest(req);
    return res.json({
        ...manifest,
        latestVersion: manifest.launcher.latestVersion,
        downloadUrl: manifest.launcher.downloadUrl,
        mandatory: manifest.launcher.mandatory,
        changelog: manifest.launcher.changelog,
    });
});
const COSMETIC_SLOTS = Object.freeze([
    'hat',
    'wings',
    'pet',
    'cape',
    'shield',
    'aura',
    'back',
    'trail',
]);
const isValidSlot = (s) => typeof s === 'string' && COSMETIC_SLOTS.includes(s);
app.get('/cosmetics', async (req, res) => {
    const CTX = 'Cosmetics/List';
    try {
        let q = supabase
            .from('cosmetics')
            .select(
                'id, slot, name, description, rarity, price_usd, model_url, thumbnail_url, idle_animation, random_animations, animation_chance, creator_id, is_public, metadata, created_at',
            )
            .eq('is_public', true)
            .order('created_at', {
                ascending: false,
            });
        if (req.query.slot) {
            if (!isValidSlot(req.query.slot)) return fail(res, 'Invalid slot');
            q = q.eq('slot', req.query.slot);
        }
        const { data, error } = await q;
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch cosmetics', 500);
        }
        return ok(res, {
            cosmetics: data ?? [],
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
/**
 * What one account owns and wears, for its Wardrobe. Shared by the launcher's
 * route (account token) and the mod's (a token for that same player), so the
 * two can never disagree on what the player owns.
 */
async function sendOwnedCosmetics(res, userUuid, CTX) {
    try {
        const { data: owned, error: ownedErr } = await supabase
            .from('user_cosmetics')
            .select(
                'cosmetic_id, acquired_at, cosmetic:cosmetics(id, slot, name, description, rarity, model_url, thumbnail_url, idle_animation, random_animations, animation_chance, metadata)',
            )
            .eq('user_uuid', userUuid);
        if (ownedErr) {
            log.error(CTX, 'DB error (owned)', {
                msg: ownedErr.message,
            });
            return fail(res, 'Failed to fetch owned cosmetics', 500);
        }
        const { data: equipped, error: equipErr } = await supabase
            .from('user_equipped_cosmetics')
            .select('slot, cosmetic_id')
            .eq('user_uuid', userUuid);
        if (equipErr) {
            log.error(CTX, 'DB error (equipped)', {
                msg: equipErr.message,
            });
            return fail(res, 'Failed to fetch equipped cosmetics', 500);
        }
        const equippedMap = {};
        for (const row of equipped ?? []) equippedMap[row.slot] = row.cosmetic_id;
        const me = await dbGetUser(userUuid);
        return ok(res, {
            owned: owned ?? [],
            equipped: equippedMap,
            placements: placementsOf(me),
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
}
app.get('/cosmetics/owned', requireAuth, (req, res) => sendOwnedCosmetics(res, req.user.uuid, 'Cosmetics/Owned'));
// The mod's Wardrobe: the game holds a game token, which requireAuth refuses.
app.get('/cosmetics/owned/:modUuid', lookupLimiter, requirePlayerToken, (req, res) =>
    sendOwnedCosmetics(res, req.modUuid, 'Cosmetics/OwnedMod'));
app.get('/cosmetics/equipped/:uuid', async (req, res) => {
    const CTX = 'Cosmetics/Equipped';
    try {
        const { uuid } = req.params;
        if (!uuid || uuid.length < 10) return fail(res, 'Invalid UUID');
        const { data, error } = await supabase
            .from('user_equipped_cosmetics')
            .select(
                'slot, cosmetic_id, cosmetic:cosmetics(id, slot, name, model_url, thumbnail_url, idle_animation, random_animations, animation_chance, metadata)',
            )
            .eq('user_uuid', uuid);
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch equipped cosmetics', 500);
        }
        const { data: wearer } = await supabase.from('users').select('metadata').eq('uuid', uuid).maybeSingle();
        const placements = placementsOf(wearer);
        return ok(res, {
            uuid,
            equipped: (data ?? []).map((row) => ({ ...row, placement: placements[row.cosmetic_id] || null })),
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.post('/cosmetics/batch', async (req, res) => {
    const CTX = 'Cosmetics/Batch';
    try {
        const { uuids } = req.body ?? {};
        if (!Array.isArray(uuids) || uuids.length === 0)
            return fail(res, 'uuids must be a non-empty array');
        if (uuids.length > 100) return fail(res, 'Maximum 100 UUIDs per request');
        const [cosmeticsResult, capesResult] = await Promise.all([
            supabase
                .from('user_equipped_cosmetics')
                .select(
                    'user_uuid, slot, cosmetic_id, cosmetic:cosmetics(id, slot, name, model_url, thumbnail_url, idle_animation, random_animations, animation_chance, metadata)',
                )
                .in('user_uuid', uuids),
            supabase
                .from('user_capes')
                .select(
                    'user_uuid, equipped, cape:capes(id, name, image_url, is_animated, animation_frames, animation_fps, rarity)',
                )
                .in('user_uuid', uuids)
                .eq('equipped', true),
        ]);
        if (cosmeticsResult.error) {
            log.error(CTX, 'cosmetics DB error', {
                msg: cosmeticsResult.error.message,
            });
            return fail(res, 'Failed to batch-fetch equipped cosmetics', 500);
        }
        if (capesResult.error) {
            log.warn(CTX, 'capes DB warning', {
                msg: capesResult.error.message,
            });
        }
        const players = {};
        for (const u of uuids) players[u] = [];
        for (const row of cosmeticsResult.data ?? []) {
            (players[row.user_uuid] ??= []).push({
                slot: row.slot,
                cosmetic: row.cosmetic,
            });
        }
        for (const row of capesResult.data ?? []) {
            if (!row.cape) continue;
            const cape = row.cape;
            (players[row.user_uuid] ??= []).push({
                slot: 'cape',
                cosmetic: {
                    id: cape.id,
                    slot: 'cape',
                    name: cape.name,
                    model_url: cape.image_url,
                    thumbnail_url: cape.image_url,
                    is_animated: !!cape.is_animated,
                    animation_frames: cape.animation_frames ?? [],
                    animation_fps: cape.animation_fps ?? 0,
                    metadata: {
                        rarity: cape.rarity,
                    },
                },
            });
        }
        return ok(res, {
            players,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
/**
 * A player's own placement for their cosmetics: { [cosmeticId]: transform }.
 * Kept in users.metadata, which user rows already have and nothing else uses,
 * so this needs no migration. The creator's transform is the default; this
 * replaces it for one player.
 */
function placementsOf(user) {
    const map = user?.metadata?.cosmeticPlacement;
    return map && typeof map === 'object' && !Array.isArray(map) ? map : {};
}

app.put('/cosmetics/:id/placement', requireAuth, async (req, res) => {
    const CTX = 'Cosmetics/Placement';
    try {
        const { data: cosmetic } = await supabase
            .from('cosmetics')
            .select('id')
            .eq('id', req.params.id)
            .maybeSingle();
        if (!cosmetic) return fail(res, 'Cosmetic not found', 404);
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        if (![ROLES.OWNER, ROLES.ADMIN].includes(dbUser.role)) {
            const { data: owned } = await supabase
                .from('user_cosmetics')
                .select('cosmetic_id')
                .eq('user_uuid', req.user.uuid)
                .eq('cosmetic_id', cosmetic.id)
                .maybeSingle();
            if (!owned) return fail(res, 'You do not own this cosmetic', 403);
        }
        // Only an explicit null resets. A missing or malformed body is refused
        // rather than read as "reset" or as the identity transform.
        const raw = req.body ? req.body.transform : undefined;
        if (raw === undefined) return fail(res, 'Send { transform }, or { transform: null } to reset', 400);
        const resetting = raw === null;
        let parsed = raw;
        if (typeof raw === 'string') {
            try { parsed = JSON.parse(raw); } catch { return fail(res, 'transform must be valid JSON', 400); }
        }
        if (!resetting && (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))) {
            return fail(res, 'transform must be an object with offset, rotation and scale', 400);
        }
        const placements = { ...placementsOf(dbUser) };
        if (resetting) delete placements[cosmetic.id];
        else placements[cosmetic.id] = cosmeticAsset.sanitizeTransform(parsed);
        const metadata = { ...(dbUser.metadata && typeof dbUser.metadata === 'object' ? dbUser.metadata : {}), cosmeticPlacement: placements };
        const { error } = await supabase.from('users').update({ metadata }).eq('uuid', req.user.uuid);
        if (error) {
            log.error(CTX, 'DB error', { msg: error.message });
            return fail(res, 'Could not save the placement', 500);
        }
        return ok(res, { placement: resetting ? null : placements[cosmetic.id] });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not save the placement', 500);
    }
});

/**
 * Equip one owned cosmetic in its slot. Shared by the launcher's route and the
 * mod's: same ownership rule, same staff bypass, same row.
 */
async function equipCosmeticFor(res, userUuid, body, CTX) {
    try {
        const { cosmetic_id } = body ?? {};
        if (!cosmetic_id) return fail(res, 'cosmetic_id is required');
        const { data: cosmetic, error: cErr } = await supabase
            .from('cosmetics')
            .select('id, slot')
            .eq('id', cosmetic_id)
            .maybeSingle();
        if (cErr || !cosmetic) return fail(res, 'Cosmetic not found', 404);
        if (!isValidSlot(cosmetic.slot))
            return fail(res, `Cosmetic has invalid slot: ${cosmetic.slot}`, 500);
        const dbUser = await dbGetUser(userUuid);
        const bypass = dbUser && [ROLES.OWNER, ROLES.ADMIN].includes(dbUser.role);
        if (!bypass) {
            const { data: owned, error: ownErr } = await supabase
                .from('user_cosmetics')
                .select('cosmetic_id')
                .eq('user_uuid', userUuid)
                .eq('cosmetic_id', cosmetic_id)
                .maybeSingle();
            if (ownErr) return fail(res, 'DB error checking ownership', 500);
            if (!owned) return fail(res, 'You do not own this cosmetic', 403);
        }
        const { error: upErr } = await supabase.from('user_equipped_cosmetics').upsert(
            {
                user_uuid: userUuid,
                slot: cosmetic.slot,
                cosmetic_id: cosmetic.id,
                equipped_at: new Date().toISOString(),
            },
            {
                onConflict: 'user_uuid,slot',
            },
        );
        if (upErr) {
            log.error(CTX, 'Upsert error', {
                msg: upErr.message,
            });
            return fail(res, 'Failed to equip cosmetic', 500);
        }
        log.info(CTX, `Equipped ${cosmetic.slot}=${cosmetic.id} for ${userUuid}`);
        return ok(res, {
            slot: cosmetic.slot,
            cosmetic_id: cosmetic.id,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
}
app.post('/cosmetics/equip', requireAuth, (req, res) => equipCosmeticFor(res, req.user.uuid, req.body, 'Cosmetics/Equip'));
app.post('/cosmetics/equip/:modUuid', modWriteLimiter, requirePlayerToken, (req, res) =>
    equipCosmeticFor(res, req.modUuid, req.body, 'Cosmetics/EquipMod'));
/** Empty one slot. Shared by the launcher's route and the mod's. */
async function unequipCosmeticFor(res, userUuid, body, CTX) {
    try {
        const { slot } = body ?? {};
        if (!isValidSlot(slot)) return fail(res, 'Invalid slot');
        const { error } = await supabase
            .from('user_equipped_cosmetics')
            .delete()
            .eq('user_uuid', userUuid)
            .eq('slot', slot);
        if (error) {
            log.error(CTX, 'Delete error', {
                msg: error.message,
            });
            return fail(res, 'Failed to unequip', 500);
        }
        log.info(CTX, `Unequipped ${slot} for ${userUuid}`);
        return ok(res, {
            slot,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
}
app.post('/cosmetics/unequip', requireAuth, (req, res) => unequipCosmeticFor(res, req.user.uuid, req.body, 'Cosmetics/Unequip'));
app.post('/cosmetics/unequip/:modUuid', modWriteLimiter, requirePlayerToken, (req, res) =>
    unequipCosmeticFor(res, req.modUuid, req.body, 'Cosmetics/UnequipMod'));
const COSMETIC_UPLOAD_FIELDS = [
    { name: 'model', maxCount: 1 },
    // The files a .gltf points at, when they are not packed in a .zip.
    { name: 'resources', maxCount: cosmeticAsset.LIMITS.resourceFiles },
    { name: 'thumbnail', maxCount: 1 },
];
// Cosmetic uploads have their own size limit: a textured model is routinely
// past the 2 MB the shared uploader allows.
const cosmeticMulter = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: cosmeticAsset.LIMITS.fileBytes, files: cosmeticAsset.LIMITS.resourceFiles + 2 },
});
/**
 * Multer's own errors would otherwise reach the generic handler and come back
 * as "Internal server error", which reads as a crash when the file was simply
 * too big.
 */
function cosmeticFiles(req, res, next) {
    cosmeticMulter.fields(COSMETIC_UPLOAD_FIELDS)(req, res, (err) => {
        if (!err) return next();
        if (err instanceof multer.MulterError) {
            const mb = cosmeticAsset.LIMITS.fileBytes / 1024 / 1024;
            const message = err.code === 'LIMIT_FILE_SIZE'
                ? `Each file can be at most ${mb} MB`
                : err.code === 'LIMIT_FILE_COUNT' || err.code === 'LIMIT_UNEXPECTED_FILE'
                    ? 'Too many files, or a file sent in a field the upload does not use'
                    : 'The upload could not be read';
            return fail(res, message, 400);
        }
        // The multipart parser's own errors ("Unexpected end of form" when a
        // connection drops, a missing boundary) are the request's fault too.
        return fail(res, 'The upload could not be read. Try again.', 400);
    });
}

/**
 * Before any of the body is read: the caller must be allowed to upload
 * cosmetics, and the body must say how big it is and be small enough.
 * Otherwise any signed-in account could make the API buffer a large upload in
 * memory only to be told 403 at the end.
 */
async function cosmeticUploadGate(req, res, next) {
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        if (!hasCreatorAccess(dbUser.role)) return fail(res, 'Creator access required', 403);
        const length = Number(req.headers['content-length']);
        if (!Number.isFinite(length) || length <= 0) return fail(res, 'The upload must say how large it is', 411);
        if (length > cosmeticAsset.LIMITS.requestBytes) {
            return fail(res, `The upload is larger than ${cosmeticAsset.LIMITS.requestBytes / 1024 / 1024} MB`, 413);
        }
        req.cosmeticUploader = dbUser;
        return next();
    } catch (err) {
        log.error('Cosmetics/Gate', 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
}
// Parsing a model is real work; this keeps a script from using the API as a
// free glTF converter.
const cosmeticUploadLimiter = rateLimit({
    windowMs: 60 * 1000,
    max: parseInt(process.env.BREEZE_COSMETIC_UPLOAD_LIMIT ?? '30', 10),
    standardHeaders: true,
    legacyHeaders: false,
    message: { success: false, error: 'Too many uploads, slow down.' },
});

/**
 * Run an upload through the cosmetic pipeline with the form's choices.
 * Legacy launchers send idle_animation and a free-form metadata object; the
 * useful parts of both are honoured as choices, never as measurements.
 */
async function prepareCosmeticUpload(req, slot, clientMetadata) {
    const roles = req.body.animation_roles !== undefined
        ? req.body.animation_roles
        : req.body.idle_animation ? { idle: String(req.body.idle_animation) } : clientMetadata.animationRoles;
    return cosmeticAsset.prepareCosmeticModel(
        [req.files.model[0], ...(req.files.resources || [])],
        {
            slot,
            attachment: req.body.attachment ?? clientMetadata.attachment,
            transform: req.body.transform ?? clientMetadata.transform,
            animationRoles: roles,
        },
    );
}

/**
 * The cosmetic format, for the launcher's upload form and for anyone building
 * against it: which attachments each slot allows, the animation roles, and the
 * limits. Served rather than copied so the form cannot drift from the checks.
 */
app.get('/cosmetics/spec', (req, res) => ok(res, {
    spec: cosmeticAsset.SPEC_VERSION,
    attachments: cosmeticAsset.ATTACHMENTS,
    slotAttachments: cosmeticAsset.SLOT_ATTACHMENTS,
    roles: cosmeticAsset.ROLES,
    limits: cosmeticAsset.LIMITS,
}));

/**
 * Check an upload without saving it. The launcher previews the returned model
 * on the player before publishing, so what the creator sees is exactly the
 * file that will be stored: same textures, same clips, same placement.
 */
app.post('/cosmetics/inspect', requireAuth, cosmeticUploadLimiter, cosmeticUploadGate, cosmeticFiles, async (req, res) => {
    const CTX = 'Cosmetics/Inspect';
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        if (!hasCreatorAccess(dbUser.role)) return fail(res, 'Creator access required', 403);
        const slot = req.body.slot?.trim()?.toLowerCase();
        if (!isValidSlot(slot)) return fail(res, `Invalid slot. Allowed: ${COSMETIC_SLOTS.join(', ')}`);
        if (!req.files?.model?.[0]) return fail(res, 'A model file is required (.glb, .gltf or .zip)');
        const prepared = await prepareCosmeticUpload(req, slot, {});
        return ok(res, { metadata: prepared.metadata, model: prepared.glb.toString('base64') });
    } catch (err) {
        if (err instanceof cosmeticAsset.CosmeticAssetError) return fail(res, err.message, 400);
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not read that model', 500);
    }
});
// Same env-configurable ceiling as capes, 2 was far too low and made a
// creator's third upload look like it had silently vanished.
const CREATOR_COSMETIC_LIMIT = parseInt(process.env.CREATOR_COSMETIC_LIMIT ?? '25', 10);
/**
 * Who a gift is for.
 *
 * The name is matched exactly, and a name that matches nobody is looked up
 * through Mojang and resolved by UUID. Before this, the name went straight into
 * ilike as a pattern: "_" is a wildcard, so a gift addressed to "bushpig_"
 * could be delivered to a different account entirely.
 */
async function resolveGiftRecipient(body) {
    return findUserByNameOrUuid({
        uuid: body.target_uuid || body.recipient_uuid,
        username: body.username || body.recipient_username,
    });
}
app.post('/gifts/grant', requireAuth, async (req, res) => {
    const CTX = 'Gifts/AdminGrant';
    try {
        const actor = await dbGetUser(req.user.uuid);
        if (!actor || ![ROLES.OWNER, ROLES.ADMIN].includes(actor.role))
            return fail(res, 'Owner or admin access required', 403);
        const { user: recipient, reason } = await resolveGiftRecipient(req.body);
        if (!recipient) return lookupFailure(res, reason);
        const now = new Date().toISOString();
        const capeId = String(req.body.cape_id || '').trim();
        const cosmeticId = String(req.body.cosmetic_id || '').trim();
        if (!capeId && !cosmeticId) return fail(res, 'cape_id or cosmetic_id is required');
        if (capeId) {
            const { error } = await supabase.from('user_capes').upsert(
                {
                    user_uuid: recipient.uuid,
                    cape_id: capeId,
                    equipped: false,
                    acquired_at: now,
                },
                {
                    onConflict: 'user_uuid,cape_id',
                    ignoreDuplicates: true,
                },
            );
            if (error) return fail(res, 'Failed to grant cape', 500);
        }
        if (cosmeticId) {
            const { error } = await supabase.from('user_cosmetics').upsert(
                {
                    user_uuid: recipient.uuid,
                    cosmetic_id: cosmeticId,
                    acquired_at: now,
                },
                {
                    onConflict: 'user_uuid,cosmetic_id',
                    ignoreDuplicates: true,
                },
            );
            if (error) return fail(res, 'Failed to grant cosmetic', 500);
        }
        await supabase
            .from('gifts')
            .insert({
                sender_uuid: req.user.uuid,
                recipient_uuid: recipient.uuid,
                cape_id: capeId || null,
                cosmetic_id: cosmeticId || null,
                source: 'admin_grant',
                status: 'delivered',
                created_at: now,
            })
            .then(({ error }) => {
                if (error)
                    log.warn(CTX, 'Gift audit skipped', {
                        msg: error.message,
                    });
            });
        await createNotification(
            recipient.uuid,
            'gift',
            'A Breeze item was added to your account',
            `${req.user.username} granted you a cosmetic item.`,
            {
                cape_id: capeId || null,
                cosmetic_id: cosmeticId || null,
            },
        );
        return ok(res, {
            recipient: shapeUser(recipient),
            delivered: true,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
/**
 * Send someone an item you own.
 *
 * This is a transfer, not a copy: the item leaves the sender's inventory. The
 * previous version duplicated it, so one purchased cape could be handed to
 * every player on the server for nothing, and the creator earned nothing for
 * any of them. To give someone an item you do not own, buy it for them with
 * /store/purchase-item and a gift_to, which charges and pays the creator the
 * same way an ordinary purchase does.
 */
app.post('/gifts/send', requireAuth, async (req, res) => {
    const CTX = 'Gifts/Send';
    try {
        if (await featureDisabled('gifting_disabled', req.user)) {
            return fail(res, 'Gifting is unavailable right now', 503);
        }
        const { user: recipient, reason } = await resolveGiftRecipient(req.body);
        if (!recipient) return lookupFailure(res, reason);
        if (recipient.uuid === req.user.uuid) return fail(res, 'You cannot gift yourself');
        const now = new Date().toISOString();
        const capeId = String(req.body.cape_id || '').trim();
        const cosmeticId = String(req.body.cosmetic_id || '').trim();
        if (!capeId && !cosmeticId) return fail(res, 'cape_id or cosmetic_id is required');
        if (capeId && cosmeticId) return fail(res, 'Send one item at a time');

        const isCosmetic = Boolean(cosmeticId);
        const itemId = isCosmetic ? cosmeticId : capeId;
        const label = isCosmetic ? 'cosmetic' : 'cape';
        const ownTable = isCosmetic ? 'user_cosmetics' : 'user_capes';
        const ownCol = isCosmetic ? 'cosmetic_id' : 'cape_id';

        const { data: item } = await supabase
            .from(isCosmetic ? 'cosmetics' : 'capes')
            .select('id, name')
            .eq('id', itemId)
            .maybeSingle();
        if (!item) return fail(res, `That ${label} no longer exists`, 404);

        const { data: mine, error: mineErr } = await supabase
            .from(ownTable)
            .select('*')
            .eq('user_uuid', req.user.uuid)
            .eq(ownCol, itemId)
            .maybeSingle();
        if (mineErr) return fail(res, 'Your inventory is not ready yet, try again in a moment', 503);
        if (!mine) return fail(res, `You do not own that ${label}`, 403);

        const { data: theirs } = await supabase
            .from(ownTable)
            .select(ownCol)
            .eq('user_uuid', recipient.uuid)
            .eq(ownCol, itemId)
            .maybeSingle();
        if (theirs) return fail(res, `${recipient.username} already owns that ${label}`);

        // Give first, then take. A failure between the two leaves the sender
        // with the item rather than nobody holding it.
        const grantErr = await grantItemToUser(recipient.uuid, isCosmetic ? { cosmeticId: itemId } : { capeId: itemId });
        if (grantErr) {
            log.error(CTX, 'Delivery failed', { msg: grantErr.message });
            return fail(res, `Failed to deliver the ${label}`, 500);
        }
        const { error: takeErr } = await supabase
            .from(ownTable)
            .delete()
            .eq('user_uuid', req.user.uuid)
            .eq(ownCol, itemId);
        if (takeErr) {
            // Undo the delivery so the item is not duplicated.
            await supabase.from(ownTable).delete().eq('user_uuid', recipient.uuid).eq(ownCol, itemId);
            log.error(CTX, 'Could not remove the item from the sender', { msg: takeErr.message });
            return fail(res, `Failed to send the ${label}`, 500);
        }
        // A cape the sender was wearing is no longer theirs to wear.
        if (!isCosmetic) {
            await supabase.from('users').update({ cape_url: null }).eq('uuid', req.user.uuid).eq('cape_url', item.image_url ?? '');
        }

        const { error: auditErr } = await supabase.from('gifts').insert({
            sender_uuid: req.user.uuid,
            recipient_uuid: recipient.uuid,
            cape_id: capeId || null,
            cosmetic_id: cosmeticId || null,
            source: 'transfer',
            status: 'delivered',
            created_at: now,
        });
        if (auditErr) {
            // The gift row is what shows the recipient their unopened gift, so
            // losing it means the item arrives with no sign of where it came
            // from. Worth saying out loud rather than logging quietly.
            log.error(CTX, 'Gift record failed', { msg: auditErr.message });
        }

        await createNotification(
            recipient.uuid,
            'gift',
            'You received a Breeze gift',
            `${req.user.username} sent you ${item.name}.`,
            { cape_id: capeId || null, cosmetic_id: cosmeticId || null },
        );
        await createNotification(
            req.user.uuid,
            'gift_sent',
            'Gift delivered',
            `${item.name} was sent to ${recipient.username} and is no longer in your inventory.`,
            { recipient_uuid: recipient.uuid },
        );
        return ok(res, {
            recipient: publicUser(recipient),
            delivered: true,
            transferred: true,
            item: { id: itemId, name: item.name, type: label },
            recorded: !auditErr,
        });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});

// Gifts the user has received but not yet opened, drives the celebratory
// gift-open animation the next time they launch Breeze.
app.get('/gifts/pending', requireAuth, async (req, res) => {
    const CTX = 'Gifts/Pending';
    try {
        // The cape/cosmetic details are fetched separately rather than with
        // PostgREST's embed syntax (cape:capes(...)). Embedding requires a
        // declared foreign key from gifts.cape_id to capes.id, and the gifts
        // table has none, so the embed failed with "Could not find a
        // relationship between 'gifts' and 'capes' in the schema cache" and no
        // gift ever reached the user. This works regardless of FK state.
        const { data, error } = await supabase
            .from('gifts')
            .select('id, sender_uuid, cape_id, cosmetic_id, created_at')
            .eq('recipient_uuid', req.user.uuid)
            .eq('status', 'delivered')
            .order('created_at', { ascending: true })
            .limit(10);
        if (error) {
            log.error(CTX, 'DB error', { msg: error.message });
            return fail(res, 'Failed to load gifts', 500);
        }
        const rows = data || [];
        const uniq = (vals) => [...new Set(vals.filter(Boolean))];
        const capeIds = uniq(rows.map((g) => g.cape_id));
        const cosmeticIds = uniq(rows.map((g) => g.cosmetic_id));
        const senderUuids = uniq(rows.map((g) => g.sender_uuid));

        const [capeRes, cosmeticRes, senderRes] = await Promise.all([
            capeIds.length
                ? supabase
                      .from('capes')
                      .select('id, name, image_url, is_animated, animation_frames, animation_fps')
                      .in('id', capeIds)
                : Promise.resolve({ data: [] }),
            cosmeticIds.length
                ? supabase.from('cosmetics').select('id, name, slot, model_url, thumbnail_url, metadata, idle_animation, random_animations, animation_chance').in('id', cosmeticIds)
                : Promise.resolve({ data: [] }),
            senderUuids.length
                ? supabase.from('users').select('uuid, username').in('uuid', senderUuids)
                : Promise.resolve({ data: [] }),
        ]);

        const capeById = Object.fromEntries((capeRes.data || []).map((c) => [c.id, c]));
        const cosmeticById = Object.fromEntries((cosmeticRes.data || []).map((c) => [c.id, c]));
        const names = Object.fromEntries((senderRes.data || []).map((u) => [u.uuid, u.username]));

        const gifts = rows.map((g) => ({
            ...g,
            cape: g.cape_id ? capeById[g.cape_id] || null : null,
            cosmetic: g.cosmetic_id ? cosmeticById[g.cosmetic_id] || null : null,
            sender_username: names[g.sender_uuid] || 'A Breeze user',
        }));
        return ok(res, { gifts });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});

// Mark a received gift as opened so its animation is not shown again.
app.post('/gifts/:id/seen', requireAuth, async (req, res) => {
    const CTX = 'Gifts/Seen';
    try {
        const { error } = await supabase
            .from('gifts')
            .update({ status: 'seen' })
            .eq('id', req.params.id)
            .eq('recipient_uuid', req.user.uuid);
        if (error) {
            log.error(CTX, 'Update error', { msg: error.message });
            return fail(res, 'Failed to update gift', 500);
        }
        return ok(res, { seen: true });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Server error', 500);
    }
});
app.post('/cosmetics', requireAuth, cosmeticUploadLimiter, cosmeticUploadGate, cosmeticFiles, async (req, res) => {
    const CTX = 'Cosmetics/Upload';
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        if (!hasCreatorAccess(dbUser.role))
            return fail(res, 'Creator access required', 403);
        if (isCreatorTier(dbUser.role)) {
            const { count, error: countErr } = await supabase
                .from('cosmetics')
                .select('id', {
                    count: 'exact',
                    head: true,
                })
                .eq('creator_id', req.user.uuid);
            if (countErr) {
                log.error(CTX, 'Creator count query failed', {
                    msg: countErr.message,
                });
                return fail(res, 'Could not verify creator upload slots', 500);
            }
            if ((count ?? 0) >= CREATOR_COSMETIC_LIMIT) {
                return fail(
                    res,
                    `You've reached your cosmetic limit (${CREATOR_COSMETIC_LIMIT}). Delete an old cosmetic or ask an owner to raise the limit.`,
                    403,
                );
            }
        }
        const name = req.body.name?.trim();
        const slot = req.body.slot?.trim()?.toLowerCase();
        if (!name) return fail(res, 'Cosmetic name is required');
        if (!isValidSlot(slot))
            return fail(res, `Invalid slot. Allowed: ${COSMETIC_SLOTS.join(', ')}`);
        const modelFile = req.files?.model?.[0];
        const thumbnailFile = req.files?.thumbnail?.[0];
        if (!modelFile) return fail(res, 'A model file is required (.glb, .gltf or .zip)');
        const price_usd = parseFloat(req.body.price_usd ?? '0');
        if (isNaN(price_usd) || price_usd < 0) return fail(res, 'Invalid price_usd');
        const rarity = (req.body.rarity || 'premium').toLowerCase();
        const description = req.body.description || null;
        const isPublic = req.body.is_public !== 'false';
        const animChance =
            req.body.animation_chance != null ? parseFloat(req.body.animation_chance) : 0.15;
        let randomAnimations = [];
        if (req.body.random_animations) {
            try {
                const parsed = JSON.parse(req.body.random_animations);
                if (Array.isArray(parsed)) randomAnimations = parsed.map(String);
            } catch {
                randomAnimations = String(req.body.random_animations)
                    .split(',')
                    .map((s) => s.trim())
                    .filter(Boolean);
            }
        }
        let clientMetadata = {};
        if (req.body.metadata) {
            try {
                clientMetadata = JSON.parse(req.body.metadata) || {};
            } catch {
                return fail(res, 'metadata must be valid JSON');
            }
        }
        // Every upload becomes one self-contained GLB, checked and measured.
        let prepared;
        try {
            prepared = await prepareCosmeticUpload(req, slot, clientMetadata);
        } catch (assetErr) {
            if (assetErr instanceof cosmeticAsset.CosmeticAssetError) return fail(res, assetErr.message, 400);
            throw assetErr;
        }
        // What the server measured always wins over what the client claimed.
        const metadata = { ...clientMetadata, ...prepared.metadata };
        delete metadata.animationRoles;
        const roles = prepared.metadata.animations.roles;
        const safeSlug = name.toLowerCase().replace(/[^a-z0-9]+/g, '_');
        const stamp = Date.now();
        const baseFolder = `${slot}/${req.user.uuid}_${safeSlug}_${stamp}`;
        // Cosmetic models + thumbnails live on the API disk (storage/cosmetics)
        // and serve from /assets, Supabase stays relational-only. Always a GLB
        // now, whatever was uploaded.
        const modelRel = `cosmetics/${baseFolder}/model.glb`;
        try {
            breezeAssets.storeAsset(modelRel, prepared.glb);
        } catch (modelErr) {
            log.error(CTX, 'Model store error', {
                msg: modelErr.message,
            });
            return fail(res, 'Failed to store model', 500);
        }
        const modelUrl = breezeAssets.assetUrl(getRequestBaseUrl(req), modelRel);
        let thumbnailUrl = null;
        if (thumbnailFile) {
            try {
                const thumbBuf = await sharp(thumbnailFile.buffer)
                    .resize(256, 256, {
                        fit: 'cover',
                    })
                    .png()
                    .toBuffer();
                const thumbRel = `cosmetics/${baseFolder}/thumbnail.png`;
                breezeAssets.storeAsset(thumbRel, thumbBuf);
                thumbnailUrl = breezeAssets.assetUrl(getRequestBaseUrl(req), thumbRel);
            } catch (e) {
                log.warn(CTX, 'Thumbnail processing failed (non-fatal)', {
                    msg: e.message,
                });
            }
        }
        const { data: created, error: insertErr } = await supabase
            .from('cosmetics')
            .insert({
                slot,
                name,
                description,
                rarity,
                price_usd,
                model_url: modelUrl,
                thumbnail_url: thumbnailUrl,
                // Kept filled for launchers and mods that read these columns
                // rather than metadata.animations.
                idle_animation: roles.idle || null,
                random_animations: randomAnimations.length ? randomAnimations : prepared.metadata.animations.extras,
                animation_chance: isNaN(animChance) ? 0.15 : animChance,
                creator_id: req.user.uuid,
                is_public: isPublic,
                metadata,
            })
            .select()
            .single();
        if (insertErr) {
            log.error(CTX, 'DB insert error', {
                msg: insertErr.message,
            });
            return fail(res, 'Failed to create cosmetic', 500);
        }
        log.info(CTX, `Cosmetic created: ${created.id} "${name}" (${slot}) by ${req.user.uuid}`);
        return ok(
            res,
            {
                cosmetic: created,
            },
            201,
        );
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Failed to upload cosmetic', 500);
    }
});
app.patch('/cosmetics/:id', requireAuth, async (req, res) => {
    const CTX = 'Cosmetics/Update';
    try {
        const dbUser = await dbGetUser(req.user.uuid);
        if (!dbUser) return fail(res, 'User not found', 404);
        const { data: row, error: rowErr } = await supabase
            .from('cosmetics')
            .select('id, creator_id, slot, metadata')
            .eq('id', req.params.id)
            .maybeSingle();
        if (rowErr) {
            log.error(CTX, 'DB error', {
                msg: rowErr.message,
            });
            return fail(res, 'DB error', 500);
        }
        if (!row) return fail(res, 'Cosmetic not found', 404);
        const isOwner = dbUser.role === ROLES.OWNER || dbUser.role === ROLES.ADMIN;
        if (!isOwner && row.creator_id !== req.user.uuid)
            return fail(res, 'You can only edit your own cosmetics', 403);
        const patch = {};
        const allow = [
            'name',
            'description',
            'rarity',
            'price_usd',
            'is_public',
            'idle_animation',
            'animation_chance',
            'random_animations',
        ];
        for (const k of allow) {
            if (req.body[k] === undefined) continue;
            if (k === 'random_animations') {
                let arr = [];
                try {
                    const parsed =
                        typeof req.body[k] === 'string' ? JSON.parse(req.body[k]) : req.body[k];
                    if (Array.isArray(parsed)) arr = parsed.map(String);
                } catch {
                    arr = String(req.body[k])
                        .split(',')
                        .map((s) => s.trim())
                        .filter(Boolean);
                }
                patch[k] = arr;
            } else if (k === 'price_usd' || k === 'animation_chance') {
                const n = parseFloat(req.body[k]);
                if (isNaN(n)) return fail(res, `Invalid ${k}`);
                patch[k] = n;
            } else if (k === 'is_public') {
                patch[k] = !(req.body[k] === 'false' || req.body[k] === false);
            } else {
                patch[k] = req.body[k];
            }
        }
        // Placement and animation roles. Accepted as fields or inside a metadata
        // object (older launchers), and validated against what the file holds.
        let clientMeta = {};
        if (req.body.metadata !== undefined) {
            try {
                clientMeta = (typeof req.body.metadata === 'string' ? JSON.parse(req.body.metadata) : req.body.metadata) || {};
            } catch {
                return fail(res, 'metadata must be valid JSON');
            }
        }
        const edit = {};
        for (const [field, key] of [['attachment', 'attachment'], ['transform', 'transform'], ['animation_roles', 'animationRoles']]) {
            if (req.body[field] !== undefined) edit[key] = req.body[field];
            else if (clientMeta[key] !== undefined) edit[key] = clientMeta[key];
        }
        // For a cosmetic whose clips the server measured, idle_animation and
        // random_animations are derived from metadata.animations. Editing them
        // directly would make the two disagree, so the idle edit is applied as
        // a role, and the extras follow the roles rather than being set.
        const measured = Array.isArray(row.metadata?.animations?.clips);
        if (measured && patch.random_animations !== undefined) {
            return fail(res, 'The extra animations are the clips no role uses. Change the roles instead.', 400);
        }
        if (measured && patch.idle_animation !== undefined) {
            const idle = patch.idle_animation ? String(patch.idle_animation) : '';
            delete patch.idle_animation;
            let roles = {};
            try {
                roles = typeof edit.animationRoles === 'string' ? JSON.parse(edit.animationRoles || '{}') : { ...(edit.animationRoles || {}) };
            } catch {
                return fail(res, 'animation_roles must be valid JSON');
            }
            edit.animationRoles = { ...roles, idle };
        }
        if (Object.keys(edit).length) {
            try {
                patch.metadata = cosmeticAsset.editCosmeticMetadata(row.metadata, row.slot, edit);
            } catch (editErr) {
                if (editErr instanceof cosmeticAsset.CosmeticAssetError) return fail(res, editErr.message, 400);
                throw editErr;
            }
            if (edit.animationRoles !== undefined) {
                patch.idle_animation = patch.metadata.animations?.roles?.idle || null;
                patch.random_animations = patch.metadata.animations?.extras || [];
            }
        }
        if (Object.keys(patch).length === 0) return fail(res, 'No editable fields provided');
        const { data: updated, error: upErr } = await supabase
            .from('cosmetics')
            .update(patch)
            .eq('id', req.params.id)
            .select()
            .single();
        if (upErr) {
            log.error(CTX, 'Update error', {
                msg: upErr.message,
            });
            return fail(res, 'Update failed', 500);
        }
        log.info(CTX, `Cosmetic updated: ${req.params.id} by ${req.user.uuid}`);
        return ok(res, {
            cosmetic: updated,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
app.delete(
    '/cosmetics/:id',
    requireAuth,
    requireRole(...CREATOR_ACCESS_ROLES),
    async (req, res) => {
        const CTX = 'Cosmetics/Delete';
        try {
            // Only the Owner (any) or a cosmetic's own Creator may delete.
            // Admins are intentionally NOT allowed to delete cosmetics.
            const dbUser = await dbGetUser(req.user.uuid);
            const isOwner = dbUser && dbUser.role === ROLES.OWNER;
            if (!isOwner) {
                const { data: ownRow } = await supabase
                    .from('cosmetics').select('creator_id').eq('id', req.params.id).maybeSingle();
                if (!ownRow) return fail(res, 'Cosmetic not found', 404);
                if (ownRow.creator_id !== req.user.uuid)
                    return fail(res, 'You can only delete your own cosmetics', 403);
            }
            if (req.query.hard === 'true') {
                const { error } = await supabase.from('cosmetics').delete().eq('id', req.params.id);
                if (error) {
                    log.error(CTX, 'Hard delete error', {
                        msg: error.message,
                    });
                    return fail(res, 'Delete failed', 500);
                }
                log.info(CTX, `Cosmetic HARD-deleted: ${req.params.id} by ${req.user.uuid}`);
                return ok(res, {
                    id: req.params.id,
                    deleted: true,
                });
            }
            const { data, error } = await supabase
                .from('cosmetics')
                .update({
                    is_public: false,
                })
                .eq('id', req.params.id)
                .select()
                .single();
            if (error) {
                log.error(CTX, 'Soft delete error', {
                    msg: error.message,
                });
                return fail(res, 'Delete failed', 500);
            }
            log.info(CTX, `Cosmetic unlisted: ${req.params.id} by ${req.user.uuid}`);
            return ok(res, {
                cosmetic: data,
                unlisted: true,
            });
        } catch (err) {
            log.error(CTX, 'Error', {
                msg: err.message,
            });
            return fail(res, 'Server error', 500);
        }
    },
);
app.get('/cosmetics/:id', async (req, res) => {
    const CTX = 'Cosmetics/Get';
    try {
        const { data, error } = await supabase
            .from('cosmetics')
            .select('*')
            .eq('id', req.params.id)
            .maybeSingle();
        if (error) {
            log.error(CTX, 'DB error', {
                msg: error.message,
            });
            return fail(res, 'Failed to fetch cosmetic', 500);
        }
        if (!data) return fail(res, 'Cosmetic not found', 404);
        return ok(res, {
            cosmetic: data,
        });
    } catch (err) {
        log.error(CTX, 'Error', {
            msg: err.message,
        });
        return fail(res, 'Server error', 500);
    }
});
async function sendTicketOpenedEmail(to, username, ticketId, subject) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_SUPPORT,
        subject: `[Breeze Support] Ticket ${ticketId} received`,
        html: buildEmailHtml(
            'Breeze Support',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>Your support ticket has been received. Our team will respond as soon as possible.</p>
          <p><strong>Ticket ID:</strong> ${ticketId}<br><strong>Subject:</strong> ${escH(subject)}</p>
          <a class="btn" href="https://breezeclient.net/account.html">View My Tickets</a>
        `,
        ),
    });
}
async function sendTicketReplyEmail(to, username, ticketId, subject, staffReply) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_SUPPORT,
        subject: `[Breeze Support] New reply on ticket ${ticketId}`,
        html: buildEmailHtml(
            'Breeze Support',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>A staff member has replied to your support ticket.</p>
          <p><strong>Subject:</strong> ${escH(subject)}</p>
          <blockquote>${escH(staffReply).replace(/\n/g, '<br>')}</blockquote>
          <a class="btn" href="https://breezeclient.net/account.html">View Full Thread</a>
        `,
        ),
    });
}
async function sendTicketClosedEmail(to, username, ticketId, subject) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_SUPPORT,
        subject: `[Breeze Support] Ticket ${ticketId} closed`,
        html: buildEmailHtml(
            'Breeze Support',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>Your support ticket has been closed.</p>
          <p><strong>Subject:</strong> ${escH(subject)}</p>
          <p>If your issue is not resolved, you can open a new ticket at any time.</p>
          <a class="btn" href="https://breezeclient.net/support.html">Open New Ticket</a>
        `,
        ),
    });
}
async function sendFeedbackReceivedEmail(to, username) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_INFO,
        subject: 'Breeze Client, Your feedback was received',
        html: buildEmailHtml(
            'Breeze Client',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>Thanks for your feedback. We read every submission and use it to guide development. We will update the public status as we act on it.</p>
        `,
        ),
    });
}
async function sendFeedbackStatusEmail(to, username, title, newStatus) {
    if (!hasMailConfig() || !to) return;
    const labels = {
        planned: 'Planned',
        in_progress: 'In Progress',
        resolved: 'Resolved',
        wont_fix: "Won't Fix",
    };
    const label = labels[newStatus] || newStatus;
    await sendSystemEmail({
        to,
        from: EMAIL_INFO,
        subject: `[Breeze Feedback] Status update on your submission`,
        html: buildEmailHtml(
            'Breeze Client',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>Your feedback submission has a new status.</p>
          <p><strong>Submission:</strong> ${escH(title)}<br>
          <strong>Status:</strong> <span style="color:#5AAFFF;font-weight:700">${label}</span></p>
          <a class="btn" href="https://breezeclient.net/feedback.html">View on Feedback Board</a>
        `,
        ),
    });
}
async function sendCreatorWelcomeEmail(to, username) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_ADMIN,
        subject: 'Welcome to the Breeze Creator Program',
        html: buildEmailHtml(
            'Breeze Creator Program',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>Your account has been granted <strong>Creator</strong> status on Breeze Client. Welcome to the team!</p>
          <p>You now have access to the Creator Dashboard where you can:</p>
          <ul style="color:#8892A4;padding-left:20px;margin:12px 0">
            <li>Upload and manage your capes and cosmetics</li>
            <li>View your earnings and commission rate</li>
            <li>Track your sales and payout history</li>
          </ul>
          <a class="btn" href="https://admin.breezeclient.net">Open Creator Dashboard</a>
          <p style="margin-top:16px;font-size:13px">Questions? Reply to this email or open a support ticket.</p>
        `,
        ),
    });
}
async function sendAdminWelcomeEmail(to, username) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_ADMIN,
        subject: 'You have been added to the Breeze team',
        html: buildEmailHtml(
            'Breeze Staff',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>Your account has been granted <strong>Admin</strong> access on Breeze Client.</p>
          <p>You now have access to the Admin Panel where you can manage support tickets, feedback, news, changelogs, and users.</p>
          <a class="btn" href="https://admin.breezeclient.net">Open Admin Panel</a>
          <p style="margin-top:16px;font-size:13px;color:#4A5568">This email was sent from a monitored address. Do not share your credentials.</p>
        `,
        ),
    });
}
async function sendSecurityAlertEmail(to, username, action, details) {
    if (!hasMailConfig() || !to) return;
    await sendSystemEmail({
        to,
        from: EMAIL_SECURITY,
        subject: '[Breeze Security] Account activity alert',
        html: buildEmailHtml(
            'Breeze Security',
            `
          <p>Hi <strong>${username}</strong>,</p>
          <p>We detected the following activity on your Breeze account:</p>
          <p><strong>Action:</strong> ${escH(action)}<br>
          <strong>Details:</strong> ${escH(details)}<br>
          <strong>Time:</strong> ${new Date().toUTCString()}</p>
          <p>If this was not you, contact us immediately at <a href="mailto:security@breezeclient.net" style="color:#5AAFFF">security@breezeclient.net</a>.</p>
        `,
        ),
    });
}
function buildEmailHtml(brandName, bodyContent) {
    return `<!DOCTYPE html><html><head><meta charset="UTF-8">
<style>
*{box-sizing:border-box}
body{margin:0;padding:0;background:#060810;font-family:'Segoe UI',Arial,sans-serif;color:#F0F4FF}
.wrap{max-width:560px;margin:40px auto;background:#0C101C;border:1px solid rgba(255,255,255,.08);border-radius:16px;overflow:hidden}
.header{background:linear-gradient(135deg,#0C2040 0%,#0A1422 100%);padding:28px 32px;display:flex;align-items:center;gap:12px}
.header-name{font-size:18px;font-weight:700;color:#5AAFFF;letter-spacing:-.02em}
.body{padding:28px 32px}
.body p{color:#8892A4;line-height:1.75;margin:0 0 14px;font-size:15px}
.body ul li{color:#8892A4;line-height:1.75;font-size:15px;margin-bottom:6px}
blockquote{border-left:3px solid #5AAFFF;padding:10px 16px;background:rgba(90,175,255,.06);border-radius:0 8px 8px 0;color:#8892A4;margin:14px 0;font-size:14px}
.btn{display:inline-block;margin-top:6px;padding:11px 24px;background:#5AAFFF;color:#060810;border-radius:10px;font-weight:700;text-decoration:none;font-size:14px}
.footer{padding:18px 32px;border-top:1px solid rgba(255,255,255,.06);text-align:center}
.footer p{color:#4A5568;font-size:12px;margin:0}
</style>
</head><body>
<div class="wrap">
  <div class="header">
    <div class="header-name">⚡ ${brandName}</div>
  </div>
  <div class="body">${bodyContent}</div>
  <div class="footer"><p>&copy; ${new Date().getFullYear()} Breeze Client , BreezeClient.net</p></div>
</div>
</body></html>`;
}
function escH(str) {
    return String(str || '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}
app.post('/tickets', requireAuth, async (req, res) => {
    const { subject, message, category, priority, version } = req.body;
    if (!subject?.trim() || !message?.trim() || !category)
        return fail(res, 'subject, message, category are required');
    const ticketId = 'TKT-' + Date.now().toString(36).toUpperCase();
    const { error } = await supabase.from('tickets').insert({
        ticket_id: ticketId,
        uuid: req.user.uuid,
        username: req.user.username,
        subject: subject.trim(),
        category,
        priority: priority || 'low',
        version: version || null,
        status: 'open',
    });
    if (error) {
        log.error('Tickets', 'Insert error', {
            msg: error.message,
        });
        return fail(res, 'Database error', 500);
    }
    await supabase.from('ticket_messages').insert({
        ticket_id: ticketId,
        author_uuid: req.user.uuid,
        author_name: req.user.username,
        message: message.trim(),
        is_staff: false,
    });
    const { data: userRow } = await supabase
        .from('users')
        .select('email')
        .eq('uuid', req.user.uuid)
        .maybeSingle();
    if (userRow?.email) {
        await sendTicketOpenedEmail(userRow.email, req.user.username, ticketId, subject.trim());
    }
    return ok(
        res,
        {
            ticket: {
                ticket_id: ticketId,
                status: 'open',
            },
        },
        201,
    );
});
app.get('/tickets/mine', requireAuth, async (req, res) => {
    const { data, error } = await supabase
        .from('tickets')
        .select('ticket_id, subject, category, priority, status, created_at, updated_at')
        .eq('uuid', req.user.uuid)
        .order('updated_at', {
            ascending: false,
        });
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {
        tickets: data,
    });
});
app.get('/tickets', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { status, category, limit = 50, offset = 0 } = req.query;
    let query = supabase
        .from('tickets')
        .select('*')
        .order('updated_at', {
            ascending: false,
        })
        .range(parseInt(offset), parseInt(offset) + parseInt(limit) - 1);
    if (status) query = query.eq('status', status);
    if (category) query = query.eq('category', category);
    const { data, error } = await query;
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {
        tickets: data,
    });
});
app.get('/tickets/:id', requireAuth, async (req, res) => {
    const isAdmin = [ROLES.ADMIN, ROLES.OWNER].includes(req.user.role);
    const { data: ticket, error } = await supabase
        .from('tickets')
        .select('*')
        .eq('ticket_id', req.params.id)
        .maybeSingle();
    if (error || !ticket) return fail(res, 'Ticket not found', 404);
    if (!isAdmin && ticket.uuid !== req.user.uuid) return fail(res, 'Access denied', 403);
    const { data: messages } = await supabase
        .from('ticket_messages')
        .select('*')
        .eq('ticket_id', req.params.id)
        .order('created_at', {
            ascending: true,
        });
    return ok(res, {
        ticket,
        messages: messages || [],
    });
});
app.post('/tickets/:id/messages', requireAuth, async (req, res) => {
    const { message } = req.body;
    if (!message?.trim()) return fail(res, 'message is required');
    const isAdmin = [ROLES.ADMIN, ROLES.OWNER].includes(req.user.role);
    const { data: ticket, error } = await supabase
        .from('tickets')
        .select('*')
        .eq('ticket_id', req.params.id)
        .maybeSingle();
    if (error || !ticket) return fail(res, 'Ticket not found', 404);
    if (!isAdmin && ticket.uuid !== req.user.uuid) return fail(res, 'Access denied', 403);
    if (ticket.status === 'closed' && !isAdmin) return fail(res, 'Ticket is closed', 400);
    await supabase.from('ticket_messages').insert({
        ticket_id: req.params.id,
        author_uuid: req.user.uuid,
        author_name: req.user.username,
        message: message.trim(),
        is_staff: isAdmin,
    });
    await supabase
        .from('tickets')
        .update({
            status: isAdmin ? 'in_progress' : ticket.status,
            updated_at: new Date().toISOString(),
        })
        .eq('ticket_id', req.params.id);
    if (isAdmin && ticket.uuid !== req.user.uuid) {
        const { data: userRow } = await supabase
            .from('users')
            .select('email')
            .eq('uuid', ticket.uuid)
            .maybeSingle();
        if (userRow?.email) {
            await sendTicketReplyEmail(userRow.email, ticket.username, req.params.id, ticket.subject, message.trim());
        }
    }
    return ok(res, {});
});
app.patch('/tickets/:id', requireAuth, async (req, res) => {
    const { status, subject } = req.body;
    const isAdmin = [ROLES.ADMIN, ROLES.OWNER].includes(req.user.role);
    const { data: ticket, error } = await supabase
        .from('tickets')
        .select('*')
        .eq('ticket_id', req.params.id)
        .maybeSingle();
    if (error || !ticket) return fail(res, 'Ticket not found', 404);
    if (!isAdmin && ticket.uuid !== req.user.uuid) return fail(res, 'Access denied', 403);
    if (!isAdmin && status && !['closed'].includes(status)) return fail(res, 'Access denied', 403);
    const updates = {
        updated_at: new Date().toISOString(),
    };
    const validStatuses = ['open', 'in_progress', 'closed', 'resolved'];
    if (status && validStatuses.includes(status)) updates.status = status;
    if (subject?.trim() && isAdmin) updates.subject = subject.trim();
    await supabase.from('tickets').update(updates).eq('ticket_id', req.params.id);
    if (status === 'closed') {
        const { data: userRow } = await supabase
            .from('users')
            .select('email')
            .eq('uuid', ticket.uuid)
            .maybeSingle();
        if (userRow?.email) {
            await sendTicketClosedEmail(userRow.email, ticket.username, req.params.id, ticket.subject);
        }
    }
    return ok(res, {});
});
app.post('/feedback', optionalAuth, async (req, res) => {
    const { type, title, description, tags } = req.body;
    if (!title?.trim() || !description?.trim() || !type)
        return fail(res, 'type, title, description are required');
    const validTypes = ['bug', 'feature', 'other'];
    if (!validTypes.includes(type)) return fail(res, 'Invalid type');
    const tagsArr = Array.isArray(tags) ? tags.slice(0, 10).map(String) : [];
    const { error } = await supabase.from('feedback').insert({
        type,
        title: title.trim(),
        description: description.trim(),
        tags: tagsArr,
        uuid: req.user?.uuid || null,
        username: req.user?.username || 'Anonymous',
        status: 'under_review',
        votes: 0,
    });
    if (error) {
        log.error('Feedback', 'Insert error', {
            msg: error.message,
        });
        return fail(res, 'Database error', 500);
    }
    if (req.user) {
        const { data: userRow } = await supabase
            .from('users')
            .select('email')
            .eq('uuid', req.user.uuid)
            .maybeSingle();
        if (userRow?.email) {
            await sendFeedbackReceivedEmail(userRow.email, req.user.username);
        }
    }
    return ok(res, {}, 201);
});
app.get('/feedback', async (req, res) => {
    const { type, status } = req.query;
    // Clamped, and NaN-safe. `parseInt` on a missing or junk value yields NaN,
    // which used to flow straight into .range(NaN, NaN); an unbounded `limit`
    // also let one request ask for the entire table.
    const toInt = (v, dflt) => {
        const n = parseInt(v, 10);
        return Number.isFinite(n) && n >= 0 ? n : dflt;
    };
    const offset = toInt(req.query.offset, 0);
    const limit = Math.min(Math.max(toInt(req.query.limit, 50), 1), 200);
    let query = supabase
        .from('feedback')
        .select('id, type, title, description, tags, status, votes, admin_note, created_at')
        .order('votes', {
            ascending: false,
        })
        .order('created_at', {
            ascending: false,
        })
        .range(offset, offset + limit - 1);
    if (type) query = query.eq('type', type);
    if (status) query = query.eq('status', status);
    const { data, error } = await query;
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {
        items: data || [],
    });
});
app.patch('/feedback/:id', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { status, admin_note } = req.body;
    const validStatuses = ['under_review', 'planned', 'in_progress', 'resolved', 'wont_fix'];
    const updates = {};
    if (status && validStatuses.includes(status)) updates.status = status;
    if (admin_note !== undefined) updates.admin_note = admin_note;
    if (!Object.keys(updates).length) return fail(res, 'Nothing to update');
    const { error, data: updated } = await supabase
        .from('feedback')
        .update(updates)
        .eq('id', req.params.id)
        .select('title, uuid, username')
        .single();
    if (error) return fail(res, 'Database error', 500);
    if (updates.status && !['under_review'].includes(updates.status) && updated?.uuid) {
        const { data: uRow } = await supabase
            .from('users')
            .select('email')
            .eq('uuid', updated.uuid)
            .maybeSingle();
        if (uRow?.email)
            await sendFeedbackStatusEmail(
                uRow.email,
                updated.username,
                updated.title,
                updates.status,
            ).catch(() => {});
    }
    return ok(res, {});
});
app.get('/changelog', async (req, res) => {
    const { data, error } = await supabase.from('changelog').select('*').order('created_at', {
        ascending: false,
    });
    if (error) {
        return ok(res, {
            entries: [
                {
                    id: 1,
                    version: '0.1.0',
                    title: 'Website Released',
                    changes: [
                        {
                            type: 'new',
                            text: 'BreezeClient.net website launched',
                        },
                    ],
                    latest: true,
                    created_at: new Date().toISOString(),
                },
            ],
        });
    }
    return ok(res, {
        entries: data || [],
    });
});
app.post('/changelog', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { version, title, changes, latest } = req.body;
    if (!version?.trim() || !Array.isArray(changes))
        return fail(res, 'version and changes[] are required');
    if (latest)
        await supabase
            .from('changelog')
            .update({
                latest: false,
            })
            .eq('latest', true);
    const { data, error } = await supabase
        .from('changelog')
        .insert({
            version: version.trim(),
            title: title?.trim() || null,
            changes,
            latest: Boolean(latest),
        })
        .select()
        .single();
    if (error) return fail(res, 'Database error', 500);
    return ok(
        res,
        {
            entry: data,
        },
        201,
    );
});
app.patch(
    '/changelog/:id',
    requireAuth,
    requireRole(ROLES.ADMIN, ROLES.OWNER),
    async (req, res) => {
        const { version, title, changes, latest } = req.body;
        const updates = {};
        if (version?.trim()) updates.version = version.trim();
        if (title !== undefined) updates.title = title?.trim() || null;
        if (Array.isArray(changes)) updates.changes = changes;
        if (latest !== undefined) {
            if (latest)
                await supabase
                    .from('changelog')
                    .update({
                        latest: false,
                    })
                    .eq('latest', true);
            updates.latest = Boolean(latest);
        }
        if (!Object.keys(updates).length) return fail(res, 'Nothing to update');
        const { error } = await supabase.from('changelog').update(updates).eq('id', req.params.id);
        if (error) return fail(res, 'Database error', 500);
        return ok(res, {});
    },
);
app.delete(
    '/changelog/:id',
    requireAuth,
    requireRole(ROLES.ADMIN, ROLES.OWNER),
    async (req, res) => {
        const { error } = await supabase.from('changelog').delete().eq('id', req.params.id);
        if (error) return fail(res, 'Database error', 500);
        return ok(res, {});
    },
);
app.get('/news', async (req, res) => {
    const { limit = 20, offset = 0 } = req.query;
    const { data, error } = await supabase
        .from('news')
        .select('id, category, title, excerpt, published_at')
        .order('published_at', {
            ascending: false,
        })
        .range(parseInt(offset), parseInt(offset) + parseInt(limit) - 1);
    if (error)
        return ok(res, {
            items: [],
        });
    return ok(res, {
        items: data || [],
    });
});
app.get('/news/:id', async (req, res) => {
    const { data, error } = await supabase
        .from('news')
        .select('*')
        .eq('id', req.params.id)
        .maybeSingle();
    if (error || !data) return fail(res, 'Not found', 404);
    return ok(res, {
        item: data,
    });
});
app.post('/news', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { category, title, excerpt, content } = req.body;
    if (!title?.trim() || !content?.trim()) return fail(res, 'title and content are required');
    const { data, error } = await supabase
        .from('news')
        .insert({
            category: category || 'announcement',
            title: title.trim(),
            excerpt: excerpt?.trim() || null,
            content: content.trim(),
            author_uuid: req.user.uuid,
            author_name: req.user.username,
            published_at: new Date().toISOString(),
        })
        .select()
        .single();
    if (error) return fail(res, 'Database error', 500);
    return ok(
        res,
        {
            item: data,
        },
        201,
    );
});
app.patch('/news/:id', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { category, title, excerpt, content } = req.body;
    const updates = {};
    if (category) updates.category = category;
    if (title?.trim()) updates.title = title.trim();
    if (excerpt !== undefined) updates.excerpt = excerpt?.trim() || null;
    if (content?.trim()) updates.content = content.trim();
    if (!Object.keys(updates).length) return fail(res, 'Nothing to update');
    const { error } = await supabase.from('news').update(updates).eq('id', req.params.id);
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {});
});
app.delete('/news/:id', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { error } = await supabase.from('news').delete().eq('id', req.params.id);
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {});
});
app.get('/admin/users', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { limit = 50, offset = 0, search } = req.query;
    let query = supabase
        .from('users')
        .select('uuid, username, role, email, created_at')
        .order('created_at', {
            ascending: false,
        })
        .range(parseInt(offset), parseInt(offset) + parseInt(limit) - 1);
    if (search) query = query.ilike('username', `%${search}%`);
    const { data, error } = await query;
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {
        users: data,
    });
});
app.get('/admin/stats', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const [users, tickets, openTickets, feedback, news, changelog] = await Promise.all([
        supabase.from('users').select('uuid', {
            count: 'exact',
            head: true,
        }),
        supabase.from('tickets').select('ticket_id', {
            count: 'exact',
            head: true,
        }),
        supabase
            .from('tickets')
            .select('ticket_id', {
                count: 'exact',
                head: true,
            })
            .eq('status', 'open'),
        supabase.from('feedback').select('id', {
            count: 'exact',
            head: true,
        }),
        supabase.from('news').select('id', {
            count: 'exact',
            head: true,
        }),
        supabase.from('changelog').select('id', {
            count: 'exact',
            head: true,
        }),
    ]);
    return ok(res, {
        users: users.count || 0,
        tickets: tickets.count || 0,
        openTickets: openTickets.count || 0,
        feedback: feedback.count || 0,
        news: news.count || 0,
        changelog: changelog.count || 0,
    });
});
// Revenue analytics for the owner/admin dashboard: monthly Wind Charge revenue
// (last 6 months), totals, catalogue counts, and recent purchases.
app.get('/admin/analytics', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const CTX = 'Admin/Analytics';
    try {
        const now = new Date();
        const since = new Date(now.getFullYear(), now.getMonth() - 5, 1);
        const { data: orders } = await supabase
            .from('orders')
            .select('gross_amount_usd, wind_charges, order_type, status, completed_at, created_at, user_uuid')
            .eq('order_type', 'wind_charges')
            .eq('status', 'completed')
            .gte('created_at', since.toISOString())
            .order('created_at', { ascending: false });
        const list = orders || [];

        const months = [];
        for (let i = 5; i >= 0; i--) {
            const d = new Date(now.getFullYear(), now.getMonth() - i, 1);
            months.push({
                key: `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`,
                label: d.toLocaleString('en-US', { month: 'short' }),
                revenue: 0,
                count: 0,
            });
        }
        const byKey = Object.fromEntries(months.map((m) => [m.key, m]));
        let totalRevenue = 0;
        let totalWc = 0;
        for (const o of list) {
            const when = new Date(o.completed_at || o.created_at);
            const key = `${when.getFullYear()}-${String(when.getMonth() + 1).padStart(2, '0')}`;
            totalRevenue += Number(o.gross_amount_usd || 0);
            totalWc += Number(o.wind_charges || 0);
            if (byKey[key]) {
                byKey[key].revenue += Number(o.gross_amount_usd || 0);
                byKey[key].count += 1;
            }
        }

        const [capes, cosmetics, creators] = await Promise.all([
            supabase.from('capes').select('id', { count: 'exact', head: true }),
            supabase.from('cosmetics').select('id', { count: 'exact', head: true }),
            supabase.from('users').select('uuid', { count: 'exact', head: true }).eq('role', 'creator'),
        ]);

        const recent = list.slice(0, 8).map((o) => ({
            user_uuid: o.user_uuid,
            usd: Number(o.gross_amount_usd || 0),
            wind_charges: Number(o.wind_charges || 0),
            at: o.completed_at || o.created_at,
        }));

        return ok(res, {
            monthly: months,
            total_revenue_usd: totalRevenue,
            total_wc_sold: totalWc,
            order_count: list.length,
            capes: capes.count || 0,
            cosmetics: cosmetics.count || 0,
            creators: creators.count || 0,
            recent,
        });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Failed to load analytics', 500);
    }
});
app.get('/earnings/me', requireAuth, async (req, res) => {
    const { data: row } = await supabase
        .from('earnings')
        .select('*')
        .eq('uuid', req.user.uuid)
        .maybeSingle();
    if (!row)
        return ok(res, {
            total_earned: 0,
            pending_payout: 0,
            commission_pct: 0,
            payouts: [],
        });
    const { data: payouts } = await supabase
        .from('payouts')
        .select('*')
        .eq('uuid', req.user.uuid)
        .order('created_at', {
            ascending: false,
        })
        .limit(20);
    return ok(res, {
        ...row,
        payouts: payouts || [],
    });
});
app.get('/admin/earnings', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const { data, error } = await supabase.from('earnings').select('*').order('total_earned', {
        ascending: false,
    });
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {
        earnings: data || [],
    });
});
app.patch(
    '/admin/earnings/:uuid/commission',
    requireAuth,
    requireRole(ROLES.OWNER),
    async (req, res) => {
        const { commission_pct } = req.body;
        if (commission_pct === undefined || commission_pct < 0 || commission_pct > 100)
            return fail(res, 'commission_pct must be 0-100');
        const { error } = await supabase.from('earnings').upsert(
            {
                uuid: req.params.uuid,
                commission_pct,
                updated_at: new Date().toISOString(),
            },
            {
                onConflict: 'uuid',
            },
        );
        if (error) return fail(res, 'Database error', 500);
        return ok(res, {});
    },
);
app.patch('/admin/earnings/:uuid', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const allowed = ['commission_pct', 'pending_payout', 'total_paid_out', 'username', 'role'];
    const updates = {};
    allowed.forEach((k) => {
        if (req.body[k] !== undefined) updates[k] = req.body[k];
    });
    updates.updated_at = new Date().toISOString();
    if (!Object.keys(updates).length) return fail(res, 'Nothing to update');
    const { error } = await supabase.from('earnings').update(updates).eq('uuid', req.params.uuid);
    if (error) return fail(res, 'Database error', 500);
    return ok(res, {});
});
let _featureFlagCache = null;
let _featureFlagCacheAt = 0;
const FF_CACHE_TTL_MS = 30 * 1000;
const FF_ROW_ID = '__feature_flags__';
async function loadFeatureFlags() {
    const now = Date.now();
    if (_featureFlagCache && now - _featureFlagCacheAt < FF_CACHE_TTL_MS) return _featureFlagCache;
    const { data, error } = await supabase
        .from('users')
        .select('metadata')
        .eq('uuid', FF_ROW_ID)
        .maybeSingle();
    if (error) {
        log.warn('FeatureFlags', 'Load failed, serving last known flags', {
            msg: error.message,
        });
        return _featureFlagCache || {};
    }
    _featureFlagCache = data?.metadata || {};
    _featureFlagCacheAt = now;
    return _featureFlagCache;
}
/**
 * Is a feature switched off for this caller?
 *
 * The flags used to be read only by the launcher, which drew a "currently under
 * maintenance" screen while the endpoints behind it stayed wide open: anything
 * that talked to the API directly carried on working. Staff are exempt, so a
 * feature can be finished and tried in production while it is still dark for
 * players.
 */
async function featureDisabled(flag, user) {
    if (user && [ROLES.OWNER, ROLES.ADMIN, ROLES.DEVELOPER].includes(user.role)) return false;
    const flags = await loadFeatureFlags();
    return flags?.[flag] === true;
}

async function saveFeatureFlags(flags) {
    const { error } = await supabase.from('users').upsert(
        {
            uuid: FF_ROW_ID,
            username: FF_ROW_ID,
            role: ROLES.USER,
            metadata: flags,
        },
        {
            onConflict: 'uuid',
        },
    );
    if (error) {
        log.error('FeatureFlags', 'Persist failed', {
            msg: error.message,
        });
        throw new Error('Could not persist feature flags');
    }
    _featureFlagCache = flags;
    _featureFlagCacheAt = Date.now();
    return flags;
}
app.get('/feature-flags', requireAuth, async (req, res) => {
    try {
        const flags = await loadFeatureFlags();
        return ok(res, {
            flags,
        });
    } catch (e) {
        return ok(res, {
            flags: {},
        });
    }
});
app.get(
    '/admin/feature-flags',
    requireAuth,
    requireRole(ROLES.ADMIN, ROLES.OWNER),
    async (req, res) => {
        try {
            const flags = await loadFeatureFlags();
            return ok(res, {
                flags,
            });
        } catch (e) {
            return ok(res, {
                flags: {},
            });
        }
    },
);
app.patch('/admin/feature-flags', requireAuth, requireRole(ROLES.OWNER), async (req, res) => {
    const allowed = [
        'mods_disabled',
        'store_disabled',
        'hosting_disabled',
        'chat_disabled',
        'gifting_disabled',
        'radio_disabled',
        'rewards_disabled',
    ];
    const updates = {};
    for (const key of allowed) {
        if (key in req.body) updates[key] = Boolean(req.body[key]);
    }
    if (!Object.keys(updates).length) return fail(res, 'No valid flag keys provided');
    try {
        const current = await loadFeatureFlags();
        const merged = {
            ...current,
            ...updates,
        };
        const flags = await saveFeatureFlags(merged);
        return ok(res, {
            flags,
        });
    } catch (e) {
        return fail(res, 'Could not persist feature flags', 500);
    }
});

/* ------------------------------------------------------------------ */
/*  Breeze Minecraft mod endpoints (tag + cape sync, file-based)      */
/* ------------------------------------------------------------------ */
const breezeModUuidRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const breezeModUuidFind = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i;
const breezeModNameRe = /^[A-Za-z0-9_\-]{1,48}(\.(png|gif))?$/;
const breezeModCapeDir = path.resolve(__dirname, 'mod_capes');
const breezeModOwnersFile = path.join(MOD_DATA_DIR, 'cape_owners.txt');
const breezeModSelectionsFile = path.join(MOD_DATA_DIR, 'selections.txt');
const breezeModOnlineMs = 90000;
const breezeModUsers = new Map();
const breezeModSelected = new Map();

try {
    if (!fs.existsSync(breezeModCapeDir)) fs.mkdirSync(breezeModCapeDir, { recursive: true });
    if (!fs.existsSync(breezeModOwnersFile)) {
        fs.writeFileSync(breezeModOwnersFile,
            '# one line per player: <uuid> <cape> <cape> ...\r\n' +
            '# use * instead of a uuid to give a cape to everyone\r\n' +
            '# cape names = file names inside the mod_capes/ folder\r\n');
    }
    if (fs.existsSync(breezeModSelectionsFile)) {
        for (const line of fs.readFileSync(breezeModSelectionsFile, 'utf8').split(/\r?\n/)) {
            const parts = line.trim().split(/\s+/);
            if (parts.length === 2 && breezeModUuidRe.test(parts[0])) breezeModSelected.set(parts[0].toLowerCase(), parts[1]);
        }
    }
} catch (e) {
    log.warn('BreezeMod', 'init failed: ' + e.message);
}

function breezeModSaveSelections() {
    let out = '';
    for (const [k, v] of breezeModSelected) out += k + ' ' + v + '\r\n';
    try { fs.writeFileSync(breezeModSelectionsFile, out); } catch (e) {}
}

function breezeModStripExt(n) {
    const i = n.lastIndexOf('.');
    return i > 0 ? n.substring(0, i) : n;
}

function breezeModResolveCape(rawName) {
    if (!rawName || !breezeModNameRe.test(rawName)) return null;
    const candidates = rawName.includes('.')
        ? [rawName]
        : [rawName + '.png', rawName + '.gif', rawName + '.PNG', rawName + '.GIF'];
    for (const c of candidates) {
        const f = path.normalize(path.join(breezeModCapeDir, c));
        // Compare against the directory PLUS a separator. A bare startsWith
        // also matches a sibling directory whose name merely begins with the
        // same text (mod_capes_backup, mod_capes.old), which is not inside the
        // cape folder. breezeModNameRe already blocks separators, so this is
        // belt-and-braces rather than a live hole, but the containment check
        // should be correct on its own terms.
        if (f.startsWith(breezeModCapeDir + path.sep) && fs.existsSync(f)) return f;
    }
    return null;
}

function breezeModOwnedCapes(uuid) {
    const out = [];
    try {
        for (const line of fs.readFileSync(breezeModOwnersFile, 'utf8').split(/\r?\n/)) {
            const s = line.trim();
            if (!s || s.startsWith('#')) continue;
            const parts = s.split(/\s+/);
            if (parts.length < 2) continue;
            const who = parts[0].toLowerCase();
            if (who !== '*' && who !== uuid) continue;
            for (let i = 1; i < parts.length; i++) {
                const base = breezeModStripExt(parts[i]);
                if (breezeModResolveCape(base) && !out.some(o => o.toLowerCase() === base.toLowerCase())) out.push(base);
            }
        }
    } catch (e) {}
    return out;
}

// ── Unified cape source (Supabase) for the in-game mod ───────────────────────
// The launcher + website store cape ownership in Supabase (user_capes → capes,
// plus users.cape_url for personal capes). These helpers let the mod endpoints
// read from that single source of truth, while the flat mod_capes/ files remain
// a FALLBACK so hand-placed capes never break. The mod's request/response
// formats are unchanged, a Supabase cape's "name" is just its UUID (which fits
// breezeModNameRe), so ownership → select → byte-fetch still round-trips.
const breezeCapeIdRe = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function breezeUndash(id) { return String(id || '').replace(/-/g, '').toLowerCase(); }

// The mod sends dashed UUIDs; Supabase stores them undashed. Accept either on
// the cape endpoints and normalise to the dashed form the flat-file fallback
// keys off, so a caller using the DB's native format isn't rejected.
function breezeDashUuid(raw) {
    const flat = breezeUndash(raw);
    if (!/^[0-9a-f]{32}$/.test(flat)) return null;
    return `${flat.slice(0, 8)}-${flat.slice(8, 12)}-${flat.slice(12, 16)}-${flat.slice(16, 20)}-${flat.slice(20)}`;
}

async function breezeSupaOwnedCapeIds(dashedUuid) {
    const ids = [];
    try {
        const { data } = await supabase
            .from('user_capes')
            .select('cape:capes(id, image_url)')
            .eq('user_uuid', breezeUndash(dashedUuid));
        for (const row of data || []) {
            if (row.cape && row.cape.image_url && !ids.includes(row.cape.id)) ids.push(row.cape.id);
        }
    } catch (e) { log.warn('BreezeMod', 'supaOwnedCapeIds: ' + e.message); }
    return ids;
}

async function breezeSupaEquippedCape(dashedUuid) {
    const undashed = breezeUndash(dashedUuid);
    try {
        const { data } = await supabase
            .from('user_capes')
            .select('cape:capes(id, image_url)')
            .eq('user_uuid', undashed).eq('equipped', true).maybeSingle();
        if (data && data.cape && data.cape.image_url) return { id: data.cape.id, image_url: data.cape.image_url };
        const { data: u } = await supabase.from('users').select('cape_url').eq('uuid', undashed).maybeSingle();
        if (u && u.cape_url) return { id: 'personal', image_url: u.cape_url };
    } catch (e) { log.warn('BreezeMod', 'supaEquippedCape: ' + e.message); }
    return null;
}

// Bytes for a cape image URL: read from the API's own storage/ when it is a
// local /assets/ URL, otherwise fetch it (covers legacy Supabase-storage URLs).
/**
 * Hosts this API is willing to fetch a cape image from.
 *
 * image_url is a database value, and creator uploads can influence it, so
 * treating it as a fetchable URL let a crafted row make the server issue
 * requests of someone else's choosing, including to addresses only reachable
 * from inside the hosting network. Local assets are read from disk and never
 * fetched at all; anything else must be one of our own https hosts.
 */
const CAPE_FETCH_HOSTS = new Set(
    [API_PUBLIC_BASE_URL, FRONTEND_URL, process.env.SUPABASE_URL]
        .filter(Boolean)
        .map((value) => {
            try {
                return new URL(value).host.toLowerCase();
            } catch {
                return null;
            }
        })
        .filter(Boolean),
);

/** Returns an https URL we are allowed to fetch, or null. */
function capeFetchUrl(rawUrl) {
    let parsed;
    try {
        parsed = new URL(String(rawUrl));
    } catch {
        return null;
    }
    if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') return null;
    if (!CAPE_FETCH_HOSTS.has(parsed.host.toLowerCase())) return null;
    // Rows written before the proxy forwarded x-forwarded-proto still carry
    // http://; the host is ours either way, so upgrade rather than refuse.
    parsed.protocol = 'https:';
    return parsed.toString();
}

async function breezeCapeBytesFromUrl(imageUrl) {
    if (!imageUrl) return null;
    try {
        const idx = imageUrl.indexOf('/assets/');
        if (idx !== -1) {
            const rel = decodeURIComponent(imageUrl.slice(idx + '/assets/'.length).split('?')[0]);
            const buf = breezeAssets.readAsset(rel);
            if (buf) return buf;
        }
        const fetchable = capeFetchUrl(imageUrl);
        if (!fetchable) {
            log.warn('BreezeMod', 'Refused to fetch a cape image from an unapproved host');
            return null;
        }
        const r = await axios.get(fetchable, {
            responseType: 'arraybuffer',
            timeout: 8000,
            maxRedirects: 0,
            maxContentLength: 8 * 1024 * 1024,
        });
        return Buffer.from(r.data);
    } catch (e) { log.warn('BreezeMod', 'capeBytesFromUrl: ' + e.message); return null; }
}

async function breezeCapeBytesById(capeId) {
    try {
        const { data } = await supabase.from('capes').select('image_url').eq('id', capeId).maybeSingle();
        if (data && data.image_url) return await breezeCapeBytesFromUrl(data.image_url);
    } catch (e) {}
    return null;
}

app.get('/users', lookupLimiter, requireAnyBreezeToken, (req, res) => {
    const now = Date.now();
    const online = [];
    for (const [k, v] of breezeModUsers) if (now - v < breezeModOnlineMs) online.push(k);
    res.json(online);
});

/**
 * Drop presence entries that have aged out.
 *
 * breezeModUsers only ever grew: entries were written on every /announce and
 * read with a freshness check, but never deleted. Over a long uptime that is an
 * unbounded Map fed by an unauthenticated endpoint. Sweeping on write keeps it
 * proportional to the players actually online.
 */
function breezeModSweepPresence() {
    const cutoff = Date.now() - breezeModOnlineMs;
    for (const [k, v] of breezeModUsers) if (v < cutoff) breezeModUsers.delete(k);
}

app.post('/announce', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), (req, res) => {
    const body = typeof req.body === 'string' ? req.body : JSON.stringify(req.body || {});
    const m = body.match(breezeModUuidFind);
    if (!m) return res.status(400).send('no uuid');
    const uuid = m[0].toLowerCase();
    // Same staged rollout as the other mod routes; the uuid comes from the body
    // here rather than the path, so the check is inline instead of middleware.
    if (!modCallerAllowed(req, uuid)) {
        return res.status(403).send('mod authentication required');
    }
    breezeModSweepPresence();
    // Refuse to grow past the ceiling rather than accepting every UUID a script
    // can invent. Players already tracked still refresh normally.
    if (!breezeModUsers.has(uuid) && breezeModUsers.size >= MOD_MAX_TRACKED_USERS) {
        log.warn('BreezeMod', `Presence table full (${breezeModUsers.size}), ignoring new uuid`);
        return res.status(503).send('presence table full');
    }
    breezeModUsers.set(uuid, Date.now());
    breezeActivity.seen(uuid, 'game');
    const nm = body.match(/"name"\s*:\s*"([A-Za-z0-9_]{1,16})"/);
    if (nm && breezeModNames.get(uuid) !== nm[1]) {
        if (breezeModNames.has(uuid) || breezeModNames.size < MOD_MAX_TRACKED_USERS) {
            breezeModNames.set(uuid, nm[1]);
            breezeModSaveNames();
        }
    }
    res.send('ok');
});

// ─── Tag resolution (Section 5) ──────────────────────────────────────────────
// Tags come from the database. Role-derived tags (creator/admin/owner) are
// resolved from users.role rather than a hand-maintained membership list, which
// is what previously let 7 real creators fall through to the generic Breeze tag.

const TAG_CACHE_MS = 30 * 1000;
let tagCache = { at: 0, tags: [], userTags: new Map() };

/** Load tag definitions and explicit grants, briefly cached. */
async function loadTagData() {
    if (tagCache.at > Date.now() - TAG_CACHE_MS) return tagCache;
    const [tagsRes, grantsRes] = await Promise.all([
        supabase.from('tags').select('*').order('priority_weight', { ascending: false }),
        supabase.from('user_tags').select('user_uuid, tag_id'),
    ]);
    if (tagsRes.error) throw new Error(tagsRes.error.message);
    const userTags = new Map();
    for (const row of grantsRes.data || []) {
        if (!userTags.has(row.user_uuid)) userTags.set(row.user_uuid, []);
        userTags.get(row.user_uuid).push(row.tag_id);
    }
    tagCache = { at: Date.now(), tags: tagsRes.data || [], userTags };
    return tagCache;
}

function invalidateTagCache() { tagCache.at = 0; }

/** Tag icons render at 16px; 64 gives retina headroom without wasting bytes. */
const TAG_ICON_SIZE = 64;

/**
 * Every tag a user qualifies for, highest priority first.
 *
 * Two sources, deliberately: `auto_role` tags follow users.role automatically so
 * a newly promoted creator is tagged the instant their role changes, and
 * explicit user_tags grants cover everything else (Donator, custom tags).
 */
function tagsForUser(user, data) {
    if (!user) return [];
    const granted = new Set(data.userTags.get(user.uuid) || []);
    return data.tags
        .filter((t) => (t.auto_role && t.auto_role === user.role) || granted.has(t.id) || t.slug === 'breeze')
        .sort((a, b) => b.priority_weight - a.priority_weight);
}

/**
 * The single tag to display: the user's explicit choice when they have made one
 * and still qualify for it, otherwise the highest-priority tag they qualify for.
 * An equipped tag they no longer qualify for is ignored rather than shown, so a
 * demoted user cannot keep displaying a staff tag.
 */
function displayTagForUser(user, data) {
    const owned = tagsForUser(user, data);
    if (!owned.length) return null;
    if (user.equipped_tag_id) {
        const chosen = owned.find((t) => t.id === user.equipped_tag_id);
        if (chosen) return chosen;
    }
    return owned[0];
}

/**
 * The official role badge for a user: the highest-priority tag they qualify for
 * that is marked as a role badge.
 *
 * Separate from displayTagForUser on purpose. That one honours the user's own
 * choice, which is what should appear as their tag. This one ignores it, because
 * a badge the user picked is not evidence of anything: an Admin who equips
 * Donator is still an Admin and must still render as one.
 */
function roleBadgeForUser(user, data) {
    if (!user) return null;
    const owned = tagsForUser(user, data).filter((t) => t.is_role_badge);
    return owned.length ? owned[0] : null;
}

/**
 * The role table the mod renders from.
 *
 * Sent whole on every /tag poll so a role added in the database appears in game
 * without a mod update. The mod holds no hardcoded list of roles; it colours the
 * Tab List icon and the nameplate badge from whatever arrives here.
 */
function roleConfig(data) {
    const roles = {};
    for (const t of data.tags) {
        if (!t.is_role_badge) continue;
        roles[t.slug] = {
            name: t.name,
            color: t.color,
            priority: t.priority_weight,
            icon: t.icon_asset || null,
        };
    }
    return roles;
}

app.get('/tag', lookupLimiter, requireAnyBreezeToken, async (req, res) => {
    let text = '[Breeze]';
    let color = '#55C8FF';
    const players = {};
    const names = {};
    let roles = {};

    // Database tags first. The response shape is a superset of the previous
    // file-based one: older mod builds read text/color/players/names and ignore
    // the rest, so this does not break clients that have not updated.
    try {
        const data = await loadTagData();
        roles = roleConfig(data);
        if (data.tags.length) {
            const { data: users } = await supabase
                .from('users')
                .select('uuid, username, role, equipped_tag_id, custom_tag_color');
            for (const user of users || []) {
                if (String(user.uuid).startsWith('__')) continue;
                const tag = displayTagForUser(user, data);
                const badge = roleBadgeForUser(user, data);

                // A user with nothing but the default Breeze tag needs no entry:
                // the global default already covers them.
                const meaningful = (tag && tag.slug !== 'breeze') || (badge && badge.slug !== 'breeze');
                if (!meaningful) continue;

                const shown = tag && tag.slug !== 'breeze' ? tag : badge;
                const entry = {
                    text: shown ? `[${shown.name}]` : text,
                    color: shown ? tagColorFor(user, shown) : color,
                };
                if (shown && shown.icon_asset) entry.icon = shown.icon_asset;
                // The role badge travels separately from the displayed tag so
                // the mod can render both: the official badge is not something
                // the user's tag choice is allowed to hide. No `custom` field is
                // sent any more; older mods treat its absence as "none".
                if (badge) entry.badge = badge.slug;

                const dashed = breezeDashUuid(user.uuid);
                if (dashed) players[dashed.toLowerCase()] = entry;
                if (user.username) names[String(user.username).toLowerCase()] = entry;
            }
            const base = data.tags.find((t) => t.slug === 'breeze');
            if (base) { text = `[${base.name}]`; color = base.color; }
        }
    } catch (e) {
        // Players still get the default tag; nothing else is guessed.
        log.warn('Tags', `Database tags unavailable: ${e.message}`);
    }

    return res.json({ text: String(text).split('"').join(''), color, players, names, roles });
});

/**
 * Colours a creator may give their Creator tag, besides the role's own yellow.
 *
 * These are the wind charge variants that ship as art, so the tag chip in the
 * launcher shows the real wind charge in the colour picked. Red and purple are
 * left out on purpose: they are the Owner and Developer colours, and a creator
 * tag in either would read as staff at a glance.
 */
const CREATOR_TAG_COLORS = Object.freeze([
    { id: 'blue', name: 'Blue', color: '#55C8FF' },
    { id: 'pink', name: 'Pink', color: '#FF78C8' },
    { id: 'white', name: 'White', color: '#C8EBF5' },
]);

/** The slug of the one tag whose colour its holder may choose. */
const COLOR_CHOICE_TAG = 'creator';

function normalizeHex(raw) {
    const s = String(raw || '').trim();
    return /^#[0-9a-fA-F]{6}$/.test(s) ? s.toUpperCase() : null;
}

/**
 * A stored creator colour, or null when it is not one of the options.
 *
 * Checked on read, not only on write. The column held a free-form colour under
 * the old custom tag system, and an option could be withdrawn later; either way
 * a value that is not on the list today must not render.
 */
function creatorColorOption(raw) {
    const hex = normalizeHex(raw);
    return hex && CREATOR_TAG_COLORS.some((o) => o.color === hex) ? hex : null;
}

/**
 * The colour a tag is drawn in for this user.
 *
 * Every tag keeps the colour that identifies it, except the Creator tag, which
 * its holder may recolour. The badge is never passed through here: it is the
 * official mark and always carries the role colour.
 */
function tagColorFor(user, tag) {
    if (!tag) return null;
    if (tag.slug === COLOR_CHOICE_TAG) {
        const chosen = creatorColorOption(user && user.custom_tag_color);
        if (chosen) return chosen;
    }
    return tag.color;
}

/** Whether this user holds the tag whose colour can be chosen. */
function canChooseTagColor(user, data) {
    return tagsForUser(user, data).some((t) => t.slug === COLOR_CHOICE_TAG);
}

/**
 * The custom-name tag system is retired. Tags are role-based now, and the one
 * thing a creator configures is the colour of their Creator tag (POST
 * /tags/color). Launchers from before 1.0.22 still call this, so it answers
 * with a clear reason instead of a bare 404.
 */
app.post('/tags/custom', requireAuth, (req, res) =>
    fail(res, 'Custom tags have been retired. Creators can choose their tag colour instead.', 410));

/**
 * Choose the colour of the signed-in creator's tag. Passing null returns it to
 * the role colour.
 *
 * Stored in users.custom_tag_color, which the retired custom tag used, so this
 * needs no new column and works before any migration is run.
 */
app.post('/tags/color', requireAuth, async (req, res) => {
    const CTX = 'Tags/Color';
    try {
        const user = await dbGetUser(req.user.uuid);
        if (!user) return fail(res, 'User not found', 404);

        const data = await loadTagData();
        if (!canChooseTagColor(user, data)) return fail(res, 'Only creators can choose a tag colour', 403);

        const raw = req.body ? req.body.color : null;
        const resetting = raw === null || raw === undefined || String(raw).trim() === '';
        const color = resetting ? null : creatorColorOption(raw);
        if (!resetting && !color) return fail(res, 'That colour is not one of the options', 400);

        const { error } = await supabase
            .from('users')
            .update({ custom_tag_color: color })
            .eq('uuid', user.uuid);
        if (error) return fail(res, 'Could not save your tag colour', 500);

        // The mod reads /tag, which is cached; without this the change would not
        // appear in game for up to 30 seconds.
        invalidateTagCache();
        log.info(CTX, `${user.username || user.uuid} ${resetting ? 'reset' : 'set'} their tag colour`);
        return ok(res, { tagColor: color });
    } catch (e) {
        log.error(CTX, e.message);
        return fail(res, 'Could not save your tag colour', 500);
    }
});

/** Every tag the signed-in user owns, plus which one is displayed. */
app.get('/tags/mine', requireAuth, async (req, res) => {
    const CTX = 'Tags/Mine';
    try {
        const user = await dbGetUser(req.user.uuid);
        if (!user) return fail(res, 'User not found', 404);
        const data = await loadTagData();
        const owned = tagsForUser(user, data);
        const display = displayTagForUser(user, data);
        const badge = roleBadgeForUser(user, data);
        const choosable = canChooseTagColor(user, data);
        const roleTag = data.tags.find((t) => t.slug === COLOR_CHOICE_TAG);
        return ok(res, {
            tags: owned.map((t) => ({
                id: t.id,
                slug: t.slug,
                name: t.name,
                color: tagColorFor(user, t),
                icon: t.icon_asset || null,
                priority: t.priority_weight,
                // Explains why the user has it, so the wardrobe can say so.
                source: t.auto_role === user.role ? 'role' : t.slug === 'breeze' ? 'default' : 'granted',
            })),
            equippedTagId: display?.id || null,
            // True when the user has not chosen and is seeing the priority default.
            usingDefault: !user.equipped_tag_id,
            // The official badge, which the user's tag choice cannot change.
            badge: badge ? { slug: badge.slug, name: badge.name, color: badge.color } : null,
            // Creators recolour their Creator tag. tagColor is null while the
            // role colour applies; the options list leads with that default.
            canChooseColor: choosable,
            tagColor: choosable ? creatorColorOption(user.custom_tag_color) : null,
            colorOptions: choosable
                ? [
                    { id: 'role', name: 'Creator yellow', color: roleTag ? roleTag.color : null },
                    ...CREATOR_TAG_COLORS,
                ]
                : [],
        });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not load tags', 500);
    }
});

/** Choose which owned tag to display. Passing null returns to the default. */
app.post('/tags/equip', requireAuth, async (req, res) => {
    const CTX = 'Tags/Equip';
    try {
        const tagId = req.body?.tag_id ?? req.body?.tagId ?? null;
        const user = await dbGetUser(req.user.uuid);
        if (!user) return fail(res, 'User not found', 404);
        if (tagId) {
            const data = await loadTagData();
            // Never let a user equip a tag they do not qualify for.
            if (!tagsForUser(user, data).some((t) => t.id === tagId)) {
                return fail(res, 'You do not own that tag', 403);
            }
        }
        const { error } = await supabase
            .from('users')
            .update({ equipped_tag_id: tagId })
            .eq('uuid', req.user.uuid);
        if (error) {
            log.error(CTX, 'DB error', { msg: error.message });
            return fail(res, 'Could not equip that tag', 500);
        }
        invalidateTagCache();
        return ok(res, { equippedTagId: tagId });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not equip that tag', 500);
    }
});

/** Admin: list every tag definition. */
app.get('/admin/tags', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    try {
        const { data, error } = await repos.tags.all();
        if (error) throw new Error(error.message);
        const { data: counts } = await repos.tags.grants();
        const holders = {};
        for (const row of counts || []) holders[row.tag_id] = (holders[row.tag_id] || 0) + 1;
        return ok(res, { tags: (data || []).map((t) => ({ ...t, granted_to: holders[t.id] || 0 })) });
    } catch (err) {
        log.error('Tags/AdminList', 'Error', { msg: err.message });
        return fail(res, 'Could not load tags', 500);
    }
});

/** Admin: create or update a tag definition. */
app.post('/admin/tags', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const CTX = 'Tags/AdminSave';
    try {
        const slug = String(req.body?.slug || '').trim().toLowerCase().replace(/[^a-z0-9_-]/g, '');
        const name = String(req.body?.name || '').trim();
        if (!slug || !name) return fail(res, 'A slug and a display name are required');
        const color = /^#[0-9a-fA-F]{6}$/.test(req.body?.color || '') ? req.body.color : '#55FFFF';
        const priority = Number.isFinite(Number(req.body?.priority_weight))
            ? parseInt(req.body.priority_weight, 10)
            : 0;
        const row = {
            slug,
            name,
            color,
            priority_weight: priority,
            icon_asset: req.body?.icon_asset || null,
            auto_role: req.body?.auto_role || null,
            created_by: req.user.uuid,
        };
        const { data, error } = await repos.tags.upsertBySlug(row);
        if (error) throw new Error(error.message);
        invalidateTagCache();
        log.info(CTX, `Tag "${slug}" saved by ${req.user.uuid}`);
        return ok(res, { tag: data });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not save that tag', 500);
    }
});

/** Admin: grant or revoke a tag for one user, by username or uuid. */
app.post('/admin/tags/assign', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
    const CTX = 'Tags/AdminAssign';
    try {
        const tagId = req.body?.tag_id ?? req.body?.tagId;
        const revoke = Boolean(req.body?.revoke);
        const target = String(req.body?.username || req.body?.uuid || '').trim();
        if (!tagId || !target) return fail(res, 'A tag and a target user are required');

        const { data: users } = await repos.users.byNameOrUuid(target);
        const user = (users || [])[0];
        if (!user) return fail(res, 'No Breeze user with that name or uuid', 404);

        if (revoke) {
            const { error } = await repos.tags.revoke(user.uuid, tagId);
            if (error) throw new Error(error.message);
            // A user displaying a tag they just lost must fall back to default.
            await supabase
                .from('users')
                .update({ equipped_tag_id: null })
                .eq('uuid', user.uuid)
                .eq('equipped_tag_id', tagId);
        } else {
            const { error } = await repos.tags.grant(user.uuid, tagId, req.user.uuid);
            if (error) throw new Error(error.message);
        }
        invalidateTagCache();
        log.info(CTX, `${revoke ? 'Revoked' : 'Granted'} tag ${tagId} ${revoke ? 'from' : 'to'} ${user.username}`);
        return ok(res, { username: user.username, revoked: revoke });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not update that tag assignment', 500);
    }
});

/**
 * Admin: upload an icon for a tag (Section 5.3).
 *
 * Reuses the cape asset pipeline rather than adding a second upload mechanism:
 * same multer memory storage, same breezeAssets store, same public /assets URL.
 * Icons render at 16px in the client, so anything larger is wasted bytes on
 * every profile render; they are squared off at 64px for retina headroom.
 */
app.post(
    '/admin/tags/icon',
    requireAuth,
    requireRole(ROLES.ADMIN, ROLES.OWNER),
    upload.single('icon'),
    async (req, res) => {
        const CTX = 'Tags/AdminIcon';
        try {
            const tagId = req.body?.tag_id || req.body?.tagId;
            if (!tagId) return fail(res, 'Which tag is this icon for?');
            if (!req.file) return fail(res, 'No image provided');
            if (!['image/png', 'image/jpeg', 'image/jpg', 'image/gif'].includes(req.file.mimetype)) {
                return fail(res, 'Only PNG, JPEG and GIF icons are accepted');
            }

            const { data: tag, error: findErr } = await supabase
                .from('tags')
                .select('id, slug')
                .eq('id', tagId)
                .single();
            if (findErr || !tag) return fail(res, 'No tag with that id', 404);

            // `contain` rather than `cover`, because a wind charge cropped to a
            // square stops looking like a wind charge.
            const icon = await sharp(req.file.buffer)
                .resize(TAG_ICON_SIZE, TAG_ICON_SIZE, {
                    fit: 'contain',
                    background: { r: 0, g: 0, b: 0, alpha: 0 },
                })
                .png()
                .toBuffer();

            const fileRel = `tags/icons/${tag.slug}.png`;
            try {
                breezeAssets.storeAsset(fileRel, icon);
            } catch (uploadErr) {
                log.error(CTX, 'Storage error', { msg: uploadErr.message });
                return fail(res, 'Failed to store that icon', 500);
            }

            // Cache-bust: the path is derived from the slug, so re-uploading an
            // icon reuses the URL and clients would keep the old image.
            const iconUrl = `${breezeAssets.assetUrl(getRequestBaseUrl(req), fileRel)}?v=${Date.now()}`;
            const { error: updateErr } = await supabase
                .from('tags')
                .update({ icon_asset: iconUrl })
                .eq('id', tagId);
            if (updateErr) throw new Error(updateErr.message);

            invalidateTagCache();
            log.info(CTX, `Icon set for tag "${tag.slug}"`);
            return ok(res, { icon_asset: iconUrl });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Could not save that icon', 500);
        }
    },
);

/** Admin: drop a tag's icon and fall back to the built-in wind charge. */
app.delete(
    '/admin/tags/icon/:id',
    requireAuth,
    requireRole(ROLES.ADMIN, ROLES.OWNER),
    async (req, res) => {
        const CTX = 'Tags/AdminIconDelete';
        try {
            const { data: tag } = await supabase
                .from('tags')
                .select('id, slug')
                .eq('id', req.params.id)
                .single();
            if (!tag) return fail(res, 'No tag with that id', 404);

            try {
                breezeAssets.deleteAsset(`tags/icons/${tag.slug}.png`);
            } catch {
                // Already gone. Clearing the column is what actually matters.
            }
            const { error } = await supabase
                .from('tags')
                .update({ icon_asset: null })
                .eq('id', tag.id);
            if (error) throw new Error(error.message);

            invalidateTagCache();
            log.info(CTX, `Icon cleared for tag "${tag.slug}"`);
            return ok(res, { icon_asset: null });
        } catch (err) {
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Could not clear that icon', 500);
        }
    },
);

// Owned capes for a player, Supabase capes (by UUID) merged with any legacy
// flat-file / '*' capes. Same JSON-array-of-names response the mod expects.
/**
 * Complete cosmetic state for one player, in a single call.
 *
 * Why this exists
 * ---------------
 * The mod previously assembled a player's appearance from several endpoints
 * that each returned a different shape: `/capes/:uuid` gave bare name strings,
 * `/selected/:uuid` gave a name, `/tag` gave a merged blob for everyone. None
 * of them carried animation frames, so the mod could not render an animated
 * cape even in principle: it received one image and registered one static
 * texture.
 *
 * Worse, those endpoints could disagree. A cape equipped in the launcher
 * updated the database immediately, but the mod only noticed after its own
 * cache expired, and the tag path had a separate cache with a different TTL.
 * That is the desynchronisation between launcher and game.
 *
 * This endpoint is the single source of truth. Everything the mod needs to draw
 * a player is here, resolved at request time from the same tables the launcher
 * writes to, so the two cannot drift.
 *
 * `revision` lets a client poll cheaply: if it has not changed, nothing about
 * this player's appearance has changed and the client can skip all the work of
 * re-fetching textures.
 */
app.get('/cosmetics/state/:modUuid', lookupLimiter, async (req, res) => {
    const CTX = 'Cosmetics/State';
    const id = breezeDashUuid(req.params.modUuid);
    if (!id) return fail(res, 'bad uuid', 400);
    // Supabase stores uuids undashed (every row, checked 2026-10-03); the mod
    // sends them dashed. Looking up the dashed form found nobody, so every
    // player came back as "not a Breeze account" with no cape.
    // Both forms are asked for, so a row written either way is found.
    const ids = [breezeUndash(id), id];

    try {
        // users has no equipped_cape_id column. Asking for it failed the whole
        // read, which also answered "not a Breeze account" for everyone. The
        // equipped cape is user_capes.equipped, which the launcher writes.
        const { data: user, error: userError } = await supabase
            .from('users')
            .select('uuid, username, role, cape_url, equipped_tag_id, custom_tag_color')
            .in('uuid', ids)
            .maybeSingle();
        if (userError) {
            // A failed read is not "no account". Answer with an error so the mod
            // keeps its last state and uses its fallback routes meanwhile.
            log.error(CTX, 'Could not read the user', { msg: userError.message });
            return fail(res, 'Could not read cosmetic state', 500);
        }

        if (!user) {
            // Not a Breeze account. Answer with an empty-but-valid state rather
            // than a 404, so the mod does not have to special-case it.
            return ok(res, {
                uuid: id, username: null, role: 'user',
                cape: null, ownedCapes: [], tag: null, availableTags: [],
                badge: null, customTag: null, canCustomTag: false, revision: '0',
            });
        }

        // Equipped cape, with its animation frames if it has any: the
        // user_capes row marked equipped, the same one /cape and /selected read.
        let cape = null;
        const { data: equippedRow } = await supabase
            .from('user_capes')
            .select('capes(id, name, image_url, rarity, is_animated, animation_fps, animation_frames)')
            .in('user_uuid', ids)
            .eq('equipped', true)
            .limit(1)
            .maybeSingle();
        const c = equippedRow && equippedRow.capes;
        if (c) {
            const frames = Array.isArray(c.animation_frames) ? c.animation_frames : [];
            cape = {
                id: c.id,
                name: c.name,
                imageUrl: c.image_url,
                rarity: c.rarity || 'premium',
                // Only animated when there is genuinely more than one frame.
                // A single-frame "animated" cape is a still image and the
                // mod should not spin up a frame timer for it.
                animated: Boolean(c.is_animated) && frames.length > 1,
                fps: Number(c.animation_fps) || 12,
                frames: frames,
            };
        } else if (user.cape_url) {
            // A personal cape: the player's own upload. Most are kept in
            // Supabase Storage, and the mod fetches images only from this API,
            // so it is served through /cape/:uuid, which reads the same
            // users.cape_url. The mod caches textures by cape id: one shared
            // "personal" id drew the first personal cape it loaded on everyone
            // who has one, so the id names the player and the image.
            const imageTag = nodeCrypto.createHash('sha1').update(String(user.cape_url)).digest('hex').slice(0, 8);
            cape = {
                id: `personal-${breezeUndash(id)}-${imageTag}`, name: 'Personal Cape',
                imageUrl: `${getRequestBaseUrl(req)}/cape/${id}`,
                rarity: 'personal', animated: false, fps: 0, frames: [],
            };
        }

        const { data: ownedRows } = await supabase
            .from('user_capes')
            .select('cape_id, capes(id, name, image_url, rarity, is_animated)')
            .in('user_uuid', ids);

        const ownedCapes = (ownedRows || [])
            .map((r) => r.capes)
            .filter(Boolean)
            .map((c) => ({
                id: c.id, name: c.name, imageUrl: c.image_url,
                rarity: c.rarity || 'premium', animated: Boolean(c.is_animated),
            }));

        // Tags, resolved through the SAME functions the launcher and the /tag
        // endpoint use, so the badge in game cannot disagree with the badge in
        // the launcher. tagsForUser takes the user row and the loaded tag data,
        // not a uuid, and displayTagForUser applies the equipped-vs-priority
        // rule in one place.
        const tagData = await loadTagData();
        const owned = tagsForUser(user, tagData) || [];
        const availableTags = owned.map((t) => ({
            id: t.id, slug: t.slug, name: t.name, color: tagColorFor(user, t),
            icon: t.icon_asset ? t.icon_asset : null,
            priority: t.priority_weight ?? 0,
            source: t.auto_role ? 'role' : 'grant',
        }));

        // The official role badge, resolved independently of what the user
        // chose to display. An Admin who equips Donator is still an Admin and
        // the mod must be able to draw that, which is why this is a separate
        // field rather than being folded into `tag`.
        const badgeTag = roleBadgeForUser(user, tagData);
        const badge = badgeTag
            ? {
                slug: badgeTag.slug, name: badgeTag.name, color: badgeTag.color,
                icon: badgeTag.icon_asset || null, priority: badgeTag.priority_weight ?? 0,
            }
            : null;

        const shown = displayTagForUser(user, tagData);
        const displayed = shown
            ? availableTags.find((t) => t.id === shown.id) || {
                id: shown.id, slug: shown.slug, name: shown.name, color: tagColorFor(user, shown),
                icon: shown.icon_asset ? shown.icon_asset : null,
                priority: shown.priority_weight ?? 0,
                source: shown.auto_role ? 'role' : 'grant',
            }
            : null;

        // A cheap change detector. Any field that affects how this player is
        // drawn contributes, so a client can poll this and skip everything else
        // when it has not moved.
        const revision = nodeCrypto
            .createHash('sha1')
            .update([
                user.role, cape?.id || '', user.cape_url || '',
                user.equipped_tag_id || '', displayed?.id || '',
                badge?.slug || '', creatorColorOption(user.custom_tag_color) || '',
                ownedCapes.map((c) => c.id).sort().join(','),
                availableTags.map((t) => t.id).sort().join(','),
            ].join('|'))
            .digest('hex')
            .slice(0, 16);

        return ok(res, {
            uuid: id,
            username: user.username,
            role: user.role || 'user',
            cape,
            ownedCapes,
            tag: displayed,
            availableTags,
            badge,
            // The custom tag is retired. Kept as null and false so mod builds
            // from before 1.0.22 read "none" rather than failing on a missing key.
            customTag: null,
            canCustomTag: false,
            revision,
        });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not read cosmetic state', 500);
    }
});

app.get('/capes/:modUuid', lookupLimiter, async (req, res) => {
    const id = breezeDashUuid(req.params.modUuid);
    if (!id) return res.status(400).send('bad uuid');
    const names = await breezeSupaOwnedCapeIds(id);
    for (const legacy of breezeModOwnedCapes(id.toLowerCase())) {
        if (!names.some((n) => n.toLowerCase() === legacy.toLowerCase())) names.push(legacy);
    }
    res.json(names);
});

// Cape bytes by name. A UUID name resolves to the Supabase cape's texture;
// anything else falls back to a flat mod_capes/ file.
app.get('/capefile/:name', lookupLimiter, async (req, res) => {
    const name = req.params.name;
    if (breezeCapeIdRe.test(name)) {
        const buf = await breezeCapeBytesById(name);
        if (buf) {
            res.setHeader('Content-Type', 'application/octet-stream');
            return res.send(buf);
        }
    }
    const file = breezeModResolveCape(name);
    if (!file) return res.status(404).send('no such cape');
    res.setHeader('Content-Type', 'application/octet-stream');
    res.send(fs.readFileSync(file));
});

/**
 * Equip a tag from inside the game.
 *
 * Follows the same uuid-addressed convention as POST /select/:modUuid, which is
 * how the mod already changes capes: the mod holds no Breeze bearer token, so
 * the authenticated /tags/equip route the launcher uses is not available to it.
 *
 * Ownership is still enforced. The body is a tag id, or "none" to return to the
 * automatic highest-priority choice, and a tag the player does not qualify for
 * is refused rather than written.
 */
app.post('/cosmetics/tag/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const CTX = 'Cosmetics/TagEquip';
    const id = breezeDashUuid(req.params.modUuid);
    if (!id) return fail(res, 'bad uuid', 400);
    // Stored undashed; the dashed form matched no one (see /cosmetics/state).
    const ids = [breezeUndash(id), id];

    try {
        const { data: user } = await supabase
            .from('users')
            .select('uuid, role, equipped_tag_id')
            .in('uuid', ids)
            .maybeSingle();
        if (!user) return fail(res, 'No Breeze user with that uuid', 404);

        const raw = (typeof req.body === 'string' ? req.body : '').trim();
        const clearing = !raw || raw.toLowerCase() === 'none';

        if (!clearing) {
            // Same ownership rule the launcher applies, via the same helper, so
            // the two cannot diverge on what a player is allowed to wear.
            const tagData = await loadTagData();
            const owned = tagsForUser(user, tagData) || [];
            if (!owned.some((t) => String(t.id) === raw)) {
                return fail(res, 'You do not have that tag', 403);
            }
        }

        const { error } = await supabase
            .from('users')
            .update({ equipped_tag_id: clearing ? null : raw })
            .in('uuid', ids);
        if (error) throw new Error(error.message);

        // The tag cache feeds /tag and /cosmetics/state. Without this the mod
        // would write a change and then read back the old value until the cache
        // expired, which is the desync this whole release is fixing.
        invalidateTagCache();

        log.info(CTX, `${clearing ? 'Cleared' : 'Set'} tag for ${id}`);
        return ok(res, { equippedTagId: clearing ? null : raw });
    } catch (err) {
        log.error(CTX, 'Error', { msg: err.message });
        return fail(res, 'Could not change that tag', 500);
    }
});

app.post('/select/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const id = breezeDashUuid(req.params.modUuid);
    if (!id) return res.status(400).send('bad uuid');
    const uuid = id.toLowerCase();
    const undashed = breezeUndash(id);
    const name = (typeof req.body === 'string' ? req.body : '').trim();
    if (!name || name.toLowerCase() === 'none') {
        // Clear the selection in all three sources. users.cape_url must be
        // nulled too: breezeSupaEquippedCape treats a non-null cape_url as an
        // equipped cape, so skipping it left /selected and /cape serving the
        // old cape forever while this endpoint reported "cleared".
        // Mirrors POST /capes/unequip, which is the established convention.
        try { await supabase.from('user_capes').update({ equipped: false }).eq('user_uuid', undashed).eq('equipped', true); } catch (e) {}
        // cape_url only: users has no equipped_cape_id column, and naming it
        // failed the whole update, so cape_url was never cleared and the cape
        // removed in game came back through /cape and /selected.
        try { await supabase.from('users').update({ cape_url: null }).eq('uuid', undashed); } catch (e) {}
        breezeModSelected.delete(uuid);
        breezeModSaveSelections();
        return res.send('cleared');
    }
    // Supabase cape they own → equip it there (single source of truth).
    if (breezeCapeIdRe.test(name)) {
        try {
            const { data: owns } = await supabase
                .from('user_capes').select('cape_id')
                .eq('user_uuid', undashed).eq('cape_id', name).maybeSingle();
            if (owns) {
                await supabase.from('user_capes').update({ equipped: false }).eq('user_uuid', undashed).eq('equipped', true);
                await supabase.from('user_capes').update({ equipped: true }).eq('user_uuid', undashed).eq('cape_id', name);
                // cape_url follows, as POST /capes/equip (the launcher) sets it,
                // so every reader agrees on the new cape. (users has no
                // equipped_cape_id column; writing one failed this update.)
                const { data: capeRow } = await supabase.from('capes').select('image_url').eq('id', name).maybeSingle();
                if (capeRow?.image_url) await supabase.from('users').update({ cape_url: capeRow.image_url }).eq('uuid', undashed);
                breezeModSelected.delete(uuid); // Supabase now authoritative for this player
                breezeModSaveSelections();
                return res.send('selected ' + name);
            }
        } catch (e) { log.warn('BreezeMod', 'select supa: ' + e.message); }
    }
    // Legacy flat-file cape.
    if (!breezeModNameRe.test(name) || !breezeModResolveCape(name)) return res.status(404).send('no such cape');
    const base = breezeModStripExt(name);
    const owned = breezeModOwnedCapes(uuid).some(o => breezeModStripExt(o).toLowerCase() === base.toLowerCase());
    if (!owned) return res.status(403).send('not owned');
    breezeModSelected.set(uuid, base);
    breezeModSaveSelections();
    res.send('selected ' + base);
});

app.get('/selected/:modUuid', lookupLimiter, async (req, res) => {
    const id = breezeDashUuid(req.params.modUuid);
    if (!id) return res.status(400).send('bad uuid');
    const supa = await breezeSupaEquippedCape(id);
    if (supa) return res.send(supa.id);
    const name = breezeModSelected.get(id.toLowerCase());
    if (!name) return res.status(404).send('none');
    res.send(name);
});

// Bytes of the player's equipped cape: Supabase first, flat-file fallback.
app.get('/cape/:modUuid', lookupLimiter, async (req, res) => {
    const id = breezeDashUuid(req.params.modUuid);
    if (!id) return res.status(400).send('bad uuid');
    const supa = await breezeSupaEquippedCape(id);
    if (supa) {
        const buf = await breezeCapeBytesFromUrl(supa.image_url);
        if (buf) {
            res.setHeader('Content-Type', 'application/octet-stream');
            return res.send(buf);
        }
    }
    const name = breezeModSelected.get(id.toLowerCase());
    const file = name ? breezeModResolveCape(name) : null;
    if (!file) return res.status(404).send('no cape');
    res.setHeader('Content-Type', 'application/octet-stream');
    res.send(fs.readFileSync(file));
});
/* ------------------------------------------------------------------ */


/* ------------------------------------------------------------------ */
/*  Breeze mod: friends + DMs (file-based)                            */
/* ------------------------------------------------------------------ */
const breezeModNamesFile = path.join(MOD_DATA_DIR, 'names.txt');
const breezeModFriendsFile = path.join(MOD_DATA_DIR, 'friends.txt');
const breezeModRequestsFile = path.join(MOD_DATA_DIR, 'friend_requests.txt');
const breezeModDmsFile = path.join(MOD_DATA_DIR, 'dms.txt');
const breezeModNames = new Map();
const breezeModFriendPairs = new Set();
const breezeModFriendReqs = new Set();
const breezeModDms = [];

try {
    if (fs.existsSync(breezeModNamesFile)) {
        for (const line of fs.readFileSync(breezeModNamesFile, 'utf8').split(/\r?\n/)) {
            const p = line.trim().split(/\s+/);
            if (p.length === 2 && breezeModUuidRe.test(p[0])) breezeModNames.set(p[0].toLowerCase(), p[1]);
        }
    }
    if (fs.existsSync(breezeModFriendsFile)) {
        for (const line of fs.readFileSync(breezeModFriendsFile, 'utf8').split(/\r?\n/)) {
            const p = line.trim().split(/\s+/);
            if (p.length === 2 && breezeModUuidRe.test(p[0]) && breezeModUuidRe.test(p[1])) breezeModFriendPairs.add(breezeModPairKey(p[0].toLowerCase(), p[1].toLowerCase()));
        }
    }
    if (fs.existsSync(breezeModRequestsFile)) {
        for (const line of fs.readFileSync(breezeModRequestsFile, 'utf8').split(/\r?\n/)) {
            const p = line.trim().split(/\s+/);
            if (p.length === 2 && breezeModUuidRe.test(p[0]) && breezeModUuidRe.test(p[1])) breezeModFriendReqs.add(p[0].toLowerCase() + '|' + p[1].toLowerCase());
        }
    }
    if (fs.existsSync(breezeModDmsFile)) {
        for (const line of fs.readFileSync(breezeModDmsFile, 'utf8').split(/\r?\n/)) {
            const p = line.trim().split(/\s+/);
            if (p.length === 4 && breezeModUuidRe.test(p[1]) && breezeModUuidRe.test(p[2])) {
                try {
                    breezeModDms.push({ ts: parseInt(p[0], 10), from: p[1].toLowerCase(), to: p[2].toLowerCase(), text: Buffer.from(p[3], 'base64').toString('utf8') });
                } catch (e) {}
            }
        }
    }
} catch (e) {
    log.warn('BreezeMod', 'friends init failed: ' + e.message);
}

function breezeModPairKey(a, b) {
    return a < b ? a + '|' + b : b + '|' + a;
}

function breezeModSaveNames() {
    let out = '';
    for (const [k, v] of breezeModNames) out += k + ' ' + v + '\r\n';
    try { fs.writeFileSync(breezeModNamesFile, out); } catch (e) {}
}

function breezeModSaveFriends() {
    let out = '';
    for (const key of breezeModFriendPairs) out += key.split('|').join(' ') + '\r\n';
    try { fs.writeFileSync(breezeModFriendsFile, out); } catch (e) {}
}

function breezeModSaveReqs() {
    let out = '';
    for (const key of breezeModFriendReqs) out += key.split('|').join(' ') + '\r\n';
    try { fs.writeFileSync(breezeModRequestsFile, out); } catch (e) {}
}

function breezeModSaveDms() {
    if (breezeModDms.length > 5000) breezeModDms.splice(0, breezeModDms.length - 5000);
    let out = '';
    for (const m of breezeModDms.slice(-2000)) out += m.ts + ' ' + m.from + ' ' + m.to + ' ' + Buffer.from(m.text, 'utf8').toString('base64') + '\r\n';
    try { fs.writeFileSync(breezeModDmsFile, out); } catch (e) {}
}

/**
 * Move the friends, requests and messages made in game into the database, once.
 *
 * They were kept in flat files under the mod data directory, which the routes
 * no longer read. Those are real friendships and real conversations, so they
 * are imported rather than abandoned, and the files are left untouched as the
 * backup. A marker file records that it has run; each row is checked first, so
 * running it again cannot duplicate anything.
 */
async function importModSocialFiles() {
    const marker = path.join(MOD_DATA_DIR, 'social-imported.txt');
    if (fs.existsSync(marker)) return;
    const CTX = 'Mod/SocialImport';
    const counts = { friends: 0, requests: 0, messages: 0, skipped: 0 };
    try {
        const pairs = [
            ...[...breezeModFriendPairs].map((key) => ({ key, status: 'accepted' })),
            ...[...breezeModFriendReqs].map((key) => ({ key, status: 'pending' })),
        ];
        for (const { key, status } of pairs) {
            const [a, b] = key.split('|').map(undash);
            if (!looksLikeUuid(a) || !looksLikeUuid(b)) { counts.skipped++; continue; }
            const { row } = await modFriendship(a, b);
            if (row) { counts.skipped++; continue; }
            const { error } = await supabase.from('friendships').insert({
                requester_uuid: a,
                addressee_uuid: b,
                status,
                created_at: new Date().toISOString(),
                accepted_at: status === 'accepted' ? new Date().toISOString() : null,
            });
            if (error) counts.skipped++;
            else if (status === 'accepted') counts.friends++;
            else counts.requests++;
        }
        for (const m of breezeModDms) {
            const from = undash(m.from);
            const to = undash(m.to);
            if (!looksLikeUuid(from) || !looksLikeUuid(to) || !m.text) { counts.skipped++; continue; }
            const at = new Date(m.ts).toISOString();
            const { data: existing } = await supabase
                .from('messages')
                .select('id')
                .eq('sender_uuid', from)
                .eq('recipient_uuid', to)
                .eq('created_at', at)
                .maybeSingle();
            if (existing) { counts.skipped++; continue; }
            const { error } = await supabase.from('messages').insert({
                sender_uuid: from,
                recipient_uuid: to,
                body: m.text,
                created_at: at,
                // Already delivered in game; nothing should show as unread now.
                read_at: at,
            });
            if (error) counts.skipped++;
            else counts.messages++;
        }
        fs.writeFileSync(marker, `${new Date().toISOString()} ${JSON.stringify(counts)}\r\n`);
        log.info(CTX, `In-game social data imported: ${counts.friends} friendships, ${counts.requests} requests, ${counts.messages} messages, ${counts.skipped} skipped`);
    } catch (e) {
        log.warn(CTX, `Import did not finish, will try again next start: ${e.message}`);
    }
}

/** Seen within the presence window, from the stored heartbeat. */
function isRecentlySeen(lastSeen) {
    if (!lastSeen) return false;
    const age = Date.now() - new Date(lastSeen).getTime();
    return age >= 0 && age <= PRESENCE_ONLINE_WINDOW_MINUTES * 60 * 1000;
}

function breezeModOnline(u) {
    const t = breezeModUsers.get(u);
    return !!t && Date.now() - t < breezeModOnlineMs;
}

function breezeModFindByName(name) {
    const n = name.toLowerCase();
    for (const [u, nm] of breezeModNames) if (nm.toLowerCase() === n) return u;
    return null;
}

/*  In game, friends and direct messages are the same friends and messages the
    launcher shows. They used to be separate flat files whose only record of a
    player was an /announce from someone in a world, so a player who existed,
    had an account and simply was not online came back as "unknown player".

    The wire format is unchanged: text in, the same JSON out, dashed uuids. */

/** The mod speaks dashed uuids; the database stores them without dashes. */
const dashUuid = (u) => {
    const h = undash(u);
    return h.length === 32
        ? `${h.slice(0, 8)}-${h.slice(8, 12)}-${h.slice(12, 16)}-${h.slice(16, 20)}-${h.slice(20)}`
        : String(u);
};

/** The friendship row between two players, in whichever direction it was made. */
async function modFriendship(a, b) {
    const { data, error } = await supabase
        .from('friendships')
        .select('*')
        .or(`and(requester_uuid.eq.${a},addressee_uuid.eq.${b}),and(requester_uuid.eq.${b},addressee_uuid.eq.${a})`)
        .maybeSingle();
    return { row: data || null, error };
}

app.post('/friends/request/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const from = undash(req.params.modUuid);
    if (!looksLikeUuid(from)) return res.status(400).send('bad uuid');
    const name = (typeof req.body === 'string' ? req.body : '').trim();
    if (!/^[A-Za-z0-9_]{1,16}$/.test(name)) return res.status(400).send('bad name');

    const { user: target, reason } = await findUserByName(name);
    if (!target) {
        if (reason === 'db') return res.status(503).send('friends are not ready, try again in a moment');
        if (reason === 'lookup_failed') return res.status(503).send('could not check that name right now');
        return res.status(404).send('no Breeze account with that name');
    }
    const to = undash(target.uuid);
    if (to === from) return res.status(400).send('that is you');

    const { row, error } = await modFriendship(from, to);
    if (error) return res.status(503).send('friends are not ready, try again in a moment');
    if (row?.status === 'accepted') return res.send('already friends with ' + target.username);
    if (row?.status === 'blocked') return res.status(403).send('cannot add that player');
    if (row?.status === 'pending' && row.addressee_uuid === from) {
        // They asked first; asking back means yes.
        const { error: acceptErr } = await supabase
            .from('friendships')
            .update({ status: 'accepted', accepted_at: new Date().toISOString() })
            .eq('id', row.id);
        if (acceptErr) return res.status(503).send('could not accept, try again');
        await createNotification(to, 'friend_accept', 'Friend request accepted',
            `${(await dbGetUser(from))?.username || 'A player'} accepted your friend request.`, { request_id: row.id });
        return res.send('you are now friends with ' + target.username);
    }
    if (row?.status === 'pending') return res.send('request already sent');

    const { data: made, error: insertErr } = await supabase
        .from('friendships')
        .insert({ requester_uuid: from, addressee_uuid: to, status: 'pending', created_at: new Date().toISOString() })
        .select()
        .single();
    if (insertErr) return res.status(503).send('could not send the request, try again');
    await createNotification(to, 'friend_request', 'New friend request',
        `${(await dbGetUser(from))?.username || 'A player'} sent you a friend request.`, { request_id: made.id });
    res.send('request sent to ' + target.username);
});

app.get('/friends/list/:modUuid', lookupLimiter, requireModIdentity, async (req, res) => {
    const u = undash(req.params.modUuid);
    if (!looksLikeUuid(u)) return res.status(400).send('bad uuid');
    const { data, error } = await supabase
        .from('friendships')
        .select('*')
        .or(`requester_uuid.eq.${u},addressee_uuid.eq.${u}`);
    if (error) return res.status(503).send('friends are not ready, try again in a moment');
    const rows = data ?? [];
    const others = [...new Set(rows.map((r) => (r.requester_uuid === u ? r.addressee_uuid : r.requester_uuid)))];
    let profiles = [];
    if (others.length) {
        const result = await supabase.from('users').select('uuid, username, last_seen').in('uuid', others);
        profiles = result.data ?? [];
    }
    const byUuid = new Map(profiles.map((p) => [p.uuid, p]));
    const nameOf = (id) => byUuid.get(id)?.username || breezeModNames.get(dashUuid(id)) || '?';
    // In game presence is the better answer when we have it; the stored
    // last_seen covers a friend who is in the launcher rather than in a world.
    const onlineOf = (id) => breezeModOnline(dashUuid(id)) || isRecentlySeen(byUuid.get(id)?.last_seen);

    const friends = [];
    const incoming = [];
    const outgoing = [];
    for (const r of rows) {
        const other = r.requester_uuid === u ? r.addressee_uuid : r.requester_uuid;
        if (r.status === 'accepted') friends.push({ uuid: dashUuid(other), name: nameOf(other), online: onlineOf(other) });
        else if (r.status === 'pending' && r.addressee_uuid === u) incoming.push({ uuid: dashUuid(other), name: nameOf(other) });
        else if (r.status === 'pending') outgoing.push({ uuid: dashUuid(other), name: nameOf(other) });
    }
    res.json({ friends, incoming, outgoing });
});

app.post('/friends/accept/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const u = undash(req.params.modUuid);
    const other = undash(typeof req.body === 'string' ? req.body : '');
    if (!looksLikeUuid(u) || !looksLikeUuid(other)) return res.status(400).send('bad uuid');
    const { data: row } = await supabase
        .from('friendships')
        .select('id')
        .eq('requester_uuid', other)
        .eq('addressee_uuid', u)
        .eq('status', 'pending')
        .maybeSingle();
    if (!row) return res.status(404).send('no request');
    const { error } = await supabase
        .from('friendships')
        .update({ status: 'accepted', accepted_at: new Date().toISOString() })
        .eq('id', row.id);
    if (error) return res.status(503).send('could not accept, try again');
    await createNotification(other, 'friend_accept', 'Friend request accepted',
        `${(await dbGetUser(u))?.username || 'A player'} accepted your friend request.`, { request_id: row.id });
    res.send('accepted');
});

app.post('/friends/deny/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const u = undash(req.params.modUuid);
    const other = undash(typeof req.body === 'string' ? req.body : '');
    if (!looksLikeUuid(u) || !looksLikeUuid(other)) return res.status(400).send('bad uuid');
    await supabase
        .from('friendships')
        .delete()
        .eq('requester_uuid', other)
        .eq('addressee_uuid', u)
        .eq('status', 'pending');
    res.send('denied');
});

app.post('/friends/remove/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const u = undash(req.params.modUuid);
    const other = undash(typeof req.body === 'string' ? req.body : '');
    if (!looksLikeUuid(u) || !looksLikeUuid(other)) return res.status(400).send('bad uuid');
    const { row } = await modFriendship(u, other);
    // A blocked row belongs to whoever made it; removing must not lift a block.
    if (row && row.status !== 'blocked') await supabase.from('friendships').delete().eq('id', row.id);
    res.send('removed');
});

app.post('/dm/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, async (req, res) => {
    const from = undash(req.params.modUuid);
    if (!looksLikeUuid(from)) return res.status(400).send('bad uuid');
    const body = (typeof req.body === 'string' ? req.body : '').trim();
    const sp = body.indexOf(' ');
    if (sp <= 0) return res.status(400).send('bad body');
    const to = undash(body.substring(0, sp));
    const text = body.substring(sp + 1).trim().substring(0, 300);
    if (!looksLikeUuid(to) || !text) return res.status(400).send('bad body');
    const { row, error } = await modFriendship(from, to);
    if (error) return res.status(503).send('messages are not ready, try again in a moment');
    if (row?.status !== 'accepted') return res.status(403).send('not friends');
    const { error: insertErr } = await supabase.from('messages').insert({
        sender_uuid: from,
        recipient_uuid: to,
        body: text,
        created_at: new Date().toISOString(),
    });
    if (insertErr) return res.status(503).send('could not send, try again');
    res.send('sent');
});

// A player's own conversations. Gated on the same identity proof as sending,
// because handing someone else's messages to whoever asks is worse than letting
// them write a cape selection.
app.get('/dm/:modUuid', lookupLimiter, requireModIdentity, async (req, res) => {
    const u = undash(req.params.modUuid);
    if (!looksLikeUuid(u)) return res.status(400).send('bad uuid');
    const since = parseInt(req.query.since || '0', 10) || 0;
    let query = supabase
        .from('messages')
        .select('*')
        .or(`sender_uuid.eq.${u},recipient_uuid.eq.${u}`)
        .order('created_at', { ascending: true })
        .limit(100);
    if (since > 0) query = query.gt('created_at', new Date(since).toISOString());
    const { data, error } = await query;
    if (error) return res.status(503).send('messages are not ready, try again in a moment');
    const rows = data ?? [];
    const senders = [...new Set(rows.map((m) => m.sender_uuid))];
    let names = new Map();
    if (senders.length) {
        const result = await supabase.from('users').select('uuid, username').in('uuid', senders);
        names = new Map((result.data ?? []).map((p) => [p.uuid, p.username]));
    }
    res.json(rows.map((m) => ({
        ts: new Date(m.created_at).getTime(),
        from: dashUuid(m.sender_uuid),
        to: dashUuid(m.recipient_uuid),
        fromName: names.get(m.sender_uuid) || breezeModNames.get(dashUuid(m.sender_uuid)) || '?',
        text: m.body || '',
    })));
});
/* ---------------------- end breeze friends ------------------------ */


/* ------------------------------------------------------------------ */
/*  Breeze mod: host-a-world via server relay (no port forwarding)    */
/* ------------------------------------------------------------------ */
const breezeModHosts = new Map();          // hostUuid -> { session, name, invited:Set, ts }
const breezeRelaySessions = new Map();     // session  -> { control, pending:Map<cid,socket> }
const breezeModHostTtl = 120000;
let breezeRelayCid = 1;
// Ceiling on half-open joiners per relay session, see the JOIN branch below.
const BREEZE_RELAY_MAX_PENDING = parseInt(process.env.BREEZE_RELAY_MAX_PENDING ?? '32', 10);
// Ceiling on live host sessions, so a loop of HOST upgrades cannot hold
// sockets until the process runs out of file descriptors.
const BREEZE_RELAY_MAX_SESSIONS = parseInt(process.env.BREEZE_RELAY_MAX_SESSIONS ?? '500', 10);

app.post('/host/announce/:modUuid', modWriteLimiter, express.text({ type: () => true, limit: '32kb' }), requireModIdentity, (req, res) => {
    const host = req.params.modUuid.toLowerCase();
    if (!breezeModUuidRe.test(host)) return res.status(400).send('bad uuid');
    let body = {};
    try { body = JSON.parse(typeof req.body === 'string' ? req.body : '{}'); } catch (e) {}
    const session = (body.session || '').toString().slice(0, 48);
    const name = (body.name || '?').toString().slice(0, 16);
    if (!/^[A-Za-z0-9]{8,48}$/.test(session)) return res.status(400).send('bad session');
    const invited = (body.invited || '').toString().split(',').map(s => s.trim().toLowerCase()).filter(s => breezeModUuidRe.test(s));
    let rec = breezeModHosts.get(host);
    if (!rec) { rec = { session, name, invited: new Set(), ts: Date.now() }; breezeModHosts.set(host, rec); }
    rec.session = session;
    rec.name = name;
    rec.ts = Date.now();
    for (const u of invited) rec.invited.add(u);
    res.send('ok');
});

app.post('/host/stop/:modUuid', modWriteLimiter, requireModIdentity, (req, res) => {
    const host = req.params.modUuid.toLowerCase();
    // The UUID shape was never checked here, unlike every sibling route, so any
    // string at all was accepted and used as a Map key.
    if (!breezeModUuidRe.test(host)) return res.status(400).send('bad uuid');
    breezeModHosts.delete(host);
    res.send('stopped');
});

app.get('/host/invites/:modUuid', lookupLimiter, requireModIdentity, (req, res) => {
    const me = req.params.modUuid.toLowerCase();
    if (!breezeModUuidRe.test(me)) return res.status(400).send('bad uuid');
    const now = Date.now();
    const out = [];
    for (const [host, rec] of breezeModHosts) {
        if (now - rec.ts > breezeModHostTtl) { breezeModHosts.delete(host); continue; }
        if (rec.invited.has(me)) out.push({ host, name: rec.name, session: rec.session });
    }
    res.json(out);
});

function breezeAttachRelay(srv) {
    srv.on('upgrade', (req, socket, head) => {
        try {
            if ((req.url || '') !== '/breeze-relay') return;
            const bh = (req.headers['x-breeze'] || '').toString().trim();
            const parts = bh.split(/\s+/);
            const role = parts[0];
            if (role !== 'HOST' && role !== 'JOIN' && role !== 'PIPE') { socket.destroy(); return; }
            socket.setNoDelay(true);
            const session = parts[1];
            // Validate BEFORE switching protocols. The 101 used to be written
            // first, so a malformed session still got an accepted upgrade and
            // only then a destroyed socket, which is a free way to make the
            // server do work and to probe what it accepts.
            if (!/^[A-Za-z0-9]{8,48}$/.test(session || '')) { socket.destroy(); return; }
            if (role === 'HOST') {
                let s = breezeRelaySessions.get(session);
                if (!s && breezeRelaySessions.size >= BREEZE_RELAY_MAX_SESSIONS) { socket.destroy(); return; }
                if (!s) { s = { control: null, pending: new Map() }; breezeRelaySessions.set(session, s); }
                // A second HOST for a live session used to overwrite the first,
                // handing the session (and every joiner steered into it) to
                // whoever connected last. Sessions are single-host; the
                // incumbent keeps it until its socket closes.
                if (s.control && !s.control.destroyed) { socket.destroy(); return; }
                socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: breeze\r\nConnection: Upgrade\r\n\r\n');
                s.control = socket;
                socket.on('error', () => {});
                socket.on('close', () => {
                    const cur = breezeRelaySessions.get(session);
                    if (cur && cur.control === socket) breezeRelaySessions.delete(session);
                });
            } else if (role === 'JOIN') {
                const s = breezeRelaySessions.get(session);
                if (!s || !s.control) { socket.destroy(); return; }
                // Cap the half-open connections one session can accumulate.
                // Each pending joiner holds a live socket for up to 15s, so
                // without a ceiling a loop of JOINs exhausts the file
                // descriptor table and takes the whole API down with it.
                if (s.pending.size >= BREEZE_RELAY_MAX_PENDING) { socket.destroy(); return; }
                socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: breeze\r\nConnection: Upgrade\r\n\r\n');
                const cid = breezeRelayCid++;
                socket.pause();
                socket.on('error', () => {});
                socket.on('close', () => s.pending.delete(cid));
                s.pending.set(cid, socket);
                try { s.control.write('OPEN ' + cid + '\n'); } catch (e) { socket.destroy(); return; }
                setTimeout(() => { if (s.pending.get(cid) === socket) { s.pending.delete(cid); socket.destroy(); } }, 15000);
            } else if (role === 'PIPE') {
                const cid = parseInt(parts[2], 10);
                const s = breezeRelaySessions.get(session);
                const joiner = s && s.pending.get(cid);
                if (!joiner) { socket.destroy(); return; }
                socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: breeze\r\nConnection: Upgrade\r\n\r\n');
                s.pending.delete(cid);
                socket.on('error', () => {});
                joiner.on('error', () => {});
                socket.pipe(joiner);
                joiner.pipe(socket);
                joiner.resume();
            }
        } catch (e) {
            try { socket.destroy(); } catch (e2) {}
        }
    });
}
/* ---------------------- end breeze host --------------------------- */

/* ════════════════════════════════════════════════════════════════
   MODULAR SYSTEMS (src/): economy, asset serving, Audius music
   New backend systems live in src/ modules instead of growing this
   file. They receive the shared context below; server.js remains the
   entry point and keeps the proven legacy routes.
   ════════════════════════════════════════════════════════════════ */
const breezeCtx = {
    supabase,
    requireAuth,
    requireRole,
    ROLES,
    CREATOR_ACCESS_ROLES,
    hasCreatorPasses,
    ok,
    fail,
    log,
    jwt,
    JWT_SECRET,
    dbGetUser,
    createNotification,
    sendBreezeEmail,
    sendSystemEmail,
    getUserEmail,
    paypalCreateOrder,
    purchaseLimiter,
    grantItemToUser,
    expirePromoIfOneUse,
    findUserByNameOrUuid,
    lookupFailure,
    publicUser,
    featureDisabled,
    getRequestBaseUrl,
    upload,
    fitCapeBuffer,
    issueBreezeSessionForMcToken,
    CREATOR_SHARE_PERCENT,
    EMAIL_ADMIN,
    REPORT_EMAIL,
    // Players the mod reports as in game right now (POST /announce).
    modOnlineUuids: () => {
        const now = Date.now();
        const out = [];
        for (const [k, v] of breezeModUsers) if (now - v < breezeModOnlineMs) out.push(k);
        return out;
    },
};
breezeAssets(app, breezeCtx);
require('./src/auth-ms')(app, breezeCtx);
require('./src/economy')(app, breezeCtx);
require('./src/audius')(app, breezeCtx);
require('./src/announcements')(app, breezeCtx);
require('./src/adBoxes')(app, breezeCtx);
registerActivity(app, breezeCtx, breezeActivity);
registerDownloadStats(app, breezeCtx);

// Spotify lives in its own module and is mounted with the other feature
// modules, BEFORE the catch-all 404 below. Mounting it after that handler is
// what made every /spotify/* request answer "Endpoint not found": a pathless
// app.use() matches everything, so nothing registered after it is ever
// reached. It reports "not configured" rather than throwing when its
// environment variables are absent, so this is safe to mount unconditionally.
//
// It sits in src/ with the other API modules, and it must NOT be moved to the
// project root. The Pterodactyl egg's start command evaluates an unquoted
// `[[ "$MAIN_FILE" == *.js ]]`, and eval expands that glob against the working
// directory: with a second .js file beside server.js the pattern becomes two
// words and bash aborts with "syntax error in conditional expression" before
// Node is ever reached.
try {
    require('./src/spotify')(app, { supabase, requireAuth, ok, fail, log });
} catch (e) {
    // A failure to load Spotify must never stop the API from starting.
    log.error('Spotify', `Could not mount Spotify routes: ${e.message}`);
}

app.use((req, res) => {
    log.warn('Router', `404, ${req.method} ${req.path}`);
    res.status(404).json({
        success: false,
        error: 'Endpoint not found',
    });
});
app.use((err, req, res, next) => {
    log.error('Express', 'Unhandled error', {
        msg: err.message,
        stack: err.stack,
    });
    // The message stays in the log: error text can name tables, files and
    // hosts, so it never goes back to the caller, in any NODE_ENV.
    res.status(500).json({
        success: false,
        error: 'Internal server error',
    });
});
async function ensurePlatformSchema() {
    const schemaPath = path.join(__dirname, 'breeze-platform-schema.sql');
    if (!fs.existsSync(schemaPath)) return;
    const sql = fs.readFileSync(schemaPath, 'utf8');
    const { error } = await supabase.rpc('exec_sql', {
        sql,
    });
    if (error) {
        log.warn(
            'Schema',
            'Automatic schema install skipped; apply breeze-platform-schema.sql in Supabase if tables are missing',
            {
                msg: error.message,
            },
        );
    } else {
        log.info('Schema', 'Platform schema verified');
    }
}
async function sendStartupStatusEmail() {
    if (!STARTUP_EMAIL_ENABLED) return;
    const subject = `Breeze API online (${NODE_ENV})`;
    const text = [
        'Breeze API/backend started successfully.',
        `Mode: ${NODE_ENV}`,
        `Port: ${PORT}`,
        `Public API URL: ${API_PUBLIC_BASE_URL || '(derived from reverse proxy request headers)'}`,
        `PayPal: ${PAYPAL_CLIENT_ID ? PAYPAL_MODE : 'not configured'}`,
        `SMTP: ${hasMailConfig() ? SMTP_HOST : 'not configured'}`,
        `Versions manifest: ${fs.existsSync(VERSIONS_MANIFEST_PATH) ? VERSIONS_MANIFEST_PATH : 'environment fallback'}`,
        `Started: ${new Date().toISOString()}`,
    ].join('\n');
    await sendSystemEmail({
        to: STARTUP_EMAIL_TO,
        subject,
        text,
        html: `<pre style="font-family:ui-monospace,Menlo,Consolas,monospace;white-space:pre-wrap">${text}</pre>`,
    });
}
ensurePlatformSchema().catch((err) =>
    log.warn('Schema', 'Schema verification failed', {
        msg: err.message,
    }),
);
const breezeHttpServer = app.listen(PORT, '0.0.0.0', () => {
    console.log(`\n🚀 Breeze API v2.0 running on port ${PORT}`);
    console.log(`   Mode        : ${NODE_ENV}`);
    console.log(`   Public API  : ${API_PUBLIC_BASE_URL || '(derived from proxy headers)'}`);
    console.log(
        `   Versions    : ${fs.existsSync(VERSIONS_MANIFEST_PATH) ? VERSIONS_MANIFEST_PATH : 'environment fallback'}`,
    );
    console.log(
        `   PayPal      : ${PAYPAL_CLIENT_ID ? `✅ ${PAYPAL_MODE}` : '⚠️  not configured (mock mode)'}`,
    );
    console.log(`   SMTP        : ${SMTP_HOST ? `✅ ${SMTP_HOST}` : '⚠️  not configured'}`);
    console.log(`   Report to   : ${REPORT_EMAIL || '(not set)'}`);
    console.log(
        `   Creator %   : ${CREATOR_SHARE_PERCENT}% (env default, overridable per user in DB)`,
    );
    console.log(`   Co-owner %  : ${COOWNER_SHARE_PERCENT}% of remainder after creator cut`);
    console.log(`   Dev one %   : ${DEVELOPER_ONE_SHARE_PERCENT}% of remainder after creator cut`);
    console.log(`   Dev two %   : ${DEVELOPER_TWO_SHARE_PERCENT}% of remainder after creator cut`);
    console.log(`   Owner %     : remainder after creator + co-owner + developers\n`);

    // Security posture, printed every boot so a missing switch is visible in
    // the log rather than discovered by being exploited.
    const modAuthState = MOD_AUTH_REQUIRED
        ? '✅ enforced (a token must match the player it acts for)'
        : '⚠️  DISABLED by BREEZE_MOD_AUTH_REQUIRED=false, anyone can act as any player';
    console.log('   ── Security ──');
    console.log(`   Mod identity: ${modAuthState}`);
    console.log(
        `   Mod jar     : ${MOD_PUBLIC_LEGACY ? '⚠️  legacy public path open (BREEZE_MOD_PUBLIC_LEGACY=true)' : '✅ authorized downloads only'}`,
    );
    console.log(`   Rate limits : global ${GLOBAL_RATE_LIMIT}/min, mod writes ${process.env.BREEZE_MOD_WRITE_LIMIT ?? 60}/min`);
    if (PAYPAL_ALLOW_UNVERIFIED_WEBHOOKS) {
        console.log('   PayPal      : ⚠️  UNVERIFIED WEBHOOKS ACCEPTED, do not run this in production');
    }
    if (NODE_ENV !== 'production') {
        console.log(`   ⚠️  NODE_ENV is "${NODE_ENV}". Set NODE_ENV=production on the live server.`);
    }
    console.log('');
    // Friends and messages made in game live in the database from 1.0.23. The
    // old flat files are imported once, in the background: startup must not
    // wait on it, and a failure only means it is tried again next start.
    importModSocialFiles();
    sendStartupStatusEmail().catch((err) =>
        log.warn('Email', 'Startup email failed', {
            msg: err.message,
        }),
    );
});

try { breezeAttachRelay(breezeHttpServer); } catch (e) { console.warn('[Breeze] relay attach failed', e && e.message); }
