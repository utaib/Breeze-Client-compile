package dev.breeze.modules;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;

public class DeathInfoHud extends AbstractHudModule {

    private boolean wasDead;
    private boolean has;
    private int dx;
    private int dy;
    private int dz;

    public DeathInfoHud() {
        super("Death Info", Category.HUD, "Records your last death location.", KEY_NONE, 4, 144);
    }

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.player == null) return;
        boolean dead = mc.player.getHealth() <= 0.0f || mc.player.isDeadOrDying();
        if (dead && !wasDead) {
            dx = (int) Math.floor(mc.player.getX());
            dy = (int) Math.floor(mc.player.getY());
            dz = (int) Math.floor(mc.player.getZ());
            has = true;
        }
        wasDead = dead;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (!has) {
            line(g, font, "Death: none");
        } else {
            line(g, font, "Death: " + dx + " " + dy + " " + dz, 0xFFFF5555);
        }
    }
}
