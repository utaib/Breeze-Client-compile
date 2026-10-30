package dev.breeze.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.bridge.BridgeException;
import dev.breeze.settings.Setting;
import net.minecraft.client.KeyMapping;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Modules and their settings as the page sees them (contract: ModuleInfo), and
 * the validated way back.
 *
 * Every write is checked against the setting's own type and bounds and then
 * applied through the setting itself, so the page can never put a module into
 * a state its own controls could not reach.
 */
public final class ModuleJson {

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})");

    /** Defaults captured before the player's config is loaded. */
    private static final Map<String, JsonObject> DEFAULTS = new HashMap<>();

    private ModuleJson() {}

    /** Call once, after modules are constructed and before BreezeConfig.load(). */
    public static void captureDefaults() {
        for (Module m : ModuleManager.getModules()) {
            JsonObject o = new JsonObject();
            for (Setting s : m.getSettings()) {
                try {
                    s.write(o);
                } catch (Throwable ignored) {
                }
            }
            DEFAULTS.put(m.getName(), o);
        }
    }

    static Module find(String name) {
        for (Module m : ModuleManager.getModules()) {
            if (m.getName().equals(name)) return m;
        }
        throw BridgeException.invalid("There is no module called " + name + ".");
    }

    static JsonArray all() {
        JsonArray out = new JsonArray();
        for (Module m : ModuleManager.getModules()) out.add(of(m));
        return out;
    }

    static JsonObject of(Module m) {
        JsonObject o = new JsonObject();
        o.addProperty("name", m.getName());
        o.addProperty("category", m.getCategory().displayName());
        o.addProperty("description", m.getDescription());
        o.addProperty("enabled", m.isEnabled());
        o.addProperty("hud", m.isHud());
        KeyMapping key = m.getKeyMapping();
        if (key != null && !key.isUnbound()) o.addProperty("keybind", key.getTranslatedKeyMessage().getString());
        else o.add("keybind", null);
        JsonArray settings = new JsonArray();
        for (Setting s : m.getSettings()) {
            if (!s.visible()) continue;
            JsonObject j = setting(s);
            if (j != null) settings.add(j);
        }
        o.add("settings", settings);
        return o;
    }

    private static JsonObject setting(Setting s) {
        JsonObject j = new JsonObject();
        j.addProperty("id", s.id);
        j.addProperty("label", s.label);
        j.addProperty("group", s.group == null ? "" : s.group);
        if (s instanceof Setting.Bool b) {
            j.addProperty("type", "bool");
            j.addProperty("value", b.value);
        } else if (s instanceof Setting.Int i) {
            j.addProperty("type", "int");
            j.addProperty("value", i.value);
            j.addProperty("min", i.min);
            j.addProperty("max", i.max);
            j.addProperty("suffix", i.suffix == null ? "" : i.suffix);
        } else if (s instanceof Setting.Color c) {
            j.addProperty("type", "color");
            j.addProperty("value", Setting.Color.hex(c.argb));
        } else if (s instanceof Setting.Mode m) {
            j.addProperty("type", "mode");
            j.addProperty("value", m.value());
            JsonArray options = new JsonArray();
            for (String opt : m.options) options.add(opt);
            j.add("options", options);
        } else {
            return null;
        }
        return j;
    }

    static void set(Module m, String id, JsonElement value) {
        Setting s = null;
        for (Setting candidate : m.getSettings()) {
            if (candidate.id.equals(id)) {
                s = candidate;
                break;
            }
        }
        if (s == null) throw BridgeException.invalid(m.getName() + " has no setting " + id + ".");
        boolean prim = value != null && value.isJsonPrimitive();
        if (s instanceof Setting.Bool b) {
            if (!prim || !value.getAsJsonPrimitive().isBoolean()) throw BridgeException.invalid(s.label + " must be on or off.");
            b.value = value.getAsBoolean();
        } else if (s instanceof Setting.Int i) {
            if (!prim || !value.getAsJsonPrimitive().isNumber()) throw BridgeException.invalid(s.label + " must be a number.");
            double d = value.getAsDouble();
            if (d != Math.rint(d) || d < i.min || d > i.max) {
                throw BridgeException.invalid(s.label + " must be a whole number from " + i.min + " to " + i.max + ".");
            }
            i.set((int) d);
        } else if (s instanceof Setting.Color c) {
            if (!prim || !value.getAsJsonPrimitive().isString() || !HEX.matcher(value.getAsString()).matches()) {
                throw BridgeException.invalid(s.label + " must be a colour like #FF78B2FF.");
            }
            String hex = value.getAsString().substring(1);
            long v = Long.parseLong(hex.length() == 6 ? "FF" + hex : hex, 16);
            c.argb = (int) v;
        } else if (s instanceof Setting.Mode mode) {
            if (!prim || !value.getAsJsonPrimitive().isString()) throw BridgeException.invalid(s.label + " must be one of its options.");
            String want = value.getAsString();
            int idx = -1;
            for (int k = 0; k < mode.options.length; k++) {
                if (mode.options[k].equals(want)) idx = k;
            }
            if (idx < 0) throw BridgeException.invalid(want + " is not an option for " + s.label + ".");
            mode.index = idx;
        } else {
            throw BridgeException.invalid(s.label + " cannot be changed here.");
        }
    }

    static void reset(Module m) {
        JsonObject defaults = DEFAULTS.get(m.getName());
        if (defaults == null) throw BridgeException.unavailable("Defaults for " + m.getName() + " were not recorded.");
        for (Setting s : m.getSettings()) {
            try {
                s.read(defaults);
            } catch (Throwable ignored) {
            }
        }
    }
}
