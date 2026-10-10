'use strict';
/**
 * Starts the API in a child process with a stubbed database and a temp data
 * directory, and hands tests a small client for talking to it.
 *
 * Usage:
 *   const api = await startApi({ seed: { users: [...] } })
 *   const res = await api.get('/health')
 *   api.token({ uuid, username, role })   // a signed Breeze JWT
 *   await api.stop()
 */

const { fork } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');
const jwt = require('jsonwebtoken');

const API_ROOT = path.resolve(__dirname, '..', '..');
const TEST_JWT_SECRET = 'breeze-test-secret-not-a-real-key-0123456789';

function makeDataDir(name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), `breeze-api-test-${name}-`));
  fs.mkdirSync(path.join(dir, 'versions', 'launcher', 'windows'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'versions', 'launcher', 'testing', 'pre-beta', 'windows'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'versions', 'mods'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'storage'), { recursive: true });
  fs.mkdirSync(path.join(dir, 'data'), { recursive: true });
  return dir;
}

async function startApi(options = {}) {
  const dataDir = makeDataDir(options.name || 'run');
  const seedPath = path.join(dataDir, 'seed.json');
  fs.writeFileSync(seedPath, JSON.stringify(options.seed || {}));

  const env = {
    ...process.env,
    NODE_ENV: options.nodeEnv || 'production',
    PORT: '0',
    SUPABASE_URL: 'http://stub.invalid',
    SUPABASE_KEY: 'stub-key',
    JWT_SECRET: TEST_JWT_SECRET,
    BREEZE_TEST_SEED: seedPath,
    BREEZE_VERSIONS_DIR: path.join(dataDir, 'versions'),
    BREEZE_LAUNCHER_VERSIONS_DIR: path.join(dataDir, 'versions', 'launcher'),
    BREEZE_MOD_VERSIONS_DIR: path.join(dataDir, 'versions', 'mods'),
    // Keeps the flat mod data files (names.txt, dms.txt, ...) inside the temp
    // directory. Without this a test run writes into the repository copies.
    BREEZE_MOD_DATA_DIR: path.join(dataDir, 'data'),
    // Uploaded capes and cosmetics, for the same reason.
    BREEZE_STORAGE_DIR: path.join(dataDir, 'storage'),
    BREEZE_STATS_DIR: path.join(dataDir, 'stats'),
    BREEZE_ACTIVITY_SAMPLER: 'off',
    API_PUBLIC_BASE_URL: 'https://api.test.invalid',
    FRONTEND_URL: 'https://test.invalid',
    STARTUP_EMAIL_ENABLED: 'false',
    SMTP_HOST: '',
    SMTP_USER: '',
    SMTP_PASS: '',
    PAYPAL_CLIENT_ID: '',
    PAYPAL_CLIENT_SECRET: '',
    ...(options.env || {}),
  };

  const child = fork(path.join(__dirname, 'boot.cjs'), [], {
    env,
    cwd: API_ROOT,
    stdio: ['ignore', 'pipe', 'pipe', 'ipc'],
  });

  const logs = [];
  child.stdout.on('data', (b) => logs.push(b.toString()));
  child.stderr.on('data', (b) => logs.push(b.toString()));

  const port = await new Promise((resolve, reject) => {
    // Generous, because a developer machine running a Rust build at the same
    // time can take a while to get Node scheduled. A flaky timeout here reads
    // as a security test failure, which is the worst kind of false alarm.
    const timer = setTimeout(() => reject(new Error(`API did not start in time.\n${logs.join('')}`)), 60000);
    child.stdout.on('data', (buf) => {
      for (const line of buf.toString().split('\n')) {
        if (!line.trim().startsWith('{')) continue;
        try {
          const msg = JSON.parse(line);
          if (msg.ready && msg.port) {
            clearTimeout(timer);
            resolve(msg.port);
          }
        } catch {
          /* startup banner lines are not JSON */
        }
      }
    });
    child.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`API exited with code ${code} before listening.\n${logs.join('')}`));
    });
  });

  const base = `http://127.0.0.1:${port}`;

  async function request(method, routePath, { token, body, headers, redirect } = {}) {
    const res = await fetch(base + routePath, {
      method,
      // 'manual' lets a test see a redirect instead of following it off-box.
      redirect: redirect || 'follow',
      headers: {
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(headers || {}),
      },
      body: body === undefined ? undefined : typeof body === 'string' ? body : JSON.stringify(body),
    });
    const text = await res.text();
    let json = null;
    try {
      json = JSON.parse(text);
    } catch {
      /* not every response is JSON */
    }
    return { status: res.status, body: json, text, headers: res.headers };
  }

  return {
    base,
    dataDir,
    logs,
    token(claims, options = {}) {
      return jwt.sign(claims, TEST_JWT_SECRET, { expiresIn: options.expiresIn || '1h', ...options.signOptions });
    },
    expiredToken(claims) {
      return jwt.sign({ ...claims, exp: Math.floor(Date.now() / 1000) - 60 }, TEST_JWT_SECRET);
    },
    tokenSignedWith(secret, claims) {
      return jwt.sign(claims, secret, { expiresIn: '1h' });
    },
    get: (p, o) => request('GET', p, o),
    post: (p, o) => request('POST', p, o),
    patch: (p, o) => request('PATCH', p, o),
    delete: (p, o) => request('DELETE', p, o),
    async stop() {
      child.kill();
      await new Promise((r) => child.once('exit', r));
      fs.rmSync(dataDir, { recursive: true, force: true });
    },
  };
}

module.exports = { startApi, TEST_JWT_SECRET };
