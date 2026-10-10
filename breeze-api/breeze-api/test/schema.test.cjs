// A fresh Breeze database has to be buildable from the two files in this
// repository, and running them again must not change or delete anything. This
// builds a throwaway Postgres (PGlite, in process) and does exactly that, so
// the claim in DEPLOYMENT.md is checked rather than asserted.
const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const API_DIR = path.join(__dirname, '..');
const ROOT = path.join(API_DIR, '..', '..');
const base = fs.readFileSync(path.join(API_DIR, 'schema-base.sql'), 'utf8');
const releases = fs.readFileSync(path.join(API_DIR, 'schema.sql'), 'utf8');

// What Supabase provides and a bare Postgres does not.
const SUPABASE_STUB = `
CREATE ROLE anon;
CREATE ROLE authenticated;
CREATE ROLE service_role;
CREATE SCHEMA IF NOT EXISTS extensions;
CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS storage;
CREATE OR REPLACE FUNCTION auth.uid() RETURNS uuid LANGUAGE sql STABLE AS $$ SELECT NULL::uuid $$;
CREATE OR REPLACE FUNCTION auth.role() RETURNS text LANGUAGE sql STABLE AS $$ SELECT NULL::text $$;
CREATE TABLE IF NOT EXISTS storage.buckets (id text PRIMARY KEY, name text NOT NULL, public boolean NOT NULL DEFAULT false);
CREATE TABLE IF NOT EXISTS storage.objects (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), bucket_id text, name text);
ALTER TABLE storage.objects ENABLE ROW LEVEL SECURITY;
`;

// PGlite ships no contrib extensions. gen_random_uuid() is core since Postgres
// 13, and nothing else in the schema needs pgcrypto or uuid-ossp, so the
// CREATE EXTENSION lines are dropped for the harness only.
const forTest = (sql) => sql.replace(/^CREATE EXTENSION[^;]*;/gm, '-- extension (not available in the test harness)');

const snapshot = async (db) => {
  const q = async (sql) => (await db.query(sql)).rows;
  return {
    tables: (await q(`select relname from pg_class c join pg_namespace n on n.oid=c.relnamespace
      where n.nspname='public' and c.relkind='r' order by 1`)).map((r) => r.relname),
    columns: (await q(`select c.relname||'.'||a.attname||' '||format_type(a.atttypid,a.atttypmod)
        ||case when a.attnotnull then ' NOT NULL' else '' end as col
      from pg_class c join pg_namespace n on n.oid=c.relnamespace
      join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
      where n.nspname='public' and c.relkind='r' order by 1`)).map((r) => r.col),
    constraints: (await q(`select conname from pg_constraint where connamespace='public'::regnamespace order by 1`)).map((r) => r.conname),
    indexes: (await q(`select indexname from pg_indexes where schemaname='public' order by 1`)).map((r) => r.indexname),
    policies: (await q(`select tablename||'.'||policyname as p from pg_policies where schemaname='public' order by 1`)).map((r) => r.p),
    functions: (await q(`select proname from pg_proc where pronamespace='public'::regnamespace order by 1`)).map((r) => r.proname),
    triggers: (await q(`select tgname from pg_trigger t join pg_class c on c.oid=t.tgrelid
      join pg_namespace n on n.oid=c.relnamespace where n.nspname='public' and not tgisinternal order by 1`)).map((r) => r.tgname),
    enums: (await q(`select t.typname||':'||string_agg(e.enumlabel, ',' order by e.enumsortorder) as v
      from pg_type t join pg_enum e on e.enumtypid=t.oid
      where t.typnamespace='public'::regnamespace group by t.typname order by 1`)).map((r) => r.v),
  };
};

test('a fresh database can be built from schema-base.sql and schema.sql', async (t) => {
  let PGlite;
  try {
    ({ PGlite } = await import('@electric-sql/pglite'));
  } catch {
    t.skip('@electric-sql/pglite is not installed');
    return;
  }
  const db = new PGlite();

  await db.exec(SUPABASE_STUB);
  await db.exec(forTest(base));
  await db.exec(forTest(releases));

  const first = await snapshot(db);
  assert.ok(first.tables.includes('users'), 'users table');
  assert.ok(first.tables.includes('cosmetics'), 'cosmetics table');
  assert.ok(first.tables.includes('friendships'), 'friendships table');
  assert.equal(first.tables.length, 29, 'every table');

  // The role ladder the mod and the launcher read.
  const roles = (await db.query(
    `select slug, color, priority_weight from public.tags where is_role_badge order by priority_weight desc`,
  )).rows;
  assert.deepEqual(
    roles.map((r) => `${r.slug}:${r.color}:${r.priority_weight}`),
    ['owner:#FF5555:100', 'developer:#A56EFF:90', 'admin:#800080:80', 'creator:#FFD23F:50', 'donator:#FFD700:30', 'breeze:#55C8FF:10'],
    'role badges, colours and order',
  );

  // user_role must carry developer, or assigning that role fails in production.
  assert.ok(first.enums.some((e) => e.startsWith('user_role:') && e.includes('developer')), 'developer role');

  // Only the service role may reach anything.
  assert.ok(first.policies.length > 0, 'policies exist');
  assert.equal(first.policies.filter((p) => !p.endsWith('.service_full_access')).length, 0,
    'no policy is written for a role other than service_role');

  // A tag created after the 1.0.22 cleanup must survive a re-run of the files.
  await db.exec(`INSERT INTO public.tags (slug, name, color, priority_weight, is_role_badge)
    VALUES ('event-test', 'Event', '#00FFAA', 20, false) ON CONFLICT (slug) DO NOTHING`);

  await db.exec(forTest(base));
  await db.exec(forTest(releases));

  const second = await snapshot(db);
  for (const key of Object.keys(first)) {
    assert.deepEqual(second[key], first[key], `${key} unchanged by a re-run`);
  }
  const kept = (await db.query(`select count(*)::int as n from public.tags where slug = 'event-test'`)).rows[0].n;
  assert.equal(kept, 1, 'a tag added later is not deleted by re-running schema.sql');

  // Every order the API writes must be storable. A Wind Charge pack has no
  // cape, and production refused it while cape_id was NOT NULL (2026-10-09);
  // a refund writes the status 'refunded'.
  await db.exec(`INSERT INTO public.users (uuid, username) VALUES ('a1111111111111111111111111111111', 'OrderTest')
    ON CONFLICT DO NOTHING`);
  const order = (await db.query(`INSERT INTO public.orders (id, user_uuid, order_type, wind_charges, gross_amount_usd, status)
    VALUES (gen_random_uuid(), 'a1111111111111111111111111111111', 'wind_charges', 320, 5, 'pending') RETURNING id`)).rows[0];
  assert.ok(order && order.id, 'a Wind Charge order without a cape is stored');
  await db.query(`UPDATE public.orders SET status = 'refunded', refunded_at = now() WHERE id = $1`, [order.id]);
  const status = (await db.query(`select status::text as s from public.orders where id = $1`, [order.id])).rows[0].s;
  assert.equal(status, 'refunded', 'a refund can be recorded');

  await db.close();
});

test('schema-base.sql covers the tables the API reads', async () => {
  // Cheap guard against the base file drifting behind the code: every
  // supabase.from('x') in the API has to exist in the schema.
  const server = fs.readFileSync(path.join(API_DIR, 'server.js'), 'utf8');
  const src = fs.readdirSync(path.join(API_DIR, 'src'))
    .filter((f) => f.endsWith('.js'))
    .map((f) => fs.readFileSync(path.join(API_DIR, 'src', f), 'utf8'))
    .join('\n');
  const used = new Set();
  for (const m of `${server}\n${src}`.matchAll(/\.from\(\s*'([a-z_]+)'\s*\)/g)) used.add(m[1]);
  const declared = new Set([...base.matchAll(/CREATE TABLE IF NOT EXISTS public\.([a-z_]+)/g)].map((m) => m[1]));
  const missing = [...used].filter((t) => !declared.has(t));
  assert.deepEqual(missing, [], `tables the API reads but schema-base.sql does not create: ${missing.join(', ')}`);
  assert.ok(ROOT.length > 0);
});
