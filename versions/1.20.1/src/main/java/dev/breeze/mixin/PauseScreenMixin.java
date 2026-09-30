package dev.breeze.mixin;

import dev.breeze.menu.BreezeMenuScreen;
import dev.breeze.menu.FriendsScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("RETURN"))
    private void breeze$buttons(CallbackInfo ci) {
        addRenderableWidget(dev.breeze.compat.Widgets.button(Component.literal("Friends"), b -> dev.breeze.compat.ActiveScreen.set(this.minecraft, new FriendsScreen(this)), 4, 4, 70, 20));
        addRenderableWidget(dev.breeze.compat.Widgets.button(Component.literal("Breeze"), b -> dev.breeze.ui.BreezeUi.open(this.minecraft), 4, 26, 70, 20));
    }
}
