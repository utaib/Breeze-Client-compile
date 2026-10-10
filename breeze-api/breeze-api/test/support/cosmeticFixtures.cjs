'use strict';
/**
 * Small, real glTF assets for the cosmetic pipeline tests: a textured cube
 * with "Idle" and "Fly_Loop" clips, in each shape a creator might upload it.
 * Built here rather than committed, so the fixtures cannot drift from the
 * library that reads them.
 */

const zlib = require('node:zlib');
const { Document, NodeIO } = require('@gltf-transform/core');
const { zipSync } = require('fflate');

function crc32(buf) {
  let crc = 0xffffffff;
  for (let n = 0; n < buf.length; n++) {
    let c = (crc ^ buf[n]) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function pngChunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** A 16x16 checkerboard PNG. */
function checkerPng(size = 16) {
  const raw = Buffer.alloc((size * 4 + 1) * size);
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const o = y * (size * 4 + 1) + 1 + x * 4;
      const on = ((x >> 2) + (y >> 2)) % 2 === 0;
      raw[o] = 255; raw[o + 1] = on ? 0 : 220; raw[o + 2] = on ? 255 : 0; raw[o + 3] = 255;
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', zlib.deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

function buildDocument({ clips = ['Idle', 'Fly_Loop'], texture = checkerPng() } = {}) {
  const doc = new Document();
  const buffer = doc.createBuffer('data').setURI('pet.bin');
  const acc = (type, arr) => doc.createAccessor().setType(type).setArray(arr).setBuffer(buffer);
  const positions = new Float32Array([-0.5, -0.5, 0, 0.5, -0.5, 0, 0.5, 0.5, 0, -0.5, 0.5, 0]);
  const uvs = new Float32Array([0, 1, 1, 1, 1, 0, 0, 0]);
  const prim = doc.createPrimitive()
    .setAttribute('POSITION', acc('VEC3', positions))
    .setAttribute('TEXCOORD_0', acc('VEC2', uvs))
    .setIndices(acc('SCALAR', new Uint16Array([0, 1, 2, 0, 2, 3])));
  if (texture) {
    const tex = doc.createTexture('skin').setImage(texture).setMimeType('image/png').setURI('textures/skin.png');
    prim.setMaterial(doc.createMaterial('mat').setBaseColorTexture(tex));
  }
  const node = doc.createNode('Pet').setMesh(doc.createMesh('quad').addPrimitive(prim));
  doc.createScene('scene').addChild(node);
  for (const name of clips) {
    const sampler = doc.createAnimationSampler()
      .setInput(acc('SCALAR', new Float32Array([0, 1.5])))
      .setOutput(acc('VEC3', new Float32Array([0, 0, 0, 0, 0.25, 0])))
      .setInterpolation('LINEAR');
    doc.createAnimation(name).addSampler(sampler)
      .addChannel(doc.createAnimationChannel().setTargetNode(node).setTargetPath('translation').setSampler(sampler));
  }
  return doc;
}

async function glb(options) {
  return Buffer.from(await new NodeIO().writeBinary(buildDocument(options)));
}

/** The .gltf and the files it points at, as { 'pet.gltf': Buffer, ... }. */
async function split(options) {
  const { json, resources } = await new NodeIO().writeJSON(buildDocument(options));
  const files = { 'pet.gltf': Buffer.from(JSON.stringify(json)) };
  for (const [name, data] of Object.entries(resources)) files[name] = Buffer.from(data);
  return files;
}

/** A .zip of the split files, inside a folder the way exporters usually do it. */
async function zip(options, extra = {}) {
  const entries = {};
  for (const [name, data] of Object.entries(await split(options))) entries[`MyPet/${name}`] = new Uint8Array(data);
  for (const [name, data] of Object.entries(extra)) entries[name] = new Uint8Array(Buffer.from(data));
  return Buffer.from(zipSync(entries));
}

/**
 * One .gltf with its buffer and texture inline as data: URIs, the way
 * Blockbench exports by default. Stored as-is by uploads before 1.0.22.
 */
async function embeddedGltf(options) {
  const { json, resources } = await new NodeIO().writeJSON(buildDocument(options));
  const inline = (uri, mime) => `data:${mime};base64,${Buffer.from(resources[uri]).toString('base64')}`;
  for (const buffer of json.buffers || []) buffer.uri = inline(buffer.uri, 'application/octet-stream');
  for (const image of json.images || []) image.uri = inline(image.uri, image.mimeType || 'image/png');
  return Buffer.from(JSON.stringify(json));
}

const file = (originalname, buffer) => ({ originalname, buffer });

module.exports = { checkerPng, glb, split, zip, embeddedGltf, file };
