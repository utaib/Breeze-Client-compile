package dev.breeze.modules;

import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.level.Level;

/**
 * Consecutive landed hits.
 *
 * This used to count clicks, not hits: it polled {@code keyAttack.consumeClick()}
 * every tick, so swinging at empty air ran the counter up and a genuine combo was
 * indistinguishable from spamming the button. It also consumed the queued click
 * out from under Minecraft's own attack handling, which is not something a HUD
 * readout has any business doing.
 *
 * It now counts the same event the hit marker counts: an attack that actually
 * reached an entity, reported from MultiPlayerGameMode.attack.
 */
public class ComboCounterHud extends AbstractHudModule {

    /** A combo ends when no hit lands for this long. */
    private static final long RESET_MS = 1500;

    private static ComboCounterHud instance;

    private int combo;
    private long lastHit;
    /** Watched so leaving a world or dying does not carry a combo across. */
    private Level lastLevel;
    private boolean wasDead;

    public ComboCounterHud() {
        super("Combo Counter", Category.HUD, "Tracks consecutive hits.", KEY_NONE, 4, 204);
        instance = this;
    }

    /** One landed hit. Called from the attack mixin, on the client thread. */
    public static void onHit() {
        if (instance == null) return;
        instance.combo++;
        instance.lastHit = System.currentTimeMillis();
    }

    @Override
    protected void onTick(Minecraft mc) {
        // Timing out is the ordinary way a combo ends.
        if (combo > 0 && System.currentTimeMillis() - lastHit > RESET_MS) combo = 0;

        if (mc.player == null || mc.level == null) {
            combo = 0;
            lastLevel = null;
            return;
        }
        // Changing world or dying ends it immediately rather than letting the
        // old count sit on screen until the timeout happens to expire.
        if (mc.level != lastLevel) {
            lastLevel = mc.level;
            combo = 0;
        }
        boolean dead = !mc.player.isAlive();
        if (dead && !wasDead) combo = 0;
        wasDead = dead;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        line(g, font, "Combo: " + combo, combo >= 5 ? 0xFFFFAA00 : 0xFFFFFFFF);
    }
}
