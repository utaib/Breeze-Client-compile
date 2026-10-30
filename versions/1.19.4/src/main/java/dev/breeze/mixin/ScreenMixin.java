package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.modules.DarkMode;
import dev.breeze.compat.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Before 1.20 Minecraft draws with a PoseStack; the handler wraps it in
// Breeze's GuiGraphics (compat/GuiGraphics) so the body stays the same.
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void breeze$darkMode(PoseStack poseStack, CallbackInfo ci) {
        GuiGraphics g = new GuiGraphics(poseStack);
        if (!DarkMode.active()) return;
        Screen self = (Screen) (Object) this;
        g.fill(0, 0, self.width, self.height, 0x90000000);
    }
}
