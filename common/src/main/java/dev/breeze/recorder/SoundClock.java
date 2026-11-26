package dev.breeze.recorder;

import java.time.Instant;

/**
 * The clock every part of a recording is stamped with: microseconds since
 * 1970. The video's frames are stamped with System.nanoTime (which never
 * jumps), the microphone the same way, and the Breeze launcher stamps the
 * game's sound with the system's time (another process, so nanoTime is not
 * shared). One clock is made per recording, tying nanoTime to the system's
 * time at its start, so all three land on the same line.
 */
public final class SoundClock {

    private final long offsetUs;

    public SoundClock() {
        Instant now = Instant.now();
        long nanos = System.nanoTime();
        offsetUs = now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000 - nanos / 1_000;
    }

    /** A System.nanoTime moment on this clock. */
    public long us(long nanos) {
        return offsetUs + nanos / 1_000;
    }

    /** Now on this clock. */
    public long nowUs() {
        return us(System.nanoTime());
    }

    /** Now by the system's time (how another process stamps its sound). */
    public static long systemUs() {
        Instant now = Instant.now();
        return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
    }
}
