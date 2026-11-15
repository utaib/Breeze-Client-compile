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
 * This is the form from 1.21.9: a category is an id (shown as
 * key.category.breeze.breeze) instead of a translation key, and Minecraft
 * reads key presses as KeyEvents and takes the window itself, as in the
 * Fabric build's versions/1.21.9 Keys. 26.3: a keyboard binding is made
 * without naming the input type, key state comes from SDL, and the window's
 * own key state is not asked (as versions/26.3's Keys).
 */
public final class Keys {

    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Ids.breeze("breeze"));
    private static final List<KeyMapping> PENDING = new ArrayList<>();
    private static boolean handedOver;

    private Keys() {}

    /** A keyboard binding named by its translation key, registered with the loader's next key event. */
    public static synchronized KeyMapping register(String name, int defaultKey) {
        int key = defaultKey < 0 ? InputConstants.UNKNOWN.getValue() : defaultKey;
        KeyMapping km = new KeyMapping(name, key, CATEGORY);
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
        return InputConstants.isKeyDown(key);
    }

    public static InputConstants.Key keyOf(int key, int scanCode) {
        return InputConstants.getKey(new net.minecraft.client.input.KeyEvent(key, scanCode, 0));
    }

    public static boolean[] windowKeyState(int key) {
        return null;
    }
}
