package dev.breeze.modules;

import dev.breeze.hud.Fmt;
import dev.breeze.Category;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;

public class TntTimerHud extends AbstractHudModule {

    public TntTimerHud() {
        super("TNT Timer", Category.HUD, "Shows nearest TNT fuse.", KEY_NONE, 4, 344);
    }

    private int breeze$minFuse = -1;

    @Override
    protected void onTick(Minecraft mc) {
        if (mc.level == null) {
            breeze$minFuse = -1;
            return;
        }
        int minFuse = -1;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof PrimedTnt) {
                int f = ((PrimedTnt) e).getFuse();
                if (minFuse < 0 || f < minFuse) minFuse = f;
            }
        }
        breeze$minFuse = minFuse;
    }

    @Override
    protected void draw(Minecraft mc, GuiGraphics g, Font font) {
        if (mc.level == null) return;
        int minFuse = breeze$minFuse;
        if (minFuse < 0) {
            line(g, font, "TNT: none");
        } else {
            line(g, font, "TNT: " + Fmt.d1(minFuse / 20.0) + "s", minFuse < 20 ? 0xFFFF5555 : 0xFFFFFFFF);
        }
    }
}
