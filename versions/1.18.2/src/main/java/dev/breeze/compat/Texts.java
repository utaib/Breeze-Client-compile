package dev.breeze.compat;

import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;

/**
 * Text components before Minecraft 1.19, which made them with
 * Component.literal, Component.empty and Component.translatable. On these
 * versions renames.txt points those calls here, so the rest of the source
 * stays the same.
 */
public final class Texts {

    private Texts() {}

    public static MutableComponent literal(String text) {
        return new TextComponent(text);
    }

    public static MutableComponent empty() {
        return new TextComponent("");
    }

    public static MutableComponent translatable(String key, Object... args) {
        return new TranslatableComponent(key, args);
    }
}
