'use strict';
/**
 * Launcher download counts.
 *
 * Every public installer is served by the /versions/launcher/* route in
 * server.js, which calls the counter below for each request. One row per
 * download goes to launcher_downloads: which system and file, where the
 * download came from, and whether the whole file was sent.
 *
 *   source  'website'  a Download button on breezeclient.net (its links carry ?src=site)
 *           'updater'  the launcher updating itself (User-Agent BreezeLauncher)
 *           'direct'   anything else: a link shared somewhere, a script
 *
 * Nothing that identifies a person is stored. `visitor` is a hash of the
 * address and browser under a random key that lives only in this process and
 * changes every day, so it can tell "the same person twice today" from "two
 * people" and nothing more. It cannot be reversed or matched across days.
 *
 * Counting never gets in the way of the download: a missing table or a
 * database error is logged once and the file is sent as usual.
 */
const crypto = require('crypto');
const path = require('path');

const INSTALLER_EXT = [
    ['.exe', 'windows'],
    ['.msi', 'windows'],
    ['.dmg', 'macos'],
    ['.appimage', 'linux'],
    ['.deb', 'linux'],
    ['.rpm', 'linux'],
];
const OS_FOLDERS = new Set(['windows', 'macos', 'linux']);
const SOURCES = ['website', 'updater', 'direct'];
// Link previews and crawlers fetch a URL without anyone downloading anything.
const NOT_A_PERSON = /bot\b|bot\/|crawler|spider|slurp|facebookexternalhit|embedly|preview|monitor|uptime/i;

/** windows, macos or linux for an installer path; null for anything else. */
function osOf(file) {
    const lower = String(file || '').toLowerCase();
    const match = INSTALLER_EXT.find(([ext]) => lower.endsWith(ext));
    if (!match) return null;
    const folder = lower.split('/')[0];
    return OS_FOLDERS.has(folder) ? folder : match[1];
}

/** The x.y.z in Breeze-Client-1.0.30-x86_64.exe, or null. */
function versionOf(file) {
    const m = /(\d+\.\d+\.\d+)/.exec(path.basename(String(file || '')));
    return m ? m[1] : null;
}

function sourceOf(req) {
    const src = String(req.query?.src || '').toLowerCase();
    if (src === 'site' || src === 'website') return 'website';
    if (/^BreezeLauncher/i.test(String(req.get('user-agent') || ''))) return 'updater';
    return 'direct';
}

let dailyKey = { day: '', key: null };
function visitorOf(req) {
    const day = new Date().toISOString().slice(0, 10);
    if (dailyKey.day !== day) dailyKey = { day, key: crypto.randomBytes(32) };
    return crypto
        .createHmac('sha256', dailyKey.key)
        .update(`${req.ip || ''}|${req.get('user-agent') || ''}`)
        .digest('hex')
        .slice(0, 20);
}

/**
 * Returns count(req, res, file). Call it just before the file is sent; the row
 * is written when the response closes, so a 404 or a refused request is never
 * counted and `completed` says whether the last byte went out.
 */
function createDownloadCounter({ supabase, log }) {
    let warned = false;
    return function countLauncherDownload(req, res, file) {
        try {
            if (req.method !== 'GET') return;
            const os = osOf(file);
            if (!os) return;
            if (NOT_A_PERSON.test(String(req.get('user-agent') || ''))) return;
            // A resumed or split download asks for later byte ranges; only the
            // part that starts at byte 0 is a new download.
            const range = String(req.headers.range || '').trim();
            if (range && !/^bytes=0-/i.test(range)) return;

            const row = {
                os,
                file: path.basename(file),
                version: versionOf(file),
                source: sourceOf(req),
                visitor: visitorOf(req),
            };
            res.once('close', () => {
                if (res.statusCode !== 200 && res.statusCode !== 206) return;
                Promise.resolve(
                    supabase.from('launcher_downloads').insert({
                        ...row,
                        completed: res.writableFinished === true,
                        created_at: new Date().toISOString(),
                    }),
                ).then(
                    ({ error } = {}) => {
                        if (error && !warned) {
                            warned = true;
                            log.warn('Downloads', 'Not counted (run the launcher_downloads SQL in Supabase)', {
                                msg: error.message,
                            });
                        }
                    },
                    () => {},
                );
            });
        } catch {
            /* counting is best effort; the download goes ahead */
        }
    };
}

const blank = () => ({ downloads: 0, website: 0, direct: 0, completed: 0, people: 0, updates: 0 });
const PAGE = 1000;
const MAX_ROWS = 500000;

/**
 * GET /admin/downloads?days=30
 *
 * downloads  new installs: the website plus direct links (not the updater)
 * website    of those, from a Download button on breezeclient.net
 * completed  of those, where the whole file was sent
 * people     of those, distinct visitors per day, summed (an estimate)
 * updates    the launcher updating itself
 */
function registerDownloadStats(app, ctx) {
    const { supabase, requireAuth, requireRole, ROLES, ok, fail, log } = ctx;

    app.get('/admin/downloads', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
        const days = Math.min(Math.max(parseInt(req.query.days, 10) || 30, 1), 365);
        const rows = [];
        try {
            for (let from = 0; from < MAX_ROWS; from += PAGE) {
                const { data, error } = await supabase
                    .from('launcher_downloads')
                    .select('os, source, completed, visitor, version, created_at')
                    .order('created_at', { ascending: true })
                    .range(from, from + PAGE - 1);
                if (error) {
                    log.warn('Downloads/Stats', 'Could not read launcher_downloads', { msg: error.message });
                    return fail(res, 'Download counting is not set up yet. Run the launcher_downloads SQL in Supabase.', 503);
                }
                rows.push(...(data || []));
                if (!data || data.length < PAGE) break;
            }
        } catch (err) {
            log.error('Downloads/Stats', 'Error', { msg: err.message });
            return fail(res, 'Server error', 500);
        }

        const total = blank();
        const byOs = { windows: blank(), macos: blank(), linux: blank() };
        const versions = new Map();
        const seen = new Set();
        const today = new Date();
        const daily = new Map();
        for (let i = days - 1; i >= 0; i--) {
            const d = new Date(Date.UTC(today.getUTCFullYear(), today.getUTCMonth(), today.getUTCDate() - i));
            daily.set(d.toISOString().slice(0, 10), { date: d.toISOString().slice(0, 10), windows: 0, macos: 0, linux: 0, updates: 0 });
        }

        for (const r of rows) {
            const os = byOs[r.os] ? r.os : null;
            const day = String(r.created_at || '').slice(0, 10);
            const buckets = os ? [total, byOs[os]] : [total];
            const v = r.version || 'unknown';
            if (!versions.has(v)) versions.set(v, { version: v, downloads: 0, updates: 0 });
            const dayRow = daily.get(day);

            if (r.source === 'updater') {
                for (const b of buckets) b.updates++;
                versions.get(v).updates++;
                if (dayRow) dayRow.updates++;
                continue;
            }
            const source = SOURCES.includes(r.source) ? r.source : 'direct';
            for (const b of buckets) {
                b.downloads++;
                b[source]++;
                if (r.completed) b.completed++;
            }
            versions.get(v).downloads++;
            if (dayRow && os) dayRow[os]++;
            const who = r.visitor ? `${day}|${r.visitor}` : null;
            if (!who || !seen.has(who)) total.people++;
            if (os && (!who || !seen.has(`${os}|${who}`))) byOs[os].people++;
            if (who) {
                seen.add(who);
                if (os) seen.add(`${os}|${who}`);
            }
        }

        return ok(res, {
            total,
            byOs,
            versions: [...versions.values()].sort((a, b) =>
                b.version.localeCompare(a.version, undefined, { numeric: true }),
            ),
            daily: [...daily.values()],
            days,
            since: rows.length ? rows[0].created_at : null,
        });
    });
}

module.exports = { createDownloadCounter, registerDownloadStats, osOf, versionOf };
