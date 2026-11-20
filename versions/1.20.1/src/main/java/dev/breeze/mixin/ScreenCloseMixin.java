package dev.breeze.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Escape on a vanilla screen Breeze opened goes back to the Breeze screen that
 * opened it ({@link dev.breeze.menu.ReturnTo}). Screens that already go back
 * to their parent override onClose and never reach this.
 */
@Mixin(Screen.class)
public class ScreenCloseMixin {

    @Inject(method = "onClose", at = @At("HEAD"), cancellable = true)
    private void breeze$returnTo(CallbackInfo ci) {
        Screen parent = dev.breeze.menu.ReturnTo.take((Screen) (Object) this);
        if (parent == null) return;
        dev.breeze.compat.ActiveScreen.set(Minecraft.getInstance(), parent);
        ci.cancel();
    }
}
