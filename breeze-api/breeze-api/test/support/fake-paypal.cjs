'use strict';
/**
 * A stand-in for PayPal's REST API (token, create order, capture, get order,
 * verify webhook signature), for the payments tests. It answers the way
 * PayPal does where the API depends on it: an idempotent capture replays its
 * first answer, a second capture is ORDER_ALREADY_CAPTURED, and captures
 * carry the order's amount.
 */
const http = require('http');

// `behaviour` sets how one PayPal order behaves at capture:
//   'pending'  PayPal holds the payment (capture status PENDING)
//   'wrong-amount'  the capture is for a different amount
//   'captured-elsewhere'  already captured: capture answers ORDER_ALREADY_CAPTURED
// and `release(id)` turns a held capture into a completed one.
function fakePayPal() {
  const calls = [];
  const orders = new Map();
  const behaviour = new Map();
  const replies = new Map();
  let next = 1;
  const captureOf = (id) => {
    const o = orders.get(id);
    const how = behaviour.get(id);
    return {
      id: `CAP-${id}`,
      status: how === 'pending' ? 'PENDING' : 'COMPLETED',
      ...(how === 'pending' ? { status_details: { reason: 'PENDING_REVIEW' } } : {}),
      amount: { currency_code: 'USD', value: how === 'wrong-amount' ? '1.00' : o.value },
    };
  };
  const orderBody = (id) => ({ id, status: 'COMPLETED', purchase_units: [{ payments: { captures: [captureOf(id)] } }] });
  const server = http.createServer((req, res) => {
    let body = '';
    req.on('data', (c) => { body += c; });
    req.on('end', () => {
      const url = req.url;
      calls.push({ method: req.method, url, headers: req.headers, body });
      const send = (status, obj) => { res.writeHead(status, { 'Content-Type': 'application/json' }); res.end(JSON.stringify(obj)); };
      if (url === '/v1/oauth2/token') {
        const auth = Buffer.from(String(req.headers.authorization || '').replace(/^Basic /, ''), 'base64').toString();
        if (auth !== 'test-client:test-secret') return send(401, { error: 'invalid_client' });
        return send(200, { access_token: 'ACCESS-TOKEN', expires_in: 3600 });
      }
      if (url === '/v2/checkout/orders' && req.method === 'POST') {
        const id = `PPORDER${String(next++).padStart(4, '0')}`;
        orders.set(id, { value: JSON.parse(body).purchase_units[0].amount.value, captured: false });
        return send(201, { id, status: 'CREATED', links: [{ rel: 'approve', href: `https://www.sandbox.paypal.com/checkoutnow?token=${id}` }] });
      }
      const cap = url.match(/^\/v2\/checkout\/orders\/([^/]+)\/capture$/);
      if (cap) {
        const id = cap[1];
        if (id.endsWith('UNAPPROVED')) return send(422, { name: 'UNPROCESSABLE_ENTITY', details: [{ issue: 'ORDER_NOT_APPROVED' }] });
        // Like PayPal: a repeated PayPal-Request-Id gets the first answer back.
        const rid = req.headers['paypal-request-id'];
        if (rid && replies.has(rid)) return send(201, replies.get(rid));
        const o = orders.get(id);
        // An order made with other PayPal keys: PayPal does not know it.
        if (!o) return send(404, { name: 'RESOURCE_NOT_FOUND', debug_id: 'dbg404', details: [{ issue: 'INVALID_RESOURCE_ID', description: 'Specified resource ID does not exist.' }] });
        if (behaviour.get(id) === 'captured-elsewhere' || (o && o.captured)) {
          if (o) o.captured = true;
          return send(422, { name: 'UNPROCESSABLE_ENTITY', details: [{ issue: 'ORDER_ALREADY_CAPTURED' }] });
        }
        o.captured = true;
        const reply = orderBody(id);
        if (rid) replies.set(rid, reply);
        return send(201, reply);
      }
      const get = url.match(/^\/v2\/checkout\/orders\/([^/]+)$/);
      if (get && req.method === 'GET') {
        if (!orders.has(get[1])) return send(404, { name: 'RESOURCE_NOT_FOUND' });
        return send(200, orderBody(get[1]));
      }
      if (url === '/v1/notifications/verify-webhook-signature') {
        const b = JSON.parse(body || '{}');
        // What production saw on 2026-10-09: PayPal refused the check itself.
        if (b.transmission_sig === 'refused-by-paypal') {
          return send(400, { name: 'VALIDATION_ERROR', debug_id: 'dbg123', details: [{ field: 'webhook_id', issue: 'INVALID_RESOURCE_ID', description: 'Webhook id not found' }] });
        }
        const good = b.webhook_id === 'WH-TEST' && b.transmission_sig === 'good-signature';
        return send(200, { verification_status: good ? 'SUCCESS' : 'FAILURE' });
      }
      return send(404, { name: 'RESOURCE_NOT_FOUND' });
    });
  });
  const release = (id) => behaviour.delete(id);
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({ server, calls, behaviour, release, base: `http://127.0.0.1:${server.address().port}` })));
}


module.exports = { fakePayPal };
