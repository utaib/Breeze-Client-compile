package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class TitleTweaker extends Module {

    private static TitleTweaker instance;

    public TitleTweaker() {
        super("Title Tweaker", Category.UTILITY, "Hides on-screen title and subtitle text.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
