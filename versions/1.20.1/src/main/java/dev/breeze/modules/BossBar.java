package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class BossBar extends Module {

    private static BossBar instance;

    public BossBar() {
        super("Boss Bar", Category.VISUAL, "Hides the boss health bar.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
