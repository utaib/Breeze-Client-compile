# MySQL Migration Preparation

**Status: PREPARED, NOT ACTIVE. Do not switch this on.**

The instruction for this cycle was explicit: prepare the codebase for a future
move to a custom MySQL-backed API, do not perform the move. Nothing here changes
where production data lives. `DB_DRIVER` must stay `supabase`.

---

## What was built

A seam between the route handlers and the datastore, so a MySQL implementation
can be dropped in without touching business logic.

| file | role |
| --- | --- |
| `src/db/index.js` | picks a driver from `DB_DRIVER`, defaults to Supabase |
| `src/db/supabaseDriver.js` | pass-through to the existing client, no behaviour change |
| `src/db/mysqlDriver.js` | inert, throws a clear error if selected |
| `src/db/repositories.js` | domain modules (users, tags, capes, notifications, friends) |

The Supabase driver is a **deliberate pass-through**. `db.from(x)` and
`supabase.from(x)` return the identical PostgREST builder, so moving a call site
over is a one-word change that cannot alter behaviour. That property is what
makes finishing the migration safe to do incrementally rather than as one large
risky commit.

Verified by test: the repositories emit byte-identical query chains to the
hand-written queries they replaced (10 assertions, all passing), the driver
never transforms the `{ data, error }` result, and selecting the MySQL driver
fails loudly instead of half-working.

---

## Credentials for the future MySQL target

Add these to the API `env` when the time comes. Nothing reads them today.

```
# Inert until DB_DRIVER=mysql. Leave unset or 'supabase'.
DB_DRIVER=supabase

MYSQL_HOST=
MYSQL_PORT=3306
MYSQL_DATABASE=
MYSQL_USER=
MYSQL_PASSWORD=
# Some hosts hand you a single JDBC string instead of discrete fields. If so,
# put it here and parse it once, rather than hand-splitting it in several
# places:
#   jdbc:mysql://HOST:PORT/DATABASE?user=USER&password=PASSWORD
MYSQL_JDBC_URL=
MYSQL_SSL=false
```

**Never commit the env file.** It already holds the live Supabase service key,
the JWT secret and the SMTP password.

---

## Remaining work, inventoried

Adoption so far:

- **Migrated to repositories:** the tag admin endpoints (list, upsert, grant,
  revoke, set icon) and the user lookup they share.
- **Everything else** still calls `supabase` directly.

Why this was not finished in one pass: the API env holds live production
credentials, so the server cannot be booted here to regression-test. The plan
itself states that a preparation refactor which silently breaks a feature is
unacceptable, and moving 237 call sites unverified would risk exactly that. The
seam is built and proven; the remaining moves are mechanical and each is
individually safe.

### Call sites by table

| table | call sites |
| --- | --- |
| `users` | 57 |
| `capes` | 27 |
| `user_capes` | 19 |
| `orders` | 16 |
| `cosmetics` | 13 |
| `promo_codes` | 11 |
| `earnings` | 10 |
| `tickets` | 10 |
| `tags` | 10 |
| `friendships` | 7 |
| `changelog` | 7 |
| `notifications` | 6 |
| `user_cosmetics` | 6 |
| `promo_code_uses` | 6 |
| `news` | 6 |
| `ad_reward_sessions` | 5 |
| `user_equipped_cosmetics` | 5 |
| `gifts` | 4 |
| `feedback` | 4 |
| `ticket_messages` | 3 |
| `messages` | 2 |
| `user_tags` | 2 |
| `payouts` | 1 |

**237 call sites across 23 tables.**

---

## Implementing the MySQL driver

The hard part is not the connection, it is matching the PostgREST surface the
codebase already relies on. `src/db/mysqlDriver.js` lists the exact subset
required. Three things deserve attention up front:

1. **Embedded resources.** `select('a, b:other(*)')` performs a join and nests
   the result. This is the largest piece of work and the easiest thing to get
   subtly wrong.
2. **`.or()` filter strings.** PostgREST accepts a comma-separated filter
   grammar such as `uuid.eq.x,username.ilike.y`. A MySQL implementation has to
   parse it, and must parameterise the values rather than interpolating them.
3. **Storage has no MySQL equivalent.** Assets already write to disk through
   `src/assets.js`. Keep using that; do not emulate object storage.

Suggested order, so there is never a flag day:

1. Implement the builder against one low-traffic table (`payouts` or `messages`).
2. Run both drivers in parallel and compare results for a period.
3. Widen table by table.
4. Move `users` last. It is 57 of the call sites and touches authentication.
