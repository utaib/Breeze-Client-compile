'use strict';
/**
 * Delivering a paid order: simultaneous paths, and orders PayPal does not
 * know (2026-10-10). Split from payments-delivery.test.cjs to stay under the
 * purchase rate limit (10 calls a minute across /wallet/purchase and
 * /purchases/*), which would otherwise refuse some of the calls under test.
 *
 * The first real sandbox purchase was captured (the money moved) but never
 * credited: only the verified PAYMENT.CAPTURE.COMPLETED webhook delivered,
 * and PayPal answered every signature check with a 400. The API now delivers
 * as soon as its own capture call reports a released payment for exactly the
 * order's amount, once, whichever path gets there first. In its own file
 * because the capture route is rate limited to 10 calls a minute.
 * Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { startApi } = require('./support/harness.cjs');
const { fakePayPal } = require('./support/fake-paypal.cjs');

const BUYER = { uuid: 'a7777777777777777777777777777777', username: 'Buyer', role: 'user' };
const CAPE = 'c8888888-8888-4888-8888-888888888888';

const seed = {
  users: [
    { ...BUYER, wind_charges: 0, earned_wind_charges: 0, creator_passes: 0 },
    { uuid: '0f000000000000000000000000000000', username: 'Owner', role: 'owner' },
  ],
  capes: [{ id: CAPE, name: 'Aurora Cape', price_usd: 5, creator_id: null, is_public: true, image_url: 'https://example/cape.png' }],
  cosmetics: [],
  user_capes: [],
  user_cosmetics: [],
  // An order PayPal will not recognise, as if made with other PayPal keys.
  orders: [{ id: 'd0000000-0000-4000-8000-000000000001', user_uuid: BUYER.uuid, order_type: 'wind_charges', status: 'pending', paypal_order_id: 'OTHERKEYS0001', gross_amount_usd: 5, wind_charges: 320 }],
  wallet_transactions: [],
  notifications: [],
  promo_codes: [],
  promo_code_uses: [],
};

let api;
let paypal;
let buyer;
test.before(async () => {
  paypal = await fakePayPal();
  api = await startApi({
    name: 'payments-race',
    seed,
    env: {
      PAYPAL_MODE: 'sandbox',
      PAYPAL_API_BASE: paypal.base,
      PAYPAL_CLIENT_ID: 'test-client',
      PAYPAL_CLIENT_SECRET: 'test-secret',
      PAYPAL_WEBHOOK_ID: 'WH-TEST',
      // Database round trips, so simultaneous deliveries really overlap.
      BREEZE_TEST_DB_LATENCY_MS: '3',
    },
  });
  buyer = api.token({ uuid: BUYER.uuid, username: BUYER.username, role: 'user' });
});
test.after(async () => {
  if (api) await api.stop();
  if (paypal) paypal.server.close();
});

const webhook = (event_type, resource, signature = 'good-signature') =>
  api.post('/payments/webhook', {
    body: { id: `WH-${Date.now()}-${Math.random()}`, event_type, resource },
    headers: {
      'paypal-auth-algo': 'SHA256withRSA',
      'paypal-cert-url': 'https://api.sandbox.paypal.com/cert',
      'paypal-transmission-id': `t-${Date.now()}`,
      'paypal-transmission-sig': signature,
      'paypal-transmission-time': new Date().toISOString(),
    },
  });

async function until(fn, ms = 4000) {
  const end = Date.now() + ms;
  for (;;) {
    const v = await fn();
    if (v) return v;
    if (Date.now() > end) return v;
    await new Promise((r) => setTimeout(r, 50));
  }
}
const captures = (id) => paypal.calls.filter((c) => c.url === `/v2/checkout/orders/${id}/capture`);
const balance = async () => (await api.get('/wallet', { token: buyer })).body.wind_charges;
const tokenOf = (approveUrl) => new URL(approveUrl).searchParams.get('token');


test('the return page, the approval webhook and the completion webhook at once credit exactly once', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  const before = await balance();
  const done = { id: `CAP-${ppId}`, status: 'COMPLETED', supplementary_data: { related_ids: { order_id: ppId } } };
  // PayPal also retries its own events, so the same confirmation can arrive
  // several times at once.
  const [pageA, , , , , , , pageB] = await Promise.all([
    api.post('/purchases/capture', { body: { token: ppId } }),
    webhook('CHECKOUT.ORDER.APPROVED', { id: ppId }),
    ...Array.from({ length: 5 }, () => webhook('PAYMENT.CAPTURE.COMPLETED', done)),
    api.post('/purchases/capture', { body: { token: ppId } }),
  ]);
  assert.deepEqual([pageA.status, pageB.status], [200, 200], 'both return pages were served, not rate limited');
  await new Promise((r) => setTimeout(r, 800));
  assert.equal(await balance(), before + 320, 'one credit');
});

test('an order PayPal does not know (made with other PayPal keys) is refused, credits nothing, and the log says why', async () => {
  const before = await balance();
  const res = await api.post('/purchases/capture', { body: { token: 'OTHERKEYS0001' } });
  assert.equal(res.status, 502);
  assert.equal(await balance(), before);
  const logs = api.logs.join('');
  assert.match(logs, /RESOURCE_NOT_FOUND/, "PayPal's error name");
  assert.match(logs, /dbg404/, "PayPal's debug id");
  assert.match(logs, /step.{0,4}capture/, 'which call failed');
  assert.match(logs, /current PAYPAL_CLIENT_ID/, 'what to check');
});
