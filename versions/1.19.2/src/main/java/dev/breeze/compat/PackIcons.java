package dev.breeze.compat;

import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;

import java.io.IOException;
import java.io.InputStream;

/**
 * A resource pack's picture (its pack.png). Up to 1.19.2 a pack hands its
 * root file over as a stream, and a missing file is an IOException.
 */
public final class PackIcons {

    private PackIcons() {}

    /** At most max + 1 bytes of the picture (more means too large), or null when the pack has none. */
    public static byte[] read(Pack pack, int max) throws IOException {
        try (PackResources r = pack.open(); InputStream in = r.getRootResource("pack.png")) {
            return in == null ? null : in.readNBytes(max + 1);
        }
    }
}
