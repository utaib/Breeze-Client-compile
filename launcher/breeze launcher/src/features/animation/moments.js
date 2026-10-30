import data from "./breeze-moments.animation.json";
import { parseAnimations } from "./keyframes.js";

/**
 * Breeze's own moments for the player on the stage, made for Breeze in
 * Minecraft's Bedrock animation format (breeze-moments.animation.json opens
 * in Blockbench): looking around, checking the cape, a wave, a hop and a
 * stretch. Motion-capture clips converted to the same format join them here.
 */
export const BREEZE_MOMENTS = [...parseAnimations(data).clips.values()];
