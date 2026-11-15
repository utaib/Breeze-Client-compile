package dev.breeze.forgemixin;

import dev.breeze.compat.ForgeHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeze's HUD on Forge 26.2 and 26.3 (the HUD moved from Gui to Hud) (compat/ForgeHud): added once Minecraft has
 * collected its own HUD for the frame, so it lies over every vanilla element.
 * GuiGraphics is GuiGraphicsExtractor on 26.x (renames.txt).
 */
@Mixin(Hud.class)
public class ForgeHudMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void breeze$hud(GuiGraphics g, DeltaTracker delta, CallbackInfo ci) {
        // true: the partial tick even while the game's ticking is frozen, so
        // HUD animations keep moving (as on Fabric).
        ForgeHud.draw(g, delta.getGameTimeDeltaPartialTick(true));
    }
}
