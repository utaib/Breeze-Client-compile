package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Forge 1.21.3 to 1.21.5. As
 * shared/forge-1.20.6, but Forge has no level stage event from Minecraft
 * 1.21.2's frame graph on: nothing to draw into the world with, so the
 * modules that need it are not offered (Module.offeredWithoutWorldDrawing),
 * as on Fabric 1.21.9. The HUD is drawn by a mixin at the end of Gui.render.
 */
public final class Hooks {

    private Hooks() {}

    public interface HudPainter {
        void paint(GuiGraphics g, float partialTick);
    }

    /** Draws over the game every frame, above the vanilla HUD. */
    public static void hud(HudPainter painter) {
        ForgeHud.add(painter);
    }

    public static boolean worldDrawing() {
        return false;
    }

    public static void world(Consumer<WorldCtx> painter) {
        // Forge 1.21.3 to 1.21.5 have no level stage event; see the class comment.
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
