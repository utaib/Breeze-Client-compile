package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.cape.CapeManager;

/**
 * The cape wave, and a cape image of your own from the breeze_capes folder.
 *
 * This used to be the switch for your Breeze cape itself: your equipped cape
 * only drew while it was on, and turning it on opened the old cape editor. That
 * is the activation bug the spec names. Equipping in the Wardrobe is now all a
 * cape needs (see CapePolicy); this module only adds the wave and the local
 * image, which shows when no Breeze cape is equipped and only to you.
 */
public class CustomCape extends Module {

    private static CustomCape instance;

    public CustomCape() {
        super("Custom Cape", Category.VISUAL,
                "Adds a wave to capes, and shows your own cape image from the breeze_capes folder when no Breeze cape is equipped.",
                KEY_NONE);
        instance = this;
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    @Override
    protected void onEnable() {
        // Picks up an image added to the folder since the last load. No screen
        // opens: this can be switched from the Breeze menu, and taking the
        // player to another screen from there would close the menu under them.
        try {
            CapeManager.load();
        } catch (Throwable ignored) {}
    }
}
