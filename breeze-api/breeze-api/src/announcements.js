'use strict';
/**
 * Announcements: one message from the team to every Breeze account.
 *
 * The launcher already polls GET /notifications and shows an unread count on
 * its bell, so an announcement is delivered as an ordinary notification row
 * per account. No launcher release is needed. The notification's type is what
 * the launcher prints above the title, so it names the sender:
 *   "Announcement from Phantomxdz (Owner)"
 *
 *   POST   /admin/announcements      { title, body }   send to everyone
 *   GET    /admin/announcements                        what was sent
 *   DELETE /admin/announcements/:id                    take it back from everyone
 *
 * The announcements table only keeps the history for the admin panel. If it
 * does not exist yet, sending still works; the list says how to set it up.
 * Accounts created after an announcement do not receive it.
 */
const crypto = require('crypto');

const TITLE_MAX = 120;
const BODY_MAX = 2000;
const PAGE = 1000;
const CHUNK = 500;
const ROLE_LABELS = { owner: 'Owner', admin: 'Admin', developer: 'Developer', creator: 'Creator' };
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Plain text, single spaces in the title, no control characters. */
function cleanText(value, max, { multiline = false } = {}) {
    let text = String(value ?? '').replace(/\r\n?/g, '\n');
    text = text.replace(multiline ? /[\u0000-\u0009\u000B-\u001F\u007F]/g : /[\u0000-\u001F\u007F]/g, ' ');
    text = multiline ? text.replace(/\n{3,}/g, '\n\n') : text.replace(/\s+/g, ' ');
    return text.trim().slice(0, max);
}

module.exports = function registerAnnouncements(app, ctx) {
    const { supabase, requireAuth, requireRole, ROLES, ok, fail, log, dbGetUser } = ctx;
    const adminOnly = [requireAuth, requireRole(ROLES.ADMIN, ROLES.OWNER)];
    // A double click must not send the same message twice.
    const recent = new Map();

    async function everyAccount() {
        const uuids = [];
        for (let from = 0; ; from += PAGE) {
            const { data, error } = await supabase
                .from('users')
                .select('uuid')
                .order('uuid', { ascending: true })
                .range(from, from + PAGE - 1);
            if (error) throw new Error(error.message);
            for (const row of data || []) if (row.uuid) uuids.push(row.uuid);
            if (!data || data.length < PAGE) break;
        }
        return [...new Set(uuids)];
    }

    app.post('/admin/announcements', ...adminOnly, async (req, res) => {
        const CTX = 'Announcements/Send';
        if (String(req.body?.title || '').trim().length > TITLE_MAX) {
            return fail(res, `Keep the title to ${TITLE_MAX} characters`);
        }
        if (String(req.body?.body || '').trim().length > BODY_MAX) {
            return fail(res, `Keep the message to ${BODY_MAX} characters`);
        }
        const title = cleanText(req.body?.title, TITLE_MAX);
        const body = cleanText(req.body?.body, BODY_MAX, { multiline: true });
        if (!title) return fail(res, 'Give the announcement a title');

        const fingerprint = `${req.user.uuid}|${title}|${body}`;
        const last = recent.get(fingerprint);
        if (last && Date.now() - last < 2 * 60 * 1000) {
            return fail(res, 'This announcement was just sent', 409);
        }
        recent.set(fingerprint, Date.now());
        for (const [key, at] of recent) if (Date.now() - at > 10 * 60 * 1000) recent.delete(key);

        try {
            const sender = (await dbGetUser(req.user.uuid).catch(() => null)) || {};
            const name = sender.username || req.user.username || 'Breeze';
            const role = sender.role || req.user.role || 'admin';
            const roleLabel = ROLE_LABELS[role] || 'Team';
            const id = crypto.randomUUID();
            const createdAt = new Date().toISOString();
            const type = `Announcement from ${name} (${roleLabel})`;

            const recipients = await everyAccount();
            const data = { kind: 'announcement', announcement_id: id, from_uuid: req.user.uuid, from_name: name, from_role: role };
            let delivered = 0;
            for (let i = 0; i < recipients.length; i += CHUNK) {
                const rows = recipients.slice(i, i + CHUNK).map((uuid) => ({
                    user_uuid: uuid,
                    type,
                    title,
                    body: body || null,
                    data,
                    read_at: null,
                    created_at: createdAt,
                }));
                const { error } = await supabase.from('notifications').insert(rows);
                if (error) {
                    log.error(CTX, 'Insert failed part way', { msg: error.message, delivered });
                    recent.delete(fingerprint);
                    return fail(res, `Sent to ${delivered} of ${recipients.length} accounts, then the database refused. Retract it and try again.`, 500);
                }
                delivered += rows.length;
            }

            const { error: historyError } = await supabase.from('announcements').insert({
                id,
                title,
                body: body || null,
                author_uuid: req.user.uuid,
                author_name: name,
                author_role: role,
                recipients: delivered,
                created_at: createdAt,
            });
            if (historyError) {
                log.warn(CTX, 'Sent, but not kept in the history (run the announcements SQL in Supabase)', {
                    msg: historyError.message,
                });
            }
            log.info(CTX, `${name} sent "${title}" to ${delivered} accounts`);
            return ok(res, { id, recipients: delivered, type, historySaved: !historyError });
        } catch (err) {
            recent.delete(fingerprint);
            log.error(CTX, 'Error', { msg: err.message });
            return fail(res, 'Could not send the announcement', 500);
        }
    });

    app.get('/admin/announcements', ...adminOnly, async (req, res) => {
        const { data, error } = await supabase
            .from('announcements')
            .select('*')
            .order('created_at', { ascending: false })
            .limit(50);
        if (error) {
            return fail(res, 'The announcement history is not set up yet. Run the announcements SQL in Supabase.', 503);
        }
        return ok(res, { announcements: data || [] });
    });

    app.delete('/admin/announcements/:id', ...adminOnly, async (req, res) => {
        const CTX = 'Announcements/Retract';
        const id = String(req.params.id || '').toLowerCase();
        if (!UUID_RE.test(id)) return fail(res, 'Unknown announcement', 400);
        const { error, count } = await supabase
            .from('notifications')
            .delete({ count: 'exact' })
            .contains('data', { announcement_id: id });
        if (error) {
            log.error(CTX, 'DB error', { msg: error.message });
            return fail(res, 'Could not retract the announcement', 500);
        }
        await Promise.resolve(
            supabase.from('announcements').update({ retracted_at: new Date().toISOString() }).eq('id', id),
        ).catch(() => null);
        log.info(CTX, `${req.user.username || req.user.uuid} retracted ${id} (${count ?? 0} removed)`);
        return ok(res, { id, removed: count ?? 0 });
    });
};
