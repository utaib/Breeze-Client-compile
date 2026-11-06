import data from "./breeze-moments.animation.json";
import { parseAnimations } from "./keyframes.js";

/**
 * Breeze's own moments for the player on the stage, made for Breeze in
 * Minecraft's Bedrock animation format (breeze-moments.animation.json opens
 * in Blockbench): looking around, checking the cape, a wave, a hop, a
 * stretch, and the flashier spin, backflip, dance and cheer the owner asked
 * for after trying them (1.1.0_test_3). Motion-capture clips converted to
 * the same format join them here. Two hold an item from the game (props.js):
 * looking over a sword, and throwing an ender pearl to teleport.
 */
export const BREEZE_MOMENTS = [...parseAnimations(data).clips.values()];

/** What each moment is called where a player can ask for one. */
export const MOMENT_LABELS = {
  "animation.breeze.look_around": "Look around",
  "animation.breeze.cape_check": "Check the cape",
  "animation.breeze.wave": "Wave",
  "animation.breeze.hop": "Hop",
  "animation.breeze.stretch": "Stretch",
  "animation.breeze.spin": "Spin",
  "animation.breeze.backflip": "Backflip",
  "animation.breeze.dance": "Dance",
  "animation.breeze.cheer": "Cheer",
  "animation.breeze.sword_inspect": "Look over a sword",
  "animation.breeze.pearl_teleport": "Ender pearl",
};

/**
 * Moments that hold an item (props.js): which item, when it is in the hand
 * ([from, to] seconds) and, for a thrown pearl, when it leaves the hand,
 * when it lands and how far the player is carried. Read from each clip's
 * "breeze_props" in the animation file.
 */
export const MOMENT_PROPS = Object.fromEntries(
  Object.entries(data.animations || {})
    .filter(([, a]) => a && a.breeze_props && Array.isArray(a.breeze_props.show))
    .map(([name, a]) => [name, a.breeze_props]),
);
