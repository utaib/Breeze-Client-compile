package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;

public class NoViewBobbing extends Module {

    private boolean previous;

    public NoViewBobbing() {
        super("No View Bobbing", Category.VISUAL, "Disables view bobbing.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            previous = dev.breeze.compat.Toggles.bobView(mc);
            dev.breeze.compat.Toggles.setBobView(mc, false);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            dev.breeze.compat.Toggles.setBobView(Minecraft.getInstance(), previous);
        } catch (Throwable ignored) {}
    }
}
