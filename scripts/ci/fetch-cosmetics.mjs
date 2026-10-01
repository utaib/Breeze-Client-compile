#!/usr/bin/env node
// For the in-game test only: takes a few real 3D cosmetics from the Breeze
// API's public catalogue (GET /cosmetics, the list the website shows) so the
// test player can wear real creator models, not only the test's own cube.
// Read only: one catalogue request and one download per model. Up to four
// cosmetics, each in a different slot.
//
// Usage: node fetch-cosmetics.mjs <models-dir> [api-base]
//   writes <models-dir>/catalog.json ([{id, slot, name, metadata}]) and
//   <models-dir>/<id>.glb (a GLB or a self-contained .gltf). Any failure
//   leaves an empty catalog and exits 0.
import fs from 'node:fs'
import path from 'node:path'

const dir = path.resolve(process.argv[2] || 'real-models')
const base = (process.argv[3] || 'https://api.breezeclient.net').replace(/\/+$/, '')
const SLOTS = ['hat', 'wings', 'back', 'pet', 'shield', 'aura', 'trail', 'cape']
const MAX_BYTES = 30 * 1024 * 1024

fs.mkdirSync(dir, { recursive: true })
const out = []
const done = () => {
  fs.writeFileSync(path.join(dir, 'catalog.json'), JSON.stringify(out, null, 2))
  console.log(`[cosmetics] ${out.length} real cosmetics: ${out.map((c) => `${c.slot} "${c.name}" (${c.format})`).join(', ') || 'none'}`)
}

try {
  const res = await fetch(`${base}/cosmetics`, { signal: AbortSignal.timeout(15000) })
  if (!res.ok) throw new Error(`catalogue answered ${res.status}`)
  const list = (await res.json()).cosmetics || []
  // What the catalogue holds, so a model the game cannot read is visible here.
  console.log(`[cosmetics] catalogue: ${list.length} public cosmetics`)
  for (const c of list) {
    let where = 'no model_url'
    try {
      const u = new URL(c.model_url)
      where = `${u.protocol}//${u.host} ${path.extname(u.pathname) || '(no extension)'}`
    } catch { /* not a URL */ }
    console.log(`[cosmetics]   ${c.slot} "${c.name}": ${where}, attachment ${c.metadata?.attachment ?? 'none'}`)
  }
  for (const slot of SLOTS) {
    if (out.length >= 4) break
    const c = list.find((x) => x.slot === slot && typeof x.model_url === 'string' && x.model_url.startsWith('https://'))
    if (!c) continue
    try {
      const m = await fetch(c.model_url, { signal: AbortSignal.timeout(30000) })
      if (!m.ok) throw new Error(`model answered ${m.status}`)
      const bytes = Buffer.from(await m.arrayBuffer())
      if (bytes.length > MAX_BYTES) throw new Error(`model is ${bytes.length} bytes`)
      // A GLB, or a self-contained .gltf (JSON), which the game reads too.
      const format = bytes.toString('latin1', 0, 4) === 'glTF' ? 'glb'
        : /^﻿?\s*\{/.test(bytes.toString('utf8', 0, 64)) ? 'gltf' : null
      if (!format) {
        const head = bytes.toString('latin1', 0, 24).replace(/[^\x20-\x7e]/g, '.')
        throw new Error(`not a GLB or glTF: ${m.headers.get('content-type')}, ${bytes.length} bytes, starts "${head}"`)
      }
      // Saved under one name either way; the game tells them apart by content.
      fs.writeFileSync(path.join(dir, `${c.id}.glb`), bytes)
      out.push({ id: String(c.id), slot: c.slot, name: c.name || String(c.id), format, metadata: c.metadata || {} })
    } catch (e) {
      console.log(`[cosmetics] skipped ${slot} "${c.name}": ${e.message}`)
    }
  }
} catch (e) {
  console.log(`[cosmetics] catalogue not read: ${e.message}`)
}
done()
