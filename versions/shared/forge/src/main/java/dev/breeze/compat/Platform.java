package dev.breeze.compat;

import dev.breeze.integrations.LoadedMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.forgespi.language.IModInfo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * What Breeze asks of the loader, on Forge 1.20.1: the Fabric build's
 * Platform methods, plus what Integrations needs (the mod list, Forge's Mods
 * screen and each mod's settings screen).
 */
public final class Platform {

    private Platform() {}

    public static String name() {
        return "Forge";
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
        return modVersion("forge");
    }

    public static int modCount() {
        return ModList.get().size();
    }

    public static void onEndTick(Consumer<Minecraft> tick) {
        MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, e -> {
            if (e.phase == TickEvent.Phase.END) tick.accept(Minecraft.getInstance());
        });
    }

    public static Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    /** Every mod Forge loaded, sorted by name. */
    public static List<LoadedMod> loadedMods() {
        List<LoadedMod> out = new ArrayList<>();
        for (IModInfo m : ModList.get().getMods()) {
            List<String> authors = new ArrayList<>();
            m.getConfig().getConfigElement("authors").ifPresent(a -> {
                for (String s : String.valueOf(a).split(",")) if (!s.isBlank()) authors.add(s.trim());
            });
            String id = m.getModId();
            boolean builtin = "minecraft".equals(id) || "forge".equals(id);
            out.add(new LoadedMod(id, m.getDisplayName(), m.getVersion().toString(),
                    m.getDescription() == null ? "" : m.getDescription().trim(), authors, null, builtin, List.of()));
        }
        out.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return out;
    }

    private static Optional<ConfigScreenHandler.ConfigScreenFactory> factory(String modId) {
        return ModList.get().getModContainerById(modId)
                .flatMap((ModContainer c) -> c.getCustomExtension(ConfigScreenHandler.ConfigScreenFactory.class));
    }

    public static boolean hasConfigScreen(String modId) {
        return factory(modId).isPresent();
    }

    /** A mod's settings screen over {@code parent}, or null when it registered none. */
    public static Screen configScreen(String modId, Screen parent) {
        return factory(modId).map(f -> f.screenFunction().apply(Minecraft.getInstance(), parent)).orElse(null);
    }

    /** Forge's own list of mods. */
    public static Screen modsScreen(Screen parent) {
        return new net.minecraftforge.client.gui.ModListScreen(parent);
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
