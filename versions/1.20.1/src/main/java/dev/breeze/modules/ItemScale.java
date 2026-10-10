package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class ItemScale extends Module {

    private static ItemScale instance;

    private float scale = 1.5f;

    public ItemScale() {
        super("Item Scale", Category.VISUAL, "Resizes the held item model.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static float scale() {
        return instance != null ? instance.scale : 1.5f;
    }

    public void setScale(float value) {
        this.scale = value;
    }
}
