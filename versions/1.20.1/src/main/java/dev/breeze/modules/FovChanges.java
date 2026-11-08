package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class FovChanges extends Module {

    private static FovChanges instance;

    public FovChanges() {
        super("FOV Changes", Category.VISUAL, "Disables FOV shift from sprint and speed.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
