package dev.breeze.mixin;

import dev.breeze.web.BreezeWebScreen;
import dev.breeze.web.UiState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Shows the Breeze menu wherever Minecraft would show its title screen.
 *
 * Every route to the title screen goes through setScreen (on Gui from 26.2): startup, leaving a
 * world, a disconnect, Back from world selection. Swapping the argument here
 * covers all of them in one place, and nothing else about those flows changes.
 * setScreen(null) with no world loaded is Minecraft's own shorthand for "the
 * title screen", so it is swapped too.
 *
 * It only happens when the player has the Breeze title screen on, has not
 * asked for Minecraft's own for this session, and the embedded browser is
 * ready. Otherwise the vanilla title screen appears exactly as it would
 * without Breeze.
 */
@Mixin(Gui.class)
public abstract class MinecraftSetScreenMixin {

    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true)
    private Screen breeze$titleMenu(Screen screen) {
        boolean title = screen instanceof TitleScreen || (screen == null && Minecraft.getInstance().level == null);
        if (!title) return screen;
        try {
            if (UiState.replaceTitle()) return new BreezeWebScreen(false);
        } catch (Throwable t) {
            // Never let the menu stand between the player and a working title screen.
        }
        return screen;
    }
}
