/**
 * Supabase driver: a pass-through.
 *
 * Every method hands straight to the real client, so `db.from(...)` behaves
 * identically to `supabase.from(...)` including the PostgREST chaining, the
 * `{ data, error }` result shape, and thenable behaviour. Nothing here should
 * ever transform a result. The moment it does, adopting `db` at a call site
 * stops being a safe no-op change and this whole approach loses its value.
 */
function createSupabaseDriver(supabase) {
    if (!supabase) throw new Error('The Supabase client is required.');
    return {
        name: 'supabase',
        /** Table access. Returns the PostgREST query builder unchanged. */
        from: (table) => supabase.from(table),
        /** Stored procedures. */
        rpc: (fn, params) => supabase.rpc(fn, params),
        /** Object storage, which MySQL has no equivalent for. See the notes. */
        storage: supabase.storage,
        /** Escape hatch for anything not yet covered by the interface. */
        raw: supabase,
    };
}

module.exports = { createSupabaseDriver };
