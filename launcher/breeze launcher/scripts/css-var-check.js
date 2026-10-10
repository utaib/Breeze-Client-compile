/**
 * Fail the build on CSS custom properties that are used but never defined.
 *
 * An undefined var() with no fallback makes the whole declaration invalid, so a
 * background silently becomes transparent and text silently becomes black. It
 * looks like a layout bug, not a typo, which is why this is worth a check.
 *
 * A var() that supplies a fallback is fine and is skipped.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const CSS_DIR = path.join(__dirname, '..', 'src');

/**
 * Custom properties that never appear in a stylesheet because JSX sets them as
 * inline styles. Listing them here beats sprinkling fallbacks through the CSS.
 */
const RUNTIME_VARS = new Set(['--tx', '--ty', '--drift', '--rot']);

function collect(dir, ext, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) collect(p, ext, out);
    else if (ext.some((e) => entry.name.endsWith(e))) out.push(p);
  }
  return out;
}

// Only stylesheets that something actually imports can affect the app. An
// orphaned .css file is dead weight, and its unresolved vars are noise.
const sources = collect(CSS_DIR, ['.js', '.jsx', '.ts', '.tsx']);
const sourceText = sources.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
const allCss = collect(CSS_DIR, ['.css']);
const files = allCss.filter((f) => sourceText.includes(path.basename(f)));
const orphans = allCss.filter((f) => !files.includes(f));
const defined = new Set();
const used = new Map(); // name -> [{file, line}]

for (const file of files) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  lines.forEach((line, i) => {
    // Definitions: `--name:` at the start of a declaration.
    for (const m of line.matchAll(/(?:^\s*|[;{]\s*)(--[\w-]+)\s*:/g)) defined.add(m[1]);
    // Uses without a fallback: `var(--name)` with no comma before the paren.
    for (const m of line.matchAll(/var\(\s*(--[\w-]+)\s*\)/g)) {
      if (!used.has(m[1])) used.set(m[1], []);
      used.get(m[1]).push({ file: path.relative(CSS_DIR, file), line: i + 1 });
    }
  });
}

const missing = [...used.entries()].filter(([name]) => !defined.has(name) && !RUNTIME_VARS.has(name));

for (const o of orphans) {
  console.warn(`css-var-check: ${path.relative(CSS_DIR, o)} is not imported anywhere, so it was skipped.`);
}

if (missing.length === 0) {
  console.log(`css-var-check: ${defined.size} defined, ${used.size} used, 0 undefined.`);
  process.exit(0);
}

console.error(`css-var-check: ${missing.length} undefined CSS variable(s):\n`);
for (const [name, sites] of missing) {
  console.error(`  ${name}`);
  for (const s of sites) console.error(`    ${s.file}:${s.line}`);
}
console.error('\nEither define the variable or give var() a fallback.');
process.exit(1);
