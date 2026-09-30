package dev.breeze.compat;

import net.minecraft.resources.ResourceLocation;

/**
 * Building a ResourceLocation (a namespaced id such as breeze:textures/x.png).
 * The way to make one changed in Minecraft 1.21, so versions/ has a copy of
 * this file per form; this is the 1.21 form.
 *
 * A plain call the compiler checks and the remapper renames. (This class once
 * looked the 1.21 factory up by reflection on its Mojang name, which only
 * resolves in the development client: a player's install uses intermediary
 * names, so the lookup fails there.)
 */
public final class Ids {

    private Ids() {}

    /** A ResourceLocation in the given namespace. */
    public static ResourceLocation of(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    /** Everything Breeze owns lives under the "breeze" namespace. */
    public static ResourceLocation breeze(String path) {
        return of("breeze", path);
    }
}
