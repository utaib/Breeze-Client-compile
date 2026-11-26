package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through NeoForge 1.21.10 and 1.21.11's
 * events. From 1.21.9 Minecraft draws the level from a render state: the
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

    /** Draws in the world after entities, into the game's own buffers. */
    public static void world(Consumer<WorldCtx> painter) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.AfterEntities.class, e -> {
            if (e.getPoseStack() == null) return;
            Minecraft mc = Minecraft.getInstance();
            painter.accept(new WorldCtx(e.getPoseStack(), mc.renderBuffers().bufferSource(),
                    e.getLevelRenderState().cameraRenderState.pos));
            mc.renderBuffers().bufferSource().endBatch();
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
