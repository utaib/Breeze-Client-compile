package dev.breeze.mixin;

import dev.breeze.modules.BossBar;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {

    // 26.1: the boss bars are extracted into the GUI render state.
    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void breeze$hideBoss(GuiGraphicsExtractor g, CallbackInfo ci) {
        if (BossBar.active()) ci.cancel();
    }
}
