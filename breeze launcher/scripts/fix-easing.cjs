// One-shot: give every CSS transition an explicit timing function.
//
// A `transition` with a duration but no timing function falls back to the CSS
// default `ease`, which is NOT the house `--ease`. That left two visibly
// different curves running side by side across the app. Measured before the
// fix: 24 declarations on cubic-bezier(0.23,1,0.32,1), 41 on the browser
// default.
//
// Deliberately conservative: a part that already names a curve (including
// `linear`, which progress bars want) is left exactly as it was.
const fs = require('fs');

const FILE = process.argv[2];
const APPLY = process.argv.includes('--apply');
let css = fs.readFileSync(FILE, 'utf8');

const HAS_TIMING = /\b(linear|ease-in-out|ease-out|ease-in|ease|step-start|step-end|steps\(|cubic-bezier\()|var\(--(ease|spring|smooth)\)/;
const HAS_DURATION = /(\d*\.?\d+\s*m?s)|var\(--dur-/;

/** Split on commas that are not inside parentheses. */
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

let touched = 0, skipped = 0;
const changes = [];

css = css.replace(/transition:\s*([^;}]+)/g, (whole, value) => {
  const parts = splitParts(value);
  let changedHere = false;
  const fixed = parts.map((p) => {
    const t = p.trim();
    if (!t) return p;
    if (!HAS_DURATION.test(t)) { return p; }        // e.g. `transition:none`
    if (HAS_TIMING.test(t)) { skipped++; return p; } // already explicit
    changedHere = true;
    touched++;
    return `${p.replace(/\s+$/, '')} var(--ease)`;
  });
  if (!changedHere) return whole;
  changes.push(value.trim().slice(0, 70));
  return `transition:${fixed.join(',')}`;
});

console.log(`parts given var(--ease) : ${touched}`);
console.log(`parts already explicit  : ${skipped}`);
console.log(`declarations changed    : ${changes.length}`);
if (!APPLY) {
  console.log('\nDRY RUN. Sample of what would change:');
  changes.slice(0, 8).forEach((c) => console.log('  ' + c));
  console.log('\nRe-run with --apply to write.');
} else {
  fs.writeFileSync(FILE, css);
  console.log('\nWritten.');
}
