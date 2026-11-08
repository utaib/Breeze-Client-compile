package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

public class HitIndicator extends Module {

    private static HitIndicator instance;

    private long lastHit;

    public HitIndicator() {
        super("Hit Indicator", Category.VISUAL, "Flashes a marker when your attack lands.", KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    public static void onHit() {
        if (instance != null) instance.lastHit = System.currentTimeMillis();
    }

    @Override
    protected void onHudRender(GuiGraphics g, float partialTick) {
        long since = System.currentTimeMillis() - lastHit;
        if (since > 300) return;
        Minecraft mc = Minecraft.getInstance();
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        int a = (int) (255 * (1.0 - since / 300.0));
        int color = (a << 24) | 0xFFFFFF;
        int s = 6;
        g.fill(cx - s, cy - 1, cx - 2, cy + 1, color);
        g.fill(cx + 2, cy - 1, cx + s, cy + 1, color);
        g.fill(cx - 1, cy - s, cx + 1, cy - 2, color);
        g.fill(cx - 1, cy + 2, cx + 1, cy + s, color);
    }
}
