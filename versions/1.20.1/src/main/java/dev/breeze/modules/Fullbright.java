package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import org.lwjgl.glfw.GLFW;

/**
 * Lights the world fully without changing the player's brightness setting;
 * the work is in LightTextureMixin.
 *
 * This used to set the brightness option to 100 on enable and restore it on
 * disable. The option has only accepted 0 to 1 since 1.19, so the value was
 * refused, brightness went back to its default instead, and a crash while on
 * could have left that default saved.
 */
public class Fullbright extends Module {

    /**
     * The brightness the lightmap sees while on: 15, where the slider stops at
     * 1. The lightmap blends towards full light by this factor and then clamps,
     * so dark areas come out fully lit.
     */
    public static final Double GAMMA = 15.0;

    private static Fullbright instance;

    public Fullbright() {
        super("Fullbright", Category.UTILITY, "Lights everything up fully, without changing your brightness setting.", GLFW.GLFW_KEY_G);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
