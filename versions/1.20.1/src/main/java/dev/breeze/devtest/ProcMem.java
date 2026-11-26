package dev.breeze.devtest;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Resident memory of the game's child processes (Chromium's), on Linux from
 * /proc; -1 elsewhere. Self-test only.
 */
final class ProcMem {

    private ProcMem() {}

    static long childrenRssMb() {
        try {
            long self = ProcessHandle.current().pid();
            if (!Files.isDirectory(Path.of("/proc/" + self))) return -1;
            long kb = 0;
            java.util.ArrayDeque<Long> todo = new java.util.ArrayDeque<>();
            ProcessHandle.current().children().forEach(p -> todo.add(p.pid()));
            java.util.Set<Long> seen = new java.util.HashSet<>();
            while (!todo.isEmpty()) {
                long pid = todo.poll();
                if (!seen.add(pid)) continue;
                kb += rssKb(pid);
                ProcessHandle.of(pid).ifPresent(h -> h.children().forEach(c -> todo.add(c.pid())));
            }
            return kb / 1024;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static long rssKb(long pid) {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/" + pid + "/status"))) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Throwable ignored) {}
        return 0;
    }
}
