package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class MobOverlay extends Module {

    private static MobOverlay instance;

    public MobOverlay() {
        super("Mob Overlay", Category.VISUAL, "Highlights mobs with a glow outline.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
