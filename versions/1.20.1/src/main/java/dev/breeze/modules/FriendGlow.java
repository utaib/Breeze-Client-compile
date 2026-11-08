package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class FriendGlow extends Module {

    private static FriendGlow instance;

    public FriendGlow() {
        super("Friend Glow", Category.VISUAL, "Glows players marked as friends.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
