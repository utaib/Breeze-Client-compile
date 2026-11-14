package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import com.mojang.blaze3d.platform.InputConstants;

public class Zoom extends Module {

    private static Zoom instance;

    public Zoom() {
        super("Zoom", Category.VISUAL, "Toggles a zoomed-in FOV.", InputConstants.KEY_C);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
