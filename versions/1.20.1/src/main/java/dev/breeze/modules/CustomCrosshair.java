package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class CustomCrosshair extends Module {

    private static CustomCrosshair instance;

    public CustomCrosshair() {
        super("Custom Crosshair", Category.VISUAL, "Replaces the crosshair with a custom dot.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
