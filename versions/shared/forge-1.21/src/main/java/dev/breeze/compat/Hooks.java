package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Forge 1.21 to 1.21.5's events. As
 * shared/forge (1.20.1), with Minecraft 1.21's DeltaTracker for the HUD's
 * partial tick.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderGuiEvent.Post.class,
                e -> painter.paint(e.getGuiGraphics(), partial(e.getPartialTick())));
    }

    public static boolean worldDrawing() {
        return true;
    }

    /**
     * Draws in the world after entities, every frame a world is shown, into
     * the game's own buffers (the game draws them later in the frame, as
     * with Fabric's event).
     */
    public static void world(Consumer<WorldCtx> painter) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, RenderLevelStageEvent.class, e -> {
            if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
            painter.accept(new WorldCtx(e.getPoseStack(), Minecraft.getInstance().renderBuffers().bufferSource(),
                    e.getCamera().getPosition()));
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
