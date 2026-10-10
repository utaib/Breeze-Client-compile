/**
 * MySQL driver: INERT. Not implemented, and must not be switched on.
 *
 * This exists so the seam in index.js is real rather than hypothetical, and so
 * that setting DB_DRIVER=mysql fails immediately and loudly instead of half
 * working and corrupting data. A future migration session implements this file
 * and nothing else in the route handlers.
 *
 * What a real implementation has to provide, derived from auditing the 271
 * existing operations across 25 tables:
 *
 *   from(table) must return a builder supporting the PostgREST subset actually
 *   used here:
 *     .select(cols)          including embedded resources, e.g. 'a, b:other(*)'
 *                            which become JOINs and are the hardest part
 *     .insert(rows)          .upsert(rows, { onConflict })
 *     .update(patch)         .delete()
 *     .eq / .neq / .gt / .gte / .lt / .lte / .in / .is / .ilike / .or
 *     .order(col, { ascending })  .limit(n)  .range(a, b)
 *     .single()              .maybeSingle()
 *   and it must be thenable, resolving to { data, error } and never throwing.
 *
 *   rpc(fn, params) maps to a stored procedure or a hand-written query.
 *
 *   storage has NO MySQL equivalent. Assets already write to disk through
 *   src/assets.js, so keep using that rather than emulating object storage.
 *
 * Migration order that avoids a flag day: implement the builder, point one
 * low-risk table at it, compare results against Supabase for a period, then
 * widen. See MYSQL_MIGRATION.md for credentials and the full call inventory.
 */
function notReady() {
    throw new Error(
        'The MySQL driver is not implemented. DB_DRIVER must stay "supabase". ' +
        'See MYSQL_MIGRATION.md before changing this.',
    );
}

function createMysqlDriver() {
    return {
        name: 'mysql',
        from: notReady,
        rpc: notReady,
        get storage() { return notReady(); },
        get raw() { return notReady(); },
    };
}

module.exports = { createMysqlDriver };
