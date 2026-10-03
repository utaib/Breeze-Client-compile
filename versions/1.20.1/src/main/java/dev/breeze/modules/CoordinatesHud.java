package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;

public class CoordinatesHud extends AbstractHudModule {

    public CoordinatesHud() {
        super("Coordinates", Category.HUD, "Shows player coordinates.", KEY_NONE, 4, 24);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        Vec3 p = mc.player.position();
        line(g, font, String.format("XYZ: %.1f / %.1f / %.1f", p.x(), p.y(), p.z()));
    }
}
