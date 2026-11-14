package dev.breeze.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The last position the server sent for an entity (Ping Ghost). Before 1.19
 * the client keeps it as the entity's packet coordinates.
 */
public final class ServerPos {

    private ServerPos() {}

    public static Vec3 of(Entity e) {
        return e.getPacketCoordinates();
    }
}
