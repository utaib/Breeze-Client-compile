'use strict';
/**
 * Spotify: a bad minute at Spotify must not take the API down, and must not be
 * reported to the user as a dead connection.
 *
 * The owner's report was that Spotify authorizes successfully, the launcher says
 * it connected, and then it always says the connection expired. The server side
 * of the OAuth flow turned out to be correct; what was wrong was everything
 * around a failed refresh:
 *
 *   - accessTokenFor called axios.post with no catch. Express 4 does not handle
 *     a rejected async handler, so on Node 20 a refusal from Spotify's token
 *     endpoint took the whole process down. /spotify/now-playing,
 *     /spotify/:action and /spotify/search all await it with nothing around them.
 *   - /spotify/status treated any non-200 from /me as needsReconnect, so a 429
 *     or a 5xx told the user to authorize again when nothing was wrong with the
 *     authorization.
 *
 * These drive the real src/spotify.js with Spotify and Supabase stubbed, rather
 * than restating its logic. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

process.env.SPOTIFY_CLIENT_ID = 'CID';
process.env.SPOTIFY_CLIENT_SECRET = 'CSECRET';
process.env.SPOTIFY_REDIRECT_URI = 'https://api.breezeclient.net/spotify/callback';

const ROOT = path.join(__dirname, '..');
const axios = require(path.join(ROOT, 'node_modules', 'axios'));

const USER = '0d252b72-18b0-4a9c-a06b-bf7d2b87f6b7';

/**
 * One wired-up copy of the module.
 *
 * `tokenEndpoint` and `meEndpoint` decide what Spotify does, so a test can make
 * the refresh fail the way it wants and watch what the routes do about it.
 */
function build({ tokenEndpoint, meEndpoint }) {
  axios.defaults.adapter = (config) => {
    const url = String(config.url);
    let status = 404;
    let data = {};
    if (url === 'https://accounts.spotify.com/api/token') ({ status, data } = tokenEndpoint());
    else if (url.startsWith('https://api.spotify.com/v1/')) ({ status, data } = meEndpoint());

    const response = { data, status, statusText: String(status), headers: {}, config, request: {} };
    const validate = config.validateStatus || ((s) => s >= 200 && s < 300);
    if (validate(status)) return Promise.resolve(response);
    const err = new Error(`Request failed with status code ${status}`);
    err.response = response;
    return Promise.reject(err);
  };

  // One connected account whose access token expired a moment ago, so every
  // call has to go through the refresh path.
  const row = {
    user_uuid: USER,
    access_token: 'ACCESS-OLD',
    refresh_token: 'REFRESH-1',
    expires_at: new Date(Date.now() - 5000).toISOString(),
    product: 'premium',
    display_name: 'Utaib',
  };
  const supabase = {
    from: () => ({
      select: () => ({ eq: () => ({ maybeSingle: async () => ({ data: row, error: null }) }) }),
      upsert: async (next) => { Object.assign(row, next); return { error: null }; },
      delete: () => ({ eq: async () => ({ error: null }) }),
    }),
  };

  const handlers = [];
  const app = {
    get: (p, ...h) => handlers.push({ m: 'GET', p, fn: h[h.length - 1] }),
    post: (p, ...h) => handlers.push({ m: 'POST', p, fn: h[h.length - 1] }),
  };
  const ok = (res, data = {}) => { res._out = { status: res._status || 200, data }; return res._out; };
  const fail = (res, message, status = 400) => { res._out = { status, message }; return res._out; };
  const log = { info() {}, warn() {}, error() {} };

  require(path.join(ROOT, 'src', 'spotify.js'))(app, {
    supabase,
    requireAuth: (q, s, n) => n(),
    ok,
    fail,
    log,
  });

  /** Call a route the way Express would, and return what it wrote. */
  const call = async (method, route, query = {}) => {
    const handler = handlers.find((h) => h.m === method && h.p === route);
    assert.ok(handler, `${method} ${route} is registered`);
    const res = { _status: 200, _out: null };
    res.status = (s) => { res._status = s; return res; };
    res.json = (b) => { res._out = { status: res._status, body: b }; return res; };
    res.send = (b) => { res._out = { status: res._status, body: String(b) }; return res; };
    await handler.fn({ user: { uuid: USER }, query, params: { action: 'play' }, body: {} }, res);
    return res._out;
  };

  return { call, row };
}

const OK_ME = () => ({ status: 200, data: { id: 'spot123', display_name: 'Utaib', product: 'premium' } });
const FIVE_HUNDRED = () => ({ status: 500, data: { error: 'server_error' } });

test('a 5xx from the token endpoint answers the request instead of killing the process', async () => {
  // Before the fix this rejected, and an unhandled rejection on Node 20 with
  // Express 4 exits the process: one bad minute at Spotify took the API offline
  // for everyone, not just the person whose token was being refreshed.
  const { call } = build({ tokenEndpoint: FIVE_HUNDRED, meEndpoint: OK_ME });
  // search needs a q, or it answers with an empty list before it ever asks
  // Spotify anything, which would not exercise the refresh at all.
  for (const [route, query] of [['/spotify/now-playing', {}], ['/spotify/search', { q: 'daft punk' }]]) {
    const out = await call('GET', route, query);
    assert.ok(out, `${route} wrote a response rather than rejecting`);
    assert.ok(out.status >= 400, `${route} reports the failure honestly`);
  }
  const action = await call('POST', '/spotify/:action');
  assert.ok(action, '/spotify/:action wrote a response rather than rejecting');
});

test('a network failure with no HTTP response is handled too', async () => {
  // e.response is undefined here, which is the shape a DNS or socket failure
  // takes, and the branch that reads e.response.status would throw on it.
  axios.defaults.adapter = undefined;
  const { call } = build({ tokenEndpoint: OK_ME, meEndpoint: OK_ME });
  axios.defaults.adapter = () => Promise.reject(new Error('getaddrinfo ENOTFOUND accounts.spotify.com'));
  const out = await call('GET', '/spotify/now-playing');
  assert.ok(out, 'the route answered');
  assert.ok(out.status >= 400);
});

test('Spotify having a bad minute is not reported as a dead connection', async () => {
  // The owner's actual symptom. The account is fine and the refresh token is
  // still there, so offering to reconnect is wrong and is what made a working
  // link read as permanently expired.
  const { call } = build({ tokenEndpoint: FIVE_HUNDRED, meEndpoint: OK_ME });
  const out = await call('GET', '/spotify/status');
  assert.equal(out.status, 200);
  assert.notEqual(out.data.needsReconnect, true, 'a transient failure must not ask for a reconnect');
  assert.equal(out.data.connected, true, 'the account is still connected');
  assert.equal(out.data.temporarilyUnavailable, true, 'and the launcher is told why it is thin on detail');
});

test('a refresh token Spotify has actually revoked does ask for a reconnect', async () => {
  // The one case where reconnecting is the right advice, so the fix above must
  // not have swallowed it.
  const { call } = build({
    tokenEndpoint: () => ({ status: 400, data: { error: 'invalid_grant' } }),
    meEndpoint: OK_ME,
  });
  const out = await call('GET', '/spotify/status');
  assert.equal(out.status, 200);
  assert.equal(out.data.needsReconnect, true);
  assert.equal(out.data.connected, false);
});

test('the happy path still refreshes and reports premium', async () => {
  const { call, row } = build({
    tokenEndpoint: () => ({ status: 200, data: { access_token: 'ACCESS-NEW', expires_in: 3600 } }),
    meEndpoint: OK_ME,
  });
  const out = await call('GET', '/spotify/status');
  assert.equal(out.data.connected, true);
  assert.equal(out.data.premium, true);
  assert.equal(row.access_token, 'ACCESS-NEW', 'the refreshed token was stored');
  assert.equal(row.refresh_token, 'REFRESH-1', 'and the refresh token was kept, since Spotify did not send a new one');
});
