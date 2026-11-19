package dev.breeze.mixin;

import dev.breeze.modules.Fullbright;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.LightTexture;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fullbright. The lightmap reads the brightness option each time it is
 * rebuilt; while the module is on it reads Fullbright.GAMMA instead, and the
 * option itself is never written. Before 1.19 the option is a plain double
 * field (Options.gamma) rather than an OptionInstance.
 */
@Mixin(LightTexture.class)
public class LightTextureMixin {

    @Redirect(method = "updateLightTexture",
            at = @At(value = "FIELD", target = "Lnet/minecraft/client/Options;gamma:D", opcode = Opcodes.GETFIELD),
            require = 0)
    private double breeze$fullbright(Options options) {
        return Fullbright.active() ? Fullbright.GAMMA : options.gamma;
    }
}
