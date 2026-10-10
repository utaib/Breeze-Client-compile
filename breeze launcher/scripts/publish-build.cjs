/**
 * Copy a freshly built installer into the API's versions tree under the exact
 * name discovery expects, and print its SHA-256.
 *
 * This exists because the naming mismatch is a real, repeated failure: Tauri
 * emits "Breeze Client_1.0.11_x64-setup.exe" but the API only finds
 * "Breeze-Client-1.0.11.exe". A renamed file is an invisible file, and the
 * symptom is the updater handing users a 404 page instead of an installer
 * ("Downloaded update looks incomplete"). Renaming by hand is exactly the step
 * that gets forgotten.
 *
 * Usage:
 *   node scripts/publish-build.cjs              copy the Windows build
 *   node scripts/publish-build.cjs --dry-run    show what it would do
 *   node scripts/publish-build.cjs --channel beta
 */
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.join(__dirname, '..');
const API_VERSIONS = path.join(ROOT, '..', 'breeze-api', 'breeze-api', 'versions', 'launcher');
const DRY = process.argv.includes('--dry-run');
const channelArg = process.argv.indexOf('--channel');
const CHANNEL = channelArg > -1 ? process.argv[channelArg + 1] : null;

const version = JSON.parse(fs.readFileSync(path.join(ROOT, 'package.json'), 'utf8')).version;

// Where each platform's bundler leaves its output, and where it has to land.
// Linux nests by format; Windows and macOS do not.
const BUNDLES = [
  { dir: 'nsis',    match: /\.exe$/i,      dest: 'windows',         ext: 'exe' },
  { dir: 'dmg',     match: /\.dmg$/i,      dest: 'macos',           ext: 'dmg' },
  { dir: 'appimage',match: /\.AppImage$/i, dest: 'linux/appimage',  ext: 'AppImage' },
  { dir: 'deb',     match: /\.deb$/i,      dest: 'linux/deb',       ext: 'deb' },
  { dir: 'rpm',     match: /\.rpm$/i,      dest: 'linux/rpm',       ext: 'rpm' },
];

const bundleRoot = path.join(ROOT, 'src-tauri', 'target', 'release', 'bundle');
if (!fs.existsSync(bundleRoot)) {
  console.error(`No bundle folder at ${bundleRoot}. Run "npm run tauri build" first.`);
  process.exit(1);
}

let published = 0;
for (const b of BUNDLES) {
  const from = path.join(bundleRoot, b.dir);
  if (!fs.existsSync(from)) continue;
  // The bundle folder accumulates every build ever made, so picking "the first
  // match" would happily publish a year-old binary under today's version
  // number. Require the filename to contain the version being published.
  const candidates = fs.readdirSync(from).filter((f) => b.match.test(f));
  if (!candidates.length) continue;
  const exact = candidates.filter((f) => f.includes(`_${version}_`) || f.includes(`-${version}.`) || f.includes(`_${version}-`));
  if (!exact.length) {
    console.error(`${b.dest}: no ${b.ext} built for ${version}. Found: ${candidates.join(', ')}`);
    console.error(`  Run "npm run tauri build" after bumping the version, then retry.`);
    process.exitCode = 1;
    continue;
  }
  if (exact.length > 1) {
    console.error(`${b.dest}: ${exact.length} files match ${version}: ${exact.join(', ')}`);
    console.error('  Refusing to guess. Remove the stale one.');
    process.exitCode = 1;
    continue;
  }
  const file = exact[0];

  const target = CHANNEL
    ? path.join(API_VERSIONS, 'testing', CHANNEL, b.dest)
    : path.join(API_VERSIONS, b.dest);
  // The name discovery depends on. Do not "improve" this.
  const name = `Breeze-Client-${version}.${b.ext}`;
  const src = path.join(from, file);
  const dst = path.join(target, name);

  const buf = fs.readFileSync(src);
  const sha = crypto.createHash('sha256').update(buf).digest('hex');

  console.log(`${b.dest}`);
  console.log(`  from   ${file}`);
  console.log(`  to     ${path.relative(ROOT, dst)}`);
  console.log(`  size   ${(buf.length / 1048576).toFixed(1)} MB`);
  console.log(`  sha256 ${sha}`);

  if (!DRY) {
    fs.mkdirSync(target, { recursive: true });
    fs.writeFileSync(dst, buf);
  }
  published++;
}

if (!published) {
  console.error('No installers found to publish.');
  process.exit(1);
}
console.log(`\n${published} installer(s) ${DRY ? 'would be' : ''} published for ${version}.`);
if (!CHANNEL) console.log('Paste the sha256 values into versions/versions.js, then restart the API.');
if (DRY) console.log('DRY RUN. Nothing was written.');
