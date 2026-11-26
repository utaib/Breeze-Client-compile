package dev.breeze.mixin;

import dev.breeze.modules.DropPrevention;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drop Prevention. Up to 26.2 the player dropped the held item through
 * LocalPlayer.drop; from 26.3 the game mode does it (dropItem), so this mixin
 * keeps its name and follows the call there.
 */
@Mixin(MultiPlayerGameMode.class)
public class LocalPlayerMixin {

    @Inject(method = "dropItem", at = @At("HEAD"), cancellable = true)
    private void breeze$drop(LocalPlayer player, boolean fullStack, CallbackInfo ci) {
        if (DropPrevention.active()) {
            ci.cancel();
        }
    }
}
