package dev.breeze.mixin;
import dev.breeze.compat.Ids;
import dev.breeze.ui.Palette;

import dev.breeze.menu.BreezeMenuScreen;
import dev.breeze.ui.MenuBg;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    @Unique
    private static final ResourceLocation BREEZE_BG = Ids.breeze("textures/gui/breeze_bg.png");

    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private int breeze$btnY(int i) {
        return this.height / 2 - 22 + i * 26;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void breeze$bg(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        if (!MenuBg.useBreeze()) return;
        g.blit(BREEZE_BG, 0, 0, this.width, this.height, 0f, 0f, 16, 16, 16, 16);
        g.fill(0, 0, this.width, this.height, Palette.alpha(Palette.BG_DEEP, 0x50));
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void breeze$sideButtons(GuiGraphics g, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        String[] labels = {"Breeze", MenuBg.useBreeze() ? "BG: Breeze" : "BG: Vanilla", "Friends"};
        for (int i = 0; i < labels.length; i++) {
            int y = breeze$btnY(i);
            boolean hover = mouseX >= 6 && mouseX <= 6 + 78 && mouseY >= y && mouseY <= y + 20;
            UiRender.rounded(g, 6, y, 78, 20, hover ? Theme.cardHover() : Palette.alpha(Palette.SURFACE, 0xC8));
            UiRender.accentBar(g, 6, y, 2, 20);
            g.drawString(this.font, labels[i], 14, y + 6, hover ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY, false);
        }
        UiRender.logo(g, 6, breeze$btnY(0) - 40, 32, 1.0f);
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void breeze$click(double mx, double my, int button, CallbackInfoReturnable<Boolean> cir) {
        if (button != 0 || mx < 6 || mx > 84) return;
        for (int i = 0; i < 3; i++) {
            int y = breeze$btnY(i);
            if (my >= y && my <= y + 20) {
                if (i == 0) {
                    dev.breeze.ui.BreezeUi.open(this.minecraft);
                } else if (i == 1) {
                    MenuBg.toggle();
                } else {
                    this.minecraft.setScreen(new dev.breeze.menu.FriendsScreen((net.minecraft.client.gui.screens.Screen) (Object) this));
                }
                cir.setReturnValue(true);
                return;
            }
        }
    }
}
