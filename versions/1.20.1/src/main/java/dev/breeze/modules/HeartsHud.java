package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class HeartsHud extends AbstractHudModule {

    public HeartsHud() {
        super("Hearts", Category.HUD, "Shows numeric health.", KEY_NONE, 4, 104);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        float hp = mc.player.getHealth();
        float max = mc.player.getMaxHealth();
        float abs = mc.player.getAbsorptionAmount();
        String s = String.format("HP: %.0f/%.0f", hp, max);
        if (abs > 0) s += " +" + Fmt.d0(abs);
        int color = hp > max * 0.5f ? 0xFF55FF55 : hp > max * 0.25f ? 0xFFFFFF55 : 0xFFFF5555;
        line(g, font, s, color);
    }
}
