package dev.breeze.compat;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.function.Consumer;

/**
 * What Breeze asks of the mod loader outside its own hooks (Hooks, Keys):
 * versions, the number of mods, and a tick. This is Fabric's; the Forge and
 * NeoForge builds have their own copy of this file (versions/forge-1.20.1,
 * versions/neoforge-1.21.1), with the same methods.
 */
public final class Platform {

    private Platform() {}

    /** The loader's name, as the menus show it. */
    public static String name() {
        return "Fabric";
    }

    /** A mod's version as its loader read it, or "unknown" when it is not loaded. */
    public static String modVersion(String modId) {
        return FabricLoader.getInstance().getModContainer(modId)
                .map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
    }

    /** The Minecraft version running. */
    public static String minecraftVersion() {
        String v = modVersion("minecraft");
        return "unknown".equals(v) ? "?" : v;
    }

    /** The loader's own version (Fabric Loader's here). */
    public static String loaderVersion() {
        return modVersion("fabricloader");
    }

    /** How many mods the loader loaded, Minecraft and the loader included. */
    public static int modCount() {
        return FabricLoader.getInstance().getAllMods().size();
    }

    /** Runs {@code tick} at the end of every client tick. */
    public static void onEndTick(Consumer<Minecraft> tick) {
        ClientTickEvents.END_CLIENT_TICK.register(tick::accept);
    }
}
