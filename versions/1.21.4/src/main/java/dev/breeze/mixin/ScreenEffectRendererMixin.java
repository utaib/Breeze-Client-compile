package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.LowFire;
import dev.breeze.render.FireOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Low Fire. At 100% the overlay is skipped, as No Fire Overlay did; below that
 * the whole overlay is drawn lower by the chosen amount. Nothing changes while
 * the module is off.
 */
// 1.21.4 to 1.21.8: renderFire takes the pose and a buffer source.
@Mixin(ScreenEffectRenderer.class)
public class ScreenEffectRendererMixin {

    /** Whether HEAD pushed a pose, so RETURN pops exactly what was pushed. */
    @Unique
    private static boolean breeze$lowered;

    @Inject(method = "renderFire", at = @At("HEAD"), cancellable = true)
    private static void breeze$lowFire(PoseStack poseStack, MultiBufferSource buffer, CallbackInfo ci) {
        int lower = LowFire.lowerBy();
        breeze$lowered = false;
        if (FireOverlay.hidden(lower)) {
            ci.cancel();
            return;
        }
        float drop = FireOverlay.drop(lower);
        if (drop <= 0f) return;
        poseStack.pushPose();
        poseStack.translate(0.0, -drop, 0.0);
        breeze$lowered = true;
    }

    @Inject(method = "renderFire", at = @At("RETURN"))
    private static void breeze$lowFireDone(PoseStack poseStack, MultiBufferSource buffer, CallbackInfo ci) {
        if (breeze$lowered) {
            poseStack.popPose();
            breeze$lowered = false;
        }
    }
}
