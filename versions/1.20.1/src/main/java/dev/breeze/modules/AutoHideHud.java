package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
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
            previous = mc.options.hideGui;
            mc.options.hideGui = true;
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft.getInstance().options.hideGui = previous;
        } catch (Throwable ignored) {}
    }
}
