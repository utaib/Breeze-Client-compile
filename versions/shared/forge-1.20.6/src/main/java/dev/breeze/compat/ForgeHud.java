package dev.breeze.compat;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Breeze's HUD on Forge from 1.20.6. Forge's HUD event changes or is missing
 * there (no RenderGuiEvent from 1.20.6, no HUD layer event on 1.21.6 to
 * 1.21.9), so Breeze draws at the end of Minecraft's own Gui.render instead
 * (dev.breeze.forgemixin.ForgeHudMixin, in breeze-forge.mixins.json): after
 * every vanilla element, as on Fabric.
 */
public final class ForgeHud {

    private static final List<Hooks.HudPainter> PAINTERS = new CopyOnWriteArrayList<>();

    private ForgeHud() {}

    static void add(Hooks.HudPainter painter) {
        PAINTERS.add(painter);
    }

    /** Called by the mixin once the game has drawn its HUD. */
    public static void draw(GuiGraphics g, float partialTick) {
        for (Hooks.HudPainter p : PAINTERS) p.paint(g, partialTick);
    }
}
