package dev.breeze.bridge;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Every action in contract/bridge.json with the thread it must run on.
 *
 * Version modules register a handler for each, and check at startup that none
 * is missing ({@link Router#missing}); ContractTest keeps this list equal to the
 * contract file, so the three can only drift together and loudly.
 */
public final class BridgeActions {

    private static final Map<String, Router.Thread> THREADS = new LinkedHashMap<>();

    static {
        any("app.hello");
        client("app.openExternal");
        client("ui.close");
        any("ui.escapeAck");
        any("ui.route");
        client("ui.vanillaMenu");
        client("game.state");
        client("game.singleplayer");
        client("game.multiplayer");
        client("game.joinServer");
        client("game.options");
        client("game.pauseMenu");
        client("game.quit");
        any("settings.get");
        client("settings.set");
        client("settings.reset");
        client("modules.list");
        client("modules.setEnabled");
        client("modules.setSetting");
        client("modules.reset");
        client("hud.openEditor");
        any("mods.list");
        any("account.get");
        io("cosmetics.state");
        io("cosmetics.equipCape");
        io("cosmetics.equipModel");
        io("cosmetics.unequipModel");
        any("friends.list");
        io("friends.request");
        io("friends.respond");
        io("friends.remove");
        client("hosting.state");
        client("hosting.start");
        client("hosting.stop");
        client("hosting.join");
    }

    public static final Set<String> ALL = Collections.unmodifiableSet(THREADS.keySet());

    private BridgeActions() {}

    public static Router.Thread thread(String action) {
        return THREADS.get(action);
    }

    private static void any(String a) {
        THREADS.put(a, Router.Thread.ANY);
    }

    private static void client(String a) {
        THREADS.put(a, Router.Thread.CLIENT);
    }

    private static void io(String a) {
        THREADS.put(a, Router.Thread.IO);
    }
}
