# Breeze API — Folder Structure & Architecture

The API is a Node.js + Express app. `server.js` is the entry point and holds the
stable, battle-tested routes (auth, users, capes, cosmetics, purchases, social,
notifications, admin, the in-game mod endpoints). Every **new** system since 1.0.0
lives in its own module under `src/` and is wired in near the bottom of
`server.js` with a shared context object. This keeps new work isolated and
testable without rewriting the proven core.

```
breeze-api/
├── server.js                 # Entry point. `npm start` runs this.
│                             #   - config/env, middleware, helmet, CORS, rate limits
│                             #   - core routes (auth, users, capes, cosmetics,
│                             #     purchases, promo, social, notifications, admin)
│                             #   - in-game mod endpoints (cape/friends/dm/host)
│                             #   - registers the src/ modules, then 404 + error handler
│
├── src/                      # Modular systems (each: module.exports = (app, ctx) => {…})
│   ├── auth-ms.js            #   Microsoft device-code sign-in (website + admin).
│   │                         #   Public client 00000000402b5328 — NO Azure app needed.
│   ├── economy.js            #   Wind Charges + Breeze Rods: wallet, packs, purchases,
│   │                         #   creator earnings, withdrawals, admin payout queue.
│   ├── spotify.js            #   Breeze FM — per-user Spotify OAuth + playback proxy.
│   └── assets.js             #   API-local asset storage helper (serves /assets/*).
│
├── storage/                  # API-served binary assets (NOT Supabase). Auto-created.
│   ├── capes/                #   marketplace cape textures + animation frames
│   │   ├── pending/          #   paid personal capes awaiting settlement (private)
│   │   └── personal/         #   applied personal capes
│   ├── cosmetics/            #   cosmetic .glb models + thumbnails
│   └── spotify/              #   per-user Spotify link tokens (links.json)
│
├── versions/                 # Update system — what the launcher's updater reads.
│   ├── versions.js           #   Manifest: per-OS launcher builds + mod version/file/sha.
│   ├── launcher/             #   Launcher installers, one folder per OS:
│   │   ├── windows/          #     Breeze-Client-<version>.exe   (ships pre-built)
│   │   ├── linux/            #     Breeze-Client-<version>.AppImage
│   │   ├── macos/            #     Breeze-Client-<version>.dmg
│   │   └── README.txt        #   Cross-platform release + naming convention
│   └── mods/                 #   Breeze Fabric mod jars → <mcversion>.jar (e.g. 1.21.1.jar)
│
├── mod_capes/                # In-game mod: cape PNGs the Fabric mod serves to players
├── uploads/                  # Multer scratch space (transient)
│
├── *.txt                     # In-game mod data (cape_owners, friends, dms, names,
│                             #   selections, tag). Flat files by design — the mod
│                             #   reads/writes these via the API. Leave at root.
│
├── schema.sql                # Supabase schema. Run once in the SQL editor (idempotent).
├── package.json              # deps + scripts (start = node server.js)
├── env                       # environment variables (rename/keep as .env on server)
├── STRUCTURE.md              # this file
└── DEPLOY.md                 # step-by-step deployment
```

> **Entry point is `server.js` (plain JavaScript).** Start it with `node server.js`
> (or `npm start`). There is **no** TypeScript build and the API **must not** be run
> through `ts-node` — the old `server.mts` prototype has been removed. If your host
> (e.g. Pterodactyl) runs anything other than `node server.js`, fix the startup
> command (DEPLOY.md → "Pterodactyl startup command").

## Why not fully split server.js into routes/?

Deliberate. `server.js` is a working, in-production API. Decomposing 5,600 lines
into `routes/`, `controllers/`, `services/` right at the 1.0.0 finish line is a
large refactor with real risk of breaking payments, auth, or the mod endpoints —
for a purely cosmetic gain. The chosen middle ground: **the proven core stays put;
every new and future system is a self-contained `src/` module.** New features add a
file under `src/` and one `require('./src/x')(app, breezeCtx)` line — never a change
to the core. This is clean, incremental, and low-risk.

## The shared context (`breezeCtx`)

`server.js` builds one `breezeCtx` object (supabase client, `ok`/`fail`, `log`,
`jwt`, auth middleware, PayPal helpers, `issueBreezeSessionForMcToken`, the assets
helper, etc.) and passes it to every `src/` module. Modules never re-import config —
they receive everything they need. To add a system: create `src/thing.js` exporting
`(app, ctx) => { ctx.app... }`, then register it next to the others.

## Storage philosophy (post-1.0.0)

Supabase = **relational data only** (users, orders, earnings, social, wallet
ledger…). All **binary assets** (capes, cosmetics, thumbnails) are written to the
API's own disk under `storage/` and served from `/assets/*`. This removes the
Supabase storage quota/egress bottleneck and one external dependency. Older rows
that still hold Supabase-storage URLs keep working — URLs are stored absolute.
