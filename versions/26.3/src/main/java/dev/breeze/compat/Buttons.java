package dev.breeze.compat;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Mouse button numbers. Breeze's screens and counters use GLFW's: 0 left,
 * 1 right, 2 middle. This is the 26.3 form: Minecraft reads the mouse through
 * SDL 3 and its InputConstants list the buttons left, middle, right, SDL's
 * order, so the three are translated through those constants (compiled in
 * from 26.3 itself). Other buttons pass unchanged.
 */
public final class Buttons {

    private Buttons() {}

    /** A button number from Minecraft, as Breeze numbers it. */
    public static int fromGame(int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) return 0;
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return 1;
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) return 2;
        return button;
    }

    /** A button number as Breeze numbers it, as Minecraft expects it. */
    public static int toGame(int button) {
        return switch (button) {
            case 0 -> InputConstants.MOUSE_BUTTON_LEFT;
            case 1 -> InputConstants.MOUSE_BUTTON_RIGHT;
            case 2 -> InputConstants.MOUSE_BUTTON_MIDDLE;
            default -> button;
        };
    }
}
