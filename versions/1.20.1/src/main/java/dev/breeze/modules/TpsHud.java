package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import dev.breeze.TpsTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class TpsHud extends AbstractHudModule {

    public TpsHud() {
        super("TPS", Category.HUD, "Estimated server ticks per second.", KEY_NONE, 4, 44);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.level == null) return;
        double tps = TpsTracker.tps();
        int color = tps >= 19.0 ? 0xFF55FF55 : tps >= 15.0 ? 0xFFFFFF55 : 0xFFFF5555;
        line(g, font, "TPS: " + Fmt.d1(tps), color);
    }
}
