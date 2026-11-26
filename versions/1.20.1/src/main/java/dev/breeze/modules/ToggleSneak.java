package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;

public class ToggleSneak extends Module {

    public ToggleSneak() {
        super("Toggle Sneak", Category.UTILITY, "Keeps you sneaking without holding the key.", KEY_NONE);
    }

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.player != null) mc.player.setShiftKeyDown(true);
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.setShiftKeyDown(false);
        } catch (Throwable ignored) {}
    }
}
