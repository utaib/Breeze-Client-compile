package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Forge 1.19 to 1.19.3's events (as
 * shared/forge-1.19, with the 1.18 world event): the
 * HUD, drawing in the world and item tooltips. As shared/forge (1.20), but
 * the HUD event hands over a PoseStack before 1.20, so Breeze draws through
 * its own GuiGraphics around it (versions/1.19.4/compat/GuiGraphics), as the
 * Fabric build of these versions does.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderGuiEvent.Post.class,
                e -> painter.paint(new GuiGraphics(e.getPoseStack()), e.getPartialTick()));
    }

    public static boolean worldDrawing() {
        return true;
    }

    /**
     * Draws in the world once the level is drawn, into the game's own buffers,
     * and draws them. Forge 1.19 to 1.19.3's level stages have no
     * AFTER_ENTITIES (builds 41.1.0 to 44.1.0), so this is the
     * RenderLevelLastEvent of 1.18, which those builds still have.
     */
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
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderHighlightEvent.Block.class, e -> {
            if (!drawVanilla.getAsBoolean()) e.setCanceled(true);
        });
    }

    /** Adds lines to every item tooltip. */
    public static void tooltips(BiConsumer<ItemStack, List<Component>> appender) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ItemTooltipEvent.class,
                e -> appender.accept(e.getItemStack(), e.getToolTip()));
    }
}
