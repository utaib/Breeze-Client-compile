package dev.breeze.compat;

import net.minecraft.server.packs.repository.Pack;

/**
 * A resource pack's picture: not read on 26.3, which moved a pack's root
 * files to PackMetadataResources (Pack.openMetadata), not yet checked
 * against its classes. The menu shows the pack without its picture.
 */
public final class PackIcons {

    private PackIcons() {}

    public static byte[] read(Pack pack, int max) {
        return null;
    }
}
