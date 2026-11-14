package dev.breeze.mixin;

import dev.breeze.modules.ComboCounterHud;
import dev.breeze.modules.HitIndicator;
import dev.breeze.modules.ReachDisplayHud;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place Breeze learns that an attack actually reached an entity.
 *
 * Combo Counter and Reach Display both used to guess at this and both got it
 * wrong: the combo counted key presses, including swings at air, and reach read
 * the live crosshair distance whether or not anything had been hit. Neither can
 * be derived correctly on the client without this hook, so all three consumers
 * now share it.
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void breeze$hit(Player player, Entity target, CallbackInfo ci) {
        if (HitIndicator.active()) HitIndicator.onHit();
        ComboCounterHud.onHit();
        ReachDisplayHud.onHit(breeze$reachTo(player, target));
    }

    /**
     * Eye to the nearest point of the target's hitbox.
     *
     * This is what a player means by reach: the gap they closed, not the
     * distance between two entity origins, which sits at the feet and reads
     * about 1.6 blocks longer than the swing felt. Clamping the eye position
     * into the box gives the closest surface point, which is where the hit
     * connected to within the width of the box.
     */
    private static double breeze$reachTo(Player player, Entity target) {
        Vec3 eye = player.getEyePosition(1.0f);
        AABB box = target.getBoundingBox();
        Vec3 closest = new Vec3(
                Mth.clamp(eye.x, box.minX, box.maxX),
                Mth.clamp(eye.y, box.minY, box.maxY),
                Mth.clamp(eye.z, box.minZ, box.maxZ));
        return eye.distanceTo(closest);
    }
}
