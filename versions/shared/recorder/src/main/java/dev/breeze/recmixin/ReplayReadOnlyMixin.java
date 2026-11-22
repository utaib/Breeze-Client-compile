package dev.breeze.recmixin;

import dev.breeze.recorder.Replay;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A recording being watched is read only: the viewer's clicks never break,
 * place, use, attack or move items in the recorded world (the game would
 * otherwise show the change at once, as it does in a real world before the
 * server answers). Named without arguments, which differ between versions;
 * none is read.
 */
@Mixin(MultiPlayerGameMode.class)
public class ReplayReadOnlyMixin {

    @Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noBreak(CallbackInfoReturnable<Boolean> cir) {
        if (Replay.active()) cir.setReturnValue(false);
    }

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noBreakMore(CallbackInfoReturnable<Boolean> cir) {
        if (Replay.active()) cir.setReturnValue(false);
    }

    @Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noPlace(CallbackInfoReturnable<InteractionResult> cir) {
        if (Replay.active()) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noUse(CallbackInfoReturnable<InteractionResult> cir) {
        if (Replay.active()) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "interact", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noInteract(CallbackInfoReturnable<InteractionResult> cir) {
        if (Replay.active()) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "interactAt", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noInteractAt(CallbackInfoReturnable<InteractionResult> cir) {
        if (Replay.active()) cir.setReturnValue(InteractionResult.FAIL);
    }

    @Inject(method = "attack", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noAttack(CallbackInfo ci) {
        if (Replay.active()) ci.cancel();
    }

    @Inject(method = "handleInventoryMouseClick", at = @At("HEAD"), cancellable = true, require = 0)
    private void breeze$noInventoryClick(CallbackInfo ci) {
        if (Replay.active()) ci.cancel();
    }
}
