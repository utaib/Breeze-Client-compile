package dev.breeze.recmixin;

import dev.breeze.recorder.Exporter;
import dev.breeze.recorder.Replay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watching or exporting a recording (Minecraft 26.1): the game takes
 * what it draws from the world before drawing (extract), so the camera's
 * recorded turn is set at the start of that, for the moment this frame
 * stands for (Replay.beforeFrame). An export larger or smaller than the
 * window is drawn at its own size: the frame's window state takes the
 * video's size (the render target follows it, told by the resized flag), and the interface
 * scale grows with it, so the HUD keeps the place and size it has in the
 * window. Nothing changes when no recording plays.
 */
@Mixin(GameRenderer.class)
public class FrameCameraMixin {

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(method = "extract", at = @At("HEAD"))
    private void breeze$recordedCamera(CallbackInfo ci) {
        Replay.beforeFrame(Minecraft.getInstance());
    }

    @Inject(method = "extractWindow", at = @At("TAIL"))
    private void breeze$exportSize(CallbackInfo ci) {
        WindowRenderState s = gameRenderState.windowRenderState;
        int w = Exporter.drawWidth(s.width), h = Exporter.drawHeight(s.height);
        if (w == s.width && h == s.height) {
            if (sized) {
                // The export is over: the window's own size again.
                sized = false;
                s.isResized = true;
            }
            return;
        }
        s.guiScale = Exporter.guiScale(s.guiScale, s.height, h);
        s.width = w;
        s.height = h;
        if (!sized) {
            sized = true;
            s.isResized = true;
        }
    }

    private boolean sized;
}
