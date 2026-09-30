package dev.breeze.ui;

import dev.breeze.BreezeClient;
import dev.breeze.menu.BreezeMenuScreen;
import dev.breeze.web.BreezeWebScreen;
import dev.breeze.web.WebInit;
import net.minecraft.client.Minecraft;

/**
 * The one way into the Breeze menu.
 *
 * There were three routes in and they did not agree. The keybind opened the web
 * interface whenever MCEF had initialised; the buttons on the title and pause
 * screens always opened the native one. So the same install answered the same
 * question two different ways depending on how you asked, which reads as the
 * menu being broken rather than as two interfaces existing.
 *
 * Both are worth having. The native screens run on all forty supported
 * Minecraft versions; the web interface runs on the ten with an MCEF build
 * (docs/MOD_VERSION_MATRIX.md), and for three quarters of the range the native
 * screens are the only interface there will ever be. So this is not a fallback
 * arrangement with a preferred winner, it is a choice, and the player makes it.
 */
public final class BreezeUi {

    /**
     * Kept as an escape hatch for a machine where CEF makes the game unusable.
     * It forces native regardless of preference, which is what you want when
     * the thing you would otherwise have to click to change the setting is the
     * thing that is broken.
     */
    private static final boolean WEB_FORCED_OFF = "false".equalsIgnoreCase(System.getProperty("breeze.webmenu"));

    private BreezeUi() {}

    /** Whether the web interface could open right now. */
    public static boolean webAvailable() {
        return !WEB_FORCED_OFF && WebInit.available();
    }

    /**
     * Whether the player's preference can actually be honoured.
     *
     * Used by the settings screen to say why the choice has no effect here,
     * rather than leaving a control that silently does nothing.
     */
    public static boolean webPossible() {
        return !WEB_FORCED_OFF && WebInit.state() != WebInit.State.UNAVAILABLE;
    }

    /** Open whichever interface the player asked for. Never throws. */
    public static void open(Minecraft mc) {
        try {
            if (shouldUseWeb()) {
                boolean ingame = mc.level != null;
                // Asking for Breeze from Minecraft's own title screen means the
                // player wants it back, so stop preferring vanilla this session.
                if (!ingame) dev.breeze.web.UiState.useVanillaTitle(false);
                mc.setScreen(new BreezeWebScreen(ingame));
            } else {
                mc.setScreen(new BreezeMenuScreen());
            }
        } catch (Throwable failed) {
            // Falling back rather than leaving the player with nothing. If the
            // web screen cannot be constructed the native one still can, and a
            // player locked out of their own settings has no way to fix it.
            BreezeClient.LOGGER.warn("[Breeze] Could not open the web menu, using the native one: {}", failed.toString());
            try {
                mc.setScreen(new BreezeMenuScreen());
            } catch (Throwable alsoFailed) {
                BreezeClient.LOGGER.error("[Breeze] Could not open any menu", alsoFailed);
            }
        }
    }

    private static boolean shouldUseWeb() {
        switch (Theme.uiMode) {
            case NATIVE:
                return false;
            case WEB:
                // Asking for it does not conjure a browser. On a version with no
                // MCEF build this quietly gives the native screens instead.
                return webAvailable();
            case AUTO:
            default:
                return webAvailable();
        }
    }
}
