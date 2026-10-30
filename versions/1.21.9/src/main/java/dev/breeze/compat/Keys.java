package dev.breeze.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;

/**
 * Breeze's key bindings, all in one "Breeze" category of Minecraft's Controls
 * screen. How a category is named changed in Minecraft 1.21.9 (an id instead
 * of a translation key), so versions/ has a copy of this file per form; this
 * is the 1.21.9 form. The category's label is the translation
 * key.category.breeze.breeze.
 */
public final class Keys {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ids.breeze("breeze"));

    private Keys() {}

    /** A keyboard binding named by its translation key, registered with Fabric API. */
    public static KeyMapping register(String name, int defaultKey) {
        KeyMapping km = new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
        return KeyBindingHelper.registerKeyBinding(km);
    }

    /** The key Minecraft means by a key press's key code and scan code (as its Controls screen reads it). */
    public static InputConstants.Key keyOf(int key, int scanCode) {
        return InputConstants.getKey(new net.minecraft.client.input.KeyEvent(key, scanCode, 0));
    }

    /**
     * For the self-test: whether the game window itself (GLFW, read on the
     * render thread, whose context is the window's) reports this key held,
     * and whether the window has focus. Null when there is no window.
     */
    public static boolean[] windowKeyState(int key) {
        long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        if (window == 0) return null;
        return new boolean[] {
                org.lwjgl.glfw.GLFW.glfwGetKey(window, key) == org.lwjgl.glfw.GLFW.GLFW_PRESS,
                org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != 0 };
    }
}
