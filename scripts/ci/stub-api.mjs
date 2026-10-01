#!/usr/bin/env node
// A stand-in for the Breeze API, for the in-game test only. It answers the
// cosmetics routes the way docs/COSMETICS.md describes them, keeps what the
// test player equips in memory, and serves one test model, so the game can be
// driven through equipping a 3D cosmetic and drawing it without a real
// account. It is not the API: it holds no data, no secrets and no API code.
//
// Usage: node stub-api.mjs <dir>
//   writes <dir>/stub-port when listening; serves <dir>/stub-model.glb (the
//   self-test writes it) as every cosmetic's model; logs each request to
//   <dir>/stub-api.log.
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'

const dir = path.resolve(process.argv[2] || '.')
const logFile = path.join(dir, 'stub-api.log')
const log = (line) => fs.appendFileSync(logFile, `${new Date().toISOString()} ${line}\n`)

// What the test player owns. One per slot can be worn, as in the API.
const OWNED = [
  { id: 'stub-hat', slot: 'hat', name: 'Test hat', attachment: 'HEAD' },
  { id: 'stub-pet', slot: 'pet', name: 'Test pet', attachment: 'FLYING_PET' },
]
const equipped = new Map() // uuid -> Map(slot -> id)

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
      const c = OWNED.find((o) => o.id === id)
      return {
        slot, cosmetic_id: id, placement: null,
        cosmetic: { id, slot, name: c.name, model_url: `${base}/models/${id}.glb`, metadata: { attachment: c.attachment } },
      }
    })
    return send(res, 200, { success: true, uuid, equipped: rows })
  }

  if (req.method === 'GET' && parts[0] === 'models' && parts[1]) {
    const file = path.join(dir, 'stub-model.glb')
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
        owned: OWNED.map((o) => ({ cosmetic_id: o.id, cosmetic: { id: o.id, slot: o.slot, name: o.name } })),
        equipped: Object.fromEntries(slots),
        placements: {},
      })
    }
    const body = await readJson(req)
    if (req.method === 'POST' && parts[1] === 'equip') {
      const c = OWNED.find((o) => o.id === body.cosmetic_id)
      if (!body.cosmetic_id) return send(res, 400, { success: false, error: 'cosmetic_id is required' })
      if (!c) return send(res, 403, { success: false, error: 'You do not own this cosmetic' })
      slots.set(c.slot, c.id)
      log(`equipped ${c.slot}=${c.id} for ${uuid}`)
      return send(res, 200, { success: true, slot: c.slot, cosmetic_id: c.id })
    }
    if (req.method === 'POST' && parts[1] === 'unequip') {
      if (!OWNED.some((o) => o.slot === body.slot)) return send(res, 400, { success: false, error: 'Invalid slot' })
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
