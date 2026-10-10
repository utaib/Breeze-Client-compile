'use strict';
/**
 * Measuring cosmetics uploaded before 1.0.22 (src/cosmeticBackfill.js).
 *
 * The production pets were Blockbench exports stored as uploaded: a .glb with
 * its texture inside, or a .gltf with everything in data: URIs. Both can be
 * measured where they are, so they get roles, bounds and placement defaults
 * without anyone uploading them again. Run with: npm test
 */

const test = require('node:test');
const assert = require('node:assert/strict');
const { NodeIO } = require('@gltf-transform/core');
const fx = require('./support/cosmeticFixtures.cjs');
const { planBackfill, storedPath } = require('../src/cosmeticBackfill');
const { discoverAnimationRoles } = require('../src/cosmeticAsset');

const BLOCKBENCH_CLIPS = ['animation.glare.fly', 'animation.glare.angry', 'animation.glare.dancing'];
const URL_BASE = 'https://api.breezeclient.net/assets/cosmetics/pet/owner_glare_1';

/** Storage as a map of relative path to bytes. */
const storage = (files) => ({ readAsset: (rel) => files[rel] || null });

test('Blockbench clip names are read by what they are, not by their prefix', () => {
  // Before: "animation" satisfied the weak idle words, so the idle was "angry".
  const found = discoverAnimationRoles(BLOCKBENCH_CLIPS);
  assert.deepEqual(found.roles, { fly: 'animation.glare.fly', idle: 'animation.glare.dancing' });
  assert.deepEqual(found.extras, ['animation.glare.angry'], 'a reaction plays now and then, never as the idle');
  assert.deepEqual(discoverAnimationRoles(['Armature|Idle', 'Armature|Walk']).roles, { idle: 'Armature|Idle', walk: 'Armature|Walk' });
  assert.deepEqual(discoverAnimationRoles(['animation.x.attack']).roles, { idle: 'animation.x.attack' },
    'a reaction is still used when it is the only clip');
});

test('exporter decoration does not hide what a clip is', () => {
  // Blender numbers duplicates, and creators write "Fly.Loop" or "flyLoop".
  // Reading only the last part made these "001" and "Loop", so a walk clip
  // became the idle and a fly clip was never found.
  assert.deepEqual(
    discoverAnimationRoles(['Walk.001', 'Idle.001', 'Fly.002']).roles,
    { fly: 'Fly.002', walk: 'Walk.001', idle: 'Idle.001' },
  );
  assert.deepEqual(
    discoverAnimationRoles(['Fly.Loop', 'Sit.Loop']).roles,
    { fly: 'Fly.Loop', sit: 'Sit.Loop' },
  );
  assert.deepEqual(
    discoverAnimationRoles(['flyLoop', 'idleBreathe', 'walkCycle']).roles,
    { fly: 'flyLoop', walk: 'walkCycle', idle: 'idleBreathe' },
  );
  // A model whose own name contains a role word must not claim that role.
  assert.deepEqual(
    discoverAnimationRoles(['animation.walker.idle']).roles,
    { idle: 'animation.walker.idle' },
    'the model name "walker" is not a walk clip',
  );
  // A pet that found other states is left resting rather than angry forever.
  const withStates = discoverAnimationRoles(['Fly.Loop', 'Sit.Loop', 'Angry']);
  assert.equal(withStates.roles.idle, undefined);
  assert.deepEqual(withStates.extras, ['Angry']);
});

test('a legacy .gltf with inline data becomes a measured .glb', async () => {
  const rel = 'cosmetics/pet/owner_glare_1/model.gltf';
  const row = { id: 'c1', slot: 'pet', model_url: `${URL_BASE}/model.gltf`, metadata: {}, idle_animation: null };
  const plan = await planBackfill(row, storage({ [rel]: await fx.embeddedGltf({ clips: BLOCKBENCH_CLIPS }) }));
  assert.equal(plan.status, 'measured', plan.reason);
  assert.deepEqual(plan.files.map((f) => f.rel), ['cosmetics/pet/owner_glare_1/model.glb'], 'the .gltf itself is left alone');
  assert.equal(plan.update.model_url, `${URL_BASE}/model.glb`);
  const m = plan.update.metadata;
  assert.equal(m.spec, 1);
  assert.equal(m.attachment, 'SHOULDER', 'a pet takes its slot default');
  assert.deepEqual(m.animations.roles, { fly: 'animation.glare.fly', idle: 'animation.glare.dancing' });
  assert.equal(plan.update.idle_animation, 'animation.glare.dancing');
  assert.deepEqual(plan.update.random_animations, ['animation.glare.angry']);
  const doc = await new NodeIO().readBinary(new Uint8Array(plan.files[0].bytes));
  assert.ok(doc.getRoot().listTextures()[0].getImage().byteLength > 0, 'the texture is inside the new file');
});

test('a legacy .glb is measured in place, and its original is kept', async () => {
  const rel = 'cosmetics/pet/owner_glare_1/model.glb';
  const original = await fx.glb({ clips: BLOCKBENCH_CLIPS });
  const plan = await planBackfill({ id: 'c2', slot: 'pet', model_url: `${URL_BASE}/model.glb`, metadata: null }, storage({ [rel]: original }));
  assert.equal(plan.status, 'measured', plan.reason);
  assert.deepEqual(plan.files.map((f) => f.rel), ['cosmetics/pet/owner_glare_1/model.original.glb', rel]);
  assert.ok(Buffer.from(plan.files[0].bytes).equals(original));
  assert.equal(plan.update.model_url, undefined, 'the URL does not change');
});

test('running the backfill twice does not overwrite the kept original', async () => {
  const rel = 'cosmetics/pet/owner_glare_1/model.glb';
  const originalRel = 'cosmetics/pet/owner_glare_1/model.original.glb';
  const original = await fx.glb({ clips: BLOCKBENCH_CLIPS });
  const first = await planBackfill({ slot: 'pet', model_url: `${URL_BASE}/model.glb` }, storage({ [rel]: original }));
  assert.ok(first.files.some((f) => f.rel === originalRel), 'the first run keeps the upload');

  // Second run: the row has no metadata (say the update failed), the stored
  // model is now the measured one, and the original is already beside it.
  const again = await planBackfill(
    { slot: 'pet', model_url: `${URL_BASE}/model.glb` },
    storage({ [rel]: Buffer.from(first.files[1].bytes), [originalRel]: original }),
  );
  assert.equal(again.status, 'measured', again.reason);
  assert.deepEqual(again.files.map((f) => f.rel), [rel], 'the creator\'s upload is not replaced by the measured file');
});

test('a hand-typed random_animations list survives when its clips still exist', async () => {
  const rel = 'cosmetics/pet/owner_glare_1/model.glb';
  const files = storage({ [rel]: await fx.glb({ clips: BLOCKBENCH_CLIPS }) });
  const kept = await planBackfill(
    { slot: 'pet', model_url: `${URL_BASE}/model.glb`, random_animations: ['animation.glare.dancing'] },
    files,
  );
  assert.deepEqual(kept.update.random_animations, ['animation.glare.dancing'], 'the creator chose these');

  const stale = await planBackfill(
    { slot: 'pet', model_url: `${URL_BASE}/model.glb`, random_animations: ['animation.glare.dancing', 'gone'] },
    files,
  );
  assert.deepEqual(stale.update.random_animations, ['animation.glare.angry'], 'a list naming a clip that is gone is replaced');
});

test('a hand-typed idle is kept when it names a real clip, and ignored when not', async () => {
  const rel = 'cosmetics/pet/owner_glare_1/model.glb';
  const files = storage({ [rel]: await fx.glb({ clips: BLOCKBENCH_CLIPS }) });
  const kept = await planBackfill({ slot: 'pet', model_url: `${URL_BASE}/model.glb`, idle_animation: 'animation.glare.angry' }, files);
  assert.equal(kept.update.idle_animation, 'animation.glare.angry');
  const typo = await planBackfill({ slot: 'pet', model_url: `${URL_BASE}/model.glb`, idle_animation: 'idle_v2' }, files);
  assert.equal(typo.status, 'measured');
  assert.equal(typo.update.idle_animation, 'animation.glare.dancing');
});

test('what cannot be measured is reported, not changed', async () => {
  const { 'pet.gltf': gltf } = await fx.split();
  const rel = 'cosmetics/pet/owner_glare_1/model.gltf';
  const missingFiles = await planBackfill({ slot: 'pet', model_url: `${URL_BASE}/model.gltf` }, storage({ [rel]: gltf }));
  assert.equal(missingFiles.status, 'failed');
  assert.match(missingFiles.reason, /not in the upload/);
  assert.equal(missingFiles.files, undefined);

  assert.equal((await planBackfill({ slot: 'pet', model_url: `${URL_BASE}/model.glb` }, storage({}))).status, 'failed');
  assert.equal((await planBackfill({ slot: 'pet', model_url: 'https://elsewhere.example/model.glb' }, storage({}))).status, 'failed');
  assert.equal((await planBackfill({ slot: 'pet', model_url: null }, storage({}))).status, 'skipped');
  assert.equal((await planBackfill({ slot: 'pet', model_url: `${URL_BASE}/model.glb`, metadata: { spec: 1 } }, storage({}))).status, 'skipped');
});

test('stored paths are read from the asset URL, encoded or not', () => {
  assert.equal(storedPath('https://api.breezeclient.net/assets/cosmetics/pet/a%20b/model.glb'), 'cosmetics/pet/a b/model.glb');
  assert.equal(storedPath('https://cdn.example/model.glb'), null);
});
