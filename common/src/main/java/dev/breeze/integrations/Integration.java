package dev.breeze.integrations;

import java.util.List;

/**
 * Something another mod offers that Breeze can show in its menus: a screen
 * to open, found through a real Fabric API (Mod Menu's entrypoint, or the
 * mod's own "breeze" entrypoint). Only integrations that are actually there
 * are listed, so a menu built from this list has no dead entries.
 *
 * @param id       stable id, used to open it again ("modmenu", "breeze:xaeroworldmap")
 * @param provider where it came from: "modmenu" or "breeze-entrypoint"
 */
public record Integration(String id, String name, String provider, List<Action> actions) {

    /**
     * One thing to open.
     *
     * @param mod the mod the action is about (its settings), or null
     */
    public record Action(String id, String label, String mod) {}

    public Integration {
        actions = List.copyOf(actions);
    }

    public Action action(String actionId) {
        for (Action a : actions) {
            if (a.id().equals(actionId)) return a;
        }
        return null;
    }
}
