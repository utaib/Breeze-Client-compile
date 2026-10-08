package dev.breeze.web;

import dev.breeze.BreezeClient;
import dev.breeze.settings.UiSettings;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Theme;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;

/**
 * Interface settings and session state for the web menu.
 *
 * Settings live in config/breeze-ui.json on this computer. Changing one here is
 * applied to the game immediately (the accent recolours the HUD through
 * {@link Theme}), then saved. Only the client thread touches this class.
 */
public final class UiState {

    private static UiSettings settings;
    private static Path file;

    /** Routes the page reported, per context, so a reopened menu resumes. */
    private static volatile String lastTitleRoute;
    private static volatile String lastGameRoute;

    /** The player asked for Minecraft's own title screen for this session. */
    private static volatile boolean vanillaTitleThisSession;

    private UiState() {}

    public static synchronized UiSettings settings() {
        if (settings == null) {
            file = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("breeze-ui.json");
            settings = UiSettings.load(file);
        }
        return settings;
    }

    /** A copy of the settings as JSON, safe to hand to another thread. */
    public static synchronized com.google.gson.JsonObject snapshot() {
        return settings().toJson();
    }

    /** Validate and apply one change from the page; throws INVALID_PARAMS. */
    public static synchronized void set(String key, com.google.gson.JsonElement value) {
        settings().set(key, value);
        changed();
    }

    /** Apply side effects, then persist. */
    public static synchronized void changed() {
        UiSettings s = settings();
        applyToGame(s);
        try {
            s.save(file);
        } catch (Exception e) {
            BreezeClient.LOGGER.warn("[Breeze] interface settings not saved: {}", e.toString());
        }
    }

    public static synchronized void reset() {
        settings = new UiSettings();
        changed();
    }

    /** The accent is also the HUD accent, so the whole client changes together. */
    static void applyToGame(UiSettings s) {
        int argb = 0xFF000000 | s.accentRgb();
        if (Theme.primary != argb) {
            Theme.primary = argb;
            Theme.secondary = Palette.lerp(argb, 0xFFFFFFFF, 0.25f);
            Theme.save();
        }
    }

    static String lastRoute(boolean ingame) {
        return ingame ? lastGameRoute : lastTitleRoute;
    }

    static void rememberRoute(boolean ingame, String route) {
        if (ingame) lastGameRoute = route;
        else lastTitleRoute = route;
    }

    public static boolean vanillaTitleThisSession() {
        return vanillaTitleThisSession;
    }

    public static void useVanillaTitle(boolean vanilla) {
        vanillaTitleThisSession = vanilla;
    }

    // ── failed opens of the title menu ───────────────────────────────────────
    //
    // A failed open shows Minecraft's title screen and the web menu is tried
    // again a few seconds later (WebInit.tick), up to MAX_FAILED_OPENS times a
    // session. One failure used to switch the web menu off until restart.

    static final int MAX_FAILED_OPENS = 3;
    private static final long RETRY_AFTER_MS = 3_000;
    private static volatile int failedOpens;
    private static volatile long lastFailedOpen;
    private static volatile String openProblem;

    /** Why the last browser open failed, set where it failed (BreezeBrowser.open). */
    static void openProblem(String why) {
        openProblem = why;
    }

    static String takeOpenProblem(String otherwise) {
        String why = openProblem;
        openProblem = null;
        return why != null ? why : otherwise;
    }

    /** Counts a failed title-menu open; returns which attempt it was. */
    static int titleOpenFailed() {
        lastFailedOpen = System.currentTimeMillis();
        return ++failedOpens;
    }

    static void titleOpened() {
        failedOpens = 0;
    }

    static boolean gaveUp() {
        return failedOpens >= MAX_FAILED_OPENS;
    }

    private static boolean coolingDown() {
        return failedOpens > 0 && System.currentTimeMillis() - lastFailedOpen < RETRY_AFTER_MS;
    }

    /** A failed open is waiting for its retry. */
    static boolean retryDue() {
        return failedOpens > 0 && !gaveUp() && !coolingDown();
    }

    /** Whether the Breeze menu should stand in for Minecraft's title screen right now. */
    public static boolean replaceTitle() {
        return settings().replaceTitleScreen && !vanillaTitleThisSession && !gaveUp() && !coolingDown()
                && WebInit.available() && !"false".equalsIgnoreCase(System.getProperty("breeze.webmenu"));
    }
}
