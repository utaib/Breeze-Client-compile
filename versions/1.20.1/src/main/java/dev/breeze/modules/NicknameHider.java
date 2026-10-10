package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class NicknameHider extends Module {

    private static NicknameHider instance;

    private String mask = "You";

    public NicknameHider() {
        super("Nickname Hider", Category.UTILITY, "Masks your own name in the tab list.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static String mask() {
        return instance != null ? instance.mask : "You";
    }

    public void setMask(String value) {
        if (value != null) this.mask = value;
    }
}
