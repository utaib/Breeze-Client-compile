package dev.breeze.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Breeze's key bindings, all in one "Breeze" category of Minecraft's Controls
 * screen. How a category is named changed in Minecraft 1.21.9 (an id instead
 * of a translation key), so versions/ has a copy of this file per form; this
 * is the form before 1.21.9.
 */
public final class Keys {

    private static final String CATEGORY = "key.categories.breeze";

    private Keys() {}

    /** A keyboard binding named by its translation key, registered with Fabric API. */
    public static KeyMapping register(String name, int defaultKey) {
        KeyMapping km = new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
        return KeyBindingHelper.registerKeyBinding(km);
    }
}
