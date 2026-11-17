#!/usr/bin/env node
/**
 * Fail the build when the launcher's version declarations disagree.
 *
 * Breeze carried four of them (package.json, tauri.conf.json, Cargo.toml,
 * Cargo.lock) plus a hand-written APP_VERSION constant in the React app. At
 * 1.0.13 they read 1.0.13, 1.0.12, 1.0.12, 1.0.12 and 1.0.10 respectively.
 *
 * The APP_VERSION one was the damaging drift: the update check sent it to the
 * API, so the launcher compared a dead 1.0.10 against the real latest and
 * reported an update available forever, including right after updating. That
 * constant is now injected from package.json by Vite, and this script keeps the
 * remaining four honest.
 *
 * Run: node scripts/version-sync-check.cjs
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

const sources = [];

sources.push({
  file: 'package.json',
  version: JSON.parse(read('package.json')).version,
});

sources.push({
  file: 'src-tauri/tauri.conf.json',
  version: JSON.parse(read('src-tauri/tauri.conf.json')).version,
});

// Cargo.toml: the version in [package], which is the first one in the file.
const cargoToml = read('src-tauri/Cargo.toml').match(/^version\s*=\s*"([^"]+)"/m);
sources.push({
  file: 'src-tauri/Cargo.toml',
  version: cargoToml ? cargoToml[1] : null,
});

// Cargo.lock: the breeze-launcher package entry specifically, not the first
// version key in a file that has hundreds of them.
const lock = read('src-tauri/Cargo.lock');
const lockEntry = lock.match(/name\s*=\s*"breeze-launcher"\s*\nversion\s*=\s*"([^"]+)"/);
sources.push({
  file: 'src-tauri/Cargo.lock',
  version: lockEntry ? lockEntry[1] : null,
});

// A literal APP_VERSION would silently win over the injected define, so treat
// its reappearance as a failure rather than trusting that nobody re-adds it.
const appJs = read('src/BreezeApp.jsx');
const literal = appJs.match(/const APP_VERSION\s*=\s*["']([^"']+)["']/);

let failed = false;
const fail = (msg) => { failed = true; console.error('version-sync: ' + msg); };

const missing = sources.filter((s) => !s.version);
for (const m of missing) fail(`could not read a version from ${m.file}`);

const found = sources.filter((s) => s.version);
const distinct = [...new Set(found.map((s) => s.version))];

if (distinct.length > 1) {
  fail('declarations disagree:');
  for (const s of found) console.error(`  ${s.version.padEnd(10)} ${s.file}`);
  console.error('  Set them all to the same value before building.');
}

if (literal) {
  fail(
    `src/BreezeApp.jsx declares APP_VERSION = "${literal[1]}" as a literal.\n` +
    '  It must stay `__APP_VERSION__`, which Vite injects from package.json.\n' +
    '  A literal here goes stale and breaks the update check.',
  );
}

if (!/const APP_VERSION\s*=\s*__APP_VERSION__/.test(appJs)) {
  fail('src/BreezeApp.jsx no longer reads APP_VERSION from the injected __APP_VERSION__.');
}

if (failed) process.exit(1);
console.log(`version-sync: ${distinct[0]} across ${found.length} declarations, APP_VERSION injected.`);
