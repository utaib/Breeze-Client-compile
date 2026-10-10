'use strict';
/**
 * Cosmetic model pipeline (v1.0.22).
 *
 * The bug this guards: an uploaded model showed up untextured. A .gltf was
 * stored alone, so the textures it pointed at never reached the server, and
 * nothing checked that a texture existed at all. Every upload is now turned
 * into one self-contained GLB and checked on the way in. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { NodeIO } = require('@gltf-transform/core');
const fx = require('./support/cosmeticFixtures.cjs');
const {
  prepareCosmeticModel, discoverAnimationRoles, editCosmeticMetadata, sanitizeTransform, CosmeticAssetError,
} = require('../src/cosmeticAsset');
const { startApi } = require('./support/harness.cjs');

/** Read a finished GLB back and report what a renderer would find in it. */
async function inspectGlb(bytes) {
  const doc = await new NodeIO().readBinary(new Uint8Array(bytes));
  const root = doc.getRoot();
  return {
    buffers: root.listBuffers().length,
    textures: root.listTextures().map((t) => ({ uri: t.getURI(), bytes: t.getImage()?.byteLength || 0 })),
    clips: root.listAnimations().map((a) => a.getName()),
  };
}

async function refuses(promise, pattern) {
  await assert.rejects(promise, (err) => err instanceof CosmeticAssetError && pattern.test(err.message));
}

test('a .gltf with its files beside it becomes one GLB with the texture inside', async () => {
  const files = await fx.split();
  const out = await prepareCosmeticModel(Object.entries(files).map(([n, b]) => fx.file(n, b)), { slot: 'pet' });
  const glb = await inspectGlb(out.glb);
  assert.equal(glb.buffers, 1);
  assert.equal(glb.textures.length, 1);
  assert.ok(glb.textures[0].bytes > 0, 'the texture must be embedded');
  assert.equal(glb.textures[0].uri, '', 'and must not point at a file that is not there');
  assert.equal(out.metadata.stats.source, 'gltf');
});

test('a .zip package works, folders and all', async () => {
  const out = await prepareCosmeticModel([fx.file('pet.zip', await fx.zip())], { slot: 'hat' });
  const glb = await inspectGlb(out.glb);
  assert.ok(glb.textures[0].bytes > 0);
  assert.equal(out.metadata.attachment, 'HEAD');
  assert.equal(out.metadata.stats.source, 'zip');
});

test('a .gltf on its own is refused, naming what is missing', async () => {
  const { 'pet.gltf': gltf } = await fx.split();
  await refuses(prepareCosmeticModel([fx.file('pet.gltf', gltf)], { slot: 'pet' }), /pet\.bin.*skin\.png|skin\.png.*pet\.bin/);
});

test('a texture name that differs only in case is still found', async () => {
  const files = await fx.split();
  const upload = [fx.file('pet.gltf', files['pet.gltf']), fx.file('PET.BIN', files['pet.bin']), fx.file('Skin.PNG', files['textures/skin.png'])];
  const out = await prepareCosmeticModel(upload, { slot: 'pet' });
  assert.equal(out.metadata.stats.textures.length, 1);
});

test('files are only ever read from inside the upload', async () => {
  const files = await fx.split();
  const json = JSON.parse(files['pet.gltf']);
  json.images[0].uri = '../../server.js';
  await refuses(prepareCosmeticModel([fx.file('pet.gltf', Buffer.from(JSON.stringify(json))), fx.file('pet.bin', files['pet.bin'])], { slot: 'pet' }), /outside its folder/);
  json.images[0].uri = 'https://example.com/skin.png';
  await refuses(prepareCosmeticModel([fx.file('pet.gltf', Buffer.from(JSON.stringify(json))), fx.file('pet.bin', files['pet.bin'])], { slot: 'pet' }), /must be included in the upload/);
});

test('formats the launcher cannot draw are refused with the reason', async () => {
  const files = await fx.split();
  const json = JSON.parse(files['pet.gltf']);
  json.extensionsUsed = ['KHR_draco_mesh_compression'];
  json.extensionsRequired = ['KHR_draco_mesh_compression'];
  await refuses(prepareCosmeticModel([fx.file('pet.gltf', Buffer.from(JSON.stringify(json)))], { slot: 'pet' }), /Draco/);
});

test('a texture that is not an image is refused', async () => {
  const bad = await fx.glb({ texture: Buffer.from('this is not a png at all, just text') });
  await refuses(prepareCosmeticModel([fx.file('pet.glb', bad)], { slot: 'pet' }), /not a PNG, JPEG or WebP/);
});

test('a hostile archive is refused rather than unpacked', async () => {
  await refuses(prepareCosmeticModel([fx.file('pet.zip', Buffer.from('PK nonsense'))], { slot: 'pet' }), /not a readable \.zip/);
  const twoModels = await fx.zip(undefined, { 'Other/other.glb': await fx.glb() });
  await refuses(prepareCosmeticModel([fx.file('pet.zip', twoModels)], { slot: 'pet' }), /more than one model/);
});

test('animations are found from their names, with no typing', () => {
  assert.deepEqual(discoverAnimationRoles(['Fly_Loop', 'Idle']).roles, { fly: 'Fly_Loop', idle: 'Idle' });
  assert.deepEqual(discoverAnimationRoles(['Armature|Take 001']).roles, { idle: 'Armature|Take 001' },
    'one unnamed-looking clip is still the idle one, never a frozen model');
  const pet = discoverAnimationRoles(['Walk', 'Sit', 'Idle', 'Wave']);
  assert.deepEqual(pet.roles, { walk: 'Walk', sit: 'Sit', idle: 'Idle' });
  assert.deepEqual(pet.extras, ['Wave'], 'the rest play now and then as variety');
  assert.deepEqual(discoverAnimationRoles(['Spin', 'Bounce'], { idle: 'Bounce' }).roles, { idle: 'Bounce' },
    'a creator can pick the idle clip');
  assert.deepEqual(discoverAnimationRoles([]).roles, {});
});

test('the upload records what was measured, not what was claimed', async () => {
  const out = await prepareCosmeticModel([fx.file('pet.glb', await fx.glb())], { slot: 'pet', attachment: 'flying_pet' });
  const m = out.metadata;
  assert.equal(m.spec, 1);
  assert.equal(m.attachment, 'FLYING_PET');
  assert.deepEqual(m.animations.roles, { fly: 'Fly_Loop', idle: 'Idle' });
  assert.deepEqual(m.animations.clips.map((c) => c.duration), [1.5, 1.5]);
  assert.deepEqual(m.bounds, { min: [-0.5, -0.5, 0], max: [0.5, 0.5, 0] }, 'bounds are the rest pose');
  assert.equal(m.stats.triangles, 2);
  assert.deepEqual(m.stats.textures, [{ mime: 'image/png', width: 16, height: 16 }]);
});

test('attachments must suit the slot, and placement is clamped', async () => {
  const model = await fx.glb();
  await refuses(prepareCosmeticModel([fx.file('pet.glb', model)], { slot: 'hat', attachment: 'TRAIL' }), /hat can attach to HEAD/);
  await refuses(prepareCosmeticModel([fx.file('pet.glb', model)], { slot: 'pet', animationRoles: '{"idle":"Nope"}' }), /no animation called "Nope"/);
  assert.deepEqual(sanitizeTransform({ offset: [1, 999, 'x'], rotation: [0, 720, -90], scale: 50 }),
    { offset: [1, 32, 0], rotation: [0, 360, -90], scale: 4 });
});

test('editing placement keeps the measured fields', async () => {
  const { metadata } = await prepareCosmeticModel([fx.file('pet.glb', await fx.glb())], { slot: 'pet' });
  const edited = editCosmeticMetadata(metadata, 'pet', { attachment: 'SIDE', transform: { scale: 2 }, animationRoles: { idle: 'Fly_Loop' } });
  assert.equal(edited.attachment, 'SIDE');
  assert.equal(edited.transform.scale, 2);
  assert.equal(edited.animations.roles.idle, 'Fly_Loop');
  assert.deepEqual(edited.bounds, metadata.bounds);
  assert.deepEqual(edited.stats, metadata.stats);
  assert.throws(() => editCosmeticMetadata(metadata, 'pet', { attachment: 'HEAD' }), CosmeticAssetError);
});

// ── Over HTTP ────────────────────────────────────────────────────────────────

const CREATOR = { uuid: '61111111-1111-4111-8111-111111111111', username: 'Maker', role: 'creator' };
const USER = { uuid: '62222222-2222-4222-8222-222222222222', username: 'Player', role: 'user' };

let api;
test.before(async () => {
  api = await startApi({
    name: 'cosmetics',
    seed: {
      users: [{ ...CREATOR }, { ...USER, metadata: { unrelated: true } }],
      cosmetics: [
        // Uploaded before 1.0.22: no measured clips, just the old column.
        { id: 'legacy-1', slot: 'pet', name: 'Old Pet', creator_id: CREATOR.uuid, idle_animation: 'Idle', metadata: {} },
        { id: 'pet-owned', slot: 'pet', name: 'Owned Pet', model_url: 'https://example.invalid/a.glb', is_public: true },
        { id: 'pet-other', slot: 'pet', name: 'Other Pet', model_url: 'https://example.invalid/b.glb', is_public: true },
      ],
      user_cosmetics: [{ user_uuid: USER.uuid, cosmetic_id: 'pet-owned' }],
      user_equipped_cosmetics: [{ user_uuid: USER.uuid, slot: 'pet', cosmetic_id: 'pet-owned' }],
    },
  });
});
test.after(async () => { if (api) await api.stop(); });

function form(fields, files) {
  const body = new FormData();
  for (const [k, v] of Object.entries(fields)) body.append(k, v);
  for (const [field, name, bytes] of files) body.append(field, new Blob([bytes]), name);
  return body;
}

async function send(route, token, body, method = 'POST') {
  const res = await fetch(api.base + route, { method, headers: token ? { Authorization: `Bearer ${token}` } : {}, body });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* not JSON */ }
  return { status: res.status, body: json, text };
}

test('inspect returns the finished model for the preview, and stores nothing', async () => {
  const res = await send('/cosmetics/inspect', api.token(CREATOR), form({ slot: 'pet', attachment: 'FLYING_PET' }, [['model', 'pet.zip', await fx.zip()]]));
  assert.equal(res.status, 200, res.text.slice(0, 300));
  assert.equal(res.body.metadata.attachment, 'FLYING_PET');
  const glb = Buffer.from(res.body.model, 'base64');
  assert.equal(glb.toString('latin1', 0, 4), 'glTF');
  assert.ok((await inspectGlb(glb)).textures[0].bytes > 0);

  const stored = path.join(api.dataDir, 'storage', 'cosmetics');
  const count = fs.existsSync(stored) ? fs.readdirSync(stored, { recursive: true }).filter((f) => /\.glb$/.test(f)).length : 0;
  assert.equal(count, 0, 'inspect must not write anything');
});

test('a normal user cannot use the pipeline', async () => {
  const res = await send('/cosmetics/inspect', api.token(USER), form({ slot: 'pet' }, [['model', 'pet.glb', await fx.glb()]]));
  assert.equal(res.status, 403);
  const anonymous = await send('/cosmetics/inspect', null, form({ slot: 'pet' }, [['model', 'pet.glb', await fx.glb()]]));
  assert.equal(anonymous.status, 401);
});

test('a bad model is a clear 400, not a server error', async () => {
  const { 'pet.gltf': gltf } = await fx.split();
  const res = await send('/cosmetics', api.token(CREATOR), form({ name: 'Broken', slot: 'pet' }, [['model', 'pet.gltf', gltf]]));
  assert.equal(res.status, 400);
  assert.match(res.body.error, /not in the upload/);

  const huge = await send('/cosmetics/inspect', api.token(CREATOR), form({ slot: 'pet' }, [['model', 'big.glb', Buffer.alloc(9 * 1024 * 1024)]]));
  assert.equal(huge.status, 413, 'an oversized request is refused before it is read');
  assert.match(huge.body.error, /larger than 8 MB/);

  const big = await send('/cosmetics/inspect', api.token(CREATOR), form({ slot: 'pet' }, [['model', 'big.glb', Buffer.alloc(7.5 * 1024 * 1024)]]));
  assert.equal(big.status, 400, 'a file over the per-file limit is a clear 400, not a crash');
  assert.match(big.body.error, /at most 7 MB/);
});

test('publishing stores one GLB and the measured metadata', async () => {
  const files = await fx.split();
  const res = await send('/cosmetics', api.token(CREATOR), form(
    { name: 'Checker Pet', slot: 'pet', attachment: 'SIDE', transform: JSON.stringify({ scale: 1.5 }) },
    [['model', 'pet.gltf', files['pet.gltf']], ['resources', 'pet.bin', files['pet.bin']], ['resources', 'skin.png', files['textures/skin.png']]],
  ));
  assert.equal(res.status, 201, res.text.slice(0, 300));
  const c = res.body.cosmetic;
  assert.match(c.model_url, /\/model\.glb$/);
  assert.equal(c.metadata.attachment, 'SIDE');
  assert.equal(c.metadata.transform.scale, 1.5);
  assert.equal(c.idle_animation, 'Idle', 'the old column is still filled for older readers');

  const rel = c.model_url.slice(c.model_url.indexOf('/assets/') + '/assets/'.length);
  const onDisk = fs.readFileSync(path.join(api.dataDir, 'storage', rel));
  assert.ok((await inspectGlb(onDisk)).textures[0].bytes > 0, 'the stored file carries its texture');

  const edit = await fetch(`${api.base}/cosmetics/${c.id}`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${api.token(CREATOR)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ metadata: { attachment: 'FLYING_PET', bounds: { min: [0, 0, 0], max: [0, 0, 0] } } }),
  }).then((r) => r.json());
  assert.ok(edit.cosmetic, JSON.stringify(edit));
  assert.equal(edit.cosmetic.metadata.attachment, 'FLYING_PET');
  assert.deepEqual(edit.cosmetic.metadata.bounds, c.metadata.bounds, 'a client cannot overwrite what was measured');
});

test('the format is published for the upload form, and matches the checks', async () => {
  const res = await api.get('/cosmetics/spec');
  assert.equal(res.status, 200);
  assert.equal(res.body.spec, 1);
  assert.deepEqual(res.body.slotAttachments.pet, ['SHOULDER', 'FLYING_PET', 'SIDE', 'FEET']);
  assert.deepEqual(res.body.roles, ['idle', 'fly', 'walk', 'sit']);
  assert.equal(res.body.limits.fileBytes, 7 * 1024 * 1024);
  assert.equal(res.body.limits.requestBytes, 8 * 1024 * 1024);
});

test('a role the creator turns off stays off', () => {
  const found = discoverAnimationRoles(['Fly_Loop', 'Idle'], { fly: '' });
  assert.deepEqual(found.roles, { idle: 'Idle' });
  assert.deepEqual(found.extras, ['Fly_Loop'], 'the clip is still used, as variety');
  assert.deepEqual(discoverAnimationRoles(['Spin'], { idle: null }).roles, {}, 'even the idle fallback');
});

// ── Review fixes (PR #12) ────────────────────────────────────────────────────

/** A .gltf whose JSON has been tampered with, sent with its real files. */
async function tampered(mutate) {
  const files = await fx.split();
  const json = JSON.parse(files['pet.gltf']);
  mutate(json);
  return [fx.file('pet.gltf', Buffer.from(JSON.stringify(json))), fx.file('pet.bin', files['pet.bin']), fx.file('skin.png', files['textures/skin.png'])];
}

test('a model that declares huge data is refused before it is parsed, and quickly', async () => {
  const started = Date.now();
  // A few bytes of JSON claiming a billion-element accessor with no data.
  await refuses(prepareCosmeticModel(await tampered((j) => {
    j.accessors.push({ componentType: 5126, type: 'VEC3', count: 1e9 });
  }), { slot: 'pet' }), /more geometry and animation data/);
  await refuses(prepareCosmeticModel(await tampered((j) => {
    j.accessors.push({ componentType: 5126, type: 'SCALAR', count: 10, sparse: { count: 1e9 } });
  }), { slot: 'pet' }), /malformed sparse accessor/);
  assert.ok(Date.now() - started < 2000, 'refused from the JSON, without allocating');
});

test('a model whose accessors or views run past their data is refused', async () => {
  await refuses(prepareCosmeticModel(await tampered((j) => { j.accessors[0].count = 100000; }), { slot: 'pet' }), /larger than its data/);
  await refuses(prepareCosmeticModel(await tampered((j) => { j.bufferViews[0].byteLength = 1e9; }), { slot: 'pet' }), /past the end of its buffer/);
  await refuses(prepareCosmeticModel(await tampered((j) => { j.buffers[0].byteLength = 1e9; }), { slot: 'pet' }), /more buffer data than it contains/);
});

test('a node tree that loops is refused', async () => {
  await refuses(prepareCosmeticModel(await tampered((j) => {
    j.nodes.push({ children: [2] }, { children: [1] });
  }), { slot: 'pet' }), /contains itself/);
});

test('reusing one mesh counts every use toward the triangle limit', async () => {
  // 200 triangles drawn by 300 nodes is 60,000: under the limit per mesh,
  // over it as drawn. Measuring must stay fast however many nodes reuse it.
  const { Document, NodeIO } = require('@gltf-transform/core');
  const doc = new Document();
  const buffer = doc.createBuffer();
  const positions = new Float32Array(200 * 9).map((_, i) => (i % 9) / 9);
  const prim = doc.createPrimitive().setAttribute('POSITION', doc.createAccessor().setType('VEC3').setArray(positions).setBuffer(buffer));
  const mesh = doc.createMesh().addPrimitive(prim);
  const scene = doc.createScene();
  for (let i = 0; i < 300; i++) scene.addChild(doc.createNode().setMesh(mesh).setTranslation([i, 0, 0]));
  const started = Date.now();
  await refuses(prepareCosmeticModel([fx.file('many.glb', Buffer.from(await new NodeIO().writeBinary(doc)))], { slot: 'pet' }), /draws more than 50000 triangles/);
  assert.ok(Date.now() - started < 3000);
});

test('bounds follow node transforms, and a reused mesh is measured where each copy is', async () => {
  const { Document, NodeIO } = require('@gltf-transform/core');
  const doc = new Document();
  const buffer = doc.createBuffer();
  const prim = doc.createPrimitive().setAttribute('POSITION', doc.createAccessor().setType('VEC3')
    .setArray(new Float32Array([0, 0, 0, 1, 0, 0, 0, 1, 0])).setBuffer(buffer));
  const mesh = doc.createMesh().addPrimitive(prim);
  const scene = doc.createScene();
  scene.addChild(doc.createNode().setMesh(mesh));
  scene.addChild(doc.createNode().setMesh(mesh).setTranslation([5, 0, 0]).setScale([2, 2, 2]));
  const out = await prepareCosmeticModel([fx.file('two.glb', Buffer.from(await new NodeIO().writeBinary(doc)))], { slot: 'hat' });
  assert.deepEqual(out.metadata.bounds, { min: [0, 0, 0], max: [7, 2, 0] });
  assert.equal(out.metadata.stats.triangles, 2);
});

test('GPU instancing is not accepted, since the triangle limit cannot see it', async () => {
  await refuses(prepareCosmeticModel(await tampered((j) => {
    j.extensionsUsed = ['EXT_mesh_gpu_instancing'];
    j.extensionsRequired = ['EXT_mesh_gpu_instancing'];
  }), { slot: 'pet' }), /requires EXT_mesh_gpu_instancing/);
});

test('a corrupt texture or malformed JSON is a clear refusal, never a crash', async () => {
  // A PNG signature with the rest cut off: it looks like a PNG until read.
  const truncated = fx.checkerPng().subarray(0, 12);
  await refuses(prepareCosmeticModel([fx.file('pet.glb', await fx.glb({ texture: Buffer.from(truncated) }))], { slot: 'pet' }), /could not be decoded/);
  await refuses(prepareCosmeticModel([fx.file('pet.gltf', Buffer.from('null'))], { slot: 'pet' }), /not a glTF document/);
  await refuses(prepareCosmeticModel(await tampered((j) => { j.buffers = 'nope'; }), { slot: 'pet' }), /buffers are malformed/);
  await refuses(prepareCosmeticModel(await tampered((j) => { j.images[0].uri = 42; }), { slot: 'pet' }), /malformed file reference/);
});

test('a truncated multipart upload is a 400, not a server error', async () => {
  const res = await fetch(`${api.base}/cosmetics/inspect`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${api.token(CREATOR)}`, 'Content-Type': 'multipart/form-data; boundary=XYZ' },
    body: '--XYZ\r\nContent-Disposition: form-data; name="model"; filename="a.glb"\r\n\r\nglTF',
  });
  assert.equal(res.status, 400);
});

test('role edits keep the creator\'s other choices and keep the old columns in step', async () => {
  const files = await fx.split();
  const created = await send('/cosmetics', api.token(CREATOR), form(
    { name: 'Roles Pet', slot: 'pet', animation_roles: JSON.stringify({ idle: 'Idle', fly: '', walk: '', sit: '' }) },
    [['model', 'pet.gltf', files['pet.gltf']], ['resources', 'pet.bin', files['pet.bin']], ['resources', 'skin.png', files['textures/skin.png']]],
  ));
  assert.equal(created.status, 201, created.text.slice(0, 300));
  const id = created.body.cosmetic.id;
  assert.deepEqual(created.body.cosmetic.metadata.animations.roles, { idle: 'Idle' }, 'fly was turned off at upload');

  const patchJson = (body) => fetch(`${api.base}/cosmetics/${id}`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${api.token(CREATOR)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(async (r) => ({ status: r.status, body: await r.json() }));

  // An edit that only mentions sit leaves fly off rather than rediscovering it.
  const edited = await patchJson({ animation_roles: { sit: '' } });
  assert.equal(edited.status, 200, JSON.stringify(edited.body));
  assert.deepEqual(edited.body.cosmetic.metadata.animations.roles, { idle: 'Idle' });
  assert.deepEqual(edited.body.cosmetic.random_animations, ['Fly_Loop'], 'the extras column follows the roles');

  // Setting idle_animation directly goes through the roles, so the two agree.
  const idle = await patchJson({ idle_animation: 'Fly_Loop' });
  assert.equal(idle.status, 200, JSON.stringify(idle.body));
  assert.equal(idle.body.cosmetic.idle_animation, 'Fly_Loop');
  assert.equal(idle.body.cosmetic.metadata.animations.roles.idle, 'Fly_Loop');
  assert.equal((await patchJson({ idle_animation: 'Nope' })).status, 400);
  assert.equal((await patchJson({ random_animations: ['Idle'] })).status, 400, 'extras are derived, not set');
});

test('a cosmetic from before 1.0.22 says why its roles cannot be edited', async () => {
  const res = await fetch(`${api.base}/cosmetics/legacy-1`, {
    method: 'PATCH',
    headers: { Authorization: `Bearer ${api.token(CREATOR)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ animation_roles: { idle: 'Idle' } }),
  }).then(async (r) => ({ status: r.status, body: await r.json() }));
  assert.equal(res.status, 400);
  assert.match(res.body.error, /uploaded before Breeze read animations/);
  // And its old column is untouched, rather than wiped by an empty rediscovery.
  const listed = await api.get('/cosmetics/legacy-1');
  assert.equal((listed.body.cosmetic || listed.body).idle_animation, 'Idle');
});

test('a player can place a cosmetic they own, and everyone sees it', async () => {
  const put = (id, transform, token = api.token(USER)) => fetch(`${api.base}/cosmetics/${id}/placement`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ transform }),
  }).then(async (r) => ({ status: r.status, body: await r.json() }));

  const saved = await put('pet-owned', { offset: [0, 99, 0], rotation: [0, 45, 0], scale: 2 });
  assert.equal(saved.status, 200, JSON.stringify(saved.body));
  assert.deepEqual(saved.body.placement, { offset: [0, 32, 0], rotation: [0, 45, 0], scale: 2 }, 'clamped like the creator transform');

  const owned = await api.get('/cosmetics/owned', { token: api.token(USER) });
  assert.deepEqual(owned.body.placements['pet-owned'].rotation, [0, 45, 0]);
  const worn = await api.get(`/cosmetics/equipped/${USER.uuid}`);
  assert.equal(worn.body.equipped[0].placement.scale, 2, 'what draws the player gets the placement');

  assert.equal((await put('pet-other', { scale: 2 })).status, 403, 'not for a cosmetic they do not own');
  assert.equal((await put('nope', { scale: 2 })).status, 404);

  // Only an explicit null resets: a missing or malformed body is refused, and
  // the saved placement is untouched.
  const missing = await fetch(`${api.base}/cosmetics/pet-owned/placement`, {
    method: 'PUT',
    headers: { Authorization: `Bearer ${api.token(USER)}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({}),
  });
  assert.equal(missing.status, 400);
  assert.equal((await put('pet-owned', [1, 2, 3])).status, 400);
  assert.equal((await put('pet-owned', 'not json')).status, 400);
  const still = await api.get('/cosmetics/owned', { token: api.token(USER) });
  assert.equal(still.body.placements['pet-owned'].scale, 2, 'a refused request changes nothing');
  const asString = await put('pet-owned', JSON.stringify({ scale: 3 }));
  assert.equal(asString.status, 200, 'a JSON string is read like the other transform fields');
  assert.equal(asString.body.placement.scale, 3);

  const reset = await put('pet-owned', null);
  assert.equal(reset.body.placement, null);
  const after = await api.get('/cosmetics/owned', { token: api.token(USER) });
  assert.equal(after.body.placements['pet-owned'], undefined);
});
