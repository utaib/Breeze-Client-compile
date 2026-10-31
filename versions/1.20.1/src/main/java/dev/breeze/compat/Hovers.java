package dev.breeze.compat;

import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.UUID;

/**
 * Hover events in text, up to 1.21.4: a class with an action and a value.
 * 1.21.5 made HoverEvent an interface of records (versions/1.21.5 has that
 * form).
 */
public final class Hovers {

    private Hovers() {}

    /**
     * The entity a piece of text names, when its hover shows that entity
     * (Minecraft styles every player name it writes this way: chat, joins,
     * deaths, advancements); null otherwise.
     */
    public static UUID entityId(Style style) {
        HoverEvent h = style == null ? null : style.getHoverEvent();
        if (h == null || h.getAction() != HoverEvent.Action.SHOW_ENTITY) return null;
        HoverEvent.EntityTooltipInfo info = h.getValue(HoverEvent.Action.SHOW_ENTITY);
        return info == null ? null : info.id;
    }
}
