package dev.breeze.mixin;

import dev.breeze.modules.CustomAdvancements;
import dev.breeze.modules.ToastControl;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastComponent.class)
public class ToastComponentMixin {

    @Inject(method = "addToast", at = @At("HEAD"), cancellable = true)
    private void breeze$toast(Toast toast, CallbackInfo ci) {
        if (ToastControl.active()) {
            ci.cancel();
            return;
        }
        if (CustomAdvancements.active() && toast instanceof AdvancementToast) {
            ci.cancel();
        }
    }
}
