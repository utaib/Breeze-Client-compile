/**
 * Cosmetics on the player (docs/COSMETICS.md).
 *
 * Puts a cosmetic model where it belongs on a skinview3d player, at the right
 * size and pose, and keeps it alive: the right clip for its state, flying pets
 * that follow, trails that are left behind. The mod is meant to reproduce the
 * same anchors, sizes and behaviour, so everything that decides placement is in
 * the ATTACHMENTS table and the controllers below, not spread through the UI.
 *
 * Units are the player model's: Minecraft pixels, 1/16 of a block. The player
 * faces +Z, its feet are at y = -16 and the top of its head at y = +16.
 */

import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";
import { clone as cloneSkinned } from "three/examples/jsm/utils/SkeletonUtils.js";

/**
 * Where each attachment sits.
 *
 * parent: the skinview3d part it moves with ("head", "body", "rightArm") or
 *   "wrapper" for things that are near the player rather than on them, which
 *   turn with the player but do not bob with its walk.
 * position: the anchor in that parent's space.
 * align: which point of the model's bounds sits on the anchor. "bottom" rests
 *   it on top, "front" presses its front (+Z) against the anchor, "center"
 *   centres it.
 * size: the model's longest side at scale 1, in pixels.
 * state: the animation role it plays by default.
 */
export const ATTACHMENTS = Object.freeze({
  HEAD: { parent: "head", position: [0, 8, 0], align: "bottom", size: 10, state: "idle" },
  SHOULDER: { parent: "body", position: [6, 6, 0], align: "bottom", size: 6, state: "sit" },
  BACK: { parent: "body", position: [0, 1, -2], align: "front", size: 16, state: "idle" },
  HAND: { parent: "rightArm", position: [-1, -10, 1], align: "center", size: 8, state: "idle" },
  FEET: { parent: "wrapper", position: [0, -16, 0], align: "bottom", size: 12, state: "idle" },
  SIDE: { parent: "wrapper", position: [13, -16, 2], align: "bottom", size: 9, state: "walk", follow: "walk" },
  FLYING_PET: { parent: "wrapper", position: [12, 8, -3], align: "center", size: 8, state: "fly", follow: "fly" },
  TRAIL: { parent: "wrapper", position: [0, -11, -4], align: "center", size: 4, state: "idle", follow: "trail" },
});

/** Where a cosmetic from before the format existed goes: its slot's default. */
const SLOT_DEFAULT_ATTACHMENT = {
  hat: "HEAD", wings: "BACK", cape: "BACK", back: "BACK",
  shield: "HAND", pet: "SHOULDER", aura: "FEET", trail: "TRAIL",
};

const DEG = Math.PI / 180;
const DEFAULT_TRANSFORM = Object.freeze({ offset: [0, 0, 0], rotation: [0, 0, 0], scale: 1 });

/** Normalise a transform the same way the API clamps it. */
export function cleanTransform(t) {
  const clamp = (v, lo, hi, d) => (Number.isFinite(Number(v)) ? Math.min(hi, Math.max(lo, Number(v))) : d);
  const vec = (v, limit) => [0, 1, 2].map((i) => clamp(Array.isArray(v) ? v[i] : 0, -limit, limit, 0));
  const src = t && typeof t === "object" ? t : DEFAULT_TRANSFORM;
  return { offset: vec(src.offset, 32), rotation: vec(src.rotation, 360), scale: clamp(src.scale, 0.1, 4, 1) };
}

/**
 * The attachment, transform and animation roles for a cosmetic, whether it
 * carries 1.0.22 metadata or predates it. The transform is the creator's; a
 * player's own placement is given to the rig as an override, never here, so
 * clearing it goes back to the creator's.
 */
export function describeCosmetic(cosmetic) {
  const meta = cosmetic?.metadata && typeof cosmetic.metadata === "object" ? cosmetic.metadata : {};
  const attachment = ATTACHMENTS[meta.attachment] ? meta.attachment : SLOT_DEFAULT_ATTACHMENT[cosmetic?.slot] || "HEAD";
  const roles = { ...(meta.animations?.roles || {}) };
  if (!roles.idle && cosmetic?.idle_animation) roles.idle = cosmetic.idle_animation;
  const extras = meta.animations?.extras || cosmetic?.random_animations || [];
  return {
    attachment,
    transform: cleanTransform(meta.transform),
    roles,
    extras: Array.isArray(extras) ? extras : [],
    bounds: meta.bounds || null,
    chance: Number.isFinite(Number(cosmetic?.animation_chance)) ? Number(cosmetic.animation_chance) : 0.15,
  };
}

function partFor(viewer, parent) {
  const skin = viewer.playerObject?.skin;
  if (parent === "head") return skin?.head;
  if (parent === "body") return skin?.body;
  if (parent === "rightArm") return skin?.rightArm;
  return viewer.playerWrapper;
}

function boundsOf(root, known) {
  if (known && Array.isArray(known.min) && Array.isArray(known.max)) {
    return new THREE.Box3(new THREE.Vector3(...known.min), new THREE.Vector3(...known.max));
  }
  root.updateMatrixWorld(true);
  return new THREE.Box3().setFromObject(root);
}

function alignPoint(box, align) {
  const c = box.getCenter(new THREE.Vector3());
  if (align === "bottom") return new THREE.Vector3(c.x, box.min.y, c.z);
  if (align === "front") return new THREE.Vector3(c.x, c.y, box.max.z);
  return c;
}

/**
 * Plays the clip for the current state, and now and then one of the extras
 * once, for variety. Falls back to idle when a state has no clip, and does
 * nothing for a model without animations.
 */
class ClipDirector {
  constructor(root, clips, roles, extras, chance) {
    this.mixer = clips.length ? new THREE.AnimationMixer(root) : null;
    this.clips = new Map(clips.map((c) => [c.name, c]));
    this.roles = roles;
    this.extras = extras.filter((n) => this.clips.has(n));
    this.chance = chance;
    this.current = null;
    this.state = null;
    this.nextVariety = 4 + Math.random() * 4;
    this.inVariety = false;
    if (this.mixer) {
      this.mixer.addEventListener("finished", () => {
        this.inVariety = false;
        this.play(this.state, true);
      });
    }
  }

  clipFor(state) {
    const name = this.roles[state] || this.roles.idle;
    return (name && this.clips.get(name)) || null;
  }

  play(state, force = false) {
    if (!this.mixer) return;
    if (!force && state === this.state && this.current) return;
    this.state = state;
    if (this.inVariety) return;
    const clip = this.clipFor(state) || this.clips.values().next().value;
    if (!clip) return;
    const next = this.mixer.clipAction(clip);
    next.reset().setLoop(THREE.LoopRepeat, Infinity).play();
    if (this.current && this.current !== next) this.current.crossFadeTo(next, 0.25, false);
    this.current = next;
  }

  update(dt) {
    if (!this.mixer) return;
    this.nextVariety -= dt;
    if (this.nextVariety <= 0 && !this.inVariety && this.extras.length) {
      this.nextVariety = 4 + Math.random() * 4;
      if (Math.random() < this.chance) {
        const clip = this.clips.get(this.extras[Math.floor(Math.random() * this.extras.length)]);
        const once = this.mixer.clipAction(clip);
        once.reset().setLoop(THREE.LoopOnce, 1);
        // Held on its last frame when done, so the fade back to the state clip
        // starts from there. Without this three.js drops the action's weight
        // to zero first and the model snaps to its rest pose for a frame.
        once.clampWhenFinished = true;
        once.play();
        if (this.current && this.current !== once) this.current.crossFadeTo(once, 0.2, false);
        this.current = once;
        this.inVariety = true;
      }
    }
    this.mixer.update(dt);
  }

  dispose(root) {
    if (!this.mixer) return;
    this.mixer.stopAllAction();
    this.mixer.uncacheRoot(root);
  }
}

/**
 * A flying pet: it follows a point that circles the player and bobs, eased by
 * a spring so it drifts rather than snapping, and it faces and banks into the
 * way it is flying. Slows to a hover now and then, when it plays its idle.
 */
export class FlyingFollow {
  constructor(base) {
    this.base = base.clone();
    this.pos = base.clone();
    this.vel = new THREE.Vector3();
    this.angle = 0;
    this.yaw = 0;
    this.t = 0;
    this.state = "fly";
  }

  step(dt, holder) {
    this.t += dt;
    // Orbit speed varies, and for about a third of the time it is zero: the
    // pet stops and hovers beside the player, which is when its idle plays.
    const cycle = Math.sin(this.t * 0.25);
    const pace = cycle < -0.6 ? 0 : 0.45 + 0.25 * cycle;
    this.angle += dt * pace;
    const radius = Math.hypot(this.base.x, this.base.z) || 12;
    const target = new THREE.Vector3(
      Math.cos(this.angle) * radius,
      this.base.y + Math.sin(this.t * 1.4) * 2 + Math.sin(this.t * 0.37) * 1.5,
      Math.sin(this.angle) * radius * 0.85,
    );
    const accel = target.sub(this.pos).multiplyScalar(5).addScaledVector(this.vel, -3.2);
    this.vel.addScaledVector(accel, dt);
    this.pos.addScaledVector(this.vel, dt);
    holder.position.copy(this.pos);
    const speed = Math.hypot(this.vel.x, this.vel.z);
    if (speed > 0.5) {
      const want = Math.atan2(this.vel.x, this.vel.z);
      let diff = want - this.yaw;
      diff = Math.atan2(Math.sin(diff), Math.cos(diff));
      this.yaw += diff * Math.min(1, dt * 4);
      holder.rotation.set(0, this.yaw, THREE.MathUtils.clamp(-diff * 0.6, -0.5, 0.5));
    }
    // Separate thresholds for starting and stopping, so a pet at the edge
    // does not flicker between its two clips.
    if (this.state === "fly" && speed < 1.2) this.state = "idle";
    else if (this.state === "idle" && speed > 3) this.state = "fly";
    return this.state;
  }
}

/** A trail: copies of the model left behind, drifting back, shrinking and fading. */
class TrailEmitter {
  constructor(template, parent) {
    this.template = template;
    this.parent = parent;
    this.particles = [];
    this.spawnIn = 0;
  }

  spawn(origin) {
    const copy = cloneSkinned(this.template);
    const materials = [];
    copy.traverse((o) => {
      if (!o.isMesh) return;
      o.material = Array.isArray(o.material) ? o.material.map((m) => m.clone()) : o.material.clone();
      for (const m of [].concat(o.material)) { m.transparent = true; materials.push(m); }
    });
    copy.position.set(origin.x + (Math.random() - 0.5) * 6, origin.y + (Math.random() - 0.5) * 4, origin.z);
    copy.rotation.y = Math.random() * Math.PI * 2;
    this.parent.add(copy);
    this.particles.push({ obj: copy, materials, age: 0, life: 1.4, base: copy.scale.clone() });
  }

  step(dt, origin) {
    this.spawnIn -= dt;
    if (this.spawnIn <= 0) { this.spawnIn = 0.14; this.spawn(origin); }
    for (const p of [...this.particles]) {
      p.age += dt;
      const k = p.age / p.life;
      if (k >= 1) { this.remove(p); continue; }
      // The player walks forward (+Z), so what it leaves moves back past it.
      p.obj.position.z -= dt * 16;
      p.obj.position.y += dt * 2;
      p.obj.scale.copy(p.base).multiplyScalar(1 - k * 0.8);
      for (const m of p.materials) m.opacity = 1 - k;
    }
  }

  remove(p) {
    this.parent.remove(p.obj);
    for (const m of p.materials) m.dispose();
    this.particles.splice(this.particles.indexOf(p), 1);
  }

  dispose() { for (const p of [...this.particles]) this.remove(p); }
}

function disposeTree(root) {
  root.traverse((o) => {
    if (o.geometry) o.geometry.dispose?.();
    if (o.material) {
      for (const m of [].concat(o.material)) {
        for (const value of Object.values(m)) if (value && value.isTexture) value.dispose();
        m.dispose?.();
      }
    }
  });
}

/**
 * The cosmetics on one viewer. Hook it into the viewer's frame loop with
 * wrapAnimation(); add, restyle and remove cosmetics by key.
 */
export class CosmeticRig {
  constructor(viewer, { onError } = {}) {
    this.viewer = viewer;
    this.items = new Map();
    /** key -> url currently loading, so a re-render does not load twice. */
    this.loading = new Map();
    /** The latest list asked for, read when a load finishes. */
    this.wanted = new Map();
    /** key -> url that failed to load; not retried until the url changes. */
    this.failed = new Map();
    this.loader = new GLTFLoader();
    this.onError = onError;
    this.disposed = false;
  }

  /**
   * Make the rig match a list of { key, cosmetic, url, attachment?, transform? }.
   * Safe to call on every render: only what changed is loaded, removed or
   * restyled. attachment and transform override the cosmetic's own, for
   * previews with live placement controls.
   */
  sync(list) {
    this.wanted = new Map((list || []).filter((c) => c && c.url).map((c) => [c.key, c]));
    for (const [key, item] of [...this.items]) {
      const want = this.wanted.get(key);
      if (!want || want.url !== item.url) this.remove(key);
    }
    for (const [key, loadingUrl] of [...this.loading]) {
      if (this.wanted.get(key)?.url !== loadingUrl) this.loading.delete(key);
    }
    for (const [key, want] of this.wanted) {
      const item = this.items.get(key);
      if (item) { this.applyStyle(item, want); continue; }
      if (this.loading.get(key) === want.url) continue;
      // A model that failed once is not fetched again on every render; a new
      // url for the key is a new attempt.
      if (this.failed.get(key) === want.url) continue;
      this.failed.delete(key);
      this.loading.set(key, want.url);
      this.add(key, want.cosmetic, want.url)
        .then((added) => { if (added) this.applyStyle(added, this.wanted.get(key) || want); })
        .catch((err) => {
          if (this.wanted.get(key)?.url !== want.url) return;
          this.failed.set(key, want.url);
          this.onError?.(key, err);
        })
        .finally(() => { if (this.loading.get(key) === want.url) this.loading.delete(key); });
    }
  }

  applyStyle(item, want) {
    // Animation choices can change without the model changing (the upload
    // form's role selects); the clip director is rebuilt for them.
    const next = describeCosmetic(want.cosmetic);
    const animation = JSON.stringify([next.roles, next.extras, next.chance]);
    if (animation !== item.animation) {
      item.animation = animation;
      Object.assign(item.info, { roles: next.roles, extras: next.extras, chance: next.chance });
      item.director?.dispose(item.model);
      item.director = new ClipDirector(item.model, item.gltf.animations || [], next.roles, next.extras, next.chance);
      item.director.play(ATTACHMENTS[item.info.attachment].state, true);
    }
    const style = JSON.stringify([want.attachment || null, want.transform || null]);
    if (style === (item.style ?? JSON.stringify([null, null]))) return;
    item.style = style;
    // An override that is cleared goes back to the cosmetic's own placement.
    this.restyle(item.key, {
      attachment: want.attachment || item.base.attachment,
      transform: want.transform || item.base.transform,
    });
  }

  /**
   * Wrap the viewer's own animation so the rig ticks every frame it renders,
   * and stops when the viewer pauses (off screen, hidden window).
   */
  wrapAnimation(inner) {
    const rig = this;
    return {
      progress: 0,
      speed: inner?.speed ?? 1,
      paused: false,
      update(player, dt) {
        inner?.update?.(player, dt);
        rig.tick(dt);
      },
    };
  }

  /** Load a cosmetic and put it on the player. Resolves once it is visible. */
  async add(key, cosmetic, url) {
    this.remove(key);
    const info = describeCosmetic(cosmetic);
    const gltf = await this.loader.loadAsync(url);
    // Dropped if the viewer closed, or this key now wants a different model or
    // none at all, whatever order the loads finish in.
    if (this.disposed || this.wanted.get(key)?.url !== url) { disposeTree(gltf.scene); return null; }
    this.remove(key);
    const base = { attachment: info.attachment, transform: info.transform };
    const item = {
      key, url, style: null, base, info, gltf, holder: null, model: gltf.scene, box: null,
      director: null, follow: null, trail: null,
      animation: JSON.stringify([info.roles, info.extras, info.chance]),
    };
    this.items.set(key, item);
    this.build(item);
    return item;
  }

  /** Change attachment or transform in place, for live sliders. */
  restyle(key, { attachment, transform }) {
    const item = this.items.get(key);
    if (!item) return;
    if (attachment && ATTACHMENTS[attachment]) item.info.attachment = attachment;
    if (transform) item.info.transform = cleanTransform(transform);
    this.teardown(item, false);
    this.build(item);
  }

  build(item) {
    const { info, gltf } = item;
    const spec = ATTACHMENTS[info.attachment];
    const { offset, rotation, scale } = info.transform;

    // Measured once, on the model alone. Measuring again on a restyle would
    // read it while still inside the last build's fitted, posed groups.
    if (!item.box) {
      item.model.removeFromParent();
      item.model.position.set(0, 0, 0);
      item.box = boundsOf(item.model, info.bounds);
    }
    const box = item.box;
    const size = box.getSize(new THREE.Vector3());
    const fitted = (spec.size / (Math.max(size.x, size.y, size.z) || 1)) * scale;

    // holder: at the anchor, moved by follow behaviours
    //   pose: the creator's rotation
    //     fit: the size fit
    //       model: shifted so its alignment point is at the origin
    const holder = new THREE.Group();
    // Marks the subtree as a cosmetic, so the viewer's skin texture pass
    // leaves its textures alone.
    holder.userData.breezeCosmetic = true;
    const pose = new THREE.Group();
    const fit = new THREE.Group();
    holder.position.set(spec.position[0] + offset[0], spec.position[1] + offset[1], spec.position[2] + offset[2]);
    pose.rotation.set(rotation[0] * DEG, rotation[1] * DEG, rotation[2] * DEG);
    fit.scale.setScalar(fitted);
    const anchor = alignPoint(box, spec.align);
    item.model.position.set(-anchor.x, -anchor.y, -anchor.z);
    fit.add(item.model);
    pose.add(fit);
    holder.add(pose);

    if (spec.follow === "trail") {
      // The trail's copies live in the wrapper; the original stays hidden as
      // the template they are cloned from.
      item.trail = new TrailEmitter(holder, partFor(this.viewer, "wrapper"));
      item.origin = holder.position.clone();
    } else {
      partFor(this.viewer, spec.parent)?.add(holder);
      if (spec.follow === "fly") item.follow = new FlyingFollow(holder.position);
    }
    item.holder = holder;
    item.director = item.director || new ClipDirector(item.model, gltf.animations || [], info.roles, info.extras, info.chance);
    item.director.play(spec.state, true);
  }

  teardown(item, full) {
    if (item.trail) { item.trail.dispose(); item.trail = null; }
    if (item.holder?.parent) item.holder.parent.remove(item.holder);
    item.follow = null;
    if (full) {
      item.director?.dispose(item.model);
      disposeTree(item.model);
    }
  }

  remove(key) {
    const item = this.items.get(key);
    if (!item) return;
    this.teardown(item, true);
    this.items.delete(key);
  }

  tick(dt) {
    const step = Math.min(dt, 0.1);
    for (const item of this.items.values()) {
      if (item.follow) item.director.play(item.follow.step(step, item.holder));
      if (item.trail) item.trail.step(step, item.origin);
      item.director.update(step);
    }
  }

  dispose() {
    this.disposed = true;
    for (const key of [...this.items.keys()]) this.remove(key);
  }
}
