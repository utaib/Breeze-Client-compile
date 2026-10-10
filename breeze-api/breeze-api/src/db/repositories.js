/**
 * Domain repositories (Section 18).
 *
 * One module per data domain, so route handlers ask for what they want rather
 * than composing queries inline. These wrap `db`, never the Supabase client
 * directly, which is what makes them portable.
 *
 * Scope note: these cover the read paths that appear most often across the
 * route handlers. They are the pattern to copy, not the finished set. The
 * remaining call sites are inventoried in MYSQL_MIGRATION.md; each is a
 * mechanical move because `db.from` and `supabase.from` behave identically.
 *
 * Every function returns `{ data, error }` exactly as the caller already
 * expects, so adopting one is a drop-in change.
 */
function createRepositories(db) {
    const users = {
        byUuid: (uuid, cols = '*') =>
            db.from('users').select(cols).eq('uuid', uuid).maybeSingle(),

        byUsername: (username, cols = '*') =>
            db.from('users').select(cols).ilike('username', username).maybeSingle(),

        /** Accepts a dashed or undashed uuid, or a username. */
        byNameOrUuid: (target, cols = 'uuid, username') => {
            const undashed = String(target || '').replace(/-/g, '').toLowerCase();
            return db.from('users')
                .select(cols)
                .or(`uuid.eq.${undashed},username.ilike.${target}`)
                .limit(1);
        },

        updateByUuid: (uuid, patch) =>
            db.from('users').update(patch).eq('uuid', uuid),

        roleOf: (uuid) =>
            db.from('users').select('role').eq('uuid', uuid).maybeSingle(),
    };

    const tags = {
        all: () =>
            db.from('tags').select('*').order('priority_weight', { ascending: false }),

        bySlug: (slug) =>
            db.from('tags').select('*').eq('slug', slug).maybeSingle(),

        byId: (id, cols = '*') =>
            db.from('tags').select(cols).eq('id', id).maybeSingle(),

        upsertBySlug: (row) =>
            db.from('tags').upsert(row, { onConflict: 'slug' }).select().single(),

        setIcon: (id, iconAsset) =>
            db.from('tags').update({ icon_asset: iconAsset }).eq('id', id),

        grants: () => db.from('user_tags').select('tag_id'),

        grantsFor: (uuid) =>
            db.from('user_tags').select('tag_id').eq('user_uuid', uuid),

        grant: (uuid, tagId, grantedBy) =>
            db.from('user_tags').upsert(
                { user_uuid: uuid, tag_id: tagId, granted_by: grantedBy },
                { onConflict: 'user_uuid,tag_id' },
            ),

        revoke: (uuid, tagId) =>
            db.from('user_tags').delete().eq('user_uuid', uuid).eq('tag_id', tagId),
    };

    const capes = {
        publicList: () =>
            db.from('capes').select('*').eq('is_public', true),

        byId: (id) => db.from('capes').select('*').eq('id', id).maybeSingle(),

        ownedBy: (uuid) =>
            db.from('user_capes').select('*').eq('user_uuid', uuid),
    };

    const notifications = {
        forUser: (uuid) =>
            db.from('notifications')
                .select('*')
                .eq('user_uuid', uuid)
                .order('created_at', { ascending: false }),

        remove: (uuid, id) =>
            db.from('notifications').delete().eq('user_uuid', uuid).eq('id', id),
    };

    const friends = {
        forUser: (uuid) =>
            db.from('friendships').select('*').or(`user_a.eq.${uuid},user_b.eq.${uuid}`),
    };

    return { users, tags, capes, notifications, friends };
}

module.exports = { createRepositories };
