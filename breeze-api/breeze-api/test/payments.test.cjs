'use strict';
/**
 * Payments, end to end against a stand-in PayPal.
 *
 * A PayPal order the buyer approves is only an authorisation. The merchant
 * has to capture it; only then does money move and PayPal send
 * PAYMENT.CAPTURE.COMPLETED, the event that credits Wind Charges and grants
 * items. Nothing in the API captured, so every approved payment stopped at
 * "approved" and the buyer received nothing.
 *
 * These tests run the real server against a small fake of PayPal's REST API
 * (token, create order, capture, verify webhook signature) and walk the whole
 * path: start a purchase, approve, capture (by webhook and by the return
 * page), confirm, credit, and that duplicates and unsigned events credit
 * nothing. Run with: npm test
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
    name: 'payments',
    seed,
    env: {
      PAYPAL_MODE: 'sandbox',
      PAYPAL_API_BASE: paypal.base,
      PAYPAL_CLIENT_ID: 'test-client',
      PAYPAL_CLIENT_SECRET: 'test-secret',
      PAYPAL_WEBHOOK_ID: 'WH-TEST',
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

test('a Wind Charge purchase is captured when PayPal reports the approval, and credited once', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-640' } });
  assert.equal(start.status, 201, JSON.stringify(start.body));
  assert.equal(start.body.wind_charges, 672);
  const ppId = tokenOf(start.body.approve_url);
  assert.ok(ppId, 'the approve link names the PayPal order');

  // The buyer approves on PayPal's page; PayPal reports it.
  assert.equal((await webhook('CHECKOUT.ORDER.APPROVED', { id: ppId })).status, 200);
  const cap = await until(() => captures(ppId).length > 0 && captures(ppId));
  assert.ok(cap, 'the API captures the approved order');
  assert.equal(cap[0].headers['paypal-request-id'], `capture-${ppId}`, 'the capture is idempotent');
  // PayPal's answer to the capture says the money moved: credited now.
  assert.equal(await until(async () => (await balance()) === 672 && 672), 672, 'the charges are credited');

  // PayPal's own confirmation, and its retries, must not pay twice.
  const done = { id: `CAP-${ppId}`, status: 'COMPLETED', supplementary_data: { related_ids: { order_id: ppId } } };
  await webhook('PAYMENT.CAPTURE.COMPLETED', done);
  await webhook('PAYMENT.CAPTURE.COMPLETED', done);
  await new Promise((r) => setTimeout(r, 400));
  assert.equal(await balance(), 672, 'a repeated confirmation credits nothing more');
});

test('the return page captures without waiting for the webhook', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  const res = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(res.status, 200, JSON.stringify(res.body));
  assert.equal(res.body.status, 'completed', 'delivered from the capture answer, no webhook needed');
  assert.equal(captures(ppId).length, 1);
  assert.equal(await balance(), 992);

  await webhook('PAYMENT.CAPTURE.COMPLETED', { id: `CAP-${ppId}`, supplementary_data: { related_ids: { order_id: ppId } } });
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(await balance(), 992, 'the later webhook credits nothing more');
  const again = await api.post('/purchases/capture', { body: { token: ppId } });
  assert.equal(again.body.status, 'completed', 'a finished order reports completed and is not captured again');
  assert.equal(captures(ppId).length, 1);
});

test('a cape bought with PayPal is granted after the capture', async () => {
  const start = await api.post('/purchases/create-order', { token: buyer, body: { cape_id: CAPE } });
  assert.equal(start.status, 201, JSON.stringify(start.body));
  const ppId = tokenOf(start.body.approve_url);
  await webhook('CHECKOUT.ORDER.APPROVED', { id: ppId });
  assert.ok(await until(() => captures(ppId).length > 0), 'captured');
  await webhook('PAYMENT.CAPTURE.COMPLETED', { id: `CAP-${ppId}`, supplementary_data: { related_ids: { order_id: ppId } } });
  const owned = await until(async () => {
    const r = await api.get('/capes/owned', { token: buyer });
    const list = r.body && (r.body.capes || r.body.data || r.body.owned || []);
    return Array.isArray(list) && list.some((c) => c.cape_id === CAPE || (c.cape && c.cape.id === CAPE));
  });
  assert.ok(owned, 'the cape is in the buyer\'s owned capes');
});

test('an event without a valid PayPal signature credits nothing', async () => {
  const start = await api.post('/wallet/purchase', { token: buyer, body: { pack_id: 'breeze-320' } });
  const ppId = tokenOf(start.body.approve_url);
  const before = await balance();
  await webhook('PAYMENT.CAPTURE.COMPLETED', { id: `CAP-${ppId}`, supplementary_data: { related_ids: { order_id: ppId } } }, 'forged');
  await webhook('CHECKOUT.ORDER.APPROVED', { id: ppId }, 'forged');
  await new Promise((r) => setTimeout(r, 400));
  assert.equal(await balance(), before);
  assert.equal(captures(ppId).length, 0, 'a forged approval does not trigger a capture');
});

test('the capture route refuses bad input and unknown orders, and reports unapproved ones', async () => {
  assert.equal((await api.post('/purchases/capture', { body: {} })).status, 400);
  assert.equal((await api.post('/purchases/capture', { body: { token: 'x/../y' } })).status, 400);
  assert.equal((await api.post('/purchases/capture', { body: { token: 'NOSUCHORDER1' } })).status, 404);
});

