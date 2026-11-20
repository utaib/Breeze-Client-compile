package dev.breeze.compat;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

/**
 * A clickable word Breeze writes in chat: its action rides in the style's
 * insertion (caught by ShotClickMixin before Minecraft acts), with a hover
 * text and a click event so Minecraft treats it as clickable. The click event
 * only copies the same text, so if it ever reached Minecraft nothing would be
 * sent anywhere. This is the 1.21.5 form: click and hover events are
 * records.
 */
public final class ChatButtons {

    private ChatButtons() {}

    public static Style style(Style base, String action, Component hover) {
        return base.withInsertion(action)
                .withClickEvent(new ClickEvent.CopyToClipboard(action))
                .withHoverEvent(new HoverEvent.ShowText(hover));
    }
}
