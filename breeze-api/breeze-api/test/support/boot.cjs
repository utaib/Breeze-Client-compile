'use strict';
/**
 * Boots the real server.js in this process with a stubbed Supabase client and
 * a throwaway data directory, then reports the port it is listening on.
 *
 * Run as a child process by harness.cjs. It prints one line of JSON,
 * `{"ready":true,"port":NNN}`, once the server is up.
 *
 * Nothing here may touch production: the Supabase client is in-memory, the
 * versions and data directories point at a temp folder, and no SMTP or PayPal
 * credentials are configured, so those paths fail closed.
 */

const path = require('path');
const Module = require('module');
const { createStubClient } = require('./supabase-stub.cjs');

const seedPath = process.env.BREEZE_TEST_SEED;
const seed = seedPath ? JSON.parse(require('fs').readFileSync(seedPath, 'utf8')) : {};
const stub = createStubClient(seed);

// Intercept require('@supabase/supabase-js') before server.js asks for it.
const originalLoad = Module._load;
Module._load = function patchedLoad(request, parent, isMain) {
  if (request === '@supabase/supabase-js') {
    return { createClient: () => stub };
  }
  return originalLoad.call(this, request, parent, isMain);
};

// server.js calls app.listen() at import time; PORT=0 gives us a free port.
const apiRoot = path.resolve(__dirname, '..', '..');
process.chdir(apiRoot);

// server.js does not export its http server, so capture it as it starts
// listening. This is more reliable than scanning active handles on a timer.
const http = require('http');
const originalListen = http.Server.prototype.listen;
let announced = false;
http.Server.prototype.listen = function patchedListen(...args) {
  this.once('listening', () => {
    if (announced) return;
    const address = this.address();
    if (!address || !address.port) return;
    announced = true;
    process.stdout.write('\n' + JSON.stringify({ ready: true, port: address.port }) + '\n');
  });
  return originalListen.apply(this, args);
};

process.on('uncaughtException', (error) => {
  process.stdout.write('\n' + JSON.stringify({ ready: false, error: error.message }) + '\n');
  process.exit(1);
});

const server = require(path.join(apiRoot, 'server.js'));

process.on('message', (msg) => {
  if (msg === 'db:snapshot') process.send({ db: stub.__db.snapshot() });
  if (msg === 'shutdown') process.exit(0);
});

module.exports = { stub, server };
