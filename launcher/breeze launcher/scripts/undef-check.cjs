/**
 * Fails the build on a name that is used but never defined or imported in the
 * launcher's JavaScript (.js/.jsx).
 *
 * TypeScript checks the .ts files; the .jsx files are only bundled, and a
 * bundler does not care whether a name exists. That is how a missing import
 * of listenToFileDrops shipped in main and crashed the launcher at startup on
 * every launch, with every test passing.
 *
 * This runs tsc with checkJs over the JavaScript and keeps only the "cannot
 * find name" family of errors. Everything else tsc says about untyped JSX is
 * noise and is ignored on purpose.
 */
const { spawnSync } = require('child_process');
const path = require('path');

const root = path.join(__dirname, '..');
const tsc = path.join(root, 'node_modules', 'typescript', 'bin', 'tsc');
const run = spawnSync(process.execPath, [tsc, '-p', 'tsconfig.undef.json', '--noEmit', '--pretty', 'false'], {
  cwd: root,
  encoding: 'utf8',
  maxBuffer: 64 * 1024 * 1024,
});
if (run.error) {
  console.error(`undef-check: could not run tsc: ${run.error.message}`);
  process.exit(1);
}
// 2304/2552: cannot find name; 18004: shorthand property with no value in
// scope; 2662/2663: cannot find name, did you mean the instance member.
const CODES = /error TS(2304|2552|18004|2662|2663):/;
const found = `${run.stdout}\n${run.stderr}`.split(/\r?\n/).filter((line) => CODES.test(line));
if (found.length) {
  console.error(`undef-check: ${found.length} name${found.length === 1 ? '' : 's'} used but never defined or imported:`);
  for (const line of found) console.error(`  ${line}`);
  process.exit(1);
}
console.log('undef-check: every name in src/**/*.js(x) is defined or imported.');
