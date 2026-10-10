-- ================================================================
--  Breeze Client - base schema
--
--  Generated from the live Supabase database on 2026-09-19 by reading its
--  own catalog (pg_class, pg_constraint, pg_indexes, pg_policies, pg_proc,
--  pg_trigger, pg_description). It is the structure the API actually runs
--  against, not a hand-kept guess.
--
--  A NEW project is built by running this file first and then schema.sql,
--  which carries the per-release migrations. On the existing project both
--  files are already applied; every statement here is written to be safe to
--  run again.
--
--  Row data is not included, apart from the role badge tags the mod and the
--  launcher expect to exist.
--
--  Regenerate after a schema change so this file keeps matching the database.
-- ================================================================

-- ────────────────────────────────────────────────────────────────────────
--  Extensions
--  Supabase creates these in the extensions schema.
-- ────────────────────────────────────────────────────────────────────────

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA extensions;

CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA extensions;

-- ────────────────────────────────────────────────────────────────────────
--  Types
--  users.role is an enum, so a new value must be added to the type
--  before any row or query can use it.
-- ────────────────────────────────────────────────────────────────────────

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'earnings_role') THEN CREATE TYPE public.earnings_role AS ENUM ('creator', 'coowner', 'owner'); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'order_status') THEN CREATE TYPE public.order_status AS ENUM ('pending', 'approved', 'completed', 'cancelled', 'failed'); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN CREATE TYPE public.user_role AS ENUM ('user', 'creator', 'admin', 'owner', 'developer'); END IF; END $$;

-- ────────────────────────────────────────────────────────────────────────
--  Sequences
--  Created before the tables that default to nextval() on them.
-- ────────────────────────────────────────────────────────────────────────

CREATE SEQUENCE IF NOT EXISTS public.changelog_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.earnings_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.feedback_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.news_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.payouts_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.promo_code_uses_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.ticket_messages_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.user_capes_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.wallet_transactions_id_seq;

CREATE SEQUENCE IF NOT EXISTS public.withdrawal_requests_id_seq;

-- ────────────────────────────────────────────────────────────────────────
--  Tables
-- ────────────────────────────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS public.ad_reward_sessions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_uuid text NOT NULL,
    ads_watched integer DEFAULT 0 NOT NULL,
    discount_percent integer DEFAULT 0 NOT NULL,
    status text DEFAULT 'active'::text NOT NULL,
    promo_code_id uuid,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone,
    claimed_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.announcements (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    title text NOT NULL,
    body text,
    author_uuid text NOT NULL,
    author_name text NOT NULL,
    author_role text,
    recipients integer DEFAULT 0 NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    retracted_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.breeze_spotify_accounts (
    user_uuid text NOT NULL,
    access_token text,
    refresh_token text,
    expires_at timestamp with time zone,
    spotify_user_id text,
    display_name text,
    product text,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.capes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    name text NOT NULL,
    description text,
    price_usd numeric(10,2) DEFAULT 0.00 NOT NULL,
    rarity text DEFAULT 'premium'::text NOT NULL,
    image_url text NOT NULL,
    is_public boolean DEFAULT true NOT NULL,
    is_limited boolean DEFAULT false NOT NULL,
    animation_fps smallint,
    creator_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    is_animated boolean DEFAULT false,
    animation_frames text[],
    metadata jsonb DEFAULT '{}'::jsonb
);

CREATE TABLE IF NOT EXISTS public.changelog (
    id bigint DEFAULT nextval('changelog_id_seq'::regclass) NOT NULL,
    version text NOT NULL,
    title text,
    changes jsonb DEFAULT '[]'::jsonb NOT NULL,
    latest boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.cosmetic_animation_state (
    user_uuid uuid NOT NULL,
    slot text NOT NULL,
    current_anim text,
    last_updated timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.cosmetics (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    slot text NOT NULL,
    name text NOT NULL,
    description text,
    rarity text DEFAULT 'common'::text,
    price_usd numeric(10,2) DEFAULT 0,
    model_url text,
    thumbnail_url text,
    idle_animation text,
    random_animations jsonb DEFAULT '[]'::jsonb,
    animation_chance real DEFAULT 0.15,
    creator_id uuid,
    is_public boolean DEFAULT true,
    metadata jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now(),
    is_animated boolean DEFAULT false,
    animation_frames jsonb DEFAULT '[]'::jsonb,
    animation_fps integer DEFAULT 12
);

CREATE TABLE IF NOT EXISTS public.earnings (
    id bigint DEFAULT nextval('earnings_id_seq'::regclass) NOT NULL,
    order_id uuid NOT NULL,
    user_uuid text,
    role earnings_role NOT NULL,
    amount_usd numeric(10,4) NOT NULL,
    paid_out boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    creator_id uuid,
    item_type text,
    item_id uuid,
    gross_usd numeric(10,2) DEFAULT 0,
    platform_usd numeric(10,2) DEFAULT 0,
    creator_usd numeric(10,2) DEFAULT 0
);

CREATE TABLE IF NOT EXISTS public.feedback (
    id bigint DEFAULT nextval('feedback_id_seq'::regclass) NOT NULL,
    type text DEFAULT 'other'::text NOT NULL,
    title text NOT NULL,
    description text NOT NULL,
    tags jsonb DEFAULT '[]'::jsonb NOT NULL,
    uuid text,
    username text DEFAULT 'Anonymous'::text NOT NULL,
    status text DEFAULT 'under_review'::text NOT NULL,
    votes integer DEFAULT 0 NOT NULL,
    admin_note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.friendships (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    requester_uuid text NOT NULL,
    addressee_uuid text NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    accepted_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.gifts (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sender_uuid text NOT NULL,
    recipient_uuid text NOT NULL,
    cape_id uuid,
    cosmetic_id uuid,
    source text DEFAULT 'friend_gift'::text NOT NULL,
    status text DEFAULT 'delivered'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.launcher_downloads (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    os text NOT NULL,
    file text NOT NULL,
    version text,
    source text NOT NULL,
    completed boolean DEFAULT false NOT NULL,
    visitor text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.messages (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    sender_uuid text NOT NULL,
    recipient_uuid text NOT NULL,
    body text,
    attachment jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    read_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.news (
    id bigint DEFAULT nextval('news_id_seq'::regclass) NOT NULL,
    category text DEFAULT 'announcement'::text NOT NULL,
    title text NOT NULL,
    excerpt text,
    content text NOT NULL,
    author_uuid text,
    author_name text,
    published_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.notifications (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    user_uuid text NOT NULL,
    type text NOT NULL,
    title text NOT NULL,
    body text,
    data jsonb DEFAULT '{}'::jsonb NOT NULL,
    read_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.orders (
    id uuid NOT NULL,
    user_uuid text NOT NULL,
    cape_id uuid NOT NULL,
    gross_amount_usd numeric(10,4) NOT NULL,
    net_amount_usd numeric(10,4),
    discount_percent numeric(5,2) DEFAULT 0 NOT NULL,
    promo_code_id uuid,
    status order_status DEFAULT 'pending'::order_status NOT NULL,
    paypal_order_id text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    completed_at timestamp with time zone,
    pending_cape_path text,
    metadata jsonb DEFAULT '{}'::jsonb,
    item_type text,
    item_id uuid,
    amount_usd numeric(10,2) DEFAULT 0,
    currency text DEFAULT 'USD'::text,
    cosmetic_id text,
    order_type text,
    wind_charges integer,
    refunded_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.payouts (
    id bigint DEFAULT nextval('payouts_id_seq'::regclass) NOT NULL,
    uuid text NOT NULL,
    username text NOT NULL,
    amount numeric(10,2) NOT NULL,
    currency text DEFAULT 'USD'::text NOT NULL,
    paypal_email text,
    status text DEFAULT 'pending'::text NOT NULL,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    paid_at timestamp with time zone
);

CREATE TABLE IF NOT EXISTS public.promo_code_uses (
    id bigint DEFAULT nextval('promo_code_uses_id_seq'::regclass) NOT NULL,
    promo_code_id uuid NOT NULL,
    user_uuid text NOT NULL,
    used_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.promo_codes (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    code text NOT NULL,
    discount_percent numeric(5,2) NOT NULL,
    owner_uuid text,
    usage_limit integer,
    times_used integer DEFAULT 0 NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    discount_pct numeric(5,2) DEFAULT 0,
    discount_usd numeric(10,2) DEFAULT 0,
    grant_role text,
    grant_cape_id uuid,
    grant_cosmetic_id uuid,
    max_uses integer,
    expires_at timestamp with time zone,
    description text
);

CREATE TABLE IF NOT EXISTS public.tags (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    slug text NOT NULL,
    name text NOT NULL,
    color text DEFAULT '#55FFFF'::text NOT NULL,
    icon_asset text,
    priority_weight integer DEFAULT 0 NOT NULL,
    auto_role text,
    created_by text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    is_role_badge boolean DEFAULT false NOT NULL
);

CREATE TABLE IF NOT EXISTS public.ticket_messages (
    id bigint DEFAULT nextval('ticket_messages_id_seq'::regclass) NOT NULL,
    ticket_id text NOT NULL,
    author_uuid text NOT NULL,
    author_name text NOT NULL,
    message text NOT NULL,
    is_staff boolean DEFAULT false NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.tickets (
    ticket_id text NOT NULL,
    uuid text NOT NULL,
    username text NOT NULL,
    subject text NOT NULL,
    category text DEFAULT 'other'::text NOT NULL,
    priority text DEFAULT 'low'::text NOT NULL,
    version text,
    status text DEFAULT 'open'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.user_capes (
    id bigint DEFAULT nextval('user_capes_id_seq'::regclass) NOT NULL,
    user_uuid text NOT NULL,
    cape_id uuid NOT NULL,
    equipped boolean DEFAULT false NOT NULL,
    acquired_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.user_cosmetics (
    user_uuid uuid NOT NULL,
    cosmetic_id uuid NOT NULL,
    acquired_at timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.user_equipped_cosmetics (
    user_uuid uuid NOT NULL,
    slot text NOT NULL,
    cosmetic_id uuid NOT NULL,
    equipped_at timestamp with time zone DEFAULT now()
);

CREATE TABLE IF NOT EXISTS public.user_tags (
    user_uuid text NOT NULL,
    tag_id uuid NOT NULL,
    granted_by text,
    granted_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.users (
    uuid text NOT NULL,
    username text NOT NULL,
    role user_role DEFAULT 'user'::user_role NOT NULL,
    creator_share_percent numeric(5,2),
    cape_url text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    email text,
    friends text,
    tags text,
    requests text,
    paypal_email text,
    avatar_url text,
    last_seen timestamp with time zone,
    metadata jsonb,
    wind_charges integer DEFAULT 0 NOT NULL,
    earned_wind_charges integer DEFAULT 0 NOT NULL,
    display_name text,
    creator_passes integer DEFAULT 0 NOT NULL,
    equipped_tag_id uuid,
    welcome_email_sent boolean DEFAULT false NOT NULL,
    email_notifications boolean DEFAULT true NOT NULL,
    custom_tag_text text,
    custom_tag_color text
);

CREATE TABLE IF NOT EXISTS public.wallet_transactions (
    id bigint DEFAULT nextval('wallet_transactions_id_seq'::regclass) NOT NULL,
    user_uuid text NOT NULL,
    type text NOT NULL,
    amount_wc integer NOT NULL,
    balance_after integer,
    ref text,
    note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE IF NOT EXISTS public.withdrawal_requests (
    id bigint DEFAULT nextval('withdrawal_requests_id_seq'::regclass) NOT NULL,
    user_uuid text NOT NULL,
    amount_wc integer NOT NULL,
    paypal_email text NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    note text,
    admin_note text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    resolved_at timestamp with time zone
);

-- ────────────────────────────────────────────────────────────────────────
--  Sequence ownership
--  Ties each sequence to its column so dropping the table drops it.
-- ────────────────────────────────────────────────────────────────────────

ALTER SEQUENCE public.changelog_id_seq OWNED BY public.changelog.id;

ALTER SEQUENCE public.earnings_id_seq OWNED BY public.earnings.id;

ALTER SEQUENCE public.feedback_id_seq OWNED BY public.feedback.id;

ALTER SEQUENCE public.news_id_seq OWNED BY public.news.id;

ALTER SEQUENCE public.payouts_id_seq OWNED BY public.payouts.id;

ALTER SEQUENCE public.promo_code_uses_id_seq OWNED BY public.promo_code_uses.id;

ALTER SEQUENCE public.ticket_messages_id_seq OWNED BY public.ticket_messages.id;

ALTER SEQUENCE public.user_capes_id_seq OWNED BY public.user_capes.id;

ALTER SEQUENCE public.wallet_transactions_id_seq OWNED BY public.wallet_transactions.id;

ALTER SEQUENCE public.withdrawal_requests_id_seq OWNED BY public.withdrawal_requests.id;

-- ────────────────────────────────────────────────────────────────────────
--  Primary keys
-- ────────────────────────────────────────────────────────────────────────

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ad_reward_sessions_pkey') THEN ALTER TABLE public.ad_reward_sessions ADD CONSTRAINT ad_reward_sessions_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'announcements_pkey') THEN ALTER TABLE public.announcements ADD CONSTRAINT announcements_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'breeze_spotify_accounts_pkey') THEN ALTER TABLE public.breeze_spotify_accounts ADD CONSTRAINT breeze_spotify_accounts_pkey PRIMARY KEY (user_uuid); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'capes_pkey') THEN ALTER TABLE public.capes ADD CONSTRAINT capes_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'changelog_pkey') THEN ALTER TABLE public.changelog ADD CONSTRAINT changelog_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cosmetic_animation_state_pkey') THEN ALTER TABLE public.cosmetic_animation_state ADD CONSTRAINT cosmetic_animation_state_pkey PRIMARY KEY (user_uuid, slot); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'cosmetics_pkey') THEN ALTER TABLE public.cosmetics ADD CONSTRAINT cosmetics_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'earnings_pkey') THEN ALTER TABLE public.earnings ADD CONSTRAINT earnings_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'feedback_pkey') THEN ALTER TABLE public.feedback ADD CONSTRAINT feedback_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'friendships_pkey') THEN ALTER TABLE public.friendships ADD CONSTRAINT friendships_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'gifts_pkey') THEN ALTER TABLE public.gifts ADD CONSTRAINT gifts_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'launcher_downloads_pkey') THEN ALTER TABLE public.launcher_downloads ADD CONSTRAINT launcher_downloads_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'messages_pkey') THEN ALTER TABLE public.messages ADD CONSTRAINT messages_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'news_pkey') THEN ALTER TABLE public.news ADD CONSTRAINT news_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'notifications_pkey') THEN ALTER TABLE public.notifications ADD CONSTRAINT notifications_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_pkey') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'payouts_pkey') THEN ALTER TABLE public.payouts ADD CONSTRAINT payouts_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_code_uses_pkey') THEN ALTER TABLE public.promo_code_uses ADD CONSTRAINT promo_code_uses_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_codes_pkey') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT promo_codes_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tags_pkey') THEN ALTER TABLE public.tags ADD CONSTRAINT tags_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ticket_messages_pkey') THEN ALTER TABLE public.ticket_messages ADD CONSTRAINT ticket_messages_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tickets_pkey') THEN ALTER TABLE public.tickets ADD CONSTRAINT tickets_pkey PRIMARY KEY (ticket_id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_capes_pkey') THEN ALTER TABLE public.user_capes ADD CONSTRAINT user_capes_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_cosmetics_pkey') THEN ALTER TABLE public.user_cosmetics ADD CONSTRAINT user_cosmetics_pkey PRIMARY KEY (user_uuid, cosmetic_id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_equipped_cosmetics_pkey') THEN ALTER TABLE public.user_equipped_cosmetics ADD CONSTRAINT user_equipped_cosmetics_pkey PRIMARY KEY (user_uuid, slot); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_tags_pkey') THEN ALTER TABLE public.user_tags ADD CONSTRAINT user_tags_pkey PRIMARY KEY (user_uuid, tag_id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_pkey') THEN ALTER TABLE public.users ADD CONSTRAINT users_pkey PRIMARY KEY (uuid); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'wallet_transactions_pkey') THEN ALTER TABLE public.wallet_transactions ADD CONSTRAINT wallet_transactions_pkey PRIMARY KEY (id); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'withdrawal_requests_pkey') THEN ALTER TABLE public.withdrawal_requests ADD CONSTRAINT withdrawal_requests_pkey PRIMARY KEY (id); END IF; END $$;

-- ────────────────────────────────────────────────────────────────────────
--  Unique constraints
-- ────────────────────────────────────────────────────────────────────────

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'friendships_requester_uuid_addressee_uuid_key') THEN ALTER TABLE public.friendships ADD CONSTRAINT friendships_requester_uuid_addressee_uuid_key UNIQUE (requester_uuid, addressee_uuid); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_code_uses_unique') THEN ALTER TABLE public.promo_code_uses ADD CONSTRAINT promo_code_uses_unique UNIQUE (promo_code_id, user_uuid); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_promo_code') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT uq_promo_code UNIQUE (code); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'tags_slug_key') THEN ALTER TABLE public.tags ADD CONSTRAINT tags_slug_key UNIQUE (slug); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'uq_user_cape') THEN ALTER TABLE public.user_capes ADD CONSTRAINT uq_user_cape UNIQUE (user_uuid, cape_id); END IF; END $$;

-- ────────────────────────────────────────────────────────────────────────
--  Check constraints
-- ────────────────────────────────────────────────────────────────────────

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'friendships_status_check') THEN ALTER TABLE public.friendships ADD CONSTRAINT friendships_status_check CHECK ((status = ANY (ARRAY['pending'::text, 'accepted'::text, 'blocked'::text]))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'capes_animation_fps_check') THEN ALTER TABLE public.capes ADD CONSTRAINT capes_animation_fps_check CHECK (((animation_fps IS NULL) OR (animation_fps > 0))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'capes_price_usd_check') THEN ALTER TABLE public.capes ADD CONSTRAINT capes_price_usd_check CHECK ((price_usd >= (0)::numeric)); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'capes_rarity_check') THEN ALTER TABLE public.capes ADD CONSTRAINT capes_rarity_check CHECK ((rarity = ANY (ARRAY['common'::text, 'rare'::text, 'epic'::text, 'legendary'::text, 'mythic'::text]))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'earnings_amount_usd_check') THEN ALTER TABLE public.earnings ADD CONSTRAINT earnings_amount_usd_check CHECK ((amount_usd >= (0)::numeric)); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_discount_percent_check') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_discount_percent_check CHECK (((discount_percent >= (0)::numeric) AND (discount_percent <= (100)::numeric))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_gross_amount_usd_check') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_gross_amount_usd_check CHECK ((gross_amount_usd >= (0)::numeric)); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_net_amount_usd_check') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_net_amount_usd_check CHECK (((net_amount_usd IS NULL) OR (net_amount_usd >= (0)::numeric))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_codes_code_check') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT promo_codes_code_check CHECK (((code = upper(code)) AND ((length(code) >= 3) AND (length(code) <= 20)) AND (code ~ '^[A-Z0-9_-]+$'::text))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_codes_discount_percent_check') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT promo_codes_discount_percent_check CHECK (((discount_percent > (0)::numeric) AND (discount_percent <= (100)::numeric))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_codes_times_used_check') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT promo_codes_times_used_check CHECK ((times_used >= 0)); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_codes_usage_limit_check') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT promo_codes_usage_limit_check CHECK (((usage_limit IS NULL) OR (usage_limit > 0))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_creator_share_percent_check') THEN ALTER TABLE public.users ADD CONSTRAINT users_creator_share_percent_check CHECK (((creator_share_percent IS NULL) OR ((creator_share_percent >= (0)::numeric) AND (creator_share_percent <= (100)::numeric)))); END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'users_custom_tag_len') THEN ALTER TABLE public.users ADD CONSTRAINT users_custom_tag_len CHECK (((custom_tag_text IS NULL) OR (char_length(custom_tag_text) <= 16))); END IF; END $$;

-- ────────────────────────────────────────────────────────────────────────
--  Foreign keys
-- ────────────────────────────────────────────────────────────────────────

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'capes_creator_id_fkey') THEN ALTER TABLE public.capes ADD CONSTRAINT capes_creator_id_fkey FOREIGN KEY (creator_id) REFERENCES users(uuid) ON DELETE SET NULL; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'earnings_order_id_fkey') THEN ALTER TABLE public.earnings ADD CONSTRAINT earnings_order_id_fkey FOREIGN KEY (order_id) REFERENCES orders(id) ON DELETE RESTRICT; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'earnings_user_uuid_fkey') THEN ALTER TABLE public.earnings ADD CONSTRAINT earnings_user_uuid_fkey FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE SET NULL; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_cape_id_fkey') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_cape_id_fkey FOREIGN KEY (cape_id) REFERENCES capes(id) ON DELETE RESTRICT; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_promo_code_id_fkey') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_promo_code_id_fkey FOREIGN KEY (promo_code_id) REFERENCES promo_codes(id) ON DELETE SET NULL; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'orders_user_uuid_fkey') THEN ALTER TABLE public.orders ADD CONSTRAINT orders_user_uuid_fkey FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE RESTRICT; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_code_uses_promo_code_id_fkey') THEN ALTER TABLE public.promo_code_uses ADD CONSTRAINT promo_code_uses_promo_code_id_fkey FOREIGN KEY (promo_code_id) REFERENCES promo_codes(id) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_code_uses_user_uuid_fkey') THEN ALTER TABLE public.promo_code_uses ADD CONSTRAINT promo_code_uses_user_uuid_fkey FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'promo_codes_owner_uuid_fkey') THEN ALTER TABLE public.promo_codes ADD CONSTRAINT promo_codes_owner_uuid_fkey FOREIGN KEY (owner_uuid) REFERENCES users(uuid) ON DELETE SET NULL; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ticket_messages_ticket_id_fkey') THEN ALTER TABLE public.ticket_messages ADD CONSTRAINT ticket_messages_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES tickets(ticket_id) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_capes_cape_id_fkey') THEN ALTER TABLE public.user_capes ADD CONSTRAINT user_capes_cape_id_fkey FOREIGN KEY (cape_id) REFERENCES capes(id) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_capes_user_uuid_fkey') THEN ALTER TABLE public.user_capes ADD CONSTRAINT user_capes_user_uuid_fkey FOREIGN KEY (user_uuid) REFERENCES users(uuid) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_cosmetics_cosmetic_id_fkey') THEN ALTER TABLE public.user_cosmetics ADD CONSTRAINT user_cosmetics_cosmetic_id_fkey FOREIGN KEY (cosmetic_id) REFERENCES cosmetics(id) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_equipped_cosmetics_cosmetic_id_fkey') THEN ALTER TABLE public.user_equipped_cosmetics ADD CONSTRAINT user_equipped_cosmetics_cosmetic_id_fkey FOREIGN KEY (cosmetic_id) REFERENCES cosmetics(id) ON DELETE CASCADE; END IF; END $$;

DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'user_tags_tag_id_fkey') THEN ALTER TABLE public.user_tags ADD CONSTRAINT user_tags_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE CASCADE; END IF; END $$;

-- ────────────────────────────────────────────────────────────────────────
--  Indexes
-- ────────────────────────────────────────────────────────────────────────

CREATE INDEX IF NOT EXISTS ad_reward_sessions_user_created_idx ON public.ad_reward_sessions USING btree (user_uuid, created_at DESC);

CREATE INDEX IF NOT EXISTS announcements_created_idx ON public.announcements USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS capes_creator_id_idx ON public.capes USING btree (creator_id);

CREATE INDEX IF NOT EXISTS capes_is_public_idx ON public.capes USING btree (is_public);

CREATE INDEX IF NOT EXISTS cosmetics_creator_id_idx ON public.cosmetics USING btree (creator_id);

CREATE INDEX IF NOT EXISTS cosmetics_is_public_idx ON public.cosmetics USING btree (is_public);

CREATE INDEX IF NOT EXISTS cosmetics_slot_idx ON public.cosmetics USING btree (slot);

CREATE INDEX IF NOT EXISTS earnings_creator_idx ON public.earnings USING btree (creator_id);

CREATE INDEX IF NOT EXISTS friendships_addressee_idx ON public.friendships USING btree (addressee_uuid);

CREATE INDEX IF NOT EXISTS friendships_requester_idx ON public.friendships USING btree (requester_uuid);

CREATE INDEX IF NOT EXISTS gifts_recipient_created_idx ON public.gifts USING btree (recipient_uuid, created_at DESC);

CREATE INDEX IF NOT EXISTS launcher_downloads_created_idx ON public.launcher_downloads USING btree (created_at);

CREATE INDEX IF NOT EXISTS idx_capes_created_at ON public.capes USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_changelog_created ON public.changelog USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_earnings_created_at ON public.earnings USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_earnings_monthly ON public.earnings USING btree (user_uuid, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_earnings_order_id ON public.earnings USING btree (order_id);

CREATE INDEX IF NOT EXISTS idx_earnings_paid_out ON public.earnings USING btree (paid_out);

CREATE INDEX IF NOT EXISTS idx_earnings_role ON public.earnings USING btree (role);

CREATE INDEX IF NOT EXISTS idx_earnings_user_uuid ON public.earnings USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_feedback_status ON public.feedback USING btree (status);

CREATE INDEX IF NOT EXISTS idx_feedback_type ON public.feedback USING btree (type);

CREATE INDEX IF NOT EXISTS idx_feedback_votes ON public.feedback USING btree (votes DESC);

CREATE INDEX IF NOT EXISTS idx_news_published ON public.news USING btree (published_at DESC);

CREATE INDEX IF NOT EXISTS idx_notif_created ON public.notifications USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notif_user_uuid ON public.notifications USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_orders_cape_id ON public.orders USING btree (cape_id);

CREATE INDEX IF NOT EXISTS idx_orders_created_at ON public.orders USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_orders_paypal_order_id ON public.orders USING btree (paypal_order_id);

CREATE INDEX IF NOT EXISTS idx_orders_promo_code_id ON public.orders USING btree (promo_code_id);

CREATE INDEX IF NOT EXISTS idx_orders_status ON public.orders USING btree (status);

CREATE INDEX IF NOT EXISTS idx_orders_user_uuid ON public.orders USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_payouts_status ON public.payouts USING btree (status);

CREATE INDEX IF NOT EXISTS idx_payouts_uuid ON public.payouts USING btree (uuid);

CREATE INDEX IF NOT EXISTS idx_promo_codes_code ON public.promo_codes USING btree (code);

CREATE INDEX IF NOT EXISTS idx_promo_codes_is_active ON public.promo_codes USING btree (is_active);

CREATE INDEX IF NOT EXISTS idx_promo_codes_owner_uuid ON public.promo_codes USING btree (owner_uuid);

CREATE INDEX IF NOT EXISTS idx_promo_uses_promo ON public.promo_code_uses USING btree (promo_code_id);

CREATE INDEX IF NOT EXISTS idx_promo_uses_user ON public.promo_code_uses USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_tickets_status ON public.tickets USING btree (status);

CREATE INDEX IF NOT EXISTS idx_tickets_updated ON public.tickets USING btree (updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_tickets_uuid ON public.tickets USING btree (uuid);

CREATE INDEX IF NOT EXISTS idx_tmsg_ticket_id ON public.ticket_messages USING btree (ticket_id);

CREATE INDEX IF NOT EXISTS idx_user_capes_cape ON public.user_capes USING btree (cape_id);

CREATE INDEX IF NOT EXISTS idx_user_capes_equipped ON public.user_capes USING btree (user_uuid, equipped) WHERE (equipped = true);

CREATE INDEX IF NOT EXISTS idx_user_capes_user ON public.user_capes USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_user_cosmetics_user ON public.user_cosmetics USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_user_equipped_cosmetics_user ON public.user_equipped_cosmetics USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_users_last_seen ON public.users USING btree (last_seen);

CREATE INDEX IF NOT EXISTS idx_users_role ON public.users USING btree (role);

CREATE INDEX IF NOT EXISTS idx_users_updated_at ON public.users USING btree (updated_at);

CREATE INDEX IF NOT EXISTS idx_wallet_txn_created ON public.wallet_transactions USING btree (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_wallet_txn_user ON public.wallet_transactions USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS idx_withdrawals_status ON public.withdrawal_requests USING btree (status);

CREATE INDEX IF NOT EXISTS idx_withdrawals_user ON public.withdrawal_requests USING btree (user_uuid);

CREATE INDEX IF NOT EXISTS messages_pair_created_idx ON public.messages USING btree (sender_uuid, recipient_uuid, created_at);

CREATE INDEX IF NOT EXISTS messages_unread_idx ON public.messages USING btree (recipient_uuid, sender_uuid) WHERE (read_at IS NULL);

CREATE INDEX IF NOT EXISTS notifications_user_created_idx ON public.notifications USING btree (user_uuid, created_at DESC);

CREATE INDEX IF NOT EXISTS tags_priority_idx ON public.tags USING btree (priority_weight DESC);

CREATE INDEX IF NOT EXISTS user_capes_equipped_idx ON public.user_capes USING btree (user_uuid) WHERE (equipped = true);

CREATE INDEX IF NOT EXISTS user_cosmetics_cosmetic_idx ON public.user_cosmetics USING btree (cosmetic_id);

CREATE INDEX IF NOT EXISTS user_equipped_cosmetics_cosmetic_idx ON public.user_equipped_cosmetics USING btree (cosmetic_id);

CREATE INDEX IF NOT EXISTS user_tags_tag_idx ON public.user_tags USING btree (tag_id);

CREATE INDEX IF NOT EXISTS user_tags_user_idx ON public.user_tags USING btree (user_uuid);

-- ────────────────────────────────────────────────────────────────────────
--  Functions
--  increment_promo_uses is the only one the API calls; the earnings
--  report is run by the owner from the SQL editor. Both are SECURITY
--  DEFINER with a pinned search_path.
-- ────────────────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION public.get_monthly_earnings_report(p_month integer, p_year integer)
 RETURNS TABLE(user_uuid text, username text, role earnings_role, total_earnings_usd numeric, transactions bigint)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
    IF p_month < 1 OR p_month > 12 THEN
        RAISE EXCEPTION 'Invalid month: %. Must be 1–12.', p_month;
    END IF;
    IF p_year < 2024 THEN
        RAISE EXCEPTION 'Invalid year: %. Must be >= 2024.', p_year;
    END IF;

    RETURN QUERY
    SELECT
        e.user_uuid,
        u.username,
        e.role,
        SUM(e.amount_usd)   AS total_earnings_usd,
        COUNT(*)::BIGINT    AS transactions
    FROM public.earnings e
    LEFT JOIN public.users u ON u.uuid = e.user_uuid
    WHERE
        DATE_PART('month', e.created_at AT TIME ZONE 'UTC') = p_month
        AND DATE_PART('year',  e.created_at AT TIME ZONE 'UTC') = p_year
    GROUP BY e.user_uuid, u.username, e.role
    ORDER BY total_earnings_usd DESC;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.increment_promo_uses(promo_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
DECLARE
    new_count INTEGER;
BEGIN
    UPDATE public.promo_codes
    SET    times_used = times_used + 1,
           updated_at = NOW()
    WHERE  id = promo_id
    RETURNING times_used INTO new_count;

    IF new_count IS NULL THEN
        RAISE EXCEPTION 'Promo code not found: %', promo_id;
    END IF;

    RETURN new_count;
END;
$function$
;

CREATE OR REPLACE FUNCTION public.set_updated_at()
 RETURNS trigger
 LANGUAGE plpgsql
 SET search_path TO 'public', 'pg_temp'
AS $function$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$function$
;

-- ────────────────────────────────────────────────────────────────────────
--  Triggers
-- ────────────────────────────────────────────────────────────────────────

DROP TRIGGER IF EXISTS trg_capes_updated_at ON public.capes; CREATE TRIGGER trg_capes_updated_at BEFORE UPDATE ON public.capes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_promo_codes_updated_at ON public.promo_codes; CREATE TRIGGER trg_promo_codes_updated_at BEFORE UPDATE ON public.promo_codes FOR EACH ROW EXECUTE FUNCTION set_updated_at();

DROP TRIGGER IF EXISTS trg_users_updated_at ON public.users; CREATE TRIGGER trg_users_updated_at BEFORE UPDATE ON public.users FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ────────────────────────────────────────────────────────────────────────
--  Comments
-- ────────────────────────────────────────────────────────────────────────

COMMENT ON TABLE public.announcements IS 'Messages sent from the admin panel to every account, delivered as one notifications row each. History only.';

COMMENT ON TABLE public.capes IS 'Marketplace cape listings. price_usd is the real-money price in USD.';

COMMENT ON TABLE public.earnings IS 'Pending-payout earnings ledger. Append-only. One row per beneficiary per order.';

COMMENT ON TABLE public.orders IS 'One row per purchase attempt. Financial source of truth.';

COMMENT ON TABLE public.launcher_downloads IS 'One row per public launcher installer download. No address is stored; visitor is a hash under a key that changes daily.';

COMMENT ON TABLE public.promo_code_uses IS 'Tracks per-user promo code usage. Unique per (code, user).';

COMMENT ON TABLE public.promo_codes IS 'Discount / affiliate promo codes. code is always UPPER CASE.';

COMMENT ON TABLE public.user_capes IS 'Records which capes a user owns and which is currently equipped.';

COMMENT ON TABLE public.users IS 'Minecraft player accounts. UUID = Mojang profile id.';

COMMENT ON COLUMN public.capes.creator_id IS 'User who uploaded this cape (nullable for platform-owned capes).';

COMMENT ON COLUMN public.capes.is_public IS 'Only public capes are shown in the store and purchaseable.';

COMMENT ON COLUMN public.capes.price_usd IS 'Sell price in USD, 2dp. Never in cents or virtual currency.';

COMMENT ON COLUMN public.earnings.amount_usd IS 'Share of net revenue (after PayPal fees). Matches calcEarningsSplit() output.';

COMMENT ON COLUMN public.earnings.paid_out IS 'Manually set to TRUE after payout is processed. Never auto-transferred.';

COMMENT ON COLUMN public.earnings.role IS 'creator | coowner | owner — which revenue split this row belongs to.';

COMMENT ON COLUMN public.orders.gross_amount_usd IS 'USD charged to buyer (post-discount, pre-PayPal-fee).';

COMMENT ON COLUMN public.orders.net_amount_usd IS 'USD after PayPal fees. Populated on payment capture. Used for all revenue splits.';

COMMENT ON COLUMN public.orders.paypal_order_id IS 'PayPal order ID returned by /v2/checkout/orders. Used to match webhook events.';

COMMENT ON COLUMN public.promo_codes.discount_percent IS 'Percentage discount applied to cape price before PayPal charge.';

COMMENT ON COLUMN public.promo_codes.times_used IS 'Incremented atomically by increment_promo_uses() RPC.';

COMMENT ON COLUMN public.promo_codes.usage_limit IS 'Max total uses across all users. NULL = unlimited.';

COMMENT ON COLUMN public.user_capes.equipped IS 'At most one row per user_uuid should have equipped=TRUE. Enforced in server.js.';

COMMENT ON COLUMN public.messages.read_at IS 'When the recipient opened the conversation. NULL means unread.';

COMMENT ON COLUMN public.friendships.status IS 'pending | accepted | blocked. For blocked, requester_uuid is the player who blocked.';

COMMENT ON COLUMN public.users.cape_url IS 'Denormalised equipped cape image URL, synced by equip/unequip endpoints.';

COMMENT ON COLUMN public.users.creator_share_percent IS 'Per-user creator revenue share override (0-100). NULL = use server default.';

COMMENT ON COLUMN public.users.friends IS 'the list of friends';

COMMENT ON COLUMN public.users.role IS 'Single role field: user | creator | developer | admin | owner. No boolean flags.';

COMMENT ON COLUMN public.users.tags IS 'the tag they have in game';

-- ────────────────────────────────────────────────────────────────────────
--  Row level security
--  Only the API touches this database, with the service_role key, which
--  bypasses RLS. RLS stays on with one explicit service_role policy per
--  table so that a key handed out by mistake reaches nothing.
-- ────────────────────────────────────────────────────────────────────────

ALTER TABLE public.ad_reward_sessions ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.announcements ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.breeze_spotify_accounts ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.capes ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.changelog ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.cosmetic_animation_state ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.cosmetics ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.earnings ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.feedback ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.friendships ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.gifts ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.launcher_downloads ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.news ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.notifications ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.orders ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.payouts ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.promo_code_uses ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.promo_codes ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.tags ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.ticket_messages ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.tickets ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.user_capes ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.user_cosmetics ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.user_equipped_cosmetics ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.user_tags ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.wallet_transactions ENABLE ROW LEVEL SECURITY;

ALTER TABLE public.withdrawal_requests ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS service_full_access ON public.ad_reward_sessions; CREATE POLICY service_full_access ON public.ad_reward_sessions AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.announcements; CREATE POLICY service_full_access ON public.announcements AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.breeze_spotify_accounts; CREATE POLICY service_full_access ON public.breeze_spotify_accounts AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.capes; CREATE POLICY service_full_access ON public.capes AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.changelog; CREATE POLICY service_full_access ON public.changelog AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.cosmetic_animation_state; CREATE POLICY service_full_access ON public.cosmetic_animation_state AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.cosmetics; CREATE POLICY service_full_access ON public.cosmetics AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.earnings; CREATE POLICY service_full_access ON public.earnings AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.feedback; CREATE POLICY service_full_access ON public.feedback AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.friendships; CREATE POLICY service_full_access ON public.friendships AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.gifts; CREATE POLICY service_full_access ON public.gifts AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.launcher_downloads; CREATE POLICY service_full_access ON public.launcher_downloads AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.messages; CREATE POLICY service_full_access ON public.messages AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.news; CREATE POLICY service_full_access ON public.news AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.notifications; CREATE POLICY service_full_access ON public.notifications AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.orders; CREATE POLICY service_full_access ON public.orders AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.payouts; CREATE POLICY service_full_access ON public.payouts AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.promo_code_uses; CREATE POLICY service_full_access ON public.promo_code_uses AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.promo_codes; CREATE POLICY service_full_access ON public.promo_codes AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.tags; CREATE POLICY service_full_access ON public.tags AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.ticket_messages; CREATE POLICY service_full_access ON public.ticket_messages AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.tickets; CREATE POLICY service_full_access ON public.tickets AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.user_capes; CREATE POLICY service_full_access ON public.user_capes AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.user_cosmetics; CREATE POLICY service_full_access ON public.user_cosmetics AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.user_equipped_cosmetics; CREATE POLICY service_full_access ON public.user_equipped_cosmetics AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.user_tags; CREATE POLICY service_full_access ON public.user_tags AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.users; CREATE POLICY service_full_access ON public.users AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.wallet_transactions; CREATE POLICY service_full_access ON public.wallet_transactions AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

DROP POLICY IF EXISTS service_full_access ON public.withdrawal_requests; CREATE POLICY service_full_access ON public.withdrawal_requests AS PERMISSIVE FOR ALL TO service_role USING (true) WITH CHECK (true);

-- ────────────────────────────────────────────────────────────────────────
--  Privileges
--  anon and authenticated are the two keys that can be public. Neither is
--  used by anything in Breeze, and neither may hold a privilege here.
--  See docs/SECURITY_BACKLOG.md item 19.
-- ────────────────────────────────────────────────────────────────────────

REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;

REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM anon, authenticated;

REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA public FROM anon, authenticated, public;

GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO service_role;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE ALL ON SEQUENCES FROM anon, authenticated;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM anon, authenticated, public;

ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO service_role;

-- ────────────────────────────────────────────────────────────────────────
--  Archive schema
--  Where removed rows go instead of being deleted outright. No API role
--  can read it.
-- ────────────────────────────────────────────────────────────────────────

CREATE SCHEMA IF NOT EXISTS breeze_archive;

REVOKE ALL ON SCHEMA breeze_archive FROM public, anon, authenticated;

-- ────────────────────────────────────────────────────────────────────────
--  Storage
--  Buckets are public because the launcher, the website and the mod all
--  load these files without a key; a public bucket serves its objects
--  without consulting a policy. Writes go through the API only, so no
--  write policy exists for anon or authenticated.
-- ────────────────────────────────────────────────────────────────────────

INSERT INTO storage.buckets (id, name, public) VALUES
    ('capes', 'capes', true),
    ('cosmetics', 'cosmetics', true),
    ('cosmetics-assets', 'cosmetics-assets', true)
ON CONFLICT (id) DO UPDATE SET public = EXCLUDED.public;

DROP POLICY IF EXISTS capes_public_read ON storage.objects;
CREATE POLICY capes_public_read ON storage.objects AS PERMISSIVE FOR SELECT TO public USING (bucket_id = 'capes');

DROP POLICY IF EXISTS cosmetics_assets_public_read ON storage.objects;
CREATE POLICY cosmetics_assets_public_read ON storage.objects AS PERMISSIVE FOR SELECT TO public USING (bucket_id = 'cosmetics-assets');

-- ────────────────────────────────────────────────────────────────────────
--  Role badges
--  The only seed rows. The mod reads the whole set from /tag and renders
--  whatever is there, so a new role is a row, not a release.
--  Blue users, yellow creators, purple developers, red owners.
-- ────────────────────────────────────────────────────────────────────────

INSERT INTO public.tags (slug, name, color, priority_weight, auto_role, is_role_badge) VALUES
    ('breeze',    'Breeze',    '#55C8FF',  10, NULL,        true),
    ('donator',   'Donator',   '#FFD700',  30, NULL,        true),
    ('creator',   'Creator',   '#FFD23F',  50, 'creator',   true),
    ('admin',     'Admin',     '#800080',  80, 'admin',     true),
    ('developer', 'Developer', '#A56EFF',  90, 'developer', true),
    ('owner',     'Owner',     '#FF5555', 100, 'owner',     true)
ON CONFLICT (slug) DO NOTHING;
