package dev.breeze.mixin;

import dev.breeze.modules.Fullbright;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fullbright. The lightmap reads the brightness option each time it is
 * rebuilt (every tick); while Fullbright is on, that one read answers a value
 * far past the slider's 100%, and the player's own setting is never touched.
 *
 * The previous approach set the option to 100. Since 1.19 the option only
 * accepts 0 to 1, so Minecraft refused the value and reset brightness to its
 * default: turning Fullbright on made the game darker for anyone above it.
 *
 * Every option read in updateLightTexture passes through here, and only the
 * brightness option is changed, so this does not depend on the order of the
 * reads. require = 0: if a later Minecraft moves the read, Fullbright stops
 * working rather than the game failing to start.
 */
// 26.1: the lightmap is worked out by LightmapRenderStateExtractor.extract,
// which reads the gamma option as LightTexture.updateLightTexture did.
@Mixin(LightmapRenderStateExtractor.class)
public class LightTextureMixin {

    @Redirect(method = "extract",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"),
            require = 0)
    private Object breeze$fullbright(OptionInstance<?> option) {
        if (Fullbright.active() && option == Minecraft.getInstance().options.gamma()) return Fullbright.GAMMA;
        return option.get();
    }
}
