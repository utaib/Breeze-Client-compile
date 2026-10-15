package dev.breeze.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import net.fabricmc.loader.api.metadata.Person;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Other mods in this game, and what Breeze can open from them.
 *
 * Everything here comes from what Fabric actually loaded and from real
 * published APIs: Mod Menu's own (its mods list, and each mod's settings
 * screen from the "modmenu" entrypoint), and the "breeze" entrypoint any mod
 * can declare ({@link BreezeEntrypoint}). Nothing is keyed on a particular
 * mod id, and a menu built from {@link #integrations()} has no dead entries.
 *
 * Every call into another mod is guarded: a mod whose API fails is reported
 * ({@link #problems()}) and left out, and Breeze carries on. Called on the
 * game's own thread.
 */
public final class Integrations {

    public static final String MOD_MENU = "modmenu";

    private static List<LoadedMod> mods;
    private static ModMenuApiCalls modMenu;
    /** Mod id to Mod Menu settings factory, for every mod that registered one. */
    private static final Map<String, Object> factories = new LinkedHashMap<>();
    /** Mod id to whether its factory really makes a screen; asked once, when first listed. */
    private static final Map<String, Boolean> hasScreen = new LinkedHashMap<>();
    private static final Map<String, BreezeEntrypoint> entries = new LinkedHashMap<>();
    private static final Map<String, String> entryLabels = new LinkedHashMap<>();
    private static final List<IntegrationProblem> problems = new ArrayList<>();
    private static boolean discovered;

    /** Integration ids of other mods' title screen buttons start with this. */
    public static final String TITLE_PREFIX = "title:";
    /** Labels of the buttons other mods put on Minecraft's title screen, read once per language (titleButtons). */
    private static List<String> titleButtons;
    /** "Singleplayer" in the language titleButtons was read in. */
    private static String titleButtonsLanguage;

    private Integrations() {}

    /** Reads the loaded mods and asks each integration API whether it answers. Makes no screens. */
    public static synchronized void discover() {
        if (discovered) return;
        discovered = true;
        mods = readMods();
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded(MOD_MENU)) {
            try {
                modMenu = ModMenuApiCalls.find(Integrations.class.getClassLoader(), Screen.class);
            } catch (Throwable t) {
                problem(MOD_MENU, MOD_MENU, "Mod Menu is installed, but its API did not answer as published: " + IntegrationProblem.describe(t));
            }
            if (modMenu != null) {
                for (EntrypointContainer<Object> c : entrypoints(ModMenuApiCalls.ENTRYPOINT)) {
                    String id = c.getProvider().getMetadata().getId();
                    try {
                        for (Map.Entry<String, Object> e : modMenu.factories(id, c.getEntrypoint()).entrySet()) {
                            // Minecraft's own options are already a button of their own.
                            if (!"minecraft".equals(e.getKey()) && loader.isModLoaded(e.getKey())) factories.putIfAbsent(e.getKey(), e.getValue());
                        }
                    } catch (Throwable t) {
                        problem(MOD_MENU, id, nameOf(id) + "'s settings could not be read: " + IntegrationProblem.describe(t));
                    }
                }
            }
        }
        for (EntrypointContainer<Object> c : entrypoints(BreezeEntrypoint.KEY)) {
            String id = c.getProvider().getMetadata().getId();
            try {
                BreezeEntrypoint e = BreezeEntrypoint.of(c.getEntrypoint(), Screen.class);
                String label = e.label();
                entries.put("breeze:" + id, e);
                entryLabels.put("breeze:" + id, label.isEmpty() ? nameOf(id) : label);
            } catch (Throwable t) {
                problem("breeze-entrypoint", id, nameOf(id) + " offers a Breeze entry that could not be used: " + IntegrationProblem.describe(t));
            }
        }
        BreezeClient.LOGGER.info("[Breeze] Integrations: Mod Menu {}, {} settings screens, {} Breeze entries, {} problems",
                modMenu != null ? "available" : "absent", factories.size(), entries.size(), problems.size());
    }

    private static List<EntrypointContainer<Object>> entrypoints(String key) {
        try {
            return FabricLoader.getInstance().getEntrypointContainers(key, Object.class);
        } catch (Throwable t) {
            // A mod whose entrypoint class fails to load breaks the whole list.
            problem(key, key, "Mods declaring the \"" + key + "\" entrypoint could not be read: " + IntegrationProblem.describe(t));
            return List.of();
        }
    }

    private static void problem(String provider, String mod, String detail) {
        problems.add(new IntegrationProblem(provider, mod, detail));
        BreezeClient.LOGGER.warn("[Breeze] {}", detail);
    }

    private static List<LoadedMod> readMods() {
        List<LoadedMod> out = new ArrayList<>();
        for (ModContainer c : FabricLoader.getInstance().getAllMods()) {
            ModMetadata m = c.getMetadata();
            List<String> authors = new ArrayList<>();
            for (Person p : m.getAuthors()) authors.add(p.getName());
            String parent = null;
            try {
                parent = c.getContainingMod().map(p -> p.getMetadata().getId()).orElse(null);
            } catch (Throwable ignored) {
                // Fabric Loader before 0.14 has no nesting information.
            }
            out.add(new LoadedMod(m.getId(), m.getName(), m.getVersion().getFriendlyString(), m.getDescription(),
                    authors, parent, "builtin".equals(m.getType()), badges(m)));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    /** Mod Menu's badges from the mod's own metadata: {"custom": {"modmenu": {"badges": [...]}}}. */
    private static List<String> badges(ModMetadata m) {
        List<String> out = new ArrayList<>();
        try {
            CustomValue mm = m.getCustomValue("modmenu");
            if (mm == null || mm.getType() != CustomValue.CvType.OBJECT) return out;
            CustomValue b = mm.getAsObject().get("badges");
            if (b == null || b.getType() != CustomValue.CvType.ARRAY) return out;
            for (CustomValue v : b.getAsArray()) {
                if (v.getType() == CustomValue.CvType.STRING) out.add(v.getAsString());
            }
        } catch (Throwable ignored) {
            // Malformed custom values only lose the badge.
        }
        return out;
    }

    private static String nameOf(String id) {
        return FabricLoader.getInstance().getModContainer(id).map(c -> c.getMetadata().getName()).orElse(id);
    }

    public static synchronized List<LoadedMod> mods() {
        discover();
        return mods;
    }

    public static synchronized boolean modMenu() {
        discover();
        return modMenu != null;
    }

    public static synchronized List<IntegrationProblem> problems() {
        discover();
        return List.copyOf(problems);
    }

    /**
     * Mods whose settings screen Mod Menu offers. The first time, each factory
     * is asked for its screen once, as Mod Menu itself does when a mod is
     * picked in its list; a factory with no screen is left out.
     */
    public static synchronized List<String> withSettings(Minecraft mc) {
        discover();
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Object> e : factories.entrySet()) {
            Boolean ok = hasScreen.get(e.getKey());
            if (ok == null) {
                try {
                    ok = modMenu.configScreen(e.getValue(), dev.breeze.compat.ActiveScreen.get(mc)) instanceof Screen;
                } catch (Throwable t) {
                    ok = false;
                    problem(MOD_MENU, e.getKey(), nameOf(e.getKey()) + "'s settings screen failed to open: " + IntegrationProblem.describe(t));
                }
                hasScreen.put(e.getKey(), ok);
            }
            if (ok) out.add(e.getKey());
        }
        return out;
    }

    /** What the menus can show, without asking any settings factory (cheap). */
    public static synchronized List<Integration> integrations() {
        discover();
        List<Integration> out = new ArrayList<>();
        if (modMenu != null) {
            List<Integration.Action> actions = new ArrayList<>();
            actions.add(new Integration.Action("mods", "Mod Menu", null));
            for (String id : factories.keySet()) {
                if (Boolean.FALSE.equals(hasScreen.get(id))) continue;
                actions.add(new Integration.Action("config:" + id, nameOf(id) + " settings", id));
            }
            out.add(new Integration(MOD_MENU, "Mod Menu", MOD_MENU, actions));
        }
        for (Map.Entry<String, BreezeEntrypoint> e : entries.entrySet()) {
            String id = e.getKey();
            out.add(new Integration(id, entryLabels.get(id), "breeze-entrypoint",
                    List.of(new Integration.Action("open", entryLabels.get(id), id.substring("breeze:".length())))));
        }
        // Buttons other mods add to Minecraft's title screen (Replay Mod's
        // Replay Viewer, Flashback). Only at the title: in a world they open
        // screens that expect to start from there.
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            for (String label : titleButtons(mc)) {
                out.add(new Integration(TITLE_PREFIX + label, label, "title-screen",
                        List.of(new Integration.Action("press", label, null))));
            }
        }
        return out;
    }

    /**
     * Labels that are Minecraft's own (or Mod Menu's, which has its own entry)
     * on the title screen, in this game's language.
     */
    private static java.util.Set<String> vanillaTitleLabels() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String key : new String[]{"menu.singleplayer", "menu.multiplayer", "menu.online", "menu.options",
                "menu.quit", "menu.playdemo", "menu.resetdemo", "options.language", "options.accessibility",
                "narrator.button.language", "narrator.button.accessibility", "title.credits", "modmenu.title",
                "gui.done", "menu.returnToMenu"}) {
            try {
                out.add(net.minecraft.client.resources.language.I18n.get(key));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /**
     * What other mods put on Minecraft's title screen: read from a title
     * screen laid out off screen (TitleProbe), on the game thread, once per
     * language (the labels are matched by text). Nothing while the game is
     * still loading: other mods' labels are not translated yet then. Never
     * throws; a mod whose title buttons break the probe costs only these
     * entries.
     */
    public static synchronized List<String> titleButtons(Minecraft mc) {
        if (dev.breeze.compat.ActiveScreen.overlay(mc) != null) return titleButtons != null ? titleButtons : List.of();
        String language = net.minecraft.client.resources.language.I18n.get("menu.singleplayer");
        if (titleButtons != null && language.equals(titleButtonsLanguage)) return titleButtons;
        titleButtonsLanguage = language;
        List<String> found = new ArrayList<>();
        Screen probe = null;
        try {
            probe = dev.breeze.compat.TitleProbe.make(mc);
            java.util.Set<String> vanilla = vanillaTitleLabels();
            collectButtons(probe.children(), vanilla, found, 0);
        } catch (Throwable t) {
            problem("title-screen", "minecraft", "Other mods' title screen buttons could not be read: " + IntegrationProblem.describe(t));
        } finally {
            if (probe != null) dev.breeze.compat.TitleProbe.dispose(probe);
        }
        titleButtons = List.copyOf(found);
        if (!found.isEmpty()) BreezeClient.LOGGER.info("[Breeze] Other mods' title screen buttons: {}", String.join(", ", found));
        return titleButtons;
    }

    private static void collectButtons(List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children,
                                       java.util.Set<String> vanilla, List<String> out, int depth) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children) {
            if (child instanceof net.minecraft.client.gui.components.AbstractButton b) {
                String label = b.getMessage().getString().trim();
                if (label.isEmpty() || vanilla.contains(label) || label.startsWith("Copyright Mojang")) continue;
                if (child.getClass().getName().startsWith("com.terraformersmc.modmenu")) continue;
                if (!out.contains(label)) out.add(label);
            } else if (depth < 3 && child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                collectButtons(c.children(), vanilla, out, depth + 1);
            }
        }
    }

    private static net.minecraft.client.gui.components.AbstractButton findButton(
            List<? extends net.minecraft.client.gui.components.events.GuiEventListener> children, String label, int depth) {
        for (net.minecraft.client.gui.components.events.GuiEventListener child : children) {
            if (child instanceof net.minecraft.client.gui.components.AbstractButton b && b.getMessage().getString().trim().equals(label)) return b;
            if (depth < 3 && child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                net.minecraft.client.gui.components.AbstractButton inner = findButton(c.children(), label, depth + 1);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    /**
     * What opening an integration does, to run once the menu's answer is
     * sent: show its screen over {@code parent}, or for another mod's title
     * screen button, press it on a fresh title screen (its own screen then
     * opens with that title screen as its parent, which Breeze's menu stands
     * in for again on the way back).
     */
    public static Runnable opener(Minecraft mc, String integration, String action, Screen parent) throws Unavailable {
        if (integration.startsWith(TITLE_PREFIX)) {
            String label = integration.substring(TITLE_PREFIX.length());
            if (!titleButtons(mc).contains(label)) throw new Unavailable(label + " is not on the title screen in this game.");
            return () -> {
                try {
                    Screen title = dev.breeze.compat.TitleProbe.make(mc);
                    net.minecraft.client.gui.components.AbstractButton b = findButton(title.children(), label, 0);
                    if (b == null) {
                        BreezeClient.LOGGER.warn("[Breeze] {} was not on the title screen any more", label);
                        return;
                    }
                    Screen before = dev.breeze.compat.ActiveScreen.get(mc);
                    dev.breeze.compat.Widgets.press(b);
                    // A button that changes nothing (a toggle on the title
                    // screen itself) leaves the player where they were.
                    if (dev.breeze.compat.ActiveScreen.get(mc) == before) dev.breeze.compat.TitleProbe.dispose(title);
                } catch (Throwable t) {
                    problem("title-screen", label, label + " could not open: " + IntegrationProblem.describe(t));
                }
            };
        }
        Screen next = screen(integration, action, parent);
        return () -> dev.breeze.compat.ActiveScreen.set(mc, next);
    }

    /**
     * Opens an integration's screen over {@code parent}, which it returns to.
     * Answers why when it cannot; never throws anything but that answer.
     */
    public static synchronized Screen screen(String integration, String action, Screen parent) throws Unavailable {
        discover();
        try {
            if (MOD_MENU.equals(integration) && modMenu != null) {
                if ("mods".equals(action)) return (Screen) modMenu.modsScreen(parent);
                if (action.startsWith("config:")) {
                    String id = action.substring("config:".length());
                    Object factory = factories.get(id);
                    if (factory == null) throw new Unavailable(nameOf(id) + " has no settings screen in Mod Menu.");
                    Object s = modMenu.configScreen(factory, parent);
                    if (!(s instanceof Screen screen)) {
                        hasScreen.put(id, false);
                        throw new Unavailable(nameOf(id) + " has no settings screen.");
                    }
                    return screen;
                }
            }
            BreezeEntrypoint e = entries.get(integration);
            if (e != null && "open".equals(action)) {
                Object s = e.open(parent);
                if (s instanceof Screen screen) return screen;
                throw new Unavailable(entryLabels.get(integration) + " did not return a screen.");
            }
        } catch (Unavailable u) {
            throw u;
        } catch (Throwable t) {
            String what = MOD_MENU.equals(integration) ? "Mod Menu" : entryLabels.getOrDefault(integration, integration);
            problem(MOD_MENU.equals(integration) ? MOD_MENU : "breeze-entrypoint", integration, what + " could not open: " + IntegrationProblem.describe(t));
            throw new Unavailable(what + " could not open: " + IntegrationProblem.describe(t));
        }
        throw new Unavailable("That is not available in this game.");
    }

    /** Why an integration's screen could not be opened, in a sentence for the player. */
    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

    /** The bridge's answer for integrations.list. */
    public static JsonObject json(Minecraft mc, boolean probeSettings) {
        if (probeSettings) withSettings(mc);
        JsonObject o = new JsonObject();
        JsonArray list = new JsonArray();
        for (Integration i : integrations()) {
            JsonObject e = new JsonObject();
            e.addProperty("id", i.id());
            e.addProperty("name", i.name());
            e.addProperty("provider", i.provider());
            JsonArray actions = new JsonArray();
            for (Integration.Action a : i.actions()) {
                JsonObject ao = new JsonObject();
                ao.addProperty("id", a.id());
                ao.addProperty("label", a.label());
                if (a.mod() != null) ao.addProperty("mod", a.mod());
                actions.add(ao);
            }
            e.add("actions", actions);
            list.add(e);
        }
        o.add("integrations", list);
        JsonArray probs = new JsonArray();
        for (IntegrationProblem p : problems()) {
            JsonObject e = new JsonObject();
            e.addProperty("provider", p.provider());
            e.addProperty("mod", p.mod());
            e.addProperty("detail", p.detail());
            probs.add(e);
        }
        o.add("problems", probs);
        return o;
    }

    /**
     * Writes {@link RuntimeReport#FILE} in the game folder, for the launcher:
     * which mods Fabric loaded, and which integrations answered. Replaced
     * atomically, so the launcher never reads half a file.
     */
    public static void writeReport() {
        try {
            FabricLoader loader = FabricLoader.getInstance();
            String version = loader.getModContainer("breeze").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
            String mc = loader.getModContainer("minecraft").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
            String fl = loader.getModContainer("fabricloader").map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("");
            JsonObject report = RuntimeReport.json(version, mc, fl, mods(), integrations(), problems(), System.currentTimeMillis());
            Path file = loader.getGameDir().resolve(RuntimeReport.FILE);
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("runtime-mods.json.tmp");
            Files.writeString(tmp, report.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Could not write {}: {}", RuntimeReport.FILE, IntegrationProblem.describe(t));
        }
    }
}
