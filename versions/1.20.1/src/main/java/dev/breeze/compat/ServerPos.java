package dev.breeze.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * The last position the server sent for an entity, before the game smooths
 * the entity towards it over a few ticks (Ping Ghost). From 1.19 the client
 * keeps it as the base of the entity's position codec; decoding no change
 * gives that base. (versions/1.18.2 has the older form.)
 */
public final class ServerPos {

    private ServerPos() {}

    public static Vec3 of(Entity e) {
        return e.getPositionCodec().decode(0L, 0L, 0L);
    }
}
