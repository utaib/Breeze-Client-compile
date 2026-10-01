package dev.breeze.mixin;

import dev.breeze.modules.UiScaling;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Window.class)
public class WindowMixin {

    @Inject(method = "calculateScale", at = @At("RETURN"), cancellable = true)
    private void breeze$uiScale(int guiScale, boolean forceUnicode, CallbackInfoReturnable<Integer> cir) {
        if (UiScaling.active()) {
            cir.setReturnValue(Math.max(1, UiScaling.scale()));
        }
    }
}
