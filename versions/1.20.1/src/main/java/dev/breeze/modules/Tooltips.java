package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class Tooltips extends Module {

    private static Tooltips instance;

    public Tooltips() {
        super("Tooltips", Category.UTILITY, "Adds the item id to tooltips.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
