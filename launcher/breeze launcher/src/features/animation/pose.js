import { Euler, Quaternion } from "three";

/**
 * Puts a sampled keyframe pose (keyframes.js, Bedrock units) on the
 * launcher's 3D player (skinview3d's PlayerObject), on top of whatever pose
 * it already has this frame, scaled by a weight so moments fade in and out.
 *
 * Axes. Bedrock rotations are degrees, applied Z then Y then X, for a model
 * facing -Z (Blockbench shows them with X and Y negated); skinview3d's player
 * faces +Z. Turning the model round negates X and Z again, so a Bedrock
 * [x, y, z] is the viewer's (x, -y, -z), and a position (x, y, -z). The
 * anchors, from Minecraft's own Bedrock animations: an arm at x = -90 points
 * forward (the bow pose), and the right arm's idle z = +2.865 tilts it
 * outwards (the "bob" animation), which is skinview3d's negative z. The cape
 * hangs turned round, and lifts at a negative Bedrock x (the cape flap),
 * which is skinview3d's positive x.
 *
 * Bones: root (the whole player), head, body, rightArm, leftArm, rightLeg,
 * leftLeg, cape (lifting only). In this player each part turns about its own
 * joint, as in Minecraft Java's model: a body bend does not carry the head
 * and arms with it, so Breeze's own animations move the root instead.
 */
const DEG = Math.PI / 180;
const IDENTITY = new Quaternion();
const euler = new Euler(0, 0, 0, "ZYX");
const full = new Quaternion();
const part = new Quaternion();

export const toViewerRotation = ([x, y, z]) => [x * DEG, -y * DEG, -z * DEG];
export const toViewerPosition = ([x, y, z]) => [x, y, -z];

function partOf(player, bone) {
  if (bone === "root") return player;
  if (bone === "cape") return player.cape;
  return player.skin?.[bone] || null;
}

/** Add `pose` to the player's current pose, `weight` 0 (nothing) to 1 (all of it). */
export function applyPose(player, pose, weight = 1) {
  const w = Math.min(1, Math.max(0, Number(weight) || 0));
  if (!player || !pose || w === 0) return;
  for (const [bone, { rotation, position }] of Object.entries(pose)) {
    const target = partOf(player, bone);
    if (!target) continue;
    if (bone === "cape") {
      target.rotation.x += -rotation[0] * DEG * w;
      continue;
    }
    const [rx, ry, rz] = toViewerRotation(rotation);
    if (rx || ry || rz) {
      euler.set(rx, ry, rz, "ZYX");
      full.setFromEuler(euler);
      part.slerpQuaternions(IDENTITY, full, w);
      // In the part's own frame, after the pose it already has.
      target.quaternion.multiply(part);
    }
    const [px, py, pz] = toViewerPosition(position);
    target.position.x += px * w;
    target.position.y += py * w;
    target.position.z += pz * w;
  }
}
