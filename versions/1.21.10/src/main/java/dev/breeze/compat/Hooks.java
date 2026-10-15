package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
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
 * versions/). This is the 1.21.10 form: world drawing uses Fabric's
 * v1.world events, and the camera comes from the level's render state.
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
        return true;
    }

    /** Draws in the world after entities, every frame a world is shown. */
    public static void world(Consumer<WorldCtx> painter) {
        WorldRenderEvents.AFTER_ENTITIES.register(ctx ->
                painter.accept(new WorldCtx(ctx.matrices(), ctx.consumers(), ctx.worldState().cameraRenderState.pos)));
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> appender.accept(stack, lines));
    }
}
