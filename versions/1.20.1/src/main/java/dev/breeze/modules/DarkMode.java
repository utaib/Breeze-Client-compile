package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class DarkMode extends Module {

    private static DarkMode instance;

    public DarkMode() {
        super("Dark Mode", Category.VISUAL, "Darkens GUI backgrounds.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
