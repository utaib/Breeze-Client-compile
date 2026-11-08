package dev.breeze.compat;

import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;

import java.util.UUID;

/** Hover events from 1.21.5: an interface of records, ShowEntity among them. */
public final class Hovers {

    private Hovers() {}

    /** The entity a piece of text names, when its hover shows that entity; null otherwise. */
    public static UUID entityId(Style style) {
        HoverEvent h = style == null ? null : style.getHoverEvent();
        if (!(h instanceof HoverEvent.ShowEntity show) || show.entity() == null) return null;
        return show.entity().uuid;
    }
}
