package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.hud.Fmt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * How far away the last hit landed.
 *
 * This used to read the live crosshair distance every frame, so it answered "how
 * far is the thing I am looking at" rather than "how far was I when I hit". It
 * changed continuously while aiming, showed a number when no attack had happened
 * at all, and lost the reading the instant the crosshair drifted off the target,
 * which is precisely when the player wants to read it.
 *
 * It now records the distance at the moment an attack lands, measured from the
 * attacker's eyes to the nearest point of the target's hitbox, which is the
 * distance PvP players mean by reach. The reading is held briefly so it can be
 * read after the swing, then clears.
 */
public class ReachDisplayHud extends AbstractHudModule {

    /** How long a reading stays on screen after the hit. */
    private static final long HOLD_MS = 3000;

    private static ReachDisplayHud instance;

    private double lastReach;
    private long lastHit;

    public ReachDisplayHud() {
        super("Reach Display", Category.HUD, "Shows how far away your last hit landed.", KEY_NONE, 4, 224);
        instance = this;
    }

    /** One landed hit, with the distance it was struck from. */
    public static void onHit(double distance) {
        if (instance == null) return;
        instance.lastReach = distance;
        instance.lastHit = System.currentTimeMillis();
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.player == null) return;
        if (lastHit != 0 && System.currentTimeMillis() - lastHit <= HOLD_MS) {
            line(g, font, "Reach: " + Fmt.d2(lastReach), 0xFF55FFFF);
        } else {
            line(g, font, "Reach: -");
        }
    }
}
