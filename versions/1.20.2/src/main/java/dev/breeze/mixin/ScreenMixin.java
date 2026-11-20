package dev.breeze.mixin;

import dev.breeze.modules.DarkMode;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class ScreenMixin {

    // 1.20.2: renderBackground(GuiGraphics, mouseX, mouseY, partialTick).
    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void breeze$darkMode(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!DarkMode.active()) return;
        Screen self = (Screen) (Object) this;
        g.fill(0, 0, self.width, self.height, 0x90000000);
    }
}
