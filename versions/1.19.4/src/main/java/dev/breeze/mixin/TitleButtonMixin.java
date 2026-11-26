package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.ui.MenuBg;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.Minecraft;
import dev.breeze.compat.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ImageButton;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Before 1.20 Minecraft draws with a PoseStack; the handler wraps it in
// Breeze's GuiGraphics (compat/GuiGraphics) so the body stays the same.
@Mixin(AbstractButton.class)
public abstract class TitleButtonMixin extends AbstractWidget {

    protected TitleButtonMixin(int x, int y, int w, int h, Component message) {
        super(x, y, w, h, message);
    }

    @Inject(method = "renderWidget", at = @At("HEAD"), cancellable = true)
    private void breeze$style(PoseStack poseStack, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        GuiGraphics g = new GuiGraphics(poseStack);
        if (!MenuBg.useBreeze()) return;
        Minecraft mc = Minecraft.getInstance();
        if (!(dev.breeze.compat.ActiveScreen.get(mc) instanceof TitleScreen)) return;
        Object self = this;
        if (self instanceof ImageButton) return;
        if (getMessage().getString().isEmpty()) return;
        int bx = getX();
        int by = getY();
        int w = getWidth();
        int h = getHeight();
        boolean hover = this.active && mouseX >= bx && mouseX < bx + w && mouseY >= by && mouseY < by + h;
        UiRender.rounded(g, bx, by, w, h, hover ? Theme.withAlpha(Theme.primary(), 0x80) : 0xD214141C);
        UiRender.accentBar(g, bx, by + h - 2, w, 2);
        int color = !this.active ? 0xFF70707C : hover ? 0xFFFFFFFF : 0xFFE4E4EE;
        g.drawCenteredString(mc.font, getMessage(), bx + w / 2, by + (h - 8) / 2, color);
        ci.cancel();
    }
}
