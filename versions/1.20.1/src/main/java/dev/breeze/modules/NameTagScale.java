package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class NameTagScale extends Module {

    private static NameTagScale instance;

    private float scale = 1.5f;

    public NameTagScale() {
        super("Name Tags", Category.VISUAL, "Scales entity name tags.", KEY_NONE);
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
