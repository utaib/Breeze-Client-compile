package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through NeoForge 26.2 and 26.3's events.
 * As shared/neoforge-1.21.9, with drawing in the world submitted. From 1.21.9 Minecraft draws the level from a render state: the
 * camera's position comes from it (as in the Fabric build's 1.21.10 Hooks),
 * and the block outline is a render state too, which Breeze stops from being
 * made (ExtractBlockOutlineRenderStateEvent, cancellable) when it draws its own.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        // true: the partial tick even while the game's ticking is frozen, so
        // HUD animations keep moving (as on Fabric).
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderGuiEvent.Post.class,
                e -> painter.paint(e.getGuiGraphics(), e.getPartialTick().getGameTimeDeltaPartialTick(true)));
    }

    public static boolean worldDrawing() {
        return true;
    }

    /**
     * Submits what Breeze draws in the world each frame. From 26.2 Minecraft
     * hands out no buffers: drawing is submitted and drawn later in the frame,
     * as in the Fabric build's 26.2 Hooks (COLLECT_SUBMITS).
     */
    public static void world(Consumer<WorldCtx> painter) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, SubmitCustomGeometryEvent.class, e -> {
            if (e.getPoseStack() == null) return;
            painter.accept(new WorldCtx(e.getPoseStack(), e.getSubmitNodeCollector(),
                    e.getLevelRenderState().cameraRenderState.pos));
        });
    }

    /** Asked before Minecraft draws its outline around the block in view; false leaves it out. */
    public static void blockOutline(BooleanSupplier drawVanilla) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ExtractBlockOutlineRenderStateEvent.class, e -> {
            if (!drawVanilla.getAsBoolean()) e.setCanceled(true);
        });
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ItemTooltipEvent.class,
                e -> appender.accept(e.getItemStack(), e.getToolTip()));
    }
}
