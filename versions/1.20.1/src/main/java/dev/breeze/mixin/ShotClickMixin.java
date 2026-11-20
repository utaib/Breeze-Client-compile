package dev.breeze.mixin;

import dev.breeze.shots.ScreenshotChat;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicks on Breeze's screenshot buttons in chat (View, Folder, Copy): caught
 * before Minecraft acts on the text's style. Up to 1.21.10 the chat screen
 * hands a clicked style to Screen.handleComponentClicked; 1.21.11 moved it
 * into ChatScreen (versions/1.21.11 has that form).
 */
@Mixin(Screen.class)
public class ShotClickMixin {

    @Inject(method = "handleComponentClicked", at = @At("HEAD"), cancellable = true)
    private void breeze$shotButton(Style style, CallbackInfoReturnable<Boolean> cir) {
        if (ScreenshotChat.click(style)) cir.setReturnValue(true);
    }
}
