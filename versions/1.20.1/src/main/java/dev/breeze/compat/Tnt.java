package dev.breeze.compat;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.MinecartTNT;

/**
 * Lit TNT and lit TNT minecarts, for their countdowns (TNT Countdown, TNT
 * Timer); this is the form before 26.x (the TNT minecart in vehicle; 26.x has it in vehicle.minecart).
 */
public final class Tnt {

    private Tnt() {}

    /** Ticks left before it explodes, or -1 when it is not lit TNT or a lit TNT minecart. */
    public static int fuse(Entity e) {
        if (e instanceof PrimedTnt t) return t.getFuse();
        if (e instanceof MinecartTNT m && m.isPrimed()) return m.getFuse();
        return -1;
    }
}
