package dev.breeze.mixin;

import dev.breeze.modules.FovChanges;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 1.20.2 folded a player's textures into one PlayerSkin record (getSkin),
 * replacing isCapeLoaded and getCloakTextureLocation. The Breeze cape goes in
 * as the skin's cape texture; the elytra keeps using the cape when the skin
 * has no elytra texture of its own, as vanilla does.
 */
@Mixin(AbstractClientPlayer.class)
public class AbstractClientPlayerMixin {

    @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
    private void breeze$fov(CallbackInfoReturnable<Float> cir) {
        if (FovChanges.active()) cir.setReturnValue(1.0f);
    }

    @Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
    private void breeze$cape(CallbackInfoReturnable<PlayerSkin> cir) {
        ResourceLocation cape = dev.breeze.cape.RemoteCapes.capeFor(((AbstractClientPlayer) (Object) this).getUUID());
        PlayerSkin skin = cir.getReturnValue();
        if (cape == null || skin == null || cape.equals(skin.capeTexture())) return;
        cir.setReturnValue(new PlayerSkin(skin.texture(), skin.textureUrl(), cape, skin.elytraTexture(),
                skin.model(), skin.secure()));
    }
}
