package dev.breeze.recmixin;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.breeze.recorder.Exporter;
import dev.breeze.recorder.Replay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Watching or exporting a recording: the camera's recorded turn is set at
 * the start of every frame, after the game's own input and ticks, so each
 * frame shows the moment it stands for (Replay.beforeFrame). (Minecraft
 * 1.17 to 1.21.4: the world is drawn in a viewport of the window's size,
 * set here; an export drawn at its own size widens it to the render
 * target's, Exporter.drawWidth.) Nothing changes when no recording plays.
 */
@Mixin(GameRenderer.class)
public class FrameCameraMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void breeze$recordedCamera(CallbackInfo ci) {
        Replay.beforeFrame(Minecraft.getInstance());
    }

    @Redirect(method = "render", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;viewport(IIII)V"))
    private void breeze$exportViewport(int x, int y, int w, int h) {
        RenderSystem.viewport(x, y, Exporter.drawWidth(w), Exporter.drawHeight(h));
    }
}
