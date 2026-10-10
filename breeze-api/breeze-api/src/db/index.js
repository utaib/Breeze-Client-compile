/**
 * Datastore abstraction (Section 18).
 *
 * Purpose: let a future MySQL backend be substituted without touching route
 * handlers. Today this is a pass-through to Supabase, so adopting it at a call
 * site is a one-word change with **zero** behavioural difference:
 *
 *     supabase.from('users').select('*')   ->   db.from('users').select('*')
 *
 * That is deliberate. A preparation refactor that changes behaviour while
 * touching 93 call sites is worse than no refactor at all, so the interface is
 * shaped to match what the code already does rather than inventing a nicer one
 * and rewriting every caller to suit it.
 *
 * The migration is NOT active and must not be switched on. See MYSQL_MIGRATION.md.
 */
const { createSupabaseDriver } = require('./supabaseDriver');
const { createMysqlDriver } = require('./mysqlDriver');

/**
 * Which datastore is live. Only 'supabase' is functional; 'mysql' exists so the
 * seam is real and testable rather than hypothetical, and it refuses loudly
 * rather than silently half-working.
 */
const DB_DRIVER = (process.env.DB_DRIVER || 'supabase').trim().toLowerCase();

function createDb(supabaseClient) {
    if (DB_DRIVER === 'mysql') return createMysqlDriver();
    return createSupabaseDriver(supabaseClient);
}

module.exports = { createDb, DB_DRIVER };
