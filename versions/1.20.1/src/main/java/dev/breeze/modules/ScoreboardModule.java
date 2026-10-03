package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class ScoreboardModule extends Module {

    private static ScoreboardModule instance;

    public ScoreboardModule() {
        super("Scoreboard", Category.UTILITY, "Hides the sidebar scoreboard.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
