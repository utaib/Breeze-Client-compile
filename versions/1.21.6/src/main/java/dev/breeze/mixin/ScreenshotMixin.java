package dev.breeze.mixin;

import dev.breeze.shots.ScreenshotChat;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

/**
 * Every screenshot (F2, Breeze's own key, other mods) ends in Minecraft's
 * named grab, which tells its caller the result as a chat line. Breeze wraps
 * that callback so a saved screenshot is announced as "Screenshot saved:
 * name" with View, Folder and Copy (shots/ScreenshotChat). The grab's
 * arguments changed in 1.18 and 1.21.6 (versions/1.17 and versions/1.21.6
 * have those forms); this is the 1.21.6 and later form (with a downscale factor).
 */
@Mixin(Screenshot.class)
public class ScreenshotMixin {

    @ModifyVariable(method = "grab(Ljava/io/File;Ljava/lang/String;Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
            at = @At("HEAD"), argsOnly = true)
    private static Consumer<Component> breeze$screenshotActions(Consumer<Component> done) {
        return ScreenshotChat.wrap(done);
    }
}
