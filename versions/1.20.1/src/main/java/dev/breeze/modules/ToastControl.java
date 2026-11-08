package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class ToastControl extends Module {

    private static ToastControl instance;

    public ToastControl() {
        super("Toast Control", Category.UTILITY, "Hides all toast notifications.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
