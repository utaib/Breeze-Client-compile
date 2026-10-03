package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class SpeedMeterHud extends AbstractHudModule {

    private double display;

    public SpeedMeterHud() {
        super("Speed Meter", Category.HUD, "Shows movement speed.", KEY_NONE, 4, 84);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        double bps = mc.player.getDeltaMovement().horizontalDistance() * 20.0;
        display += (bps - display) * 0.2;
        line(g, font, "Speed: " + Fmt.d2(display) + " b/s");
    }
}
