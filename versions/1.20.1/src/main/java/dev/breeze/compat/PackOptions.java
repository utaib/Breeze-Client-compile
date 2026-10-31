package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

/**
 * After the pack repository's selection changed: Minecraft's own options
 * take it as its Resource Packs screen leaves it, save it, and reload
 * resources only when the list changed. From 1.19.4 that is one call.
 */
public final class PackOptions {

    private PackOptions() {}

    public static void apply(Minecraft mc, PackRepository repo) {
        mc.options.updateResourcePacks(repo);
    }
}
