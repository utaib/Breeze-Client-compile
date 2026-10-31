package dev.breeze.mixin;

import dev.breeze.ui.MenuBg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.Panorama;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.1: PanoramaRenderer became Panorama, drawn through extractRenderState.
@Mixin(Panorama.class)
public class PanoramaRendererMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void breeze$noPanorama(CallbackInfo ci) {
        if (MenuBg.useBreeze() && dev.breeze.compat.ActiveScreen.get(Minecraft.getInstance()) instanceof TitleScreen) {
            ci.cancel();
        }
    }
}
