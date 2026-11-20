package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.BossBar;
import dev.breeze.compat.GuiGraphics;
import net.minecraft.client.gui.components.BossHealthOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Before 1.20 Minecraft draws with a PoseStack; the handler wraps it in
// Breeze's GuiGraphics (compat/GuiGraphics) so the body stays the same.
@Mixin(BossHealthOverlay.class)
public class BossHealthOverlayMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void breeze$hideBoss(PoseStack poseStack, CallbackInfo ci) {
        GuiGraphics g = new GuiGraphics(poseStack);
        if (BossBar.active()) ci.cancel();
    }
}
