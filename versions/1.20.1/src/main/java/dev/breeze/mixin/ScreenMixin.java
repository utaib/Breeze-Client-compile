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

    @Inject(method = "renderBackground", at = @At("TAIL"))
    private void breeze$darkMode(GuiGraphics g, CallbackInfo ci) {
        if (!DarkMode.active()) return;
        Screen self = (Screen) (Object) this;
        g.fill(0, 0, self.width, self.height, 0x90000000);
    }
}
