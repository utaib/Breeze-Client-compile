import {
  AdditiveBlending, BoxGeometry, BufferAttribute, Color, Group, Mesh, MeshBasicMaterial, MeshLambertMaterial, Vector3,
} from "three";
import { mergeGeometries } from "three/examples/jsm/utils/BufferGeometryUtils.js";
import { readGameItemPicture } from "../../services/nativeBridge";

/**
 * Items the character holds in a moment (a sword to look over, an ender pearl
 * to throw), built the way Minecraft draws a held item: its 16 by 16 picture
 * as a slab of little cubes. The picture comes from the Minecraft the player
 * has installed (read on their computer, never shipped by Breeze); without
 * one, Breeze's own small drawings of a sword and a pearl stand in, so the
 * moments always work.
 *
 * The item sits in a holder on the right arm at the fist. The holder is the
 * animation bone "rightItem": its x turns the item up and down, its y twists
 * it in the hand (turning a sword over).
 */

/** Where each item is held: the grip pixel (from the top left) and its size in player pixels. */
export const ITEMS = {
  diamond_sword: { grip: [3.5, 12.5], size: 11, stand: "sword" },
  iron_sword: { grip: [3.5, 12.5], size: 11, stand: "sword" },
  netherite_sword: { grip: [3.5, 12.5], size: 11, stand: "sword" },
  ender_pearl: { grip: [8, 8], size: 5, stand: "pearl" },
};

// Breeze's own stand-ins, 16 by 16: "." is empty, letters are colours.
const STAND_INS = {
  sword: {
    colors: { a: "#2c4a5c", b: "#7fd6ea", c: "#c9f3fb", h: "#5b3a1f", g: "#2c2c35" },
    rows: [
      "..............aa",
      ".............abb",
      "............abca",
      "...........abca.",
      "..........abca..",
      ".........abca...",
      "........abca....",
      ".......abca.....",
      "..g...abca......",
      "..gg.abca.......",
      "...ggbca........",
      "....hgg.........",
      "...hh.gg........",
      "..hh...g........",
      ".hh.............",
      "hh..............",
    ],
  },
  pearl: {
    colors: { a: "#0d3b3a", b: "#1d6f6a", c: "#2fa79a", d: "#8ef0d8" },
    rows: [
      "................",
      "................",
      "................",
      "................",
      "......aaaa......",
      ".....abbbba.....",
      "....abcccdba....",
      "....abccddba....",
      "....abcccdba....",
      "....abbccbba....",
      ".....abbbba.....",
      "......aaaa......",
      "................",
      "................",
      "................",
      "................",
    ],
  },
};

function standInPixels(kind) {
  const { colors, rows } = STAND_INS[kind];
  const data = new Uint8ClampedArray(16 * 16 * 4);
  const c = new Color();
  rows.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch === ".") return;
    c.set(colors[ch]);
    const i = (y * 16 + x) * 4;
    data[i] = c.r * 255; data[i + 1] = c.g * 255; data[i + 2] = c.b * 255; data[i + 3] = 255;
  }));
  return { width: 16, height: 16, data, fromGame: false };
}

async function gamePixels(name) {
  try {
    const url = await readGameItemPicture(name);
    if (!url) return null;
    const img = new Image();
    img.src = url;
    await img.decode();
    const w = img.naturalWidth;
    const h = img.naturalHeight;
    if (!w || w > 64 || h !== w) return null;
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    ctx.drawImage(img, 0, 0);
    return { width: w, height: h, data: ctx.getImageData(0, 0, w, h).data, fromGame: true };
  } catch {
    return null;
  }
}

/** The item's picture as little cubes, centred on the picture, one unit a pixel. */
export function voxelGeometry({ width, height, data }) {
  const parts = [];
  const color = new Color();
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const i = (y * width + x) * 4;
      if (data[i + 3] < 128) continue;
      const box = new BoxGeometry(1, 1, 1);
      box.translate(x - width / 2 + 0.5, height / 2 - y - 0.5, 0);
      color.setRGB(data[i] / 255, data[i + 1] / 255, data[i + 2] / 255);
      // Stored as sRGB bytes; the material works in linear light.
      color.convertSRGBToLinear();
      const n = box.attributes.position.count;
      const colors = new Float32Array(n * 3);
      for (let v = 0; v < n; v++) { colors[v * 3] = color.r; colors[v * 3 + 1] = color.g; colors[v * 3 + 2] = color.b; }
      box.setAttribute("color", new BufferAttribute(colors, 3));
      parts.push(box);
    }
  }
  if (!parts.length) return null;
  const merged = mergeGeometries(parts, false);
  parts.forEach((p) => p.dispose());
  return merged;
}

const loaded = new Map();
/** The item's cubes and how it is held, loaded once per session. */
export function loadItem(name) {
  if (!loaded.has(name)) {
    const meta = ITEMS[name];
    loaded.set(name, (async () => {
      if (!meta) return null;
      const pixels = (await gamePixels(name)) || standInPixels(meta.stand);
      const geometry = voxelGeometry(pixels);
      if (!geometry) return null;
      return { name, meta, geometry, fromGame: pixels.fromGame, width: pixels.width };
    })());
  }
  return loaded.get(name);
}

/**
 * Breeze's own cat (not Minecraft's model or texture): a ginger tabby of a
 * few boxes, its head to +z, standing on y = 0, about the size of a
 * Minecraft cat next to the player. Parts are returned so the moment can
 * walk it, sit it and swish its tail.
 */
function box(w, h, d, color, x, y, z) {
  const m = new Mesh(new BoxGeometry(w, h, d), new MeshLambertMaterial({ color }));
  m.position.set(x, y, z);
  return m;
}

export function buildCat() {
  const ginger = "#e39a4c";
  const dark = "#b86a2a";
  const cream = "#f6d9b0";
  const root = new Group();
  root.name = "breeze-cat";
  // The hip: the body turns about it when the cat sits.
  const hip = new Group();
  hip.position.set(0, 4, -4.5);
  root.add(hip);
  const body = new Group();
  hip.add(body);
  body.add(box(4, 3.6, 9.5, ginger, 0, 0.6, 4.5));
  body.add(box(4.1, 0.7, 1.2, dark, 0, 2.2, 2.2));
  body.add(box(4.1, 0.7, 1.2, dark, 0, 2.2, 4.8));
  body.add(box(4.1, 0.7, 1.2, dark, 0, 2.2, 7.2));
  body.add(box(3, 0.6, 7, cream, 0, -1.2, 4.6));
  const head = new Group();
  head.position.set(0, 2.2, 9.6);
  body.add(head);
  head.add(box(4.6, 3.8, 3.8, ginger, 0, 0.4, 1.2));
  head.add(box(2.6, 1.4, 1, cream, 0, -0.6, 3.4));
  head.add(box(0.8, 1.4, 1, dark, -1.5, 2.9, 0.8));
  head.add(box(0.8, 1.4, 1, dark, 1.5, 2.9, 0.8));
  head.add(box(0.8, 0.8, 0.3, "#2e8b57", -1.1, 0.9, 3.2));
  head.add(box(0.8, 0.8, 0.3, "#2e8b57", 1.1, 0.9, 3.2));
  head.add(box(0.6, 0.4, 0.3, "#e07a8a", 0, 0, 4));
  const legs = [];
  for (const [x, z, front] of [[-1.3, 8.4, true], [1.3, 8.4, true], [-1.3, 1, false], [1.3, 1, false]]) {
    const leg = new Group();
    leg.position.set(x, -0.8, z);
    leg.add(box(1.3, 3.6, 1.3, ginger, 0, -1.8, 0));
    leg.add(box(1.35, 0.7, 1.35, cream, 0, -3.3, 0));
    leg.userData.front = front;
    body.add(leg);
    legs.push(leg);
  }
  const tail = new Group();
  tail.position.set(0, 1.6, 0);
  body.add(tail);
  tail.add(box(1, 1, 7, ginger, 0, 0, -3.5));
  tail.add(box(1.05, 1.05, 1.4, dark, 0, 0, -6.6));
  return { root, hip, body, head, legs, tail };
}

function heartGeometry() {
  const rows = [".x.x.", "xxxxx", "xxxxx", ".xxx.", "..x.."];
  const parts = [];
  rows.forEach((row, y) => [...row].forEach((ch, x) => {
    if (ch !== "x") return;
    const b = new BoxGeometry(1, 1, 1);
    b.translate(x - 2, 2 - y, 0);
    parts.push(b);
  }));
  const merged = mergeGeometries(parts, false);
  parts.forEach((p) => p.dispose());
  return merged;
}

const PARTICLE_COLORS = ["#c77dff", "#e0aaff", "#9d4edd", "#f3d9ff"];

/**
 * The props on one stage: the holder on the arm, the item in it, a pearl in
 * flight and the purple particles where it lands.
 */
export class PropRig {
  constructor() {
    this.items = new Map();
    this.holder = null;
    this.inner = null;
    this.shown = null;
    this.material = new MeshLambertMaterial({ vertexColors: true });
    this.flight = null;
    this.particles = [];
    this.offset = new Vector3();
    this.offsetFrom = null;
    this.cat = null;
    this.hearts = [];
    this.heartGeometry = null;
    for (const name of Object.keys(ITEMS)) loadItem(name).then((it) => { if (it) this.items.set(name, it); });
  }

  /** Put the holder back at the fist with no turn, before a moment's pose is added. */
  reset(player) {
    const arm = player?.skin?.rightArm;
    if (!arm) return;
    if (!this.holder || this.holder.parent !== arm) {
      this.holder = new Group();
      this.holder.name = "breeze-right-item";
      this.inner = new Group();
      this.holder.add(this.inner);
      arm.add(this.holder);
      player.rightItem = this.holder;
    }
    // The fist: the bottom of the arm, a little to the front.
    this.holder.position.set(-1, -10.5, 0.5);
    this.holder.quaternion.identity();
  }

  setItem(name) {
    if (this.shown?.name === name) return true;
    const item = name ? this.items.get(name) : null;
    if (this.mesh) { this.inner.remove(this.mesh); this.mesh = null; }
    this.shown = null;
    if (!item || !this.inner) return false;
    const { meta, geometry, width } = item;
    const mesh = new Mesh(geometry, this.material);
    const s = meta.size / width;
    mesh.scale.setScalar(s);
    const [gx, gy] = meta.grip.map((v) => (v / 16) * width);
    mesh.position.set(-(gx - width / 2) * s, -(width / 2 - gy) * s, 0);
    this.mesh = mesh;
    this.inner.add(mesh);
    if (meta.stand === "sword") {
      // Blade forward and a little up, its flat side to the player's side.
      this.inner.rotation.set(Math.PI / 2, Math.PI / 2, Math.PI / 4, "XYZ");
    } else {
      this.inner.rotation.set(0, Math.PI / 2, 0, "XYZ");
    }
    this.shown = item;
    return true;
  }

  /** Ender particles: glowing purple bits rising around a point, as when an ender pearl lands. */
  burst(parent, at, count) {
    for (let i = 0; i < count; i++) {
      const size = 0.9 + Math.random() * 0.8;
      const m = new Mesh(
        new BoxGeometry(size, size, size),
        new MeshBasicMaterial({ color: PARTICLE_COLORS[i % PARTICLE_COLORS.length], transparent: true, blending: AdditiveBlending, depthWrite: false }),
      );
      m.position.set(at.x + (Math.random() - 0.5) * 9, at.y + Math.random() * 32, at.z + (Math.random() - 0.5) * 9);
      parent.add(m);
      this.particles.push({ m, v: new Vector3((Math.random() - 0.5) * 7, 3 + Math.random() * 7, (Math.random() - 0.5) * 7), life: 0, max: 1.0 + Math.random() * 0.7 });
    }
  }

  /**
   * After the pose: show the item while the moment holds it, fly a thrown
   * pearl, burst particles where it lands and move the player there (easing
   * back by the end of the moment). Returns the offset to add to the
   * player's position.
   */
  update(player, props, elapsed, length, dt, turn) {
    const parent = player?.parent;
    // Particles live on after their moment.
    for (const p of this.particles) {
      p.life += dt;
      p.m.position.addScaledVector(p.v, dt);
      p.v.multiplyScalar(Math.max(0, 1 - dt * 1.5));
      p.m.material.opacity = Math.max(0, 1 - p.life / p.max);
    }
    this.particles = this.particles.filter((p) => {
      if (p.life < p.max) return true;
      p.m.parent?.remove(p.m);
      p.m.geometry.dispose();
      p.m.material.dispose();
      return false;
    });

    for (const h of this.hearts) {
      h.life += dt;
      h.m.position.y += dt * 6;
      h.m.material.opacity = Math.max(0, 1 - h.life / 1.1);
    }
    this.hearts = this.hearts.filter((h) => {
      if (h.life < 1.1) return true;
      h.m.parent?.remove(h.m);
      h.m.material.dispose();
      return false;
    });

    if (!props) {
      this.setItem(null);
      this.dropFlight();
      if (this.cat) this.cat.root.visible = false;
      this.offset.set(0, 0, 0);
      this.offsetFrom = null;
      return this.offset;
    }
    if (props.companion === "cat" && parent) this.updateCat(parent, props, elapsed, turn, dt);
    const [from, to] = props.show || [0, 0];
    const inHand = Boolean(props.item) && elapsed >= from && elapsed < to;
    this.setItem(inHand ? props.item : null);

    if (props.throw != null && parent) {
      // Thrown the way the player faces, but mostly across the stage: a jump
      // towards the camera reads as a step closer, not a teleport.
      const forward = new Vector3(Math.sin(turn), 0, Math.cos(turn) * 0.25);
      if (forward.lengthSq() < 1e-4) forward.set(-1, 0, 0);
      forward.normalize();
      if (elapsed >= props.throw && elapsed < props.land && !this.flight && this.items.get(props.item)) {
        const start = new Vector3();
        (this.holder || player).getWorldPosition(start);
        parent.worldToLocal(start);
        const end = forward.clone().multiplyScalar(props.jump || 7).add(new Vector3(0, -14.5, 0));
        const it = this.items.get(props.item);
        const mesh = new Mesh(it.geometry, this.material);
        mesh.scale.setScalar(it.meta.size / it.width);
        parent.add(mesh);
        this.flight = { mesh, start, end };
      }
      if (this.flight) {
        const f = Math.min(1, Math.max(0, (elapsed - props.throw) / (props.land - props.throw)));
        const { mesh, start, end } = this.flight;
        mesh.position.lerpVectors(start, end, f);
        mesh.position.y += Math.sin(f * Math.PI) * 9;
        mesh.rotation.set(f * 9, f * 6, 0);
        if (elapsed >= props.land) {
          this.dropFlight();
          this.burst(parent, end, 34);
          this.burst(parent, new Vector3(0, -15, 0), 18);
          this.offsetFrom = { at: props.land, by: forward.multiplyScalar(props.jump || 7) };
        }
      }
      if (this.offsetFrom) {
        // At the landing spot at once, then back to the middle by the end.
        const back = Math.min(1, Math.max(0, (elapsed - this.offsetFrom.at - 0.5) / Math.max(0.3, length - this.offsetFrom.at - 0.6)));
        const ease = back * back * (3 - 2 * back);
        this.offset.copy(this.offsetFrom.by).multiplyScalar(1 - ease);
      } else {
        this.offset.set(0, 0, 0);
      }
    }
    return this.offset;
  }

  /**
   * The cat: walks in to sit at the player's front right, purrs while it is
   * petted (hearts rise), then walks off. Positions are the player's own,
   * turned with the player.
   */
  updateCat(parent, props, elapsed, turn, dt) {
    if (!this.cat) this.cat = buildCat();
    const cat = this.cat;
    if (cat.root.parent !== parent) parent.add(cat.root);
    const toStage = (x, z) => new Vector3(x * Math.cos(turn) + z * Math.sin(turn), -16, -x * Math.sin(turn) + z * Math.cos(turn));
    const spot = toStage(-8, 7);
    const from = toStage(-34, 9);
    const away = toStage(-36, 14);
    const [e0, e1] = props.enter;
    const [, s1] = props.sit;
    const [, l1] = props.leave;
    const ease = (f) => f * f * (3 - 2 * f);
    let pos;
    let walking = false;
    let face;
    if (elapsed < e1) {
      const f = ease(Math.min(1, Math.max(0, (elapsed - e0) / (e1 - e0))));
      pos = from.clone().lerp(spot, f);
      walking = f < 0.98;
      face = spot.clone().sub(from);
    } else if (elapsed < s1) {
      pos = spot.clone();
      face = new Vector3(0, -16, 0).sub(spot);
    } else {
      const f = ease(Math.min(1, Math.max(0, (elapsed - s1) / (l1 - s1))));
      pos = spot.clone().lerp(away, f);
      walking = true;
      face = away.clone().sub(spot);
    }
    cat.root.visible = elapsed < l1 - 0.02;
    cat.root.position.copy(pos);
    const yaw = Math.atan2(face.x, face.z);
    // The short way round.
    const turnBy = Math.atan2(Math.sin(yaw - cat.root.rotation.y), Math.cos(yaw - cat.root.rotation.y));
    cat.root.rotation.y += turnBy * Math.min(1, dt * 10);
    const sitting = elapsed >= e1 && elapsed < s1;
    const sitAmount = sitting ? Math.min(1, (elapsed - e1) / 0.3, (s1 - elapsed) / 0.3) : 0;
    cat.hip.rotation.x = -0.55 * sitAmount;
    const step = walking ? Math.sin(elapsed * 14) * 0.6 : 0;
    cat.legs.forEach((leg, i) => {
      const swing = (i === 0 || i === 3 ? step : -step);
      // Front legs stay upright while it sits; back legs fold under it.
      leg.rotation.x = leg.userData.front ? swing + 0.55 * sitAmount : swing - 1.1 * sitAmount;
    });
    cat.body.position.y = walking ? Math.abs(Math.sin(elapsed * 14)) * 0.25 : 0;
    cat.tail.rotation.x = 0.5 + 0.2 * sitAmount;
    cat.tail.rotation.y = Math.sin(elapsed * (sitting ? 3 : 6)) * 0.45;
    const petted = props.hearts && elapsed >= props.hearts[0] && elapsed < props.hearts[1];
    cat.head.rotation.z = petted ? Math.sin(elapsed * 9) * 0.18 : 0;
    cat.head.rotation.x = sitting ? -0.25 : 0;
    if (petted && (!this.lastHeart || elapsed - this.lastHeart > 0.35)) {
      this.lastHeart = elapsed;
      if (!this.heartGeometry) this.heartGeometry = heartGeometry();
      const m = new Mesh(this.heartGeometry, new MeshBasicMaterial({ color: "#ff4d6d", transparent: true, depthWrite: false }));
      m.scale.setScalar(0.55);
      m.position.copy(spot).add(new Vector3((Math.random() - 0.5) * 3, 13, (Math.random() - 0.5) * 3));
      m.rotation.y = Math.atan2(-spot.x, 60 - spot.z);
      parent.add(m);
      this.hearts.push({ m, life: 0 });
    }
    if (!petted && elapsed < (props.hearts?.[0] ?? 0)) this.lastHeart = 0;
  }

  dropFlight() {
    if (!this.flight) return;
    this.flight.mesh.parent?.remove(this.flight.mesh);
    this.flight = null;
  }
}
