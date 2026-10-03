#!/usr/bin/env node
// A stand-in for the Breeze API, for the in-game test only. It answers the
// cosmetics routes the way docs/COSMETICS.md describes them, keeps what the
// test player equips in memory, and serves one test model, so the game can be
// driven through equipping a 3D cosmetic and drawing it without a real
// account. It is not the API: it holds no data, no secrets and no API code.
//
// Usage: node stub-api.mjs <dir> [real-models-dir]
//   writes <dir>/stub-port when listening; serves <dir>/stub-model.glb (the
//   self-test writes it) as the test cosmetics' model; logs each request to
//   <dir>/stub-api.log. With a real-models dir (fetch-cosmetics.mjs), the
//   real cosmetics listed in its catalog.json are owned too, with their own
//   models and metadata.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import zlib from 'node:zlib'

const dir = path.resolve(process.argv[2] || '.')
const logFile = path.join(dir, 'stub-api.log')
const log = (line) => fs.appendFileSync(logFile, `${new Date().toISOString()} ${line}\n`)

// What the test player owns. One per slot can be worn, as in the API.
const OWNED = [
  { id: 'stub-hat', slot: 'hat', name: 'Test hat', attachment: 'HEAD' },
  { id: 'stub-pet', slot: 'pet', name: 'Test pet', attachment: 'FLYING_PET' },
]
// Real cosmetics from the public catalogue, if fetched: owned with their own
// models and the creator's metadata (attachment, placement, animation roles).
const realDir = process.argv[3] ? path.resolve(process.argv[3]) : null
const REAL = []
if (realDir && fs.existsSync(path.join(realDir, 'catalog.json'))) {
  for (const c of JSON.parse(fs.readFileSync(path.join(realDir, 'catalog.json'), 'utf8'))) {
    REAL.push({ id: `real-${c.id}`, realId: c.id, slot: c.slot, name: c.name, metadata: c.metadata || {} })
  }
}
const ALL = () => [...OWNED, ...REAL]
const equipped = new Map() // uuid -> Map(slot -> id)

// The account's equipped cape, served the way the live API serves it on the
// routes the mod falls back to: /selected answers its id, /capefile/<id> its
// image. /cosmetics/state below answers "no account", which is what the live
// API answered for every player until its uuid and column fix (2.9.5), so the
// self-test proves the cape still reaches Minecraft that way.
const ACCOUNT_CAPE = 'c0ffee00-0000-4000-8000-00000000cafe'
// Then the self-test switches the account to a personal cape (an upload, which
// the live API keeps in users.cape_url): /selected answers "personal" and the
// image is served on /cape/<uuid>, as the live API does.
let selfCape = ACCOUNT_CAPE

// A 64x32 cape texture in one colour, as a PNG, without image libraries.
const CRC = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buf) => {
  let c = 0xffffffff
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
function capePng(rgba) {
  const w = 64, h = 32
  const raw = Buffer.alloc((w * 4 + 1) * h)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) raw.set(rgba, y * (w * 4 + 1) + 1 + x * 4)
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length)
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data])
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td))
    return Buffer.concat([len, td, crc])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ])
}
const CAPE_PNG = capePng([0x3a, 0xc8, 0xd0, 0xff])
const PERSONAL_PNG = capePng([0xd8, 0x6a, 0x2f, 0xff])

const dash = (u) => {
  const s = String(u || '').toLowerCase().replace(/-/g, '')
  return /^[0-9a-f]{32}$/.test(s) ? `${s.slice(0, 8)}-${s.slice(8, 12)}-${s.slice(12, 16)}-${s.slice(16, 20)}-${s.slice(20)}` : null
}

// The game token's uuid claim. The stub reads it unverified; the real API
// verifies the signature. What matters here is that the mod sends it.
function tokenUuid(req) {
  const m = /^Bearer\s+(\S+)$/i.exec(req.headers.authorization || '')
  if (!m) return null
  try {
    const payload = JSON.parse(Buffer.from(m[1].split('.')[1], 'base64url').toString('utf8'))
    return dash(payload.uuid)
  } catch {
    return null
  }
}

function send(res, status, body) {
  const text = JSON.stringify(body)
  res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(text) })
  res.end(text)
}

async function readJson(req) {
  let text = ''
  for await (const chunk of req) text += chunk
  try {
    return JSON.parse(text || '{}')
  } catch {
    return {}
  }
}

const slotsOf = (uuid) => {
  if (!equipped.has(uuid)) equipped.set(uuid, new Map())
  return equipped.get(uuid)
}

const server = http.createServer(async (req, res) => {
  const base = `http://${req.headers.host}`
  const url = new URL(req.url, base)
  const parts = url.pathname.split('/').filter(Boolean)
  log(`${req.method} ${url.pathname}`)

  if (req.method === 'POST' && parts[0] === 'test' && parts[1] === 'personal-cape') {
    selfCape = 'personal'
    log('the account now wears a personal cape')
    return send(res, 200, { success: true })
  }
  if (req.method === 'GET' && parts[0] === 'selected' && parts[1]) {
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    return res.end(selfCape)
  }
  if (req.method === 'GET' && parts[0] === 'capefile' && parts[1] === ACCOUNT_CAPE) {
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': CAPE_PNG.length })
    return res.end(CAPE_PNG)
  }
  if (req.method === 'GET' && parts[0] === 'cape' && parts[1]) {
    if (selfCape !== 'personal') return send(res, 404, { success: false, error: 'no cape' })
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': PERSONAL_PNG.length })
    return res.end(PERSONAL_PNG)
  }

  // A player who has no Breeze account, as the API answers for one.
  if (req.method === 'GET' && parts[0] === 'cosmetics' && parts[1] === 'state' && parts[2]) {
    return send(res, 200, {
      success: true, uuid: dash(parts[2]), username: null, role: 'user', cape: null, ownedCapes: [], tag: null,
      availableTags: [], badge: null, customTag: null, canCustomTag: false, revision: '0',
    })
  }

  if (req.method === 'GET' && parts[0] === 'cosmetics' && parts[1] === 'equipped' && parts[2]) {
    const uuid = dash(parts[2])
    const rows = [...slotsOf(uuid).entries()].map(([slot, id]) => {
      const c = ALL().find((o) => o.id === id)
      const metadata = c.metadata || { attachment: c.attachment }
      return {
        slot, cosmetic_id: id, placement: null,
        cosmetic: { id, slot, name: c.name, model_url: `${base}/models/${id}.glb`, metadata },
      }
    })
    return send(res, 200, { success: true, uuid, equipped: rows })
  }

  if (req.method === 'GET' && parts[0] === 'models' && parts[1]) {
    const real = REAL.find((r) => `${r.id}.glb` === parts[1])
    const file = real ? path.join(realDir, `${real.realId}.glb`) : path.join(dir, 'stub-model.glb')
    if (!fs.existsSync(file)) return send(res, 404, { success: false, error: 'no model yet' })
    const bytes = fs.readFileSync(file)
    res.writeHead(200, { 'Content-Type': 'model/gltf-binary', 'Content-Length': bytes.length })
    return res.end(bytes)
  }

  // The three in-game routes: a token for exactly the player in the path.
  if (parts[0] === 'cosmetics' && ['owned', 'equip', 'unequip'].includes(parts[1]) && parts[2]) {
    const uuid = dash(parts[2])
    const claimed = tokenUuid(req)
    if (!req.headers.authorization) return send(res, 401, { success: false, error: 'Authentication required' })
    if (!claimed || claimed !== uuid) return send(res, 403, { success: false, error: 'This token cannot act for that player' })
    const slots = slotsOf(uuid)
    if (req.method === 'GET' && parts[1] === 'owned') {
      return send(res, 200, {
        success: true,
        owned: ALL().map((o) => ({ cosmetic_id: o.id, cosmetic: { id: o.id, slot: o.slot, name: o.name } })),
        equipped: Object.fromEntries(slots),
        placements: {},
      })
    }
    const body = await readJson(req)
    if (req.method === 'POST' && parts[1] === 'equip') {
      const c = ALL().find((o) => o.id === body.cosmetic_id)
      if (!body.cosmetic_id) return send(res, 400, { success: false, error: 'cosmetic_id is required' })
      if (!c) return send(res, 403, { success: false, error: 'You do not own this cosmetic' })
      slots.set(c.slot, c.id)
      log(`equipped ${c.slot}=${c.id} for ${uuid}`)
      return send(res, 200, { success: true, slot: c.slot, cosmetic_id: c.id })
    }
    if (req.method === 'POST' && parts[1] === 'unequip') {
      if (!ALL().some((o) => o.slot === body.slot)) return send(res, 400, { success: false, error: 'Invalid slot' })
      slots.delete(body.slot)
      log(`unequipped ${body.slot} for ${uuid}`)
      return send(res, 200, { success: true, slot: body.slot })
    }
  }

  return send(res, 404, { success: false, error: 'Not found' })
})

server.listen(0, '127.0.0.1', () => {
  const { port } = server.address()
  fs.writeFileSync(path.join(dir, 'stub-port'), String(port))
  log(`listening on ${port}`)
})
