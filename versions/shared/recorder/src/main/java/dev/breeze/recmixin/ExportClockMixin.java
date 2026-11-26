package dev.breeze.recmixin;

import dev.breeze.recorder.Exporter;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Exporting a recording (Exporter): the game's frame loop reads the video's
 * clock instead of the wall clock, one video frame per drawn frame, and
 * each finished frame is read back at the end of the loop (the frame is
 * still in the main render target then). While recording, the same point
 * keeps the camera of the frame the player just saw (RecorderHooks.frame). Outside an export both are the
 * game's own. (Minecraft 1.21.11 and later: Util is in net.minecraft.util.)
 */
@Mixin(Minecraft.class)
public class ExportClockMixin {

    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;getMillis()J"))
    private long breeze$videoClock() {
        return Exporter.millis(Util.getMillis());
    }

    /**
     * The size the game draws at: the window's, or while an export resizes
     * the frames (Exporter.drawSize) the video's, so 1440p and 4K are drawn
     * at full size. Only the render targets read it here; the interface
     * scale still comes from the window, so the HUD keeps its place.
     */
    @Redirect(method = "resizeDisplay", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getWidth()I"))
    private int breeze$drawWidth(Window window) {
        return Exporter.drawWidth(window.getWidth());
    }

    @Redirect(method = "resizeDisplay", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/Window;getHeight()I"))
    private int breeze$drawHeight(Window window) {
        return Exporter.drawHeight(window.getHeight());
    }

    @Inject(method = "runTick", at = @At("TAIL"))
    private void breeze$frameDrawn(boolean tick, CallbackInfo ci) {
        dev.breeze.recorder.RecorderHooks.frame((Minecraft) (Object) this);
        Exporter.frameDrawn((Minecraft) (Object) this);
    }
}
