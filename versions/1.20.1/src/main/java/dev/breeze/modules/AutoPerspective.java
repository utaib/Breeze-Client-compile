package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;

public class AutoPerspective extends Module {

    private boolean switched;

    public AutoPerspective() {
        super("Auto Perspective", Category.UTILITY, "Third-person while flying or riding.", KEY_NONE);
    }

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.player == null || mc.options == null) return;
        boolean want = mc.player.isFallFlying() || mc.player.isPassenger();
        if (want && !switched && mc.options.getCameraType() == CameraType.FIRST_PERSON) {
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            switched = true;
        } else if (!want && switched) {
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            switched = false;
        }
    }

    @Override
    protected void onDisable() {
        if (switched) {
            try {
                Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
            } catch (Throwable ignored) {}
            switched = false;
        }
    }
}
