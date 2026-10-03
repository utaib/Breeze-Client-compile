package dev.breeze.mixin;

import dev.breeze.modules.CustomCrosshair;
import dev.breeze.modules.ScoreboardModule;
import dev.breeze.modules.TitleTweaker;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

    // Handlers here take no arguments of the method they hook, so they fit it
    // whatever its parameters are on a given Minecraft version. The custom
    // crosshair itself is drawn by its module in Breeze's HUD pass.
    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void breeze$crosshair(CallbackInfo ci) {
        if (CustomCrosshair.active()) ci.cancel();
    }

    @Inject(method = "setTitle", at = @At("HEAD"), cancellable = true)
    private void breeze$title(Component title, CallbackInfo ci) {
        if (TitleTweaker.active()) ci.cancel();
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), cancellable = true)
    private void breeze$subtitle(Component subtitle, CallbackInfo ci) {
        if (TitleTweaker.active()) ci.cancel();
    }

    @Inject(method = "displayScoreboardSidebar", at = @At("HEAD"), cancellable = true)
    private void breeze$scoreboard(CallbackInfo ci) {
        if (ScoreboardModule.active()) ci.cancel();
    }
}
