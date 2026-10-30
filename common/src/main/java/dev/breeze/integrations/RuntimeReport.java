package dev.breeze.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * What this game really loaded, written to {@code .breeze/runtime-mods.json}
 * in the game folder at startup. The launcher reads it to tell the player
 * which mods in the folder Fabric actually loaded, and which integrations
 * Breeze found or could not use.
 */
public final class RuntimeReport {

    public static final String FILE = ".breeze/runtime-mods.json";

    private RuntimeReport() {}

    public static JsonObject json(String breeze, String minecraft, String loader, List<LoadedMod> mods,
                                  List<Integration> integrations, List<IntegrationProblem> problems, long writtenAt) {
        JsonObject o = new JsonObject();
        o.addProperty("format", 1);
        o.addProperty("breeze", breeze);
        o.addProperty("minecraft", minecraft);
        o.addProperty("loader", loader);
        o.addProperty("writtenAt", writtenAt);
        JsonArray list = new JsonArray();
        for (LoadedMod m : mods) {
            JsonObject e = new JsonObject();
            e.addProperty("id", m.id());
            e.addProperty("name", m.name());
            e.addProperty("version", m.version());
            e.addProperty("kind", m.kind().json);
            if (m.parent() != null) e.addProperty("parent", m.parent());
            list.add(e);
        }
        o.add("mods", list);
        JsonArray ints = new JsonArray();
        for (Integration i : integrations) {
            JsonObject e = new JsonObject();
            e.addProperty("id", i.id());
            e.addProperty("name", i.name());
            e.addProperty("provider", i.provider());
            e.addProperty("actions", i.actions().size());
            ints.add(e);
        }
        o.add("integrations", ints);
        JsonArray probs = new JsonArray();
        for (IntegrationProblem p : problems) {
            JsonObject e = new JsonObject();
            e.addProperty("provider", p.provider());
            e.addProperty("mod", p.mod());
            e.addProperty("detail", p.detail());
            probs.add(e);
        }
        o.add("problems", probs);
        return o;
    }
}
