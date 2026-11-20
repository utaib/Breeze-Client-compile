package dev.breeze.devtest;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.network.chat.Style;

/**
 * The style of the chat text at a point of the open chat screen (self-test:
 * where to click a chat button with the real mouse), found the way
 * ChatScreen finds the text under a click in 26.1 (a display mode in place
 * of the focused flag).
 */
final class ChatProbe {

    private ChatProbe() {}

    /** Opens the chat screen, empty, as T does (not a draft). */
    static void openChat(Minecraft mc) {
        dev.breeze.compat.ActiveScreen.set(mc, new net.minecraft.client.gui.screens.ChatScreen("", false));
    }

    static Style styleAt(Minecraft mc, int guiX, int guiY) {
        ActiveTextCollector.ClickableStyleFinder f = new ActiveTextCollector.ClickableStyleFinder(mc.font, guiX, guiY);
        mc.gui.getChat().captureClickableText(f, mc.getWindow().getGuiScaledHeight(), mc.gui.getGuiTicks(),
                net.minecraft.client.gui.components.ChatComponent.DisplayMode.FOREGROUND);
        return f.result();
    }
}
