package dev.breeze.compat;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Breeze's key bindings on Forge and NeoForge, all in one "Breeze" category
 * of Minecraft's Controls screen. The loader adds bindings in its key
 * registration event, so a binding made here waits in a list until that
 * event hands them over ({@link #registerPending}). Breeze makes its
 * bindings while it starts, inside that event (BreezeInit), so none is late.
 * The rest is the Fabric build's Keys, unchanged (the form before 1.21.9).
 */
public final class Keys {

    private static final String CATEGORY = "key.categories.breeze";
    private static final List<KeyMapping> PENDING = new ArrayList<>();
    private static boolean handedOver;

    private Keys() {}

    /** A keyboard binding named by its translation key, registered with the loader's next key event. */
    public static synchronized KeyMapping register(String name, int defaultKey) {
        KeyMapping km = new KeyMapping(name, InputConstants.Type.KEYSYM, defaultKey, CATEGORY);
        if (handedOver) {
            dev.breeze.BreezeClient.LOGGER.warn("[Breeze] Key {} was made after the loader registered keys; it is not in Controls", name);
        }
        PENDING.add(km);
        return km;
    }

    /** Hands every binding made so far to the loader's key event. */
    public static synchronized void registerPending(Consumer<KeyMapping> register) {
        for (KeyMapping km : PENDING) register.accept(km);
        dev.breeze.BreezeClient.LOGGER.info("[Breeze] {} key bindings registered", PENDING.size());
        PENDING.clear();
        handedOver = true;
    }

    public static boolean isDown(net.minecraft.client.Minecraft mc, int key) {
        return InputConstants.isKeyDown(mc.getWindow().getWindow(), key);
    }

    public static InputConstants.Key keyOf(int key, int scanCode) {
        return InputConstants.getKey(key, scanCode);
    }

    public static boolean[] windowKeyState(int key) {
        long window = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
        if (window == 0) return null;
        return new boolean[] {
                org.lwjgl.glfw.GLFW.glfwGetKey(window, key) == org.lwjgl.glfw.GLFW.GLFW_PRESS,
                org.lwjgl.glfw.GLFW.glfwGetWindowAttrib(window, org.lwjgl.glfw.GLFW.GLFW_FOCUSED) != 0 };
    }
}
