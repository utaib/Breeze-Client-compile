// Checks the built bundle for the ways it can break inside Minecraft while
// still working in a desktop browser. Runs after every `npm run build`.
//
// - every file index.html references exists, and every path is relative
// - every file name is lowercase (MCEF's mod:// fallback lowercases paths)
// - nothing reaches out to the network (no http(s) URLs in code or CSS,
//   no Google Fonts), because the page must work offline in game
// - the production CSP is present and forbids network access
// - the preview fixture is a separate chunk the entry never imports statically
// - total size stays inside a budget, so the jar and first paint stay small
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

// fileURLToPath, not .pathname: the URL form keeps spaces as %20 and puts a
// slash before a Windows drive letter, so any such path would not be found.
const dist = fileURLToPath(new URL('../dist/', import.meta.url))
const failures = []
const fail = (msg) => failures.push(msg)

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? walk(p) : [p]
  })
}

const files = walk(dist).map((p) => relative(dist, p))
const html = readFileSync(join(dist, 'index.html'), 'utf8')

for (const f of files) {
  if (f !== f.toLowerCase()) fail(`not lowercase: ${f}`)
}

for (const [, ref] of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
  if (ref.startsWith('data:')) continue // inline, loads nothing
  if (!ref.startsWith('./')) fail(`index.html reference is not relative: ${ref}`)
  else if (!files.includes(ref.slice(2))) fail(`index.html references a missing file: ${ref}`)
}

if (!html.includes('Content-Security-Policy') || !html.includes("connect-src 'none'")) {
  fail('production CSP missing or allows network access')
}

const code = files.filter((f) => /\.(js|css)$/.test(f))
for (const f of code) {
  const text = readFileSync(join(dist, f), 'utf8')
  if (/fonts\.googleapis|fonts\.gstatic/.test(text)) fail(`${f} loads remote fonts`)
  // React and friends mention URLs in comments and error text; a real fetch
  // target is a quoted http(s) URL outside the known documentation hosts.
  for (const [url] of text.matchAll(/https?:\/\/[^\s"'`)]+/g)) {
    if (/^https?:\/\/(react\.dev|reactjs\.org|fb\.me|www\.w3\.org|breezeclient\.net)/.test(url)) continue
    fail(`${f} contains a network URL: ${url}`)
  }
}

const entry = readFileSync(join(dist, 'assets/breeze.js'), 'utf8')
if (!files.some((f) => /^assets\/fixture.*\.js$/.test(f))) fail('preview fixture is not a separate chunk')
if (/PreviewFriendOne|Preview cape/.test(entry)) fail('preview data leaked into the entry chunk')

const total = files.filter((f) => !f.endsWith('.map')).reduce((n, f) => n + statSync(join(dist, f)).size, 0)
const BUDGET = 1_200_000
if (total > BUDGET) fail(`bundle is ${total} bytes, over the ${BUDGET} byte budget`)

if (failures.length) {
  console.error(`check-bundle: ${failures.length} problem(s)`)
  for (const f of failures) console.error(`  - ${f}`)
  process.exit(1)
}
console.log(`check-bundle: ok, ${files.length} files, ${(total / 1024).toFixed(0)} KiB without source maps`)
