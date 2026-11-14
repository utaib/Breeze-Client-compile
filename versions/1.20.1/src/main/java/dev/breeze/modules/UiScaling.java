package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;

public class UiScaling extends Module {

    private static UiScaling instance;

    private int scale = 3;

    public UiScaling() {
        super("UI Scaling", Category.UTILITY, "Forces a custom GUI scale.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static int scale() {
        return instance != null ? instance.scale : 3;
    }

    public void setScale(int value) {
        this.scale = Math.max(1, value);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft.getInstance().resizeDisplay();
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        try {
            Minecraft.getInstance().resizeDisplay();
        } catch (Throwable ignored) {}
    }
}
