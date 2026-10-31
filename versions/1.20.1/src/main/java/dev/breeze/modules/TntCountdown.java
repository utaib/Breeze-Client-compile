package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.compat.Tnt;
import dev.breeze.hud.Fmt;
import dev.breeze.render.Frame;
import dev.breeze.render.WorldCtx;
import dev.breeze.render.WorldRender;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * The seconds left above each lit TNT and lit TNT minecart in view range
 * (the TNT Timer HUD shows only the nearest one). Read from the fuse the game
 * already counts down on the client; drawn facing the camera, through
 * walls, coloured by how close it is.
 */
public class TntCountdown extends Module {

    private static TntCountdown instance;

    private final Setting.Int range = add(new Setting.Int("range", "Within", "Show", 32, 8, 64, " blocks"));
    private final Setting.Bool minecarts = add(new Setting.Bool("minecarts", "TNT minecarts too", "Show", true));
    private final Setting.Color safe = add(new Setting.Color("safe", "More than 2 seconds", "Colours", 0xFF55FF55));
    private final Setting.Color soon = add(new Setting.Color("soon", "1 to 2 seconds", "Colours", 0xFFFFDD55));
    private final Setting.Color now = add(new Setting.Color("now", "Under a second", "Colours", 0xFFFF5555));

    private static volatile int lastDrawn;
    private static volatile String lastText = "";

    public TntCountdown() {
        super("TNT Countdown", Category.VISUAL, "Shows the seconds left above each lit TNT and TNT minecart.", KEY_NONE);
        instance = this;
    }

    public static TntCountdown get() {
        return instance;
    }

    @Override
    protected void onWorldRender(WorldCtx ctx) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !ctx.ready()) return;
        float pt = Frame.partialTick();
        double maxSq = (double) range.value * range.value;
        int drawn = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            int fuse = Tnt.fuse(e);
            if (fuse < 0 || e.distanceToSqr(mc.player) > maxSq) continue;
            if (!minecarts.value && !(e instanceof net.minecraft.world.entity.item.PrimedTnt)) continue;
            // The fuse counts down each tick; between ticks, a little less.
            double seconds = Math.max(0, (fuse - pt) / 20.0);
            int colour = seconds < 1 ? now.argb : seconds < 2 ? soon.argb : safe.argb;
            double x = e.xo + (e.getX() - e.xo) * pt;
            double y = e.yo + (e.getY() - e.yo) * pt + e.getBbHeight() + 0.5;
            double z = e.zo + (e.getZ() - e.zo) * pt;
            String text = Fmt.d1(seconds);
            WorldRender.text(ctx, x, y, z, text, colour);
            lastText = text;
            drawn++;
        }
        lastDrawn = drawn;
    }

    /** For the self-test: labels drawn in the last frame, and the last one's text. */
    public static int lastDrawn() { return lastDrawn; }

    public static String lastText() { return lastText; }
}
