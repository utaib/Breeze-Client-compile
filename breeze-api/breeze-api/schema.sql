-- ================================================================
--  Breeze Client — Platform Schema (v1.0.0)
--  Run in the Supabase SQL Editor. 100% idempotent — safe to re-run.
--
--  Supabase's role after v1.0.0: RELATIONAL DATA ONLY.
--  All binary assets (capes, cosmetics, thumbnails) are stored on the
--  API server's disk under breeze-api/storage/ and served at /assets.
-- ================================================================

BEGIN;

-- ── Economy: Wind Charges & Breeze Rods ──────────────────────────
-- $1 = 64 Wind Charges. 1 Breeze Rod = 64 Wind Charges = $1.
-- All balances are integers (wind charges); rods are displayed as
-- earned_wind_charges / 64. Rods are creator-only and never purchasable.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS wind_charges        INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS earned_wind_charges INTEGER NOT NULL DEFAULT 0;

-- Orders gain a wind-charge purchase type + refund timestamp.
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS order_type    TEXT;
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS wind_charges  INTEGER;
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS cosmetic_id   TEXT;
ALTER TABLE public.orders ADD COLUMN IF NOT EXISTS refunded_at   TIMESTAMPTZ;

-- Orders that are not a catalogue cape (Wind Charge packs, personal capes,
-- 3D cosmetics) have no cape_id, but schema-base.sql created the column
-- NOT NULL, so the database refused every such order and no Wind Charge pack
-- could be bought ("null value in column cape_id", 2026-10-09).
ALTER TABLE public.orders ALTER COLUMN cape_id DROP NOT NULL;
-- A refunded order is marked 'refunded' (handlePayPalRefund in server.js);
-- the status list did not have it, so a refund could not be recorded.
ALTER TYPE public.order_status ADD VALUE IF NOT EXISTS 'refunded';

-- Every balance change is audited here (purchase / spend / earn /
-- refund / withdrawal / adjust).
CREATE TABLE IF NOT EXISTS public.wallet_transactions (
  id            BIGSERIAL    PRIMARY KEY,
  user_uuid     TEXT         NOT NULL,
  type          TEXT         NOT NULL,
  amount_wc     INTEGER      NOT NULL,
  balance_after INTEGER,
  ref           TEXT,
  note          TEXT,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_wallet_txn_user    ON public.wallet_transactions(user_uuid);
CREATE INDEX IF NOT EXISTS idx_wallet_txn_created ON public.wallet_transactions(created_at DESC);

-- Creator payout queue. Creators request; owner reviews in the admin
-- panel, pays manually via PayPal, then marks the request paid (which
-- deducts the creator's earned_wind_charges).
CREATE TABLE IF NOT EXISTS public.withdrawal_requests (
  id            BIGSERIAL    PRIMARY KEY,
  user_uuid     TEXT         NOT NULL,
  amount_wc     INTEGER      NOT NULL,
  paypal_email  TEXT         NOT NULL,
  status        TEXT         NOT NULL DEFAULT 'pending',  -- pending | approved | paid | rejected
  note          TEXT,
  admin_note    TEXT,
  created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  resolved_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_withdrawals_user   ON public.withdrawal_requests(user_uuid);
CREATE INDEX IF NOT EXISTS idx_withdrawals_status ON public.withdrawal_requests(status);

-- ── Feature flags / presence / misc (from earlier releases) ──────
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS metadata  JSONB;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS last_seen TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON public.users(last_seen);

-- ── Creator management (Owner dashboard → Create Creator) ────────
-- Columns the owner sets when creating/promoting a creator. Idempotent, so
-- safe to re-run. email/paypal_email/creator_share_percent already exist on
-- most deployments; display_name is the only genuinely new one.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS email                 TEXT;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS paypal_email          TEXT;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS creator_share_percent NUMERIC;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS display_name          TEXT;

-- ── Creator Passes ──────────────────────────────────────────────
-- A staff/creator TEST currency. Spends like Wind Charges in the store but
-- NEVER generates earnings/commission/payouts, so passes can be used to test
-- purchases without polluting the real economy.
-- Owners, admins, developers (from 1.0.22) and creators each start with 3,000.
-- No other role has passes.
-- ── Tags (Section 5) ────────────────────────────────────────────
-- Tags were a hand-typed flat file (tag.txt) keyed by username. Names drifted
-- from the database: 9 of 20 identifiers matched no user, two were typos
-- (KingKazimjr vs KingKazimlr, red3e11 vs red3e1), and 7 real creators had no
-- creator entry at all, which is exactly why creators displayed the generic
-- Breeze tag. Membership now lives in the database, and role-based tags are
-- derived from users.role so they can never drift again.

CREATE TABLE IF NOT EXISTS public.tags (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            TEXT UNIQUE NOT NULL,         -- stable key, e.g. 'creator'
    name            TEXT NOT NULL,                -- displayed text, e.g. 'Creator'
    color           TEXT NOT NULL DEFAULT '#55FFFF',
    icon_asset      TEXT,                         -- wind charge icon, uploaded via admin
    -- Higher wins when a user qualifies for several and has not chosen one.
    priority_weight INTEGER NOT NULL DEFAULT 0,
    -- When set, every user holding this role automatically qualifies for the
    -- tag. This is what removes the hand-maintenance that caused the drift.
    auto_role       TEXT,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS tags_priority_idx ON public.tags (priority_weight DESC);

-- Explicit grants, for tags that are not role-derived (Donator, one-off custom).
CREATE TABLE IF NOT EXISTS public.user_tags (
    user_uuid  TEXT NOT NULL,
    tag_id     UUID NOT NULL REFERENCES public.tags(id) ON DELETE CASCADE,
    granted_by TEXT,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_uuid, tag_id)
);
CREATE INDEX IF NOT EXISTS user_tags_user_idx ON public.user_tags (user_uuid);

-- The tag the user chose to display. Overrides the priority default once set.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS equipped_tag_id UUID;

-- Baseline tags. Weights leave gaps so tiers can be inserted between later.
INSERT INTO public.tags (slug, name, color, priority_weight, auto_role) VALUES
    ('breeze',  'Breeze',  '#55FFFF', 10,  NULL),
    ('creator', 'Creator', '#B22222', 50,  'creator'),
    ('donator', 'Donator', '#FFD700', 30,  NULL),
    ('admin',   'Admin',   '#800080', 80,  'admin'),
    ('owner',   'Owner',   '#FFD700', 100, 'owner')
ON CONFLICT (slug) DO NOTHING;

-- ── Email gating (Section 19.1) ─────────────────────────────────
-- The welcome email used to fire on every login, which risked burning the
-- 3,000 send quota through routine launcher opens. It is now sent once, gated
-- by this flag as a race safeguard.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS welcome_email_sent BOOLEAN NOT NULL DEFAULT false;
-- Existing accounts have already been welcomed (many times over). Backfill to
-- true so this fix never retroactively emails the whole user base.
UPDATE public.users SET welcome_email_sent = true WHERE welcome_email_sent = false;

-- User-facing opt out for non-essential email. Purchase and payout emails are
-- transactional and deliberately ignore this flag.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS email_notifications BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE public.users ADD COLUMN IF NOT EXISTS creator_passes INTEGER NOT NULL DEFAULT 0;
UPDATE public.users SET creator_passes = 6400
  WHERE role::text IN ('owner', 'admin', 'creator') AND COALESCE(creator_passes, 0) = 0;
-- Anyone outside the pass-holding roles must not carry a balance.
UPDATE public.users SET creator_passes = 0
  WHERE role::text NOT IN ('owner', 'admin', 'developer', 'creator') AND COALESCE(creator_passes, 0) <> 0;

-- ── 1.0.13: role badges and the creator custom tag ───────────────
-- Roles are configuration, not code. The mod reads the whole set from /tag and
-- renders whatever is there, so adding a role below is enough: no mod release,
-- no client update, no version bump. That is the whole point of this section.

-- Developer sits between Admin and Owner.
INSERT INTO public.tags (slug, name, color, priority_weight, auto_role) VALUES
    ('developer', 'Developer', '#78B2FF', 90, 'developer')
ON CONFLICT (slug) DO NOTHING;

-- Marks a tag as one of the official role badges, which is what the mod tints
-- the Tab List Wind Charge icon by. Explicit rather than inferred from
-- auto_role, because Donator is a badge that no users.role value maps to.
ALTER TABLE public.tags ADD COLUMN IF NOT EXISTS is_role_badge BOOLEAN NOT NULL DEFAULT false;
UPDATE public.tags SET is_role_badge = true
  WHERE slug IN ('owner', 'developer', 'admin', 'creator', 'donator', 'breeze');

-- The creator's own public tag. Shown ALONGSIDE the official Creator badge
-- rather than instead of it, so a creator reads as "[Creator] [TheirBrand]" and
-- the official badge can never be impersonated by someone's custom text.
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS custom_tag_text  TEXT;
ALTER TABLE public.users ADD COLUMN IF NOT EXISTS custom_tag_color TEXT;

-- Length is capped in the API too. It is capped here as well because a direct
-- database edit would otherwise be able to produce a tag wide enough to cover
-- neighbouring nameplates.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_custom_tag_len') THEN
    ALTER TABLE public.users ADD CONSTRAINT users_custom_tag_len
      CHECK (custom_tag_text IS NULL OR char_length(custom_tag_text) <= 16);
  END IF;
END $$;

-- ── 1.0.22: one colour per role; custom tag text retired ─────────
-- Blue users, yellow creators, purple developers, red owners. The mod and the
-- launcher draw whatever colour is stored here, so this is the whole change on
-- their side. The API works before this runs; tags just keep their old colours.
UPDATE public.tags SET color = '#55C8FF' WHERE slug = 'breeze';
UPDATE public.tags SET color = '#FFD23F' WHERE slug = 'creator';
UPDATE public.tags SET color = '#A56EFF' WHERE slug = 'developer';
UPDATE public.tags SET color = '#FF5555' WHERE slug = 'owner';

-- Developers have creator access, so they hold Creator Passes like creators.
-- The grant further up names only owner, admin and creator; this gives
-- developers theirs.
UPDATE public.users SET creator_passes = 6400
  WHERE role::text = 'developer' AND COALESCE(creator_passes, 0) = 0;

-- Hierarchy owner > developer > creator > user. The seeded weights already give
-- this; they are restated so a table edited by hand is put back in order.
UPDATE public.tags SET priority_weight = 100 WHERE slug = 'owner';
UPDATE public.tags SET priority_weight = 90  WHERE slug = 'developer';
UPDATE public.tags SET priority_weight = 50  WHERE slug = 'creator';
UPDATE public.tags SET priority_weight = 10  WHERE slug = 'breeze';

-- users.custom_tag_text is no longer read by anything; it is left in place
-- rather than dropped so this migration destroys no data. users.custom_tag_color
-- now holds a creator's chosen tag colour, which the API only honours when it
-- is one of the offered wind charge colours. A leftover free-form colour from
-- the old editor is therefore ignored rather than shown.

-- ── Spotify accounts ─────────────────────────────────────────────
-- One row per Breeze user who has linked Spotify. Tokens only; nothing about
-- listening history is stored.
--
-- `product` caches Spotify's plan value ('premium' or 'free') so the launcher
-- can explain up front why playback control is unavailable, rather than the
-- user pressing play and getting a bare 403. It is refreshed on every status
-- check because a plan can change at any time.
CREATE TABLE IF NOT EXISTS public.breeze_spotify_accounts (
    user_uuid       TEXT PRIMARY KEY,
    access_token    TEXT,
    refresh_token   TEXT,
    expires_at      TIMESTAMPTZ,
    spotify_user_id TEXT,
    display_name    TEXT,
    product         TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Tokens are as sensitive as a password, so this table is never reachable with
-- an anon key: only the service role the API uses can touch it.
ALTER TABLE public.breeze_spotify_accounts ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies
    WHERE tablename = 'breeze_spotify_accounts' AND policyname = 'service_full_access'
  ) THEN
    EXECUTE 'CREATE POLICY service_full_access ON public.breeze_spotify_accounts TO service_role USING (true) WITH CHECK (true)';
  END IF;
END $$;

-- ── Row Level Security (service key does all API access) ─────────
ALTER TABLE public.wallet_transactions  ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.withdrawal_requests  ENABLE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='wallet_transactions' AND policyname='service_full_access') THEN
    EXECUTE 'CREATE POLICY service_full_access ON public.wallet_transactions TO service_role USING (true) WITH CHECK (true)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='withdrawal_requests' AND policyname='service_full_access') THEN
    EXECUTE 'CREATE POLICY service_full_access ON public.withdrawal_requests TO service_role USING (true) WITH CHECK (true)';
  END IF;
END $$;

COMMIT;

-- ================================================================
--  Applied to the live database on 2026-09-19 (Supabase migrations
--  breeze_1022_lock_client_roles, breeze_1022_developer_role and
--  breeze_1022_role_tags). Recorded here so this file matches it; every
--  statement is safe to run again.
-- ================================================================

-- ── Only the Breeze API talks to this database ──────────────────
-- The API uses the service_role key. anon and authenticated held every table
-- privilege, and users had permissive USING (true) / WITH CHECK (true)
-- policies: anyone with the project's anon key could read every user's email,
-- PayPal address and balances, insert a user with any role, list promo codes
-- and run the SECURITY DEFINER earnings report. The "deny_anon_*" policies were
-- PERMISSIVE, so they denied nothing. No client connects with the anon key.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM anon, authenticated, public;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM anon, authenticated, public;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO service_role;
DROP POLICY IF EXISTS "allow_select_users" ON public.users;
DROP POLICY IF EXISTS "users_select_all" ON public.users;
DROP POLICY IF EXISTS "users_select_self" ON public.users;
DROP POLICY IF EXISTS "allow_insert_users" ON public.users;
DROP POLICY IF EXISTS "Enable insert for all" ON public.users;
DROP POLICY IF EXISTS "users_insert_self" ON public.users;
DO $$
BEGIN
  -- Only where these functions exist (they came from earlier releases).
  PERFORM 1 FROM pg_proc WHERE proname = 'buy_cape';
  IF FOUND THEN
    ALTER FUNCTION public.buy_cape(text, uuid) SET search_path = public, pg_temp;
    ALTER FUNCTION public.equip_cape(text, uuid) SET search_path = public, pg_temp;
    ALTER FUNCTION public.add_coins(text, integer) SET search_path = public, pg_temp;
    ALTER FUNCTION public.set_updated_at() SET search_path = public, pg_temp;
    ALTER FUNCTION public.get_monthly_earnings_report(integer, integer) SET search_path = public, pg_temp;
    ALTER FUNCTION public.increment_promo_uses(uuid) SET search_path = public, pg_temp;
  END IF;
END $$;

-- ── Developer role ──────────────────────────────────────────────
-- users.role is the user_role enum, so the value must exist before an owner
-- can assign it.
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
    ALTER TYPE public.user_role ADD VALUE IF NOT EXISTS 'developer';
  END IF;
END $$;

-- ── Custom-name tags retired ────────────────────────────────────
-- The tags imported from the old hand-written tag.txt were arbitrary names given
-- to individual players. They, their grants and the old custom tag text were
-- copied into breeze_archive (no API role can read it) and removed.
-- "__migrated__" stays as the record that the import ran.
CREATE SCHEMA IF NOT EXISTS breeze_archive;
REVOKE ALL ON SCHEMA breeze_archive FROM public, anon, authenticated;
-- One time only. The archive tables are the record that it has run: without
-- this guard, a later re-run of the file would delete every tag added since.
DO $$
BEGIN
  IF to_regclass('breeze_archive.retired_tags_1022') IS NOT NULL THEN
    RAISE NOTICE 'custom-name tags already retired; nothing to do';
    RETURN;
  END IF;

  CREATE TABLE breeze_archive.retired_tags_1022 AS
    SELECT now() AS archived_at, t.* FROM public.tags t
    WHERE NOT t.is_role_badge AND t.slug <> '__migrated__';
  CREATE TABLE breeze_archive.retired_user_tags_1022 AS
    SELECT now() AS archived_at, ut.* FROM public.user_tags ut
    JOIN public.tags t ON t.id = ut.tag_id
    WHERE NOT t.is_role_badge AND t.slug <> '__migrated__';
  CREATE TABLE breeze_archive.retired_custom_tag_text_1022 AS
    SELECT now() AS archived_at, uuid, username, custom_tag_text, custom_tag_color
    FROM public.users WHERE custom_tag_text IS NOT NULL;

  DELETE FROM public.tags WHERE id IN (SELECT id FROM breeze_archive.retired_tags_1022);
  UPDATE public.users SET custom_tag_text = NULL
    WHERE uuid IN (SELECT uuid FROM breeze_archive.retired_custom_tag_text_1022);
END $$;

-- ── Storage: public reads, writes through the API only ──────────
-- (migration breeze_1022_lock_storage_writes, 2026-09-19)
-- The capes and cosmetics buckets are public, so their URLs are served without
-- consulting a policy, which is how the launcher, the website and the mod load
-- them. The write policies granted to anon and authenticated were not needed by
-- anything: uploads go through the API with the service role key. Anyone with
-- the public anon key could otherwise have written or deleted any cape or
-- cosmetic asset.
DROP POLICY IF EXISTS "Allow uploads for all users" ON storage.objects;
DROP POLICY IF EXISTS "Allow all uploads" ON storage.objects;
DROP POLICY IF EXISTS "Allow read for all users" ON storage.objects;
DROP POLICY IF EXISTS "Allow public read" ON storage.objects;
DROP POLICY IF EXISTS capes_authenticated_write ON storage.objects;
DROP POLICY IF EXISTS capes_authenticated_update ON storage.objects;
DROP POLICY IF EXISTS capes_authenticated_delete ON storage.objects;
DROP POLICY IF EXISTS cosmetics_authenticated_write ON storage.objects;
DROP POLICY IF EXISTS cosmetics_authenticated_update ON storage.objects;
DROP POLICY IF EXISTS capes_public_read ON storage.objects;
CREATE POLICY capes_public_read ON storage.objects
  AS PERMISSIVE FOR SELECT TO public USING (bucket_id = 'capes');
DROP POLICY IF EXISTS cosmetics_assets_public_read ON storage.objects;
CREATE POLICY cosmetics_assets_public_read ON storage.objects
  AS PERMISSIVE FOR SELECT TO public USING (bucket_id = 'cosmetics-assets');

-- ── Tidy ────────────────────────────────────────────────────────
-- (migration breeze_1022_tidy, 2026-09-19)
-- buy_cape, equip_cape and add_coins came from an early prototype and read
-- columns that no longer exist (users.coins, capes.price, user_capes.user_id).
-- Nothing calls them. Their source is kept in breeze_archive.
CREATE TABLE IF NOT EXISTS breeze_archive.retired_functions_1022 (
    name        text PRIMARY KEY,
    definition  text NOT NULL,
    archived_at timestamptz NOT NULL DEFAULT now()
);
REVOKE ALL ON breeze_archive.retired_functions_1022 FROM public, anon, authenticated;
INSERT INTO breeze_archive.retired_functions_1022 (name, definition)
SELECT p.proname, pg_get_functiondef(p.oid)
FROM pg_proc p
WHERE p.pronamespace = 'public'::regnamespace
  AND p.proname IN ('buy_cape', 'equip_cape', 'add_coins')
ON CONFLICT (name) DO NOTHING;
DROP FUNCTION IF EXISTS public.buy_cape(text, uuid);
DROP FUNCTION IF EXISTS public.equip_cape(text, uuid);
DROP FUNCTION IF EXISTS public.add_coins(text, integer);

-- Policies written for anon and authenticated. Neither role holds a privilege
-- here any more, so none of them could ever apply, and every deny_anon_* one was
-- PERMISSIVE, which denies nothing. Row level security stays on with a single
-- service_role policy per table, which is the only role that touches this data.
DROP POLICY IF EXISTS capes_creator_insert ON public.capes;
DROP POLICY IF EXISTS capes_creator_update ON public.capes;
DROP POLICY IF EXISTS capes_public_read ON public.capes;
DROP POLICY IF EXISTS deny_anon_capes_write ON public.capes;
DROP POLICY IF EXISTS public_capes_select ON public.capes;
DROP POLICY IF EXISTS cosmetics_creator_insert ON public.cosmetics;
DROP POLICY IF EXISTS cosmetics_creator_update ON public.cosmetics;
DROP POLICY IF EXISTS cosmetics_public_read ON public.cosmetics;
DROP POLICY IF EXISTS deny_anon_earnings ON public.earnings;
DROP POLICY IF EXISTS earnings_owner_read ON public.earnings;
DROP POLICY IF EXISTS deny_anon_orders ON public.orders;
DROP POLICY IF EXISTS orders_owner_insert ON public.orders;
DROP POLICY IF EXISTS orders_owner_rw ON public.orders;
DROP POLICY IF EXISTS deny_anon_promo_uses ON public.promo_code_uses;
DROP POLICY IF EXISTS promo_code_uses_owner_rw ON public.promo_code_uses;
DROP POLICY IF EXISTS deny_anon_promo_codes ON public.promo_codes;
DROP POLICY IF EXISTS promo_codes_public_read ON public.promo_codes;
DROP POLICY IF EXISTS deny_anon_user_capes ON public.user_capes;
DROP POLICY IF EXISTS user_capes_owner_rw ON public.user_capes;
DROP POLICY IF EXISTS user_cosmetics_owner_rw ON public.user_cosmetics;
DROP POLICY IF EXISTS user_equipped_owner_rw ON public.user_equipped_cosmetics;
DROP POLICY IF EXISTS deny_anon_users ON public.users;
DROP POLICY IF EXISTS users_update_self ON public.users;
DO $$
DECLARE t text;
BEGIN
  FOR t IN SELECT c.relname FROM pg_class c
           WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'r' LOOP
    EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
    IF NOT EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname = 'public' AND tablename = t AND policyname = 'service_full_access') THEN
      EXECUTE format('CREATE POLICY service_full_access ON public.%I TO service_role USING (true) WITH CHECK (true)', t);
    END IF;
  END LOOP;
END $$;

-- Index pairs that earlier schema files created twice, and the three foreign
-- keys that had no covering index.
DROP INDEX IF EXISTS public.idx_capes_creator_id;
DROP INDEX IF EXISTS public.idx_capes_is_public;
DROP INDEX IF EXISTS public.idx_cosmetics_creator;
DROP INDEX IF EXISTS public.idx_cosmetics_slot;
DROP INDEX IF EXISTS public.orders_status_idx;
DROP INDEX IF EXISTS public.orders_user_uuid_idx;
DROP INDEX IF EXISTS public.promo_code_uses_user_idx;
DROP INDEX IF EXISTS public.user_capes_user_idx;
DROP INDEX IF EXISTS public.user_cosmetics_user_idx;
DROP INDEX IF EXISTS public.user_equipped_cosmetics_user_idx;
ALTER TABLE public.promo_code_uses DROP CONSTRAINT IF EXISTS uq_promo_use_per_user;
ALTER TABLE public.users DROP CONSTRAINT IF EXISTS users_uuid_unique;
CREATE INDEX IF NOT EXISTS user_cosmetics_cosmetic_idx ON public.user_cosmetics (cosmetic_id);
CREATE INDEX IF NOT EXISTS user_equipped_cosmetics_cosmetic_idx ON public.user_equipped_cosmetics (cosmetic_id);
CREATE INDEX IF NOT EXISTS user_tags_tag_idx ON public.user_tags (tag_id);

COMMENT ON COLUMN public.users.role IS 'Single role field: user | creator | developer | admin | owner. No boolean flags.';

-- ── 1.0.23: direct messages have a read state ───────────────────
-- (migration breeze_1023_social_read_state, 2026-09-25)
-- A message is unread until the recipient opens the conversation. Without this
-- the launcher cannot show an unread count, which is the only reason a player
-- opens the Social tab when nothing else is happening.
ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS messages_unread_idx
    ON public.messages (recipient_uuid, sender_uuid)
    WHERE read_at IS NULL;

-- friendships.status is free text and now also holds 'blocked', where
-- requester_uuid is the player who blocked. The check keeps a typo from
-- creating a fourth, invisible state.
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'friendships_status_check') THEN
    ALTER TABLE public.friendships
      ADD CONSTRAINT friendships_status_check CHECK (status IN ('pending', 'accepted', 'blocked'));
  END IF;
END $$;

COMMENT ON COLUMN public.messages.read_at IS 'When the recipient opened the conversation. NULL means unread.';
COMMENT ON COLUMN public.friendships.status IS 'pending | accepted | blocked. For blocked, requester_uuid is the player who blocked.';

-- ── 2026-10-09: announcements and launcher download counts ──────
-- announcements keeps what the admin panel sent to every account (the
-- messages themselves are notifications rows). launcher_downloads gets one row
-- per public installer download; no address is stored, and visitor is a hash
-- under a key that changes daily and is never written down.
CREATE TABLE IF NOT EXISTS public.announcements (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title        TEXT NOT NULL,
    body         TEXT,
    author_uuid  TEXT NOT NULL,
    author_name  TEXT NOT NULL,
    author_role  TEXT,
    recipients   INTEGER NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    retracted_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS announcements_created_idx ON public.announcements (created_at DESC);

CREATE TABLE IF NOT EXISTS public.launcher_downloads (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    os         TEXT NOT NULL,
    file       TEXT NOT NULL,
    version    TEXT,
    source     TEXT NOT NULL,
    completed  BOOLEAN NOT NULL DEFAULT false,
    visitor    TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS launcher_downloads_created_idx ON public.launcher_downloads (created_at);

ALTER TABLE public.announcements      ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.launcher_downloads ENABLE ROW LEVEL SECURITY;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='announcements' AND policyname='service_full_access') THEN
    EXECUTE 'CREATE POLICY service_full_access ON public.announcements TO service_role USING (true) WITH CHECK (true)';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_policies WHERE tablename='launcher_downloads' AND policyname='service_full_access') THEN
    EXECUTE 'CREATE POLICY service_full_access ON public.launcher_downloads TO service_role USING (true) WITH CHECK (true)';
  END IF;
END $$;
REVOKE ALL ON public.announcements, public.launcher_downloads FROM anon, authenticated;
