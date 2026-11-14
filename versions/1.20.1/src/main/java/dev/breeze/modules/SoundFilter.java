package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class SoundFilter extends Module {

    private static SoundFilter instance;

    public SoundFilter() {
        super("Sound Filter", Category.UTILITY, "Mutes sounds in the muted list.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
