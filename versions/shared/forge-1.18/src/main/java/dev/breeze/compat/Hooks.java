package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.DrawSelectionEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Forge 1.18 to 1.18.2's events: the HUD
 * (RenderGameOverlayEvent, the whole overlay), drawing in the world
 * (RenderLevelLastEvent, after the level) and item tooltips. Before 1.20 the
 * HUD gets a PoseStack, which Breeze draws through with its own GuiGraphics.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderGameOverlayEvent.Post.class, e -> {
            if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
            painter.paint(new GuiGraphics(e.getMatrixStack()), e.getPartialTicks());
        });
    }

    public static boolean worldDrawing() {
        return true;
    }

    /** Draws in the world once the level is drawn, into the game's own buffers. */
    public static void world(Consumer<WorldCtx> painter) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelLastEvent.class, e -> {
            Minecraft mc = Minecraft.getInstance();
            painter.accept(new WorldCtx(e.getPoseStack(), mc.renderBuffers().bufferSource(),
                    mc.gameRenderer.getMainCamera().getPosition()));
            mc.renderBuffers().bufferSource().endBatch();
        });
    }

    /** Asked before Minecraft draws its outline around the block in view; false leaves it out. */
    public static void blockOutline(BooleanSupplier drawVanilla) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, DrawSelectionEvent.HighlightBlock.class, e -> {
            if (!drawVanilla.getAsBoolean()) e.setCanceled(true);
        });
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ItemTooltipEvent.class,
                e -> appender.accept(e.getItemStack(), e.getToolTip()));
    }
}
