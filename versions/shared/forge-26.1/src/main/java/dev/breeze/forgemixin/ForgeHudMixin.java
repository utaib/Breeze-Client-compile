package dev.breeze.forgemixin;

import dev.breeze.compat.ForgeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeze's HUD on Forge 26.1 to 26.1.2 (Gui.extractRenderState) (compat/ForgeHud): added once Minecraft has
 * collected its own HUD for the frame, so it lies over every vanilla element.
 * GuiGraphics is GuiGraphicsExtractor on 26.x (renames.txt).
 */
@Mixin(Gui.class)
public class ForgeHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void breeze$hud(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        // true: the partial tick even while the game's ticking is frozen, so
        // HUD animations keep moving (as on Fabric).
        ForgeHud.draw(g, delta.getGameTimeDeltaPartialTick(true));
    }
}
