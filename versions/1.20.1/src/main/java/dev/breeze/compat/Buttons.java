package dev.breeze.compat;

/**
 * Mouse button numbers. Breeze's screens and counters use GLFW's: 0 left,
 * 1 right, 2 middle. Minecraft passed GLFW's numbers through until 26.3,
 * which reads the mouse through SDL 3 and numbers the buttons SDL's way, so
 * versions/ has a copy of this file per form; this is the form up to 26.2,
 * where both directions are the identity.
 */
public final class Buttons {

    private Buttons() {}

    /** A button number from Minecraft, as Breeze numbers it. */
    public static int fromGame(int button) {
        return button;
    }

    /** A button number as Breeze numbers it, as Minecraft expects it. */
    public static int toGame(int button) {
        return button;
    }
}
