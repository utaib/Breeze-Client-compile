package dev.breeze.compat;

import dev.breeze.integrations.LoadedMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What Breeze asks of the loader, on NeoForge 1.21.1: the Fabric build's
 * Platform methods, plus what Integrations needs (the mod list, NeoForge's
 * Mods screen and each mod's settings screen).
 */
public final class Platform {

    private Platform() {}

    public static String name() {
        return "NeoForge";
    }

    public static String modVersion(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(c -> c.getModInfo().getVersion().toString()).orElse("unknown");
    }

    public static String minecraftVersion() {
        String v = modVersion("minecraft");
        return "unknown".equals(v) ? "?" : v;
    }

    public static String loaderVersion() {
        return modVersion("neoforge");
    }

    public static int modCount() {
        return ModList.get().size();
    }

    public static void onEndTick(Consumer<Minecraft> tick) {
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post.class,
                e -> tick.accept(Minecraft.getInstance()));
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /** Every mod NeoForge loaded, sorted by name. */
    public static List<LoadedMod> loadedMods() {
        List<LoadedMod> out = new ArrayList<>();
        for (IModInfo m : ModList.get().getMods()) {
            List<String> authors = new ArrayList<>();
            m.getConfig().getConfigElement("authors").ifPresent(a -> {
                for (String s : String.valueOf(a).split(",")) if (!s.isBlank()) authors.add(s.trim());
            });
            String id = m.getModId();
            boolean builtin = "minecraft".equals(id) || "neoforge".equals(id);
            out.add(new LoadedMod(id, m.getDisplayName(), m.getVersion().toString(),
                    m.getDescription() == null ? "" : m.getDescription().trim(), authors, null, builtin, List.of()));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private static Optional<ModContainer> container(String modId) {
        // NeoForge hands back Optional<? extends ModContainer>.
        return ModList.get().getModContainerById(modId).map(c -> (ModContainer) c);
    }

    public static boolean hasConfigScreen(String modId) {
        return container(modId).flatMap(c -> c.getCustomExtension(IConfigScreenFactory.class)).isPresent();
    }

    /** A mod's settings screen over {@code parent}, or null when it registered none. */
    public static Screen configScreen(String modId, Screen parent) {
        return container(modId).flatMap(c -> c.getCustomExtension(IConfigScreenFactory.class)
                .map(f -> f.createScreen(c, parent))).orElse(null);
    }

    /** NeoForge's own list of mods. */
    public static Screen modsScreen(Screen parent) {
        return new net.neoforged.neoforge.client.gui.ModListScreen(parent);
    }

    /** The id of the mod whose file is at {@code path}, or null. */
    public static String ownerOf(Path path) {
        for (IModInfo m : ModList.get().getMods()) {
            try {
                Path p = m.getOwningFile().getFile().getFilePath().toAbsolutePath().normalize();
                if (p.equals(path)) return m.getModId();
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
