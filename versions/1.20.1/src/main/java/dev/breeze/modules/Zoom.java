package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import org.lwjgl.glfw.GLFW;

public class Zoom extends Module {

    private static Zoom instance;

    public Zoom() {
        super("Zoom", Category.VISUAL, "Toggles a zoomed-in FOV.", GLFW.GLFW_KEY_C);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
