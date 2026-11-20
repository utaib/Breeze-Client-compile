package dev.breeze.recorder;

import net.minecraft.network.protocol.Packet;

/** Bundled packets: Minecraft before 1.19.4 has none. */
final class RecBundle {

    private RecBundle() {}

    static Iterable<? extends Packet<?>> parts(Packet<?> p) {
        return null;
    }
}
