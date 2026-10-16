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
            dev.breeze.compat.Debug.setHitboxes(Minecraft.getInstance(), true);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            dev.breeze.compat.Debug.setHitboxes(Minecraft.getInstance(), false);
        } catch (Throwable ignored) {}
    }
}
