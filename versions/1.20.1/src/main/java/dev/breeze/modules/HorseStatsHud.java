package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.horse.AbstractHorse;

public class HorseStatsHud extends AbstractHudModule {

    public HorseStatsHud() {
        super("Horse Stats", Category.HUD, "Shows your horse's speed, jump and health.", KEY_NONE, 4, 64);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        Entity vehicle = mc.player.getVehicle();
        if (!(vehicle instanceof AbstractHorse horse)) return;
        double speed = horse.getAttributeValue(Attributes.MOVEMENT_SPEED) * 43.17;
        double jump = jumpToBlocks(horse.getCustomJump());
        line(g, font, "Speed: " + Fmt.d2(speed) + " m/s");
        line(g, font, "Jump: " + Fmt.d2(jump) + " blk");
        line(g, font, String.format("HP: %.0f/%.0f", horse.getHealth(), horse.getMaxHealth()));
    }

    private static double jumpToBlocks(double j) {
        return -0.1817584952 * j * j * j + 3.689713992 * j * j + 2.128599134 * j - 0.343930367;
    }
}
