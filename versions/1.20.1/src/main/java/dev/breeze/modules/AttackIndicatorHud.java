package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class AttackIndicatorHud extends AbstractHudModule {

    public AttackIndicatorHud() {
        super("Attack Indicator", Category.HUD, "Shows attack cooldown charge.", KEY_NONE, 4, 384);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        float scale = mc.player.getAttackStrengthScale(0.0f);
        line(g, font, "Attack: " + Fmt.d0(scale * 100.0f) + "%", scale >= 1.0f ? 0xFF55FF55 : 0xFFFFFF55);
    }
}
