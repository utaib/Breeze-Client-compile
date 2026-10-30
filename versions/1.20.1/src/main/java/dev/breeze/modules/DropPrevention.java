package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class DropPrevention extends Module {

    private static DropPrevention instance;

    public DropPrevention() {
        super("Drop Prevention", Category.UTILITY, "Blocks accidental item drops.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
