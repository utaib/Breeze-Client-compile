package dev.breeze.mixin;

import dev.breeze.input.Clicks;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes mouse buttons without taking them.
 *
 * The CPS readout used to call {@code keyAttack.consumeClick()}, which does not
 * mean "has a click happened": it pops one off the queue Minecraft itself drains
 * to decide whether to swing. A HUD counter competing with the game for its own
 * input is a way to drop attacks, and while two Breeze modules were both doing
 * it they were splitting the clicks between them as well.
 *
 * Watching the raw callback costs nothing and takes nothing. It also sees every
 * click rather than at most one per tick, which a counter measuring clicks per
 * second rather obviously needs.
 */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {

    // 1.21.9 and later: onButton, with the button and modifiers in one record.
    @Inject(method = "onButton", at = @At("HEAD"))
    private void breeze$press(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        // GLFW_PRESS is 1. Not imported, because pulling in the GLFW binding for
        // one integer is not worth the coupling.
        if (action == 1) Clicks.onPress(info.button());
    }
}
