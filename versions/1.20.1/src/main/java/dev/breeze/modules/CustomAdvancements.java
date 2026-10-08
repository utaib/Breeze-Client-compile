package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class CustomAdvancements extends Module {

    private static CustomAdvancements instance;

    public CustomAdvancements() {
        super("Custom Advancements", Category.UTILITY, "Hides advancement toasts.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
