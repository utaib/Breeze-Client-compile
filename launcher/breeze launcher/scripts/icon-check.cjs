#!/usr/bin/env node
/**
 * Fail the build when the UI renders an icon that does not exist.
 *
 * `I` is a plain object, so `<I.Transfer />` with no `Transfer` key is
 * `<undefined />`, and React throws "Element type is invalid" while rendering.
 * The page's error boundary then replaces the whole screen with "This page
 * failed", which is how the Mods page came to show that instead of the mod
 * list: the Transfer button shipped referring to an icon nobody had drawn.
 *
 * Nothing warned about it. A missing key is not a syntax error, TypeScript does
 * not check .jsx member access on an untyped object, and the crash only happens
 * on the render that reaches the button.
 *
 * Run: node scripts/icon-check.cjs
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const iconsFile = path.join(root, 'src', 'ui', 'icons.jsx');
const source = fs.readFileSync(iconsFile, 'utf8');

// Keys of the exported `I` object, which are written one per line.
const defined = new Set(
  [...source.matchAll(/^\s{2}([A-Za-z][A-Za-z0-9]*)\s*:/gm)].map((m) => m[1]),
);
if (defined.size === 0) {
  console.error('icon-check: no icons found in src/ui/icons.jsx; the file shape changed');
  process.exit(1);
}

function walk(dir, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'node_modules') continue;
      walk(full, out);
    } else if (/\.(jsx|tsx)$/.test(entry.name)) {
      out.push(full);
    }
  }
  return out;
}

/**
 * Several pages declare their own `const I = { … }` instead of importing the
 * shared set. Checking those against src/ui/icons.jsx proves nothing: the page
 * renders from its own object, so an icon that exists only in the shared set is
 * still `<undefined />`. That is exactly how the Mods page shipped rendering
 * `<I.Download />` against a local map with no Download in it, crashed with
 * React error #130, and showed "Mods could not load" while this check passed.
 *
 * So a file that shadows `I` is checked against the keys it actually has.
 */
function localIconSet(text) {
  const block = text.match(/^const I = \{([\s\S]*?)^\};/m);
  if (!block) return null;
  const keys = new Set([...block[1].matchAll(/^\s{2}([A-Za-z][A-Za-z0-9]*)\s*:/gm)].map((m) => m[1]));
  return keys.size ? keys : null;
}

const missing = [];
let used = 0;
let shadowed = 0;
for (const file of walk(path.join(root, 'src'))) {
  const text = fs.readFileSync(file, 'utf8');
  const local = localIconSet(text);
  if (local) shadowed += 1;
  const available = local || defined;
  const lines = text.split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const match of line.matchAll(/\bI\.([A-Za-z][A-Za-z0-9]*)/g)) {
      used += 1;
      const name = match[1];
      if (!available.has(name)) {
        const where = local ? 'its own icon set' : 'src/ui/icons.jsx';
        missing.push(`${path.relative(root, file)}:${index + 1}  I.${name} is not in ${where}`);
      }
    }
  });
}

if (missing.length) {
  console.error(`icon-check: ${missing.length} reference(s) to icons that do not exist:`);
  for (const line of missing) console.error(`  ${line}`);
  console.error('Add the icon to src/ui/icons.jsx, or use one that exists.');
  process.exit(1);
}
console.log(`icon-check: ${used} icon uses across ${shadowed} page-local icon sets and the shared one, all defined.`);
