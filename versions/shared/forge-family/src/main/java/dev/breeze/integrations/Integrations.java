package dev.breeze.integrations;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.breeze.BreezeClient;
import dev.breeze.compat.Platform;
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
 * Other mods in this game, and what Breeze can open from them, on Forge and
 * NeoForge. The same public methods as the Fabric build's Integrations, so
 * the menus, the bridge and the self-test are unchanged; what is behind them
 * is the loader's: its mod list, its own Mods screen, and each mod's settings
 * screen as that mod registered it with the loader (compat/Platform). Mod
 * Menu and the "breeze" entrypoint are Fabric's and do not exist here.
 *
 * Every call into another mod is guarded: a mod whose screen fails is
 * reported ({@link #problems()}) and left out. Called on the game's thread.
 */
public final class Integrations {

    /** Fabric's Mod Menu, kept for the code that asks; never present here. */
    public static final String MOD_MENU = "modmenu";
    /** The loader's own Mods screen and the settings screens of its mods. */
    public static final String LOADER_MODS = "loader-mods";

    private static List<LoadedMod> mods;
    /** Mod ids that registered a settings screen with the loader. */
    private static final List<String> withScreen = new ArrayList<>();
    /** Mod id to whether its settings screen really opens; asked once, when first listed. */
    private static final Map<String, Boolean> hasScreen = new LinkedHashMap<>();
    private static final List<IntegrationProblem> problems = new ArrayList<>();
    private static boolean discovered;

    /** Integration ids of other mods' title screen buttons start with this. */
    public static final String TITLE_PREFIX = "title:";
    private static List<String> titleButtons;
    private static final Map<String, String> titleOwners = new LinkedHashMap<>();
    private static String titleButtonsLanguage;

    private Integrations() {}

    public static synchronized void discover() {
        if (discovered) return;
        discovered = true;
        try {
            mods = Platform.loadedMods();
        } catch (Throwable t) {
            mods = List.of();
            problem(LOADER_MODS, "breeze", "The list of mods could not be read: " + IntegrationProblem.describe(t));
        }
        for (LoadedMod m : mods) {
            if ("minecraft".equals(m.id()) || BreezeClient.MOD_ID.equals(m.id())) continue;
            try {
                if (Platform.hasConfigScreen(m.id())) withScreen.add(m.id());
            } catch (Throwable t) {
                problem(LOADER_MODS, m.id(), m.name() + "'s settings could not be read: " + IntegrationProblem.describe(t));
            }
        }
        BreezeClient.LOGGER.info("[Breeze] Integrations ({}): {} mods, {} settings screens, {} problems",
                Platform.name(), mods.size(), withScreen.size(), problems.size());
    }

    private static void problem(String provider, String mod, String detail) {
        problems.add(new IntegrationProblem(provider, mod, detail));
        BreezeClient.LOGGER.warn("[Breeze] {}", detail);
    }

    private static String nameOf(String id) {
        if (mods != null) {
            for (LoadedMod m : mods) if (m.id().equals(id)) return m.name();
        }
        return id;
    }

    public static synchronized List<LoadedMod> mods() {
        discover();
        return mods;
    }

    /** Fabric's Mod Menu; there is none on this loader. */
    public static synchronized boolean modMenu() {
        return false;
    }

    public static synchronized List<IntegrationProblem> problems() {
        discover();
        return List.copyOf(problems);
    }

    /** Mods whose settings screen opens. Each is made once, the first time, as the loader's Mods screen does. */
    public static synchronized List<String> withSettings(Minecraft mc) {
        discover();
        List<String> out = new ArrayList<>();
        for (String id : withScreen) {
            Boolean ok = hasScreen.get(id);
            if (ok == null) {
                try {
                    ok = Platform.configScreen(id, dev.breeze.compat.ActiveScreen.get(mc)) != null;
                } catch (Throwable t) {
                    ok = false;
                    problem(LOADER_MODS, id, nameOf(id) + "'s settings screen failed to open: " + IntegrationProblem.describe(t));
                }
                hasScreen.put(id, ok);
            }
            if (ok) out.add(id);
        }
        return out;
    }

    public static synchronized List<Integration> integrations() {
        discover();
        List<Integration> out = new ArrayList<>();
        List<Integration.Action> actions = new ArrayList<>();
        actions.add(new Integration.Action("mods", Platform.name() + " mods", null));
        for (String id : withScreen) {
            if (Boolean.FALSE.equals(hasScreen.get(id))) continue;
            actions.add(new Integration.Action("config:" + id, nameOf(id) + " settings", id));
        }
        out.add(new Integration(LOADER_MODS, "Mods", LOADER_MODS, actions));
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            for (String label : titleButtons(mc)) {
                out.add(new Integration(TITLE_PREFIX + label, label, "title-screen",
                        List.of(new Integration.Action("press", label, titleOwners.get(label)))));
            }
        }
        return out;
    }

    private static java.util.Set<String> vanillaTitleLabels() {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String key : new String[]{"menu.singleplayer", "menu.multiplayer", "menu.online", "menu.options",
                "menu.quit", "menu.playdemo", "menu.resetdemo", "options.language", "options.accessibility",
                "narrator.button.language", "narrator.button.accessibility", "title.credits", "fml.menu.mods",
                "gui.done", "menu.returnToMenu"}) {
            try {
                out.add(net.minecraft.client.resources.language.I18n.get(key));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    /** What other mods put on Minecraft's title screen, read once per language (as on Fabric). */
    public static synchronized List<String> titleButtons(Minecraft mc) {
        if (dev.breeze.compat.ActiveScreen.overlay(mc) != null) return titleButtons != null ? titleButtons : List.of();
        String language = net.minecraft.client.resources.language.I18n.get("menu.singleplayer");
        if (titleButtons != null && language.equals(titleButtonsLanguage)) return titleButtons;
        titleButtonsLanguage = language;
        titleOwners.clear();
        List<String> found = new ArrayList<>();
        Screen probe = null;
        try {
            probe = dev.breeze.compat.TitleProbe.make(mc);
            collectButtons(probe.children(), vanillaTitleLabels(), found, 0);
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
                if (!out.contains(label)) {
                    out.add(label);
                    String owner = ownerOf(child.getClass());
                    if (owner != null) titleOwners.put(label, owner);
                }
            } else if (depth < 3 && child instanceof net.minecraft.client.gui.components.events.ContainerEventHandler c) {
                collectButtons(c.children(), vanilla, out, depth + 1);
            }
        }
    }

    /** The id of the mod whose jar a class came from, or null; never throws. */
    private static String ownerOf(Class<?> type) {
        try {
            java.security.CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return Platform.ownerOf(Path.of(source.getLocation().toURI()).toAbsolutePath().normalize());
        } catch (Throwable ignored) {
            return null;
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
                    if (dev.breeze.compat.ActiveScreen.get(mc) == before) dev.breeze.compat.TitleProbe.dispose(title);
                } catch (Throwable t) {
                    problem("title-screen", label, label + " could not open: " + IntegrationProblem.describe(t));
                }
            };
        }
        Screen next = screen(integration, action, parent);
        return () -> dev.breeze.compat.ActiveScreen.set(mc, next);
    }

    public static synchronized Screen screen(String integration, String action, Screen parent) throws Unavailable {
        discover();
        if (!LOADER_MODS.equals(integration) && !MOD_MENU.equals(integration)) {
            throw new Unavailable("That is not available in this game.");
        }
        try {
            if ("mods".equals(action)) return Platform.modsScreen(parent);
            if (action.startsWith("config:")) {
                String id = action.substring("config:".length());
                Screen s = Platform.configScreen(id, parent);
                if (s == null) {
                    hasScreen.put(id, false);
                    throw new Unavailable(nameOf(id) + " has no settings screen.");
                }
                return s;
            }
        } catch (Unavailable u) {
            throw u;
        } catch (Throwable t) {
            problem(LOADER_MODS, integration, "Mods could not open: " + IntegrationProblem.describe(t));
            throw new Unavailable("Mods could not open: " + IntegrationProblem.describe(t));
        }
        throw new Unavailable("That is not available in this game.");
    }

    public static final class Unavailable extends Exception {
        public Unavailable(String message) {
            super(message);
        }
    }

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

    /** {@link RuntimeReport#FILE} in the game folder, for the launcher (as on Fabric). */
    public static void writeReport() {
        try {
            JsonObject report = RuntimeReport.json(Platform.modVersion(BreezeClient.MOD_ID), Platform.minecraftVersion(),
                    Platform.loaderVersion(), mods(), integrations(), problems(), System.currentTimeMillis());
            Path file = Platform.gameDir().resolve(RuntimeReport.FILE);
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
