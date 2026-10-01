package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;

public class PackDisplayHud extends AbstractHudModule {

    public PackDisplayHud() {
        super("Pack Display", Category.HUD, "Shows the active resource pack.", KEY_NONE, 4, 244);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        String name = "vanilla";
        try {
            for (String id : mc.getResourcePackRepository().getSelectedIds()) {
                name = id;
            }
        } catch (Throwable ignored) {}
        line(g, font, "Pack: " + name);
    }
}
