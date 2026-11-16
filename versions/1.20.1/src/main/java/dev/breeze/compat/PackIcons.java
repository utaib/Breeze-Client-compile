package dev.breeze.compat;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.IOException;
import java.io.InputStream;

/**
 * A resource pack's picture (its pack.png), read as the bytes in the file,
 * for the menu to show. Read through the pack itself, as Minecraft's own
 * Resource Packs screen does; from 1.19.4 the root file comes as a supplier.
 */
public final class PackIcons {

    private PackIcons() {}

    /** At most max + 1 bytes of the picture (more means too large), or null when the pack has none. */
    public static byte[] read(Pack pack, int max) throws IOException {
        try (PackResources r = pack.open()) {
            IoSupplier<InputStream> s = r.getRootResource("pack.png");
            if (s == null) return null;
            try (InputStream in = s.get()) {
                return in.readNBytes(max + 1);
            }
        }
    }
}
