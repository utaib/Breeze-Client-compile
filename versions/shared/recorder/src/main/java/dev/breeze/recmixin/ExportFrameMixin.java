package dev.breeze.recmixin;

import dev.breeze.recorder.Exporter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The end of GameRenderer.render: the frame is drawn (world, hand, HUD,
 * screens) but not yet shown. An export reads it back here, then draws its
 * progress panel on the picture the window is about to show, so the panel
 * is never in the video (Exporter.frameRendered). Optional: where it does
 * not apply, the export reads frames at the end of the frame loop as
 * before, without the panel.
 */
@Mixin(GameRenderer.class)
public class ExportFrameMixin {

    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void breeze$frameRendered(CallbackInfo ci) {
        Exporter.frameRendered(Minecraft.getInstance());
    }
}
