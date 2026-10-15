package dev.breeze.devtest;

import com.google.gson.JsonObject;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The self-test's sweep over every module in a world: switched on, every
 * setting moved through its range (a boolean flipped, a number to its maximum
 * and then its minimum, a colour changed, a mode through two other choices),
 * then everything put back and switched off again. Each step stays for a
 * moment so the module ticks and draws with that value. A module passes when
 * it threw nothing (ModuleManager.ERRORS) and the game is still running.
 *
 * Then the saved settings are checked: one number per module is changed,
 * the config is saved and read back, and the read value must be the saved one.
 */
final class ModuleSweep {

    private static final long STEP_MS = 600;

    private final List<Module> mods = new ArrayList<>(ModuleManager.getModules());
    private int index = -1;
    private int step;
    private long stepAt;
    private boolean wasOn;
    private int errorsBefore;
    private final Map<Setting, Object> saved = new HashMap<>();
    private final List<String> problems = new ArrayList<>();
    private final List<String> screens = new ArrayList<>();
    private int settings;

    /** Advances the sweep; true once every module has been through it. */
    boolean step(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (now - stepAt < STEP_MS) return false;
        stepAt = now;
        switch (step) {
            case 0 -> {
                index++;
                if (index >= mods.size()) return true;
                Module m = mods.get(index);
                wasOn = m.isEnabled();
                errorsBefore = ModuleManager.ERRORS.getOrDefault(m.getName(), 0);
                saved.clear();
                for (Setting s : m.getSettings()) saved.put(s, valueOf(s));
                m.setEnabled(true);
                step = 1;
            }
            case 1 -> {
                for (Setting s : current().getSettings()) {
                    settings++;
                    if (s instanceof Setting.Bool b) b.toggle();
                    else if (s instanceof Setting.Int i) i.set(i.max);
                    else if (s instanceof Setting.Color c) c.argb = 0xFF33CC99;
                    else if (s instanceof Setting.Mode md) md.cycle(1);
                }
                step = 2;
            }
            case 2 -> {
                for (Setting s : current().getSettings()) {
                    if (s instanceof Setting.Int i) i.set(i.min);
                    else if (s instanceof Setting.Color c) c.argb = 0x80FF3366;
                    else if (s instanceof Setting.Mode md) md.cycle(1);
                }
                step = 3;
            }
            case 3 -> {
                Module m = current();
                for (Map.Entry<Setting, Object> e : saved.entrySet()) restore(e.getKey(), e.getValue());
                m.setEnabled(wasOn);
                // An action module (Keybind Search) opens its screen when
                // switched on. Noted, then closed, so the world stays in view.
                if (dev.breeze.compat.ActiveScreen.get(mc) != null) {
                    screens.add(m.getName());
                    dev.breeze.compat.ActiveScreen.set(mc, null);
                }
                int errors = ModuleManager.ERRORS.getOrDefault(m.getName(), 0) - errorsBefore;
                if (errors > 0) problems.add(m.getName() + " threw " + errors + "x");
                step = 0;
            }
            default -> step = 0;
        }
        return false;
    }

    private Module current() {
        return mods.get(index);
    }

    JsonObject report() {
        JsonObject o = new JsonObject();
        o.addProperty("modules", String.valueOf(mods.size()));
        o.addProperty("settings", String.valueOf(settings));
        o.addProperty("threw", String.join("; ", problems));
        o.addProperty("openedScreens", String.join(", ", screens));
        o.addProperty("pass", String.valueOf(problems.isEmpty() && mods.size() > 0));
        return o;
    }

    /**
     * One number per module is set to a new value, the config saved, the value
     * changed again in memory, and the config read back: the saved value must
     * come back. Every value is then put back and saved.
     */
    static JsonObject persistence() {
        Map<Setting.Int, Integer> original = new HashMap<>();
        Map<Setting.Int, Integer> wanted = new HashMap<>();
        for (Module m : ModuleManager.getModules()) {
            for (Setting s : m.getSettings()) {
                if (s instanceof Setting.Int i && i.max > i.min) {
                    original.put(i, i.value);
                    int v = i.value == i.max ? i.min : i.max;
                    i.set(v);
                    wanted.put(i, v);
                    break;
                }
            }
        }
        dev.breeze.config.BreezeConfig.save();
        for (Setting.Int i : wanted.keySet()) i.set(original.get(i));
        dev.breeze.config.BreezeConfig.load();
        int ok = 0;
        List<String> lost = new ArrayList<>();
        for (Map.Entry<Setting.Int, Integer> e : wanted.entrySet()) {
            if (e.getKey().value == e.getValue()) ok++;
            else lost.add(e.getKey().id + " " + e.getKey().value + " not " + e.getValue());
        }
        for (Map.Entry<Setting.Int, Integer> e : original.entrySet()) e.getKey().set(e.getValue());
        dev.breeze.config.BreezeConfig.save();
        JsonObject o = new JsonObject();
        o.addProperty("checked", String.valueOf(wanted.size()));
        o.addProperty("kept", String.valueOf(ok));
        o.addProperty("lost", String.join("; ", lost));
        o.addProperty("pass", String.valueOf(lost.isEmpty() && ok > 0));
        return o;
    }

    private static Object valueOf(Setting s) {
        if (s instanceof Setting.Bool b) return b.value;
        if (s instanceof Setting.Int i) return i.value;
        if (s instanceof Setting.Color c) return c.argb;
        if (s instanceof Setting.Mode md) return md.index;
        return null;
    }

    private static void restore(Setting s, Object v) {
        if (v == null) return;
        if (s instanceof Setting.Bool b) b.value = (Boolean) v;
        else if (s instanceof Setting.Int i) i.set((Integer) v);
        else if (s instanceof Setting.Color c) c.argb = (Integer) v;
        else if (s instanceof Setting.Mode md) md.index = (Integer) v;
    }
}
