package dev.breeze.mixin;

import dev.breeze.MutedSounds;
import dev.breeze.modules.SoundFilter;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SoundEngine.class)
public class SoundEngineMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void breeze$filter(SoundInstance sound, CallbackInfo ci) {
        if (!SoundFilter.active() || MutedSounds.isEmpty()) return;
        try {
            String name = sound == null ? null : dev.breeze.compat.Ids.soundName(sound);
            if (name != null && MutedSounds.isMuted(name)) {
                ci.cancel();
            }
        } catch (Throwable ignored) {}
    }
}
