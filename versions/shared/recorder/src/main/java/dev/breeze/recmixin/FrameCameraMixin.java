package dev.breeze.recmixin;

import dev.breeze.recorder.Replay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watching or exporting a recording: the camera's recorded turn is set at
 * the start of every frame, after the game's own input and ticks, so each
 * frame shows the moment it stands for (Replay.beforeFrame). Nothing happens
 * when no recording plays. The game's render method takes a float and two
 * more on older versions and a DeltaTracker from 1.21; none is read here.
 */
@Mixin(GameRenderer.class)
public class FrameCameraMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void breeze$recordedCamera(CallbackInfo ci) {
        Replay.beforeFrame(Minecraft.getInstance());
    }
}
