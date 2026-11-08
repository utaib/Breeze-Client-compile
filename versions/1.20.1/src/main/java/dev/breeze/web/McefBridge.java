package dev.breeze.web;

import java.util.function.Consumer;

/**
 * Every direct call into MCEF that WebInit makes, kept out of WebInit itself.
 *
 * WebInit is loaded at client start on every install. When it named MCEF's
 * types in its own body, the JVM had to resolve them as soon as WebInit was
 * verified, before its "is MCEF installed" check could run: on a game without
 * MCEF the client entrypoint failed with NoClassDefFoundError
 * (com/cinemamod/mcef/listeners/MCEFInitListener), found by the in-game test
 * run without MCEF on 1.20.2 and 1.21.4. This class is only touched after
 * WebInit has seen MCEF on the classpath, so it is only ever loaded there.
 */
final class McefBridge {

    private McefBridge() {}

    static boolean isInitialized() {
        return com.cinemamod.mcef.MCEF.isInitialized();
    }

    /** Calls {@code done} with true or false once MCEF's own start-up has finished. */
    static void whenInitialized(Consumer<Boolean> done) {
        com.cinemamod.mcef.MCEF.scheduleForInit(new com.cinemamod.mcef.listeners.MCEFInitListener() {
            public void onInit(boolean success) {
                done.accept(success);
            }
        });
    }

    static String chromeVersion() {
        return com.cinemamod.mcef.MCEF.getApp().getHandle().getVersion().getChromeVersion();
    }
}
