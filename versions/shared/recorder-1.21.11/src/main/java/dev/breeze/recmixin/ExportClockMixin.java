package dev.breeze.recmixin;

import dev.breeze.recorder.Exporter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exporting a recording (Exporter): the game's frame loop reads the video's
 * clock instead of the wall clock, one video frame per drawn frame, and
 * each finished frame is read back just before it is shown. Outside an
 * export both are the game's own.
 */
@Mixin(Minecraft.class)
public class ExportClockMixin {

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private long breeze$videoClock() {
        return Exporter.millis(Util.getMillis());
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"))
    private void breeze$frameDrawn(boolean tick, CallbackInfo ci) {
        Exporter.frameDrawn((Minecraft) (Object) this);
    }
}
