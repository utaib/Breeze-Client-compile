package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;

public class WaypointHud extends AbstractHudModule {

    private boolean set;
    private double wx;
    private double wy;
    private double wz;

    public WaypointHud() {
        super("Waypoint", Category.HUD, "Drops a marker and shows distance back.", KEY_NONE, 4, 164);
    }

    @Override
    protected void onEnable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                wx = mc.player.getX();
                wy = mc.player.getY();
                wz = mc.player.getZ();
                set = true;
            }
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onDisable() {
        set = false;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null || !set) {
            line(g, font, "Waypoint: -");
            return;
        }
        double dx = wx - mc.player.getX();
        double dy = wy - mc.player.getY();
        double dz = wz - mc.player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        line(g, font, String.format("Waypoint: %.0fm (%d %d %d)", dist, (int) wx, (int) wy, (int) wz), 0xFF55FF55);
    }
}
