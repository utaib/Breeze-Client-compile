package dev.breeze.mixin;

import dev.breeze.Friends;
import dev.breeze.modules.FriendGlow;
import dev.breeze.modules.MobOverlay;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public class EntityGlowMixin {

    @Inject(method = "isCurrentlyGlowing", at = @At("RETURN"), cancellable = true)
    private void breeze$glow(CallbackInfoReturnable<Boolean> cir) {
        Object self = this;
        if (MobOverlay.active() && self instanceof Mob) {
            cir.setReturnValue(true);
            return;
        }
        if (FriendGlow.active() && self instanceof Player player && Friends.isFriend(player.getUUID())) {
            cir.setReturnValue(true);
        }
    }
}
