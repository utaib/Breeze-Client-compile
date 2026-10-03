package dev.breeze.web;

import dev.breeze.BreezeClient;

/**
 * One-time MCEF (embedded Chromium) availability check.
 *
 * The September shipped jars called {@code MCEF.isInitialized()} as their very
 * first instruction with no loader check and no exception handler. Without the
 * MCEF mod on the classpath that threw {@link NoClassDefFoundError} out of the
 * client entrypoint and Fabric aborted the whole game. That made every
 * web-menu jar a crash on any install that had not also installed MCEF.
 *
 * Here nothing in this class names an MCEF type: every call goes through
 * {@link McefBridge}, and only after {@link #mcefPresent()} has found MCEF, so
 * this class can load and answer "no" on a machine that has never heard of
 * MCEF. (A guard alone was not enough: while this class itself held an MCEF
 * listener, the JVM resolved MCEF's types as soon as it loaded this class,
 * before the guard ran.) The rest of the mod is unaffected; the web menu
 * simply reports unavailable and the game continues with the native screens.
 */
public final class WebInit {

    public enum State {
        /** No MCEF mod on the classpath: the web menu cannot exist here. */
        UNAVAILABLE,
        /** MCEF present but CEF has not finished booting (or failed). */
        WAITING,
        /** MCEF present and CEF initialised: the browser may be created. */
        READY,
    }

    private static volatile State state = State.WAITING;
    private static volatile boolean checked;

    private WebInit() {}

    /**
     * Whether the MCEF classes are loadable.
     *
     * {@code Class.forName} is the honest test: Fabric does not isolate mod
     * classloaders by default, so an absent mod simply fails the lookup rather
     * than poisoning ours. Result is cached; the set of mods cannot change
     * mid-session.
     */
    private static boolean mcefPresent() {
        try {
            Class.forName("com.cinemamod.mcef.MCEF");
            return true;
        } catch (Throwable notInstalled) {
            return false;
        }
    }

    /**
     * Check MCEF and, when present, ask it where CEF booting has got to.
     *
     * Safe to call every tick. The listener registration happens once; after
     * that the state is only read.
     */
    /**
     * The classpath path MCEF's ModScheme will look the page up under.
     *
     * Kept beside the check rather than derived from the page URL, so
     * that if one is edited and the other is not, this fails loudly at startup
     * instead of at the moment a player opens the menu.
     */
    private static final String PAGE_RESOURCE = dev.breeze.bridge.PageResources.ROOT + "index.html";

    /**
     * Confirm the interface is actually in the jar, at startup.
     *
     * MCEF resolves mod:// URLs with getResourceAsStream and, when that returns
     * nothing, creates the browser anyway and paints an empty surface. The
     * player gets a black screen, the log gets one line from MCEF that does not
     * mention Breeze, and nothing says the interface is missing. That happened
     * here for three builds running: the menu appeared to open and had in truth
     * never loaded a page at all.
     *
     * Checking at startup turns a silent black screen into a line that names the
     * missing file, and it runs whether or not anyone opens the menu.
     */
    private static boolean pagePresent() {
        try (java.io.InputStream in = WebInit.class.getClassLoader().getResourceAsStream(PAGE_RESOURCE)) {
            return in != null;
        } catch (Exception unreadable) {
            return false;
        }
    }

    public static void init() {
        if (checked) return;
        checked = true;

        if (pagePresent()) {
            BreezeClient.LOGGER.info("[Breeze] Web interface found at {}", PAGE_RESOURCE);
        } else {
            BreezeClient.LOGGER.error(
                    "[Breeze] Web interface MISSING from the classpath at {}. The web menu would open "
                            + "onto a blank screen, so it stays unavailable and the native screens are used.",
                    PAGE_RESOURCE);
            state = State.UNAVAILABLE;
            return;
        }

        if (!mcefPresent()) {
            state = State.UNAVAILABLE;
            BreezeClient.LOGGER.info("[Breeze] MCEF not installed - web menu unavailable, native screens remain");
            return;
        }
        try {
            if (McefBridge.isInitialized()) {
                state = State.READY;
                BreezeClient.LOGGER.info("[Breeze] MCEF already initialized. Web menu is available.");
                return;
            }
            // Early in client init CEF is usually still booting on its own
            // thread. Schedule a callback instead of blocking, so the game
            // reaches the title screen at its normal speed either way.
            McefBridge.whenInitialized(success -> {
                if (success) {
                    state = State.READY;
                    BreezeClient.LOGGER.info("[Breeze] MCEF finished initializing. Web menu is available.");
                } else {
                    state = State.UNAVAILABLE;
                    BreezeClient.LOGGER.warn("[Breeze] MCEF failed to initialize. Web menu will be unavailable this session.");
                }
            });
        } catch (Throwable t) {
            // A present-but-broken MCEF (wrong build for this Minecraft
            // version, missing natives) degrades to unavailable rather than
            // taking the client down.
            state = State.UNAVAILABLE;
            BreezeClient.LOGGER.warn("[Breeze] MCEF could not be used ({}). Web menu unavailable.", t.toString());
        }
    }

    /**
     * Progress MCEF's own boot along. Cheap; called from the client tick.
     *
     * The first tick that sees CEF ready installs the interface origin and the
     * bridge, and if Minecraft's title screen is showing at that moment (CEF
     * usually finishes booting a second or two after the title appears), swaps
     * it for the Breeze menu when the player has that setting on.
     */
    public static void tick() {
        if (state == State.WAITING) {
            try {
                if (McefBridge.isInitialized()) state = State.READY;
            } catch (Throwable ignored) {}
        }
        if (state != State.READY || readyHandled) return;
        readyHandled = true;
        if (!BreezeWeb.install()) {
            state = State.UNAVAILABLE;
            return;
        }
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (dev.breeze.compat.ActiveScreen.get(mc) instanceof net.minecraft.client.gui.screens.TitleScreen && UiState.replaceTitle()) {
            dev.breeze.compat.ActiveScreen.set(mc, new BreezeWebScreen(false));
        }
    }

    private static boolean readyHandled;

    /** Whether a browser may be created right now. */
    public static boolean available() {
        return state == State.READY;
    }

    /** For status readouts: why the web menu is or is not up. */
    public static State state() {
        return state;
    }

    /** The embedded Chromium's version, or null when there is no browser. */
    public static String chromiumVersion() {
        if (state == State.UNAVAILABLE) return null;
        try {
            return McefBridge.chromeVersion();
        } catch (Throwable notAvailable) {
            return null;
        }
    }
}
