package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Fabric API: the HUD, drawing in the
 * world and item tooltips. Fabric API reshapes these events every few
 * Minecraft versions, so they are registered here only (one copy per change in
 * versions/). This is the 26.1 form: the HUD is a registered HUD element (the
 * old HUD callback is gone), and world drawing uses the level render events,
 * whose context still has a buffer source on 26.1.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphicsExtractor g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        // Added last, so it lies over every vanilla element. true: the partial
        // tick even while the game's ticking is frozen, so HUD animations keep
        // moving.
        HudElementRegistry.addLast(Ids.breeze("hud"),
                (g, delta) -> painter.paint(g, delta.getGameTimeDeltaPartialTick(true)));
    }

    /**
     * Whether this Minecraft's Fabric API has a world drawing event. Modules
     * that draw in the world are only offered where it does.
     */
    public static boolean worldDrawing() {
        return true;
    }

    /**
     * Draws in the world once the solid features (entities among them) are
     * drawn, every frame a world is shown. UNVERIFIED in a world: that this is
     * the point 1.21.10's "after entities" became.
     */
    public static void world(Consumer<WorldCtx> painter) {
        LevelRenderEvents.AFTER_SOLID_FEATURES.register(ctx ->
                painter.accept(new WorldCtx(ctx.poseStack(), ctx.bufferSource(), ctx.levelState().cameraRenderState.pos)));
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> appender.accept(stack, lines));
    }
}
