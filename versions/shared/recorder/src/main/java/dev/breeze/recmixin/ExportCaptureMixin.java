package dev.breeze.recmixin;

import dev.breeze.recorder.Exporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A render from the world replay (Exporter) reads each frame once the world
 * and the hand are drawn, before the HUD and any screen: the export's own
 * progress screen is shown to the player but never put in the video. Named
 * without its arguments (they differ between versions and none is read), and
 * not required: where a version has no renderLevel, the exporter reads the
 * finished frame instead (Exporter.frameDrawn).
 */
@Mixin(GameRenderer.class)
public class ExportCaptureMixin {

    @Inject(method = "renderLevel", at = @At("TAIL"), require = 0)
    private void breeze$worldDrawn(CallbackInfo ci) {
        Exporter.worldDrawn(Minecraft.getInstance());
    }
}
