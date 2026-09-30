package dev.breeze.compat;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Breeze's key bindings, all in one "Breeze" category of Minecraft's Controls
 * screen. How a category is named changed in Minecraft 1.21.9 (an id instead
 * of a translation key), so versions/ has a copy of this file per form; this
 * is the 26.3 form: as 1.21.9, and a keyboard binding is made without naming
 * the input type (InputConstants.Type no longer has KEYSYM by that name). The category's label is the translation
 * key.category.breeze.breeze.
 */
public final class Keys {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ids.breeze("breeze"));

    private Keys() {}

    /** A keyboard binding named by its translation key, registered with Fabric API. */
    public static KeyMapping register(String name, int defaultKey) {
        KeyMapping km = new KeyMapping(name, defaultKey, CATEGORY);
        return KeyBindingHelper.registerKeyBinding(km);
    }
}
