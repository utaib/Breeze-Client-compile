package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Fabric API: the HUD, drawing in the
 * world and item tooltips. Fabric API reshapes these events every few
 * Minecraft versions, so they are registered here only (one copy per change in
 * versions/). This is the 1.21.9 form. Fabric API's 1.21.9 build has
 * no world drawing event at all (WorldRenderEvents was removed there and came
 * back reshaped in 1.21.10), so on 1.21.9 nothing draws in the world and the
 * modules that would are not offered.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        // true: the partial tick even while the game's ticking is frozen, so
        // HUD animations keep moving.
        HudRenderCallback.EVENT.register((g, delta) -> painter.paint(g, delta.getGameTimeDeltaPartialTick(true)));
    }

    /**
     * Whether this Minecraft's Fabric API has a world drawing event. Modules
     * that draw in the world are only offered where it does.
     */
    public static boolean worldDrawing() {
        return false;
    }

    /** Draws in the world after entities, every frame a world is shown. */
    public static void world(Consumer<WorldCtx> painter) {
        // No event to hook on 1.21.9; see the class comment.
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> appender.accept(stack, lines));
    }
}
