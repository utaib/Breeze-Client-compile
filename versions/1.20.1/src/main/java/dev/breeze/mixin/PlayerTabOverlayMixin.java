package dev.breeze.mixin;

import dev.breeze.BreezeTag;
import dev.breeze.modules.NicknameHider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

}
