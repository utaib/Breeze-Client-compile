/**
 * A small player for keyframe animations in Minecraft's Bedrock animation
 * format (what Blockbench exports as "Bedrock animation", and what GeckoLib,
 * PlayerAnimator and the Player Animation Library read), so one file can
 * animate the player in the launcher and, later, in game.
 *
 *   { "format_version": "1.8.0", "animations": { "<name>": {
 *       "loop": true | false | "hold_on_last_frame",
 *       "animation_length": <seconds>,
 *       "bones": { "<bone>": {
 *         "rotation": [x, y, z] | { "<seconds>": [x, y, z] | { "pre": [..], "post": [..], "lerp_mode": "linear" | "catmullrom" | "step" } },
 *         "position": same shape } } } } }
 *
 * Rotations are degrees and positions pixels, in Bedrock's axes; pose.js
 * turns them into the launcher viewer's. Only numbers are played: Molang
 * expressions (strings) are reported and skipped, as are scale, sounds and
 * particles. The input is never trusted: sizes are capped, values must be
 * finite and in range, and unknown bones are dropped with a note, so a bad
 * file gives a smaller animation or none, never an exception at draw time.
 *
 * Everything here is pure (no three.js, no DOM) so it is tested in Node.
 */

/** The bones the launcher's player has, by their Bedrock names (any case, - or _). */
export const BONES = ["root", "head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg", "cape"];
const BONE_BY_KEY = new Map(BONES.map((b) => [b.toLowerCase(), b]));
const CHANNELS = ["rotation", "position"];
const LIMITS = { animations: 64, bones: 16, keyframes: 256, length: 60, rotation: 1080, position: 64 };

const boneName = (raw) => BONE_BY_KEY.get(String(raw).replace(/[-_]/g, "").toLowerCase()) || null;

function vec(raw, max) {
  let v = raw;
  if (typeof v === "number") v = [v, v, v];
  if (!Array.isArray(v) || v.length !== 3) return null;
  const out = v.map((n) => (typeof n === "string" && /^-?\d+(\.\d+)?$/.test(n.trim()) ? Number(n) : n));
  return out.every((n) => typeof n === "number" && Number.isFinite(n) && Math.abs(n) <= max) ? out : null;
}

function channel(raw, max, where, problems) {
  const constant = vec(raw, max);
  if (constant) return [{ t: 0, pre: constant, post: constant, lerp: "linear" }];
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    problems.push(`${where}: not numbers`);
    return null;
  }
  const keys = [];
  for (const [time, value] of Object.entries(raw).slice(0, LIMITS.keyframes)) {
    const t = Number(time);
    if (!Number.isFinite(t) || t < 0 || t > LIMITS.length) {
      problems.push(`${where}: time ${String(time).slice(0, 12)}`);
      continue;
    }
    const isObj = value && typeof value === "object" && !Array.isArray(value);
    const post = vec(isObj ? (value.post ?? value.pre) : value, max);
    const pre = isObj ? vec(value.pre ?? value.post, max) : post;
    if (!post || !pre) {
      problems.push(`${where} at ${t}: not numbers`);
      continue;
    }
    const lerp = isObj && ["catmullrom", "step"].includes(value.lerp_mode) ? value.lerp_mode : "linear";
    keys.push({ t, pre, post, lerp });
  }
  keys.sort((a, b) => a.t - b.t);
  return keys.length ? keys : null;
}

/**
 * Read an animation file. Returns { clips: Map<name, clip>, problems: [] };
 * a clip is { name, length, loop, bones: { <bone>: { rotation?, position? } } }.
 */
export function parseAnimations(raw) {
  const problems = [];
  const clips = new Map();
  const animations = raw && typeof raw === "object" ? raw.animations : null;
  if (!animations || typeof animations !== "object" || Array.isArray(animations)) {
    return { clips, problems: ["no animations"] };
  }
  for (const [name, anim] of Object.entries(animations).slice(0, LIMITS.animations)) {
    if (!anim || typeof anim !== "object" || !anim.bones || typeof anim.bones !== "object") {
      problems.push(`${name}: no bones`);
      continue;
    }
    const bones = {};
    let lastKey = 0;
    for (const [rawBone, data] of Object.entries(anim.bones).slice(0, LIMITS.bones)) {
      const bone = boneName(rawBone);
      if (!bone) {
        problems.push(`${name}: bone ${String(rawBone).slice(0, 24)} is not on the player`);
        continue;
      }
      if (!data || typeof data !== "object") continue;
      for (const key of Object.keys(data)) {
        if (!CHANNELS.includes(key)) problems.push(`${name}.${bone}: ${String(key).slice(0, 16)} is not played`);
      }
      for (const ch of CHANNELS) {
        if (data[ch] === undefined) continue;
        const keys = channel(data[ch], ch === "rotation" ? LIMITS.rotation : LIMITS.position, `${name}.${bone}.${ch}`, problems);
        if (!keys) continue;
        (bones[bone] ||= {})[ch] = keys;
        lastKey = Math.max(lastKey, keys[keys.length - 1].t);
      }
    }
    if (!Object.keys(bones).length) {
      problems.push(`${name}: nothing to play`);
      continue;
    }
    const declared = Number(anim.animation_length);
    const length = Number.isFinite(declared) && declared > 0 ? Math.min(declared, LIMITS.length) : lastKey;
    const loop = anim.loop === true ? true : anim.loop === "hold_on_last_frame" ? "hold" : false;
    clips.set(name, { name, length, loop, bones });
  }
  return { clips, problems };
}

const lerp = (a, b, f) => a + (b - a) * f;

/** Catmull-Rom through p1..p2 (as three.js's Interpolations.CatmullRom, which Blockbench uses). */
function catmullRom(f, p0, p1, p2, p3) {
  const v0 = (p2 - p0) * 0.5;
  const v1 = (p3 - p1) * 0.5;
  const f2 = f * f;
  const f3 = f * f2;
  return (2 * p1 - 2 * p2 + v0 + v1) * f3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * f2 + v0 * f + p1;
}

/** One channel's [x, y, z] at time t (seconds). */
export function sampleChannel(keys, t) {
  if (!keys?.length) return [0, 0, 0];
  if (t <= keys[0].t) return keys[0].pre.slice();
  const last = keys[keys.length - 1];
  if (t >= last.t) return last.post.slice();
  let i = 0;
  while (keys[i + 1].t <= t) i++;
  const a = keys[i];
  const b = keys[i + 1];
  if (a.lerp === "step") return a.post.slice();
  const f = (t - a.t) / (b.t - a.t);
  if (a.lerp === "catmullrom" || b.lerp === "catmullrom") {
    const before = keys[i - 1] || a;
    const after = keys[i + 2] || b;
    return [0, 1, 2].map((k) => catmullRom(f, before.post[k], a.post[k], b.pre[k], after.pre[k]));
  }
  return [0, 1, 2].map((k) => lerp(a.post[k], b.pre[k], f));
}

/** Where a clip is at `elapsed` seconds since it started, after looping or holding. */
export function clipTime(clip, elapsed) {
  if (clip.length <= 0) return 0;
  if (clip.loop === true) return ((elapsed % clip.length) + clip.length) % clip.length;
  return Math.min(Math.max(0, elapsed), clip.length);
}

/** Has a clip that does not loop played to its end? */
export const clipDone = (clip, elapsed) => clip.loop !== true && clip.loop !== "hold" && elapsed >= clip.length;

/** The pose at `elapsed` seconds: { <bone>: { rotation: [..], position: [..] } } in Bedrock units. */
export function samplePose(clip, elapsed) {
  const t = clipTime(clip, elapsed);
  const pose = {};
  for (const [bone, chans] of Object.entries(clip.bones)) {
    pose[bone] = {
      rotation: chans.rotation ? sampleChannel(chans.rotation, t) : [0, 0, 0],
      position: chans.position ? sampleChannel(chans.position, t) : [0, 0, 0],
    };
  }
  return pose;
}
