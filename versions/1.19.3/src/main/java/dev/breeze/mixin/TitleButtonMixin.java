package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.compat.GuiGraphics;
import dev.breeze.ui.MenuBg;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The title screen's buttons in Breeze's style. Up to 1.19.3 a button draws in
 * AbstractWidget.renderButton (AbstractButton has no renderWidget yet), so
 * this hooks the widget and keeps to buttons itself.
 */
@Mixin(AbstractWidget.class)
public abstract class TitleButtonMixin {

    @Inject(method = "renderButton", at = @At("HEAD"), cancellable = true)
    private void breeze$style(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        Object self = this;
        if (!(self instanceof AbstractButton) || self instanceof ImageButton) return;
        if (!MenuBg.useBreeze()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(dev.breeze.compat.ActiveScreen.get(mc) instanceof TitleScreen)) return;
        AbstractWidget button = (AbstractWidget) self;
        if (button.getMessage().getString().isEmpty()) return;
        GuiGraphics g = new GuiGraphics(poseStack);
        int bx = button.getX();
        int by = button.getY();
        int w = button.getWidth();
        int h = button.getHeight();
        boolean hover = button.active && mouseX >= bx && mouseX < bx + w && mouseY >= by && mouseY < by + h;
        UiRender.rounded(g, bx, by, w, h, hover ? Theme.withAlpha(Theme.primary(), 0x80) : 0xD214141C);
        UiRender.accentBar(g, bx, by + h - 2, w, 2);
        int color = !button.active ? 0xFF70707C : hover ? 0xFFFFFFFF : 0xFFE4E4EE;
        g.drawCenteredString(mc.font, button.getMessage(), bx + w / 2, by + (h - 8) / 2, color);
        ci.cancel();
    }
}
