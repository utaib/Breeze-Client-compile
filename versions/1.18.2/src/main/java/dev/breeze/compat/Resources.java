package dev.breeze.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;

/**
 * Reading a file from the game's active resource packs. Up to 1.18.2 a lookup
 * returns the file or throws when no pack has it, and the file is closed
 * itself; this is that form (see versions/1.20.1 for the later one).
 */
public final class Resources {

    private Resources() {}

    /** The file's bytes, or null when no pack has it or it is larger than maxBytes. */
    public static byte[] read(ResourceLocation id, int maxBytes) {
        try (Resource found = Minecraft.getInstance().getResourceManager().getResource(id);
             InputStream in = found.getInputStream()) {
            byte[] b = in.readNBytes(maxBytes + 1);
            return b.length > maxBytes ? null : b;
        } catch (Throwable t) {
            return null;
        }
    }
}
