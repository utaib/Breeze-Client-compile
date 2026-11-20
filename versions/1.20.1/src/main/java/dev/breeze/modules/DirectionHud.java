package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

public class DirectionHud extends AbstractHudModule {

    public DirectionHud() {
        super("Direction", Category.HUD, "Shows facing direction.", KEY_NONE, 4, 64);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        // getYRot() is not a compass bearing. Minecraft never wraps it, so it
        // accumulates as the player spins: after a few turns it reads 725 or
        // -430, and it keeps going. That is what made this module look broken,
        // since the cardinal name beside it stayed correct the whole time.
        //
        // Wrapped into 0-359 it reads the way a direction is expected to:
        // south 0, west 90, north 180, east 270, matching Minecraft's own yaw
        // convention rather than inventing a new one.
        int degrees = Math.floorMod(Math.round(mc.player.getYRot()), 360);
        String dir = mc.player.getDirection().getName();
        line(g, font, "Facing: " + capitalize(dir) + " (" + degrees + "°)");
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
