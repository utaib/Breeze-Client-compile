#!/usr/bin/env node
'use strict';
/**
 * End-to-end check of the launcher update chain with a real installer.
 *
 *   API manifest -> /versions/check -> platform artifact -> download ->
 *   SHA-256 -> installer format -> saved file
 *
 * Starts this API (with the in-memory database stub the security tests use),
 * publishes the given installer the way a release does, asks /versions/check
 * for an update exactly as the launcher does, then runs the launcher's own
 * download-and-verify code (a Rust test) against that answer. Nothing is
 * installed.
 *
 * Usage:
 *   node scripts/updater-e2e.cjs --installer <path> --version 1.0.22 \
 *     [--platform windows|macos|linux] [--current 1.0.21] \
 *     [--channel pre-beta|beta|pre-release --role creator|developer|admin|owner] \
 *     [--launcher "../../breeze launcher/src-tauri"]
 *
 * With --channel the installer is published as a test build in that channel
 * (versions/launcher/testing/<channel>/<platform>/) instead of as a release,
 * and the check is made with the token of an account holding --role, as a
 * signed-in tester's launcher makes it. A normal user is then asked too, and
 * must be offered nothing.
 *
 * CARGO_TARGET_DIR is passed through if set.
 */

const { spawn } = require('child_process');
const crypto = require('crypto');
const fs = require('fs');
const os = require('os');
const path = require('path');
const { startApi } = require('../test/support/harness.cjs');

function arg(name, fallback) {
  const index = process.argv.indexOf(`--${name}`);
  return index >= 0 && process.argv[index + 1] ? process.argv[index + 1] : fallback;
}

const EXT = { windows: 'exe', macos: 'dmg', linux: 'AppImage' };

async function main() {
  const installer = arg('installer');
  const version = arg('version');
  const platform = arg('platform', process.platform === 'win32' ? 'windows' : process.platform === 'darwin' ? 'macos' : 'linux');
  const current = arg('current', '0.0.1');
  const channel = arg('channel', '');
  const role = arg('role', 'owner');
  const launcherDir = path.resolve(arg('launcher', path.join(__dirname, '..', '..', '..', 'breeze launcher', 'src-tauri')));
  if (!installer || !version) {
    console.error('Usage: node scripts/updater-e2e.cjs --installer <path> --version <x.y.z>');
    process.exit(2);
  }
  const bytes = fs.readFileSync(installer);
  const localSha = crypto.createHash('sha256').update(bytes).digest('hex');
  console.log(`installer ${path.basename(installer)}: ${bytes.length} bytes, sha256 ${localSha}`);

  const manifestDir = fs.mkdtempSync(path.join(os.tmpdir(), 'breeze-updater-e2e-'));
  const manifestPath = path.join(manifestDir, 'versions.json');
  fs.writeFileSync(manifestPath, JSON.stringify({ launcher: { latestVersion: version, changelog: 'e2e' } }));

  const TESTER = { uuid: 'e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e1', username: 'Tester', role };
  const PLAYER = { uuid: 'e9e9e9e9e9e9e9e9e9e9e9e9e9e9e9e2', username: 'Player', role: 'user' };
  const api = await startApi({
    name: 'updater-e2e',
    seed: { users: [TESTER, PLAYER] },
    env: {
      // Download URLs come from the request host, so they point at this API.
      API_PUBLIC_BASE_URL: '',
      PUBLIC_API_URL: '',
      BREEZE_VERSIONS_MANIFEST: manifestPath,
    },
  });

  let exitCode = 1;
  try {
    const folder = channel
      ? path.join(api.dataDir, 'versions', 'launcher', 'testing', channel, platform)
      : path.join(api.dataDir, 'versions', 'launcher', platform);
    const published = path.join(folder, `Breeze-Client-${version}.${EXT[platform]}`);
    fs.mkdirSync(path.dirname(published), { recursive: true });
    fs.copyFileSync(installer, published);

    const query = `/versions/check?current=${encodeURIComponent(current)}&platform=${platform}`;
    if (channel) {
      const player = await api.get(query, { token: api.token(PLAYER) });
      if (player.body?.update) throw new Error(`a normal user was offered the ${channel} build`);
      console.log(`a normal user is offered nothing (role ${player.body?.role})`);
    }
    const check = await api.get(query, channel ? { token: api.token(TESTER) } : undefined);
    if (check.status !== 200 || !check.body?.update) {
      throw new Error(`/versions/check offered no update (HTTP ${check.status}): ${check.text.slice(0, 300)}`);
    }
    const update = check.body.update;
    console.log('/versions/check ->', JSON.stringify({ version: update.version, url: update.url, fileName: update.fileName, sha256: update.sha256, size: update.size }));
    if (update.version !== version) throw new Error(`expected version ${version}, got ${update.version}`);
    if (update.sha256 !== localSha) throw new Error('the API advertised a hash that does not describe the published file');
    if (!update.url || !update.url.startsWith(api.base)) throw new Error(`unexpected download URL ${update.url}`);
    if (channel && (update.channel !== channel || !/[?&]t=/.test(update.url))) {
      throw new Error(`expected a signed ${channel} link, got ${update.channel} ${update.url}`);
    }

    // What the launcher's React code passes to download_and_install_update.
    const env = {
      ...process.env,
      BREEZE_UPDATE_URL: update.url,
      BREEZE_UPDATE_SHA256: update.sha256,
      BREEZE_UPDATE_FILE_NAME: update.fileName,
      BREEZE_UPDATE_ORIGINAL: path.resolve(installer),
    };
    exitCode = await new Promise((resolve) => {
      const child = spawn(
        'cargo',
        ['test', '--lib', 'updater_chain_tests::live_update_chain_from_the_api', '--', '--ignored', '--nocapture'],
        { cwd: launcherDir, env, stdio: 'inherit', shell: process.platform === 'win32' },
      );
      child.on('exit', (code) => resolve(code ?? 1));
    });
    console.log(exitCode === 0 ? 'UPDATE CHAIN VERIFIED' : `UPDATE CHAIN FAILED (cargo exit ${exitCode})`);
  } finally {
    await api.stop();
    fs.rmSync(manifestDir, { recursive: true, force: true });
  }
  process.exit(exitCode);
}

main().catch((error) => {
  console.error(error.message || error);
  process.exit(1);
});
