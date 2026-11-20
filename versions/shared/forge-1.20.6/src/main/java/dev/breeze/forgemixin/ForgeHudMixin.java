package dev.breeze.forgemixin;

import dev.breeze.compat.ForgeHud;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Breeze's HUD on Forge 1.20.6 (Gui.render takes the partial tick as a float): drawn when Minecraft's Gui.render ends, over
 * every vanilla element (compat/ForgeHud).
 */
@Mixin(Gui.class)
public class ForgeHudMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void breeze$hud(GuiGraphics g, float partialTick, CallbackInfo ci) {
        ForgeHud.draw(g, partialTick);
    }
}
