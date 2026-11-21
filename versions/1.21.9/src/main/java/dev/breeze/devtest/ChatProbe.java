package dev.breeze.devtest;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Style;

/**
 * The style of the chat text at a point of the open chat screen (self-test:
 * where to click a chat button with the real mouse). Minecraft asks the chat
 * the same way when the player clicks. Up to 1.21.10 the chat answers
 * directly. This is the 1.21.9 and 1.21.10 form: the chat screen takes a
 * second argument.
 */
final class ChatProbe {

    private ChatProbe() {}

    /** Opens the chat screen, empty, as T does (not a draft). */
    static void openChat(Minecraft mc) {
        dev.breeze.compat.ActiveScreen.set(mc, new net.minecraft.client.gui.screens.ChatScreen("", false));
    }

    static Style styleAt(Minecraft mc, int guiX, int guiY) {
        return mc.gui.getChat().getClickedComponentStyleAt(guiX, guiY);
    }
}
