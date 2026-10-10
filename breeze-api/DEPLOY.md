# Breeze API: quick start

The full guide, with every environment variable, Supabase, PayPal, Pterodactyl
and troubleshooting, is [docs/DEPLOYMENT.md](../docs/DEPLOYMENT.md).

## Required environment

The API exits at startup unless these are set, either in a `.env`, `.env.local`
or `env` file inside `breeze-api/breeze-api/`, or in the host's environment
panel:

```env
SUPABASE_URL=https://xxxxxxxxxxxx.supabase.co
SUPABASE_KEY=<service_role key>
JWT_SECRET=<long random string>
```

The env file holds live secrets. Never commit it.

## Run

1. Upload `breeze-api/breeze-api/` without `node_modules/`.
2. Run `npm install` on the host (the `sharp` binary is OS-specific).
3. Start with `node server.js`. Not `ts-node`, and not `server.mts`.
4. Keep `server.js` as the only `.js` file at the folder's top level; modules go
   in `src/`. A second top-level `.js` file breaks the Pterodactyl startup check.

## Check

`https://api.breezeclient.net/health` should return JSON with `"status": "ok"`.

If it does not start, see
[Troubleshooting](../docs/DEPLOYMENT.md#troubleshooting).
