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
 * versions/). This is the 26.2 form: the HUD is a registered HUD element, and
 * world drawing is submitted to the frame's node collector while the level
 * render events collect submissions (there is no buffer source any more).
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
     * Draws in the world every frame a world is shown, submitted while the
     * frame's submissions are collected. UNVERIFIED in a world: that the pose
     * stack at that point is camera-relative, as it was after entities.
     */
    public static void world(Consumer<WorldCtx> painter) {
        LevelRenderEvents.COLLECT_SUBMITS.register(ctx ->
                painter.accept(new WorldCtx(ctx.poseStack(), ctx.submitNodeCollector(), ctx.levelState().cameraRenderState.pos)));
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> appender.accept(stack, lines));
    }
}
