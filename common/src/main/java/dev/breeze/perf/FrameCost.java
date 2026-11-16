package dev.breeze.perf;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What Breeze's own code costs each frame: the time and the memory allocated
 * inside its hooks (tick, HUD, world drawing), in total and per module. It
 * measures Breeze, not Minecraft, so the numbers mean the same on a build
 * server without a graphics card as on a player's computer.
 *
 * <p>Off unless something turns it on (the self-test, the benchmark): then
 * every call costs two clock reads and one allocation counter read. All calls
 * come from the game thread, which is the thread whose allocations are
 * counted; nothing here is synchronised.
 */
public final class FrameCost {

    public static final String TICK = "tick";
    public static final String HUD = "hud";
    public static final String WORLD = "world";

    /** One section's or one module's totals since the last reset. */
    public static final class Total {
        public long calls;
        public long nanos;
        public long maxNanos;
        public long bytes;

        void add(long ns, long b) {
            calls++;
            nanos += ns;
            if (ns > maxNanos) maxNanos = ns;
            if (b > 0) bytes += b;
        }

        public double microsPerCall() { return calls == 0 ? 0 : nanos / 1000.0 / calls; }
        public double bytesPerCall() { return calls == 0 ? 0 : (double) bytes / calls; }
    }

    private static volatile boolean enabled;
    private static final Map<String, Total> sections = new HashMap<>();
    private static final Map<String, Total> modules = new HashMap<>();
    private static final com.sun.management.ThreadMXBean ALLOC = allocCounter();

    private FrameCost() {}

    private static com.sun.management.ThreadMXBean allocCounter() {
        try {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean bean
                    && bean.isThreadAllocatedMemorySupported()) {
                if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
                return bean;
            }
        } catch (Throwable ignored) {
            // Another Java runtime: time is still measured, memory reads 0.
        }
        return null;
    }

    public static boolean enabled() { return enabled; }

    /** Starts (or stops) measuring; starting clears what was measured before. */
    public static void enable(boolean on) {
        if (on) reset();
        enabled = on;
    }

    public static void reset() {
        sections.clear();
        modules.clear();
    }

    /** Bytes this thread has allocated so far, or 0 when the runtime cannot say. */
    public static long allocated() {
        try {
            return ALLOC == null ? 0 : ALLOC.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void section(String name, long startNanos, long startBytes) {
        sections.computeIfAbsent(name, k -> new Total()).add(System.nanoTime() - startNanos, allocated() - startBytes);
    }

    public static void module(String section, String module, long startNanos, long startBytes) {
        modules.computeIfAbsent(section + ":" + module, k -> new Total())
                .add(System.nanoTime() - startNanos, allocated() - startBytes);
    }

    public static Total total(String section) {
        Total t = sections.get(section);
        return t == null ? new Total() : t;
    }

    /** One module's totals in a section; empty when it never ran. */
    public static Total moduleTotal(String section, String module) {
        Total t = modules.get(section + ":" + module);
        return t == null ? new Total() : t;
    }

    /**
     * The most expensive modules of a section, by time or by memory:
     * "Name 12.3us 480B" each, the costliest first.
     */
    public static List<String> top(String section, boolean byBytes, int limit) {
        List<Map.Entry<String, Total>> list = new ArrayList<>();
        String prefix = section + ":";
        for (Map.Entry<String, Total> e : modules.entrySet()) {
            if (e.getKey().startsWith(prefix)) list.add(e);
        }
        Comparator<Map.Entry<String, Total>> order = byBytes
                ? Comparator.comparingDouble(e -> -e.getValue().bytesPerCall())
                : Comparator.comparingDouble(e -> -e.getValue().microsPerCall());
        list.sort(order);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, list.size()); i++) {
            Total t = list.get(i).getValue();
            out.add(list.get(i).getKey().substring(prefix.length()) + " "
                    + format(t.microsPerCall()) + "us " + Math.round(t.bytesPerCall()) + "B");
        }
        return out;
    }

    public static String format(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
