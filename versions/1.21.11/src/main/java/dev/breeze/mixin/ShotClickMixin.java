package dev.breeze.mixin;

import dev.breeze.shots.ScreenshotChat;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Clicks on Breeze's screenshot buttons in chat (View, Folder, Copy): caught
 * before Minecraft acts on the text's style. From 1.21.11 the chat screen
 * handles a clicked style itself, in a private method with an insertion flag.
 */
@Mixin(ChatScreen.class)
public class ShotClickMixin {

    @Inject(method = "handleComponentClicked(Lnet/minecraft/network/chat/Style;Z)Z", at = @At("HEAD"), cancellable = true)
    private void breeze$shotButton(Style style, boolean insert, CallbackInfoReturnable<Boolean> cir) {
        if (ScreenshotChat.click(style)) cir.setReturnValue(true);
    }
}
