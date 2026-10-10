'use strict';
/**
 * Delivering a paid order from PayPal's answer to the capture (2026-10-09).
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
  orders: [],
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
    name: 'payments-delivery',
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


test('when PayPal refuses to verify the webhook (production, 2026-10-09), the paid order is still delivered, and the reason is logged', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  const before = await balance();
  // The approval webhook arrives but PayPal answers its check with a 400.
  await webhook('CHECKOUT.ORDER.APPROVED', { id: ppId }, 'refused-by-paypal');
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(captures(ppId).length, 0, 'an unverified event still triggers nothing');
  assert.match(api.logs.join(''), /INVALID_RESOURCE_ID/, "PayPal's reason is in the log");
  assert.match(api.logs.join(''), /dbg123/, "and PayPal's debug id");
  // The buyer lands on the return page: captured, and credited from PayPal's answer.
  const res = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(res.body.status, 'completed', JSON.stringify(res.body));
  assert.equal(await balance(), before + 320);
});

test('a payment PayPal holds is not credited until PayPal releases it', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  paypal.behaviour.set(ppId, 'pending');
  const before = await balance();
  const held = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(held.body.status, 'processing', JSON.stringify(held.body));
  assert.equal(await balance(), before, 'nothing credited while PayPal holds it');
  paypal.release(ppId);
  const later = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(later.body.status, 'completed', 'asking again reads the released payment');
  assert.equal(await balance(), before + 320);
});

test('a capture for a different amount than the order is not delivered', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  paypal.behaviour.set(ppId, 'wrong-amount');
  const before = await balance();
  const res = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(res.status, 502);
  assert.equal(await balance(), before);
});

test('an order captured earlier (ORDER_ALREADY_CAPTURED) is delivered when the return page is opened again', async () => {
  // The production order of 2026-10-09: captured, never credited.
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  paypal.behaviour.set(ppId, 'captured-elsewhere');
  const before = await balance();
  const res = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(res.body.status, 'completed', JSON.stringify(res.body));
  assert.equal(await balance(), before + 320);
});

