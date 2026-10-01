package dev.breeze.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.breeze.BreezeTag;
import dev.breeze.Roles;
import dev.breeze.modules.NicknameHider;
import dev.breeze.ui.UiRender;
import net.minecraft.client.Minecraft;
import dev.breeze.compat.GuiGraphics;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

// Before 1.20 Minecraft draws with a PoseStack; the handler wraps it in
// Breeze's GuiGraphics (compat/GuiGraphics) so the body stays the same.
@Mixin(PlayerTabOverlay.class)
public class PlayerTabOverlayMixin {

    /**
     * Prefix the tab entry with the player's official badge and their own tag.
     *
     * Order is badge, then custom, then name: "[Creator] [TheirBrand] Niv". The
     * official badge always comes first and is never replaced by the custom one,
     * so no custom tag can be arranged to read as though it were the official
     * one. A creator's branding sits beside their badge, not in place of it.
     */
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), cancellable = true)
    private void breeze$tabTag(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        try {
            if (info == null || info.getProfile() == null) return;
            UUID id = dev.breeze.compat.Profiles.id(info.getProfile());
            Minecraft mc = Minecraft.getInstance();
            if (NicknameHider.active() && mc.player != null && id != null && id.equals(mc.player.getUUID())) {
                cir.setReturnValue(Component.literal(NicknameHider.mask()));
                return;
            }
            if (!BreezeTag.hasTag(id)) return;

            Component out = Component.empty()
                    .append(coloured(BreezeTag.text(id) + " ", BreezeTag.color(id)));

            String custom = BreezeTag.custom(id);
            if (custom != null && !custom.isEmpty()) {
                out = out.copy().append(coloured(custom + " ", BreezeTag.customColor(id)));
            }

            cir.setReturnValue(out.copy().append(cir.getReturnValue()));
        } catch (Throwable ignored) {}
    }

    private static Component coloured(String text, int argb) {
        return Component.literal(text).withStyle(s -> s.withColor(TextColor.fromRgb(argb & 0xFFFFFF)));
    }

    /**
     * The Wind Charge beside each tab entry, tinted to that player's role.
     *
     * Hooked on renderPingIcon rather than on the tab list's own render, because
     * this is the one method called exactly once per row that receives both the
     * GuiGraphics and the row's position. Its shape is identical on every
     * version this client builds for.
     *
     * Drawn to the left of the ping bars, which vanilla places in the last 11
     * pixels of the row. That band is past the end of the name text, so the icon
     * cannot cover a player's name however long it is.
     */
    @Inject(method = "renderPingIcon", at = @At("HEAD"), require = 0)
    private void breeze$roleIcon(PoseStack poseStack, int width, int x, int y, PlayerInfo info, CallbackInfo ci) {
        GuiGraphics g = new GuiGraphics(poseStack);
        try {
            if (info == null || info.getProfile() == null) return;
            Roles.Role role = BreezeTag.role(dev.breeze.compat.Profiles.id(info.getProfile()));
            // Null means not a Breeze player at all. Their row stays vanilla.
            if (role == null) return;
            UiRender.windCharge(g, x + width - 22, y + 1, 9, role.color);
        } catch (Throwable ignored) {}
    }
}
