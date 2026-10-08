package dev.breeze.integrations;

import java.util.List;

/**
 * One mod Fabric loaded in this game, as its metadata describes it.
 *
 * @param parent the mod whose jar carries this one inside it, or null
 * @param builtin provided by the game itself (Minecraft, Java, Fabric Loader)
 * @param badges Mod Menu's badges from the mod's own metadata
 *               ({@code custom.modmenu.badges}); "library" marks a library
 */
public record LoadedMod(String id, String name, String version, String description, List<String> authors,
                        String parent, boolean builtin, List<String> badges) {

    public enum Kind {
        /** Breeze itself. */
        BREEZE("breeze"),
        /** Minecraft, Java, Fabric Loader: there without a jar in the mods folder. */
        GAME("game"),
        /** Bundled inside another mod, or marked as a library by its author. */
        LIBRARY("library"),
        /** Anything else: a mod the player installed. */
        MOD("mod");

        public final String json;

        Kind(String json) {
            this.json = json;
        }
    }

    public LoadedMod {
        authors = authors == null ? List.of() : List.copyOf(authors);
        badges = badges == null ? List.of() : List.copyOf(badges);
        description = description == null ? "" : description;
    }

    public Kind kind() {
        if ("breeze".equals(id)) return Kind.BREEZE;
        if (builtin) return Kind.GAME;
        if (parent != null || badges.contains("library")) return Kind.LIBRARY;
        return Kind.MOD;
    }
}
