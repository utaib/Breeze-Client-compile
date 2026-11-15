package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Forge 1.21.6 and later. Forge moved to
 * EventBus 7 there: every event has its own bus (RenderGuiEvent.Post.BUS and
 * so on) and a listener of a cancellable event cancels it by returning true.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        RenderGuiEvent.Post.BUS.addListener(e -> painter.paint(e.getGuiGraphics(), partial(e.getPartialTick())));
    }

    public static boolean worldDrawing() {
        return true;
    }

    /** Draws in the world after entities, into the game's own buffers, drawn at once. */
    public static void world(Consumer<WorldCtx> painter) {
        RenderLevelStageEvent.BUS.addListener(e -> {
            if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || e.getPoseStack() == null) return;
            Minecraft mc = Minecraft.getInstance();
            painter.accept(new WorldCtx(e.getPoseStack(), mc.renderBuffers().bufferSource(),
                    e.getCamera().getPosition()));
            mc.renderBuffers().bufferSource().endBatch();
        });
    }

    /** Asked before Minecraft draws its outline around the block in view; false leaves it out. */
    public static void blockOutline(BooleanSupplier drawVanilla) {
        RenderHighlightEvent.Block.BUS.addListener(e -> {
            return !drawVanilla.getAsBoolean();
        });
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        ItemTooltipEvent.BUS.addListener(e -> appender.accept(e.getItemStack(), e.getToolTip()));
    }

    // Forge hands the HUD's partial tick over as a float or, following
    // Minecraft from 1.21, as a DeltaTracker; either way Breeze gets a float.
    private static float partial(float partialTick) {
        return partialTick;
    }

    private static float partial(net.minecraft.client.DeltaTracker tracker) {
        // true: the partial tick even while the game's ticking is frozen, so
        // HUD animations keep moving (as on Fabric).
        return tracker.getGameTimeDeltaPartialTick(true);
    }
}
