package dev.breeze.mixin;

import dev.breeze.modules.FovChanges;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A player's textures come as one PlayerSkin record (getSkin); from 1.21.9 it
 * lives in world.entity.player and holds client assets rather than bare ids.
 * The Breeze cape goes in as the skin's cape; the elytra keeps using the cape
 * when the skin has no elytra texture of its own, as vanilla does.
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
        if (cape == null || skin == null) return;
        if (skin.cape() != null && cape.equals(skin.cape().texturePath())) return;
        cir.setReturnValue(new PlayerSkin(skin.body(), new ClientAsset.ResourceTexture(cape, cape), skin.elytra(),
                skin.model(), skin.secure()));
    }
}
