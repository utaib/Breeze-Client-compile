package dev.breeze.mixin;

import dev.breeze.modules.CustomCrosshair;
import dev.breeze.modules.ScoreboardModule;
import dev.breeze.modules.TitleTweaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public class GuiMixin {

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void breeze$crosshair(GuiGraphics g, CallbackInfo ci) {
        if (!CustomCrosshair.active()) return;
        Minecraft mc = Minecraft.getInstance();
        int cx = mc.getWindow().getGuiScaledWidth() / 2;
        int cy = mc.getWindow().getGuiScaledHeight() / 2;
        int color = 0xFF00FFAA;
        g.fill(cx - 1, cy - 1, cx + 1, cy + 1, color);
        ci.cancel();
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
    private void breeze$scoreboard(GuiGraphics g, Objective objective, CallbackInfo ci) {
        if (ScoreboardModule.active()) ci.cancel();
    }
}
