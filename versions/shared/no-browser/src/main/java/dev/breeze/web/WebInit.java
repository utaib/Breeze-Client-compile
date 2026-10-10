package dev.breeze.web;

import dev.breeze.BreezeClient;

/**
 * WebInit for Minecraft versions that MCEF (the embedded browser) publishes no
 * Fabric build for. There is no browser to start, so the web menu is always
 * unavailable and the native screens are the interface. Same API as the
 * browser version, so nothing else changes.
 *
 * Used through versions/shared/no-browser, which a version lists last in its
 * source_chain when it has no mcef_version.
 */
public final class WebInit {

    public enum State {
        UNAVAILABLE,
        WAITING,
        READY,
    }

    private WebInit() {}

    public static void init() {
        BreezeClient.LOGGER.info("[Breeze] No embedded browser exists for this Minecraft version; native screens are the interface");
    }

    public static void tick() {
    }

    public static boolean available() {
        return false;
    }

    public static State state() {
        return State.UNAVAILABLE;
    }

    public static String chromiumVersion() {
        return null;
    }
}
