package dev.breeze.compat;

import net.minecraft.server.packs.repository.Pack;

/**
 * A resource pack's picture: not read on 1.19.3, whose way of reading a
 * pack's root file was not checked against its classes (1.19.2 and 1.19.4
 * were). The menu shows the pack without its picture.
 */
public final class PackIcons {

    private PackIcons() {}

    public static byte[] read(Pack pack, int max) {
        return null;
    }
}
