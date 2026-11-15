package dev.breeze.compat;

import dev.breeze.render.WorldCtx;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RenderHighlightEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Breeze's hooks into the game through Forge 1.21.6 and later. Forge moved to
 * EventBus 7 there (each event has its own BUS; a listener of a cancellable
 * event cancels it by returning true) and has no level stage event: Forge
 * offers nothing to draw into the world with, so the modules that need it
 * are not offered, as on Fabric 1.21.9 (Module.offeredWithoutWorldDrawing).
 * The HUD is drawn by a mixin at the end of Gui.render (compat/ForgeHud).
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
        // Forge 1.21.6 and later have no level stage event; see the class comment.
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
}
