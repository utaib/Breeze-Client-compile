package dev.breeze.recorder;

import net.minecraft.client.Minecraft;

/** The recorder's tick, measured like a module's (perf/FrameCost, under "Recorder"). */
public final class RecorderTick {

    private RecorderTick() {}

    public static void run(Minecraft mc) {
        boolean cost = dev.breeze.perf.FrameCost.enabled();
        long t0 = cost ? System.nanoTime() : 0, b0 = cost ? dev.breeze.perf.FrameCost.allocated() : 0;
        RecorderHooks.tick(mc);
        if (cost) dev.breeze.perf.FrameCost.module(dev.breeze.perf.FrameCost.TICK, "Recorder", t0, b0);
    }
}
