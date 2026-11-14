package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;

public class SmoothCamera extends Module {

    private boolean previous;

    public SmoothCamera() {
        super("Smooth Camera", Category.VISUAL, "Cinematic camera smoothing.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            previous = mc.options.smoothCamera;
            mc.options.smoothCamera = true;
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft.getInstance().options.smoothCamera = previous;
        } catch (Throwable ignored) {}
    }
}
