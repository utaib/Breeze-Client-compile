/**
 * Pre-flight check for a release. Run this before restarting the API.
 *
 * It answers the three questions that have each caused a real failure:
 *   - Does the file the manifest advertises actually exist? (a wrong name is an
 *     invisible file, and the updater then hands users a 404 page)
 *   - Does its hash match what the manifest claims? (the manifest once
 *     advertised 1.0.9 while serving 1.0.10's hash, so every update failed
 *     verification)
 *   - Do the env override and versions.js agree on the version?
 *
 * Usage:  node scripts/verify-release.cjs
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const API_ROOT = path.join(__dirname, '..');
try { require('dotenv').config({ path: path.join(API_ROOT, 'env') }); } catch { /* optional */ }

const mod = require(path.join(API_ROOT, 'versions', 'versions.js'));
const manifest = typeof mod === 'function' ? mod() : mod;
const launcher = manifest.launcher || manifest;
const version = launcher.latestVersion || launcher.version;

const problems = [];
const notes = [];

console.log(`Release check for ${version}\n`);

// The env override is the one that silently wins over versions.js.
const envVersion = process.env.LATEST_LAUNCHER_VERSION;
if (envVersion && envVersion !== version) {
  problems.push(`env LATEST_LAUNCHER_VERSION is ${envVersion} but the manifest resolved to ${version}`);
} else if (envVersion) {
  console.log(`  env LATEST_LAUNCHER_VERSION agrees: ${envVersion}`);
}

for (const [os, plat] of Object.entries(launcher.platforms || {})) {
  if (!plat || !plat.file) { notes.push(`${os}: no file declared`); continue; }
  const abs = path.join(API_ROOT, 'versions', 'launcher', plat.file);
  const exists = fs.existsSync(abs);

  if (!exists) {
    // Not every OS is built every cycle, so "no file" is normal for a platform
    // nobody has published yet. It is a genuine error once a sha256 has been
    // recorded, because that means someone published it and the file has since
    // been renamed, moved or lost. `available` is computed per request by the
    // server and is not present here, so it cannot be used for this.
    const claimed = Boolean(plat.sha256) || plat.available === true;
    (claimed ? problems : notes).push(
      `${os}: ${plat.file} does not exist${claimed ? ' but a sha256 is recorded for it' : ' (not published yet)'}`,
    );
    continue;
  }

  const size = fs.statSync(abs).size;
  const real = crypto.createHash('sha256').update(fs.readFileSync(abs)).digest('hex');
  const nameHasVersion = plat.file.includes(version);

  let line = `  ${os.padEnd(8)} ${plat.file}  ${(size / 1048576).toFixed(1)} MB`;
  if (!nameHasVersion) problems.push(`${os}: filename does not contain ${version}`);
  if (!plat.sha256) notes.push(`${os}: no sha256 recorded, download verification is off`);
  else if (plat.sha256 !== real) problems.push(`${os}: sha256 mismatch\n      manifest ${plat.sha256}\n      actual   ${real}`);
  else line += '  hash ok';
  console.log(line);

  // A 404 page is a few hundred bytes; a real installer is megabytes.
  if (size < 512 * 1024) problems.push(`${os}: ${plat.file} is only ${size} bytes, that is not an installer`);
}

if (notes.length) {
  console.log('\nNotes:');
  notes.forEach((n) => console.log(`  - ${n}`));
}

if (problems.length) {
  console.error(`\n${problems.length} problem(s):`);
  problems.forEach((p) => console.error(`  ! ${p}`));
  process.exit(1);
}

console.log('\nRelease looks consistent. Safe to restart the API.');
