/**
 * Mount the real spotify.js against a stub express app and check the routes it
 * registers, plus the behaviour that matters when nothing is configured.
 * This exercises the shipped module, not a restatement of it.
 */
const path = require('path');
const mount = require(require('path').join(__dirname,'..','src','spotify.js'));

const routes = [];
const app = {
  get: (p, ...h) => routes.push(['GET', p, h.length]),
  post: (p, ...h) => routes.push(['POST', p, h.length]),
};

const requireAuth = (req, res, next) => next();
const captured = [];
const ok = (res, data = {}) => { captured.push({ kind: 'ok', data }); return data; };
const fail = (res, message, status = 400) => { captured.push({ kind: 'fail', message, status }); return { message, status }; };
const log = { info: () => {}, warn: () => {}, error: () => {} };

// Deliberately unconfigured: no SPOTIFY_* env set.
delete process.env.SPOTIFY_CLIENT_ID;
delete process.env.SPOTIFY_CLIENT_SECRET;
delete process.env.SPOTIFY_REDIRECT_URI;

const supabase = {
  from: () => ({
    select: () => ({ eq: () => ({ maybeSingle: async () => ({ data: null }) }) }),
    upsert: async () => ({ error: null }),
    delete: () => ({ eq: async () => ({ error: null }) }),
  }),
};

mount(app, { supabase, requireAuth, ok, fail, log });

let bad = 0;
const check = (label, got, want) => {
  const good = JSON.stringify(got) === JSON.stringify(want);
  if (!good) bad++;
  console.log((good ? 'ok   ' : 'FAIL ') + label + (good ? '' : `\n       got ${JSON.stringify(got)} want ${JSON.stringify(want)}`));
};

const paths = routes.map((r) => `${r[0]} ${r[1]}`).sort();
console.log('registered routes:');
paths.forEach((p) => console.log('   ' + p));
console.log();

check('login route registered', paths.includes('GET /spotify/login'), true);
check('callback route registered', paths.includes('GET /spotify/callback'), true);
check('status route registered', paths.includes('GET /spotify/status'), true);
check('disconnect route registered', paths.includes('POST /spotify/disconnect'), true);
check('now-playing route registered', paths.includes('GET /spotify/now-playing'), true);
check('search route registered', paths.includes('GET /spotify/search'), true);
check('one combined control route', paths.includes('POST /spotify/:action'), true);
// disconnect must be registered before the wildcard or it gets swallowed by it.
check('disconnect registered before the wildcard',
  routes.findIndex((r) => r[1] === '/spotify/disconnect') < routes.findIndex((r) => r[1] === '/spotify/:action'), true);

// Every route except the OAuth callback must sit behind auth. The callback
// cannot: the browser arrives from Spotify with no Breeze token.
const unauthed = routes.filter((r) => r[2] < 2).map((r) => `${r[0]} ${r[1]}`);
check('only the callback is unauthenticated', unauthed, ['GET /spotify/callback']);

// Unconfigured server: status must answer calmly rather than throwing.
captured.length = 0;
const statusHandler = routes.find((r) => r[1] === '/spotify/status');
console.log('\nunconfigured behaviour:');
(async () => {
  await mountedHandler(statusHandler);
  const last = captured[captured.length - 1];
  check('status returns ok, not an error', last?.kind, 'ok');
  check('reports configured:false', last?.data?.configured, false);
  check('reports connected:false', last?.data?.connected, false);

  console.log(bad ? `\n${bad} FAILED` : '\nall pass');
  process.exit(bad ? 1 : 0);
})();

// The handler chain is [requireAuth, realHandler]; invoke the last one.
async function mountedHandler(route) {
  const idx = routes.indexOf(route);
  // Re-mount capturing the actual functions this time.
  const fns = [];
  const app2 = {
    get: (p, ...h) => fns.push([p, h[h.length - 1]]),
    post: (p, ...h) => fns.push([p, h[h.length - 1]]),
  };
  mount(app2, { supabase, requireAuth, ok, fail, log });
  const entry = fns.find(([p]) => p === '/spotify/status');
  await entry[1]({ user: { uuid: 'u1' }, query: {}, params: {} }, { status: () => ({ json: () => {} }), json: () => {}, send: () => {} });
}
