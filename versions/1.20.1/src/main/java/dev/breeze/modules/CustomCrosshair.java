package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class CustomCrosshair extends Module {

    private static CustomCrosshair instance;

    public CustomCrosshair() {
        super("Custom Crosshair", Category.VISUAL, "Replaces the crosshair with a custom dot.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    /** Minecraft's own crosshair is cancelled (GuiMixin); this draws the dot in its place. */
    @Override
    protected void onHudRender(GuiGraphics g, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !mc.options.getCameraType().isFirstPerson()) return;
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFF00FFAA);
    }
}
