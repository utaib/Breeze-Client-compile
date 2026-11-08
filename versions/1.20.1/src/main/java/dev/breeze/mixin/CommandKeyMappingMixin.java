package dev.breeze.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.commands.CommandKeyRunner;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Command keys hear each key Minecraft's own key handler sees go down or up
 * in the world (KeyMapping.set, the same from 1.17 to 26.3), so a quick
 * press is not missed when the game draws few frames: reading the key's
 * state once a tick missed presses that began and ended between two frames.
 */
@Mixin(KeyMapping.class)
public class CommandKeyMappingMixin {

    @Inject(method = "set", at = @At("HEAD"))
    private static void breeze$set(InputConstants.Key key, boolean down, CallbackInfo ci) {
        CommandKeyRunner.keyEvent(key, down);
    }
}
