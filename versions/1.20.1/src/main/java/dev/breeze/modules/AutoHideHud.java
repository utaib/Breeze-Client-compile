package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.compat.ActiveScreen;
import net.minecraft.client.Minecraft;

public class AutoHideHud extends Module {

    private boolean previous;

    public AutoHideHud() {
        super("Auto Hide HUD", Category.UTILITY, "Hides the vanilla HUD.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            previous = ActiveScreen.hudHidden(mc);
            ActiveScreen.setHudHidden(mc, true);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            ActiveScreen.setHudHidden(Minecraft.getInstance(), previous);
        } catch (Throwable ignored) {}
    }
}
