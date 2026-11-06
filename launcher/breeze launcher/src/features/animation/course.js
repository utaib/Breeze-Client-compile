import {
  BoxGeometry, BufferAttribute, Color, DataTexture, Group, Mesh, MeshBasicMaterial, NearestFilter, RGBAFormat,
  SRGBColorSpace, Vector3,
} from "three";
import { readGameBlockPicture } from "../../services/nativeBridge";
import { BLOCK, BLOCK_KINDS, framePull, pathAt } from "./coursePath.js";

export { readCourse, pathAt, framePull } from "./coursePath.js";

const clamp01 = (f) => Math.min(1, Math.max(0, f));

/**
 * Moments on a little course of blocks (the directive's parkour across
 * platforms, and building): blocks appear on the stage, the player runs,
 * jumps from one to the next and back down, or places a few and breaks them
 * again, and the camera pulls back so the whole course fits.
 *
 * A course comes from a clip's "breeze_props.course" in the animation file:
 *
 *   frame: [x, y, halfWidth, halfHeight]  what the camera shows while it plays
 *   blocks: [{ x, z, y, kind, in, out, placed, broken }]
 *     x, z: the block's middle on the stage floor (player pixels); y: how
 *     many blocks up (0 stands on the floor); kind: its picture; in / out:
 *     seconds it appears and goes; placed: put there by the player (a puff,
 *     no pop); broken: broken by the player (bits of the block fly).
 *   path: [[t, x, z, y, how, face?], ...]  where the feet are at t; how is
 *     what happens until the next key: "stand", "walk", "run", "jump" (an
 *     arc to the next key) or "land" (a short squash); face, in degrees, is
 *     where the player looks while standing (0 the viewer, 90 the viewer's
 *     right), otherwise the way it moves.
 *
 * Positions are the stage's, not the player's, so the course is the same
 * however the player was turned. The block pictures come from the
 * Minecraft installed on the player's computer, or Breeze's own drawings.
 */

// Breeze's own 16 by 16 block drawings, used when no Minecraft is installed.
function standInBlock(kind) {
  const data = new Uint8ClampedArray(16 * 16 * 4);
  const put = (x, y, hex, shade = 0) => {
    const c = new Color(hex);
    const i = (y * 16 + x) * 4;
    data[i] = Math.max(0, Math.min(255, c.r * 255 + shade));
    data[i + 1] = Math.max(0, Math.min(255, c.g * 255 + shade));
    data[i + 2] = Math.max(0, Math.min(255, c.b * 255 + shade));
    data[i + 3] = 255;
  };
  // A fixed pattern of light and dark specks, the same every time.
  const speck = (x, y) => (((x * 7 + y * 13) ^ (x * y)) % 5) * 5 - 10;
  for (let y = 0; y < 16; y++) {
    for (let x = 0; x < 16; x++) {
      if (kind === "oak_planks") {
        // Four boards; a darker seam under each, the joins staggered.
        const board = Math.floor(y / 4);
        const seam = y % 4 === 3;
        const join = x === (board % 2 ? 11 : 4) && !seam;
        put(x, y, seam || join ? "#73562d" : board % 2 ? "#b08a50" : "#a37f48", speck(x, y));
      } else {
        // Stone bricks: two rows of bricks, staggered, with mortar between.
        const row = Math.floor(y / 8);
        const mortarY = y % 8 === 7;
        const mortarX = (x + (row ? 8 : 0)) % 16 === 15;
        put(x, y, mortarY || mortarX ? "#5c5c5c" : "#8b8b8b", speck(x, y));
      }
    }
  }
  return { width: 16, height: 16, data, fromGame: false };
}

async function gameBlockPixels(kind) {
  try {
    const url = await readGameBlockPicture(kind);
    if (!url) return null;
    const img = new Image();
    img.src = url;
    await img.decode();
    // Block pictures are square (animated ones are taller; none are listed).
    const w = img.naturalWidth;
    if (!w || w > 64 || img.naturalHeight !== w) return null;
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = w;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(img, 0, 0);
    return { width: w, height: w, data: ctx.getImageData(0, 0, w, w).data, fromGame: true };
  } catch {
    return null;
  }
}

const blockPixels = new Map();
/** The block's picture, from the game or Breeze's drawing, loaded once per session. */
export function loadBlock(kind) {
  if (!blockPixels.has(kind)) {
    blockPixels.set(kind, (async () => (await gameBlockPixels(kind)) || standInBlock(kind))());
  }
  return blockPixels.get(kind);
}

/**
 * A block mesh: its picture on every face, shaded as Minecraft shades a
 * block (top brightest, then front and back, then the sides), so it reads as
 * a block whatever the stage's light.
 */
function blockGeometry() {
  const g = new BoxGeometry(BLOCK, BLOCK, BLOCK);
  // BoxGeometry's faces: +x, -x, +y, -y, +z, -z, four vertices each.
  const shade = [0.62, 0.62, 1, 0.5, 0.82, 0.82];
  const colors = new Float32Array(24 * 3);
  for (let f = 0; f < 6; f++) for (let v = 0; v < 4; v++) colors.set([shade[f], shade[f], shade[f]], (f * 4 + v) * 3);
  g.setAttribute("color", new BufferAttribute(colors, 3));
  return g;
}

const easeOutBack = (f) => {
  const c = 1.9;
  return 1 + (c + 1) * Math.pow(f - 1, 3) + c * Math.pow(f - 1, 2);
};

/**
 * The course on one stage: its blocks, the player's place, turn and stride
 * on it, and how the camera should frame it.
 */
export class CourseRig {
  constructor() {
    this.geometry = null;
    this.materials = new Map();
    this.meshes = [];
    this.bits = [];
    this.active = null;
    this.yaw = null;
    this.offset = new Vector3();
    this.landed = new Set();
    for (const kind of Object.keys(BLOCK_KINDS)) {
      loadBlock(kind).then((px) => { this.pixels = { ...(this.pixels || {}), [kind]: px }; });
    }
  }

  material(kind) {
    if (this.materials.has(kind)) return this.materials.get(kind);
    const px = this.pixels?.[kind];
    if (!px) return null;
    // The picture's first row is its top; a texture's first row is its bottom.
    const rows = new Uint8Array(px.width * px.height * 4);
    const stride = px.width * 4;
    for (let y = 0; y < px.height; y++) {
      rows.set(px.data.subarray((px.height - 1 - y) * stride, (px.height - y) * stride), y * stride);
    }
    const tex = new DataTexture(rows, px.width, px.height, RGBAFormat);
    tex.magFilter = NearestFilter;
    tex.minFilter = NearestFilter;
    tex.generateMipmaps = false;
    tex.colorSpace = SRGBColorSpace;
    tex.needsUpdate = true;
    const m = new MeshBasicMaterial({ map: tex, vertexColors: true, transparent: true });
    this.materials.set(kind, m);
    return m;
  }

  /** Little bits flying out: a puff of dust, or the broken block's own colours. */
  spray(parent, at, colors, count, { up = 6, spread = 7 } = {}) {
    for (let i = 0; i < count; i++) {
      const s = 1 + Math.random() * 1.2;
      const m = new Mesh(new BoxGeometry(s, s, s), new MeshBasicMaterial({ color: colors[i % colors.length], transparent: true, depthWrite: false }));
      m.position.set(at.x + (Math.random() - 0.5) * spread, at.y + (Math.random() - 0.3) * spread, at.z + (Math.random() - 0.5) * spread);
      parent.add(m);
      this.bits.push({ m, v: new Vector3((Math.random() - 0.5) * 14, up + Math.random() * 10, (Math.random() - 0.5) * 14), life: 0, max: 0.6 + Math.random() * 0.5 });
    }
  }

  clear() {
    for (const { mesh } of this.meshes) mesh.parent?.remove(mesh);
    this.meshes = [];
    this.active = null;
    this.landed.clear();
  }

  /**
   * Before the pose: the player's turn on the course, blended with its own
   * (weight w), or nothing when no course plays. Returns the yaw to use.
   */
  turn(course, elapsed, turn, w, dt) {
    if (!course) {
      this.yaw = null;
      return turn;
    }
    const at = pathAt(course.path, elapsed);
    let want = at?.yaw ?? this.yaw ?? turn;
    // While running across the stage, a little towards the viewer, so the
    // face shows.
    if (at?.moving && Math.abs(Math.sin(want)) > 0.5) want -= Math.sign(Math.sin(want)) * 0.35;
    if (this.yaw == null) this.yaw = turn;
    const d = Math.atan2(Math.sin(want - this.yaw), Math.cos(want - this.yaw));
    this.yaw += d * Math.min(1, dt * 12);
    const blend = Math.atan2(Math.sin(this.yaw - turn), Math.cos(this.yaw - turn));
    return turn + blend * w;
  }

  /**
   * After the pose: the blocks, the stride or jump on the limbs, the bits in
   * the air. Returns { offset, pull, frame } (offset to add to the player's
   * position; how far the camera pulls back and to what).
   */
  update(player, course, elapsed, length, dt, w) {
    const parent = player?.parent;
    for (const b of this.bits) {
      b.life += dt;
      b.v.y -= 40 * dt;
      b.m.position.addScaledVector(b.v, dt);
      b.m.material.opacity = Math.max(0, 1 - b.life / b.max);
    }
    this.bits = this.bits.filter((b) => {
      if (b.life < b.max) return true;
      b.m.parent?.remove(b.m);
      b.m.geometry.dispose();
      b.m.material.dispose();
      return false;
    });
    if (!course || !parent) {
      if (this.active) {
        // Ended early (moments switched off): the blocks go with a puff.
        for (const { mesh } of this.meshes) if (mesh.visible) this.spray(mesh.parent, mesh.position, ["#e8e8e8", "#c9c9c9"], 6);
        this.clear();
      }
      this.offset.set(0, 0, 0);
      return { offset: this.offset, pull: 0, frame: null };
    }
    if (this.active !== course) {
      this.clear();
      this.active = course;
      if (!this.geometry) this.geometry = blockGeometry();
      this.meshes = course.blocks.map((b) => ({ b, mesh: null, shown: false, gone: false }));
    }
    for (const slot of this.meshes) {
      const { b } = slot;
      if (!slot.mesh) {
        const mat = this.material(b.kind);
        if (!mat) continue;
        slot.mesh = new Mesh(this.geometry, mat);
        slot.mesh.visible = false;
        slot.mesh.position.set(b.x, -16 + b.y * BLOCK + BLOCK / 2, b.z);
        parent.add(slot.mesh);
      }
      const mesh = slot.mesh;
      const into = elapsed - b.in;
      const left = b.out - elapsed;
      if (into < 0 || left <= 0) {
        if (mesh.visible && left <= 0 && !slot.gone) {
          slot.gone = true;
          if (b.broken) this.spray(parent, mesh.position, BLOCK_KINDS[b.kind].bits, 22, { up: 8, spread: 12 });
          else this.spray(parent, mesh.position, ["#f2f2f2", "#d6d6d6", "#bdbdbd"], 12, { up: 4, spread: 14 });
        }
        mesh.visible = false;
        continue;
      }
      if (!mesh.visible && !slot.shown) {
        slot.shown = true;
        if (b.placed) this.spray(parent, mesh.position.clone().setY(mesh.position.y - BLOCK / 2), ["#e6e1d6", "#cfc8b8"], 8, { up: 3, spread: 16 });
      }
      mesh.visible = true;
      // A placed block is just there; the others pop up; a broken one cracks
      // smaller before it goes.
      const grow = b.placed ? Math.min(1, into / 0.06) : easeOutBack(Math.min(1, into / 0.28));
      const shrink = b.broken ? 1 - 0.12 * clamp01(1 - left / 0.25) : clamp01(left / 0.18);
      mesh.scale.setScalar(Math.max(0.001, grow * shrink));
    }

    const at = pathAt(course.path, elapsed);
    this.offset.set(at.x, at.y, at.z).multiplyScalar(w);
    // The stride: legs and arms swing with the distance run, as in game.
    const skin = player.skin;
    if (skin) {
      const run = at.how === "run";
      const phase = at.dist * (run ? 0.32 : 0.26);
      const reach = at.moving && !at.air ? (run ? 1 : 0.7) : 0;
      const s = Math.sin(phase) * reach * w;
      skin.rightLeg.rotateX(s);
      skin.leftLeg.rotateX(-s);
      skin.rightArm.rotateX(-s * 0.9);
      skin.leftArm.rotateX(s * 0.9);
      if (at.air) {
        // In the air: one leg forward, arms up and out for balance.
        skin.rightLeg.rotateX(-0.55 * w);
        skin.leftLeg.rotateX(0.35 * w);
        skin.rightArm.rotateX(-1.1 * w);
        skin.leftArm.rotateX(-1.1 * w);
        skin.rightArm.rotateZ(-0.35 * w);
        skin.leftArm.rotateZ(0.35 * w);
      }
      if (at.landing != null) {
        // A landing: a quick squash, arms forward.
        const sq = Math.sin(Math.min(1, at.landing * 1.4) * Math.PI) * w;
        player.scale.y = 1 - 0.1 * sq;
        this.offset.y -= 16 * 0.1 * sq;
        skin.rightArm.rotateX(-0.6 * sq);
        skin.leftArm.rotateX(-0.6 * sq);
      }
    }
    return { offset: this.offset, pull: framePull(elapsed, length) * w, frame: course.frame };
  }
}
