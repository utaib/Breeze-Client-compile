'use strict';
/**
 * Player activity for the admin panel: who is using Breeze right now, and how
 * that changes over the hours and days.
 *
 * Two signals already reach the API:
 *   - the launcher's heartbeat, PATCH /social/presence, once a minute while a
 *     player is signed in (stamps users.last_seen);
 *   - the mod's POST /announce while a player is in game.
 * Neither kept any history, so this records it:
 *   - every 5 minutes, how many players are online (in the launcher, in game,
 *     and both together), kept for the last 7 days;
 *   - per UTC day, how many different players used Breeze, and the day's peak.
 *
 * It is one JSON file on the API's disk (BREEZE_STATS_DIR, default stats/
 * next to server.js), outside the public /assets folder, so no database table
 * is needed. Only today's list of player ids is kept, to count each player
 * once per day; past days keep numbers only.
 *
 *   GET /admin/activity?days=30   owner and admin
 */
const fs = require('fs');
const path = require('path');

const SAMPLE_MS = 5 * 60 * 1000;
const KEEP_SAMPLES_MS = 7 * 24 * 60 * 60 * 1000;
const KEEP_DAYS = 400;
const LAUNCHER_WINDOW_MS = 5 * 60 * 1000; // matches PRESENCE_ONLINE_WINDOW_MINUTES

const dayOf = (ms) => new Date(ms).toISOString().slice(0, 10);
const plainUuid = (u) => String(u || '').toLowerCase().replace(/-/g, '');

function createActivity({ log, dir, now = () => Date.now() } = {}) {
    const statsDir = path.resolve(dir || process.env.BREEZE_STATS_DIR || path.join(__dirname, '..', 'stats'));
    const file = path.join(statsDir, 'activity.json');

    function fresh() {
        return { since: dayOf(now()), days: {}, samples: [], today: { date: dayOf(now()), launcher: {}, game: {}, peak: 0, peakAt: null } };
    }

    let state = fresh();
    try {
        const loaded = JSON.parse(fs.readFileSync(file, 'utf8'));
        if (loaded && loaded.days && loaded.today) state = { ...fresh(), ...loaded };
    } catch {
        /* first start, or an unreadable file: start counting from now */
    }

    let dirty = false;
    let lastSave = 0;
    function save(force = false) {
        if (!dirty) return;
        if (!force && now() - lastSave < 60_000) return;
        try {
            fs.mkdirSync(statsDir, { recursive: true });
            const tmp = file + '.tmp';
            fs.writeFileSync(tmp, JSON.stringify(state));
            fs.renameSync(tmp, file);
            dirty = false;
            lastSave = now();
        } catch (err) {
            log?.warn?.('Activity', `Could not save activity: ${err.message}`);
        }
    }

    function todaySummary(t = state.today) {
        const launcher = Object.keys(t.launcher).length;
        const game = Object.keys(t.game).length;
        const active = new Set([...Object.keys(t.launcher), ...Object.keys(t.game)]).size;
        return { active, launcher, game, peak: t.peak || 0, peakAt: t.peakAt || null };
    }

    /** A new UTC day: the finished one keeps its numbers, not its player list. */
    function roll() {
        const today = dayOf(now());
        if (state.today.date === today) return;
        state.days[state.today.date] = todaySummary();
        const keys = Object.keys(state.days).sort();
        for (const k of keys.slice(0, Math.max(0, keys.length - KEEP_DAYS))) delete state.days[k];
        state.today = { date: today, launcher: {}, game: {}, peak: 0, peakAt: null };
        dirty = true;
    }

    function seen(uuid, where) {
        const id = plainUuid(uuid);
        if (!/^[0-9a-f]{32}$/.test(id) || (where !== 'launcher' && where !== 'game')) return;
        roll();
        if (!state.today[where][id]) {
            state.today[where][id] = 1;
            dirty = true;
        }
        save();
    }

    function sample(online) {
        roll();
        const at = now();
        state.samples.push([at, online.total, online.launcher, online.game]);
        const cutoff = at - KEEP_SAMPLES_MS;
        while (state.samples.length && state.samples[0][0] < cutoff) state.samples.shift();
        if (online.total > (state.today.peak || 0)) {
            state.today.peak = online.total;
            state.today.peakAt = new Date(at).toISOString();
        }
        dirty = true;
        save(true);
    }

    /** The last `n` days, oldest first, today included and live. */
    function daily(n) {
        roll();
        const out = [];
        const today = now();
        for (let i = n - 1; i >= 0; i--) {
            const date = dayOf(today - i * 86400000);
            const d = date === state.today.date ? todaySummary() : state.days[date];
            out.push({ date, active: d?.active || 0, launcher: d?.launcher || 0, game: d?.game || 0, peak: d?.peak || 0, recorded: !!d || date >= state.since });
        }
        return out;
    }

    return {
        seen,
        sample,
        daily,
        today: () => { roll(); return todaySummary(); },
        samples: (sinceMs) => state.samples.filter((s) => s[0] >= sinceMs),
        since: () => state.since,
        flush: () => save(true),
    };
}

/**
 * Who is online now. Launcher: a heartbeat within the presence window. In
 * game: the mod's presence table (modOnlineUuids). Together: either.
 */
async function onlineNow(ctx) {
    const { supabase } = ctx;
    const cutoff = new Date(Date.now() - LAUNCHER_WINDOW_MS).toISOString();
    const launcher = new Set();
    const { data, error } = await supabase.from('users').select('uuid').gte('last_seen', cutoff).limit(10000);
    if (!error) for (const row of data || []) launcher.add(plainUuid(row.uuid));
    const game = new Set((ctx.modOnlineUuids?.() || []).map(plainUuid));
    const total = new Set([...launcher, ...game]).size;
    return { total, launcher: launcher.size, game: game.size, at: new Date().toISOString(), launcherError: error ? error.message : null };
}

function registerActivity(app, ctx, activity) {
    const { requireAuth, requireRole, ROLES, ok, fail, log, supabase } = ctx;

    async function takeSample() {
        try {
            const online = await onlineNow(ctx);
            if (!online.launcherError) activity.sample(online);
        } catch (err) {
            log.warn('Activity', `Sample skipped: ${err.message}`);
        }
    }
    if (process.env.BREEZE_ACTIVITY_SAMPLER !== 'off') {
        const timer = setInterval(takeSample, SAMPLE_MS);
        timer.unref?.();
        setTimeout(takeSample, 15_000).unref?.();
    }

    app.get('/admin/activity', requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER), async (req, res) => {
        const days = Math.min(365, Math.max(7, parseInt(req.query.days, 10) || 30));
        try {
            const now = await onlineNow(ctx);
            const sinceMs = Date.now() - 48 * 60 * 60 * 1000;

            // Accounts: everyone who ever signed in to Breeze, and when.
            const windowStart = new Date(Date.now() - (days - 1) * 86400000);
            windowStart.setUTCHours(0, 0, 0, 0);
            const { count: totalAccounts } = await supabase.from('users').select('uuid', { count: 'exact', head: true });
            const { data: created } = await supabase.from('users').select('created_at').gte('created_at', windowStart.toISOString()).limit(100000);
            const perDay = new Map();
            for (const row of created || []) {
                const d = String(row.created_at || '').slice(0, 10);
                if (d) perDay.set(d, (perDay.get(d) || 0) + 1);
            }
            const daily = activity.daily(days).map((d) => ({ ...d, newAccounts: perDay.get(d.date) || 0 }));

            return ok(res, {
                now: { total: now.total, launcher: now.launcher, game: now.game, at: now.at },
                today: activity.today(),
                daily,
                recent: activity.samples(sinceMs).map(([at, total, launcher, game]) => ({ at: new Date(at).toISOString(), total, launcher, game })),
                accounts: { total: totalAccounts || 0, newInRange: (created || []).length },
                since: activity.since(),
            });
        } catch (err) {
            log.error('Activity', 'Error', { msg: err.message });
            return fail(res, 'Could not read activity', 500);
        }
    });
}

module.exports = { createActivity, registerActivity, onlineNow };
