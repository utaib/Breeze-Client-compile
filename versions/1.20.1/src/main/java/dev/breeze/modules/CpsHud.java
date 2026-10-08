package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.input.Clicks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Clicks per second.
 *
 * This used to poll {@code keyAttack.consumeClick()} every tick, which is not a
 * question but a withdrawal: it pops a click off the queue Minecraft drains to
 * decide whether to swing, so the counter was competing with the game for the
 * player's own input. Combo Counter was doing the same thing at the same time,
 * so the two were also splitting clicks between them.
 *
 * It reads the shared click record now, which observes the raw button callback
 * and takes nothing. That also lifts the ceiling: a tick poll can see at most
 * one click per tick, which caps a clicks-per-second readout at twenty.
 */
public class CpsHud extends AbstractHudModule {

    public CpsHud() {
        super("CPS", Category.HUD, "Shows clicks per second.", KEY_NONE, 4, 184);
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        line(g, font, "CPS: " + Clicks.perSecond(Clicks.LEFT));
    }
}
