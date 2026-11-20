package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class TimeChanger extends Module {

    private static TimeChanger instance;

    private long time = 6000L;

    public TimeChanger() {
        super("Time Changer", Category.UTILITY, "Holds the client-side world time of day.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static long time() {
        return instance != null ? instance.time : 6000L;
    }

    public void setTime(long value) {
        this.time = value;
    }
}
