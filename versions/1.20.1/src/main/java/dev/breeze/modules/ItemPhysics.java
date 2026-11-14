package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class ItemPhysics extends Module {

    private static ItemPhysics instance;

    public ItemPhysics() {
        super("Item Physics", Category.VISUAL, "Lays dropped items flat on the ground.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
