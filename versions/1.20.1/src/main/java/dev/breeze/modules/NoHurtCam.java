package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;

public class NoHurtCam extends Module {

    private static NoHurtCam instance;

    public NoHurtCam() {
        super("No Hurt Cam", Category.VISUAL, "Removes the camera tilt when hurt.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }
}
