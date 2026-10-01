package dev.breeze.render;

/**
 * Low Fire: how far to lower the first-person fire overlay.
 *
 * The overlay is two quads, each one unit tall, drawn half a unit in front of
 * the camera and pushed 0.3 down, so their tops sit at +0.2. At the default
 * field of view the top of the screen is about +0.35 at that depth, which puts
 * the bottom of the screen near -0.35: lowering by 0.55 takes the flames off
 * screen. The slider maps 0-100% onto that range, and 100% skips drawing
 * altogether, which is what the old No Fire Overlay module did.
 */
public final class FireOverlay {

    /** At this reduction the overlay is not drawn at all. */
    public static final int HIDDEN = 100;

    /** Lowering, in the overlay's own units, that takes it off screen. */
    public static final float FULL_DROP = 0.55f;

    private FireOverlay() {}

    public static boolean hidden(int percent) {
        return percent >= HIDDEN;
    }

    /** How far down to move the overlay for a reduction of 0-100%. */
    public static float drop(int percent) {
        int p = Math.max(0, Math.min(HIDDEN, percent));
        return FULL_DROP * p / HIDDEN;
    }
}
