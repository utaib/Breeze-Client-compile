package dev.breeze.recorder;

import net.minecraft.network.protocol.BundlePacket;
import net.minecraft.network.protocol.Packet;

/** Bundled packets (Minecraft 1.19.4 and later). */
final class RecBundle {

    private RecBundle() {}

    static Iterable<? extends Packet<?>> parts(Packet<?> p) {
        return p instanceof BundlePacket<?> b ? b.subPackets() : null;
    }
}
