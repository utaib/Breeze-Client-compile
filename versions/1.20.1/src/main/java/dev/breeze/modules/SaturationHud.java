package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class SaturationHud extends AbstractHudModule {

    public SaturationHud() {
        super("Saturation", Category.HUD, "Shows food saturation.", KEY_NONE, 4, 284);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        float sat = mc.player.getFoodData().getSaturationLevel();
        line(g, font, "Saturation: " + Fmt.d1(sat));
    }
}
