package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;

public class Hitboxes extends Module {

    public Hitboxes() {
        super("Hitboxes", Category.VISUAL, "Renders entity hitboxes.", KEY_NONE);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft.getInstance().getEntityRenderDispatcher().setRenderHitBoxes(true);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft.getInstance().getEntityRenderDispatcher().setRenderHitBoxes(false);
        } catch (Throwable ignored) {}
    }
}
