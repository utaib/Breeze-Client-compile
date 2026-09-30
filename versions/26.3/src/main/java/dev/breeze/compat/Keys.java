package dev.breeze.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Breeze's key bindings, all in one "Breeze" category of Minecraft's Controls
 * screen. How a category is named changed in Minecraft 1.21.9 (an id instead
 * of a translation key), so versions/ has a copy of this file per form; this
 * is the 26.3 form: as 1.21.9, and a keyboard binding is made without naming
 * the input type (InputConstants.Type no longer has KEYSYM by that name). The category's label is the translation
 * key.category.breeze.breeze.
 *
 * 26.3 reads keys from SDL: key codes are SDL scancodes and "no key" is 0,
 * where GLFW had -1 (Breeze's Module.KEY_NONE). A binding left at -1 is not
 * seen as unbound, and KeyMapping.setAll (run when the mouse is grabbed, so
 * on entering a world) asks the keyboard state for index -1 and throws.
 */
public final class Keys {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ids.breeze("breeze"));

    private Keys() {}

    /** A keyboard binding named by its translation key, registered with Fabric API. */
    public static KeyMapping register(String name, int defaultKey) {
        int key = defaultKey < 0 ? InputConstants.UNKNOWN.getValue() : defaultKey;
        KeyMapping km = new KeyMapping(name, key, CATEGORY);
        return KeyBindingHelper.registerKeyBinding(km);
    }
}
