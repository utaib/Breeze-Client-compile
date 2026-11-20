package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.Optional;

/**
 * Reading a file from the game's active resource packs (vanilla's assets with
 * any packs the player turned on over them). Looking a file up changed shape
 * in 1.19, so versions/ has a copy per form; this is the 1.19 and later one.
 */
public final class Resources {

    private Resources() {}

    /** The file's bytes, or null when no pack has it or it is larger than maxBytes. */
    public static byte[] read(ResourceLocation id, int maxBytes) {
        try {
            Optional<Resource> found = Minecraft.getInstance().getResourceManager().getResource(id);
            if (found.isEmpty()) return null;
            try (InputStream in = found.get().open()) {
                byte[] b = in.readNBytes(maxBytes + 1);
                return b.length > maxBytes ? null : b;
            }
        } catch (Throwable t) {
            return null;
        }
    }
}
