/**
 * Fail the build on CSS transitions that leave their timing function implicit.
 *
 * A `transition` with a duration but no timing function silently falls back to
 * the CSS default `ease`, which is not the house `--ease`. Nothing errors and
 * nothing logs; the app just ends up running two different curves side by side,
 * which is precisely what makes an interface feel inconsistent rather than
 * broken.
 *
 * A part that names its own curve is fine, including `linear`, which is what a
 * progress bar actually wants.
 */
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CSS_DIR = path.join(__dirname, '..', 'src');

const HAS_TIMING = /\b(linear|ease-in-out|ease-out|ease-in|ease|step-start|step-end|steps\(|cubic-bezier\()|var\(--(ease|spring|smooth)\)/;
const HAS_DURATION = /(\d*\.?\d+\s*m?s)|var\(--dur-/;
// Durations belong to the scale. A one-off number is how the scale erodes.
const RAW_DURATION = /(?<!var\(--dur-[\w-]{0,20})\b\d*\.?\d+\s*m?s\b/;

function collect(dir, ext, out = []) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) collect(p, ext, out);
    else if (ext.some((e) => entry.name.endsWith(e))) out.push(p);
  }
  return out;
}

function splitParts(value) {
  const parts = [];
  let depth = 0, cur = '';
  for (const ch of value) {
    if (ch === '(') depth++;
    if (ch === ')') depth--;
    if (ch === ',' && depth === 0) { parts.push(cur); cur = ''; continue; }
    cur += ch;
  }
  if (cur.trim()) parts.push(cur);
  return parts;
}

const sources = collect(CSS_DIR, ['.js', '.jsx', '.ts', '.tsx']);
const sourceText = sources.map((f) => fs.readFileSync(f, 'utf8')).join('\n');
const files = collect(CSS_DIR, ['.css']).filter((f) => sourceText.includes(path.basename(f)));

const problems = [];
let checked = 0;
let exempted = 0;

for (const file of files) {
  const text = fs.readFileSync(file, 'utf8');
  const lines = text.split('\n');
  const lineOf = (idx) => text.slice(0, idx).split('\n').length;
  for (const m of text.matchAll(/transition:\s*([^;}]+)/g)) {
    // An explicit `motion-ok` comment on the line documents a deliberate
    // exception, such as a progress bar whose duration tracks how often it
    // actually receives updates. Marked and reviewable beats silently skipped.
    if (/motion-ok/.test(lines[lineOf(m.index) - 1] || '')) { exempted++; continue; }
    for (const part of splitParts(m[1])) {
      const t = part.trim();
      if (!t || !HAS_DURATION.test(t)) continue;
      checked++;
      if (!HAS_TIMING.test(t)) {
        problems.push({ file: path.relative(CSS_DIR, file), line: lineOf(m.index), part: t, why: 'no timing function, falls back to the browser default' });
      } else if (RAW_DURATION.test(t)) {
        problems.push({ file: path.relative(CSS_DIR, file), line: lineOf(m.index), part: t, why: 'hardcoded duration, use --dur-fast/med/slow' });
      }
    }
  }
}

if (problems.length === 0) {
  console.log(`css-motion-check: ${checked} transition parts explicit, ${exempted} marked motion-ok.`);
  process.exit(0);
}

console.error(`css-motion-check: ${problems.length} of ${checked} transition parts need attention:\n`);
for (const p of problems) console.error(`  ${p.file}:${p.line}  ${p.part}\n    ${p.why}`);
console.error('\nRun: node scripts/fix-easing.cjs src/BreezeV2.css --apply');
process.exit(1);
