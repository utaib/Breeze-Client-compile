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
            previous = mc.options.bobView().get();
            mc.options.bobView().set(false);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft.getInstance().options.bobView().set(previous);
        } catch (Throwable ignored) {}
    }
}
