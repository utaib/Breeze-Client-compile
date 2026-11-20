package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class DayCounterHud extends AbstractHudModule {

    public DayCounterHud() {
        super("Day Counter", Category.HUD, "Shows the in-game day number.", KEY_NONE, 4, 24);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.level == null) return;
        long day = mc.level.getDayTime() / 24000L;
        line(g, font, "Day: " + day);
    }
}
