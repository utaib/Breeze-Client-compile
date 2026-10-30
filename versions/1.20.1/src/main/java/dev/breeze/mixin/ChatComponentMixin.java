package dev.breeze.mixin;

import dev.breeze.BreezeTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(ChatComponent.class)
public class ChatComponentMixin {

    // The public three-argument addMessage: every new chat line passes through
    // it (the one-argument form calls it), and it has kept this shape from
    // 1.20.1 to 1.21.11, unlike the private form behind it.
    @ModifyVariable(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component breeze$chatTag(Component msg) {
        try {
            if (msg == null) return msg;
            String s = msg.getString();
            if (s.length() < 3 || s.charAt(0) != '<') return msg;
            int gt = s.indexOf('>');
            if (gt <= 1 || gt > 17) return msg;
            String name = s.substring(1, gt);
            if (!name.matches("[A-Za-z0-9_]{1,16}")) return msg;
            String key = name.toLowerCase();
            String tag = BreezeTag.chatText(key);
            if (tag == null) return msg;
            int col = BreezeTag.chatColor(key) & 0xFFFFFF;
            return Component.empty()
                    .append(Component.literal(tag + " ").withStyle(st -> st.withColor(TextColor.fromRgb(col))))
                    .append(msg);
        } catch (Throwable t) {
            return msg;
        }
    }
}
