package dev.breeze.mixin;

import dev.breeze.MutedSounds;
import dev.breeze.modules.SoundFilter;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** From 1.21.6, play() reports whether the sound started, so a muted sound returns NOT_STARTED. */
@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void breeze$filter(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        if (!SoundFilter.active() || MutedSounds.isEmpty()) return;
        try {
            String name = sound == null ? null : dev.breeze.compat.Ids.soundName(sound);
            if (name != null && MutedSounds.isMuted(name)) {
                cir.setReturnValue(SoundEngine.PlayResult.NOT_STARTED);
            }
        } catch (Throwable ignored) {}
    }
}
