package dev.breeze.recorder;

import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * A generated sound source for the self-test (the build machines have no
 * sound device): silence in 20 ms pieces paced by the clock, with a tone of
 * {@code hz} for 200 ms from {@link #beepUs} on. Where the tone lands in the
 * finished video shows whether the track was put in line with the picture.
 */
public final class ToneSource implements Runnable {

    /** When the tones sound (microphone and game alike), on the recording's clock; -1 for none. */
    public static volatile long beepUs = -1;
    public static final int BEEP_MS = 200;

    private final SoundTrack track;
    private final LongSupplier clock;
    private final double hz;
    private final Thread thread;
    private volatile boolean stopping;
    private long sample;

    public ToneSource(Path pcm, Path info, double hz, LongSupplier clockUs, String name) throws java.io.IOException {
        this.track = new SoundTrack(pcm, info, 48_000, 2);
        this.clock = clockUs;
        this.hz = hz;
        thread = new Thread(this, name);
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void run() {
        int n = track.rate() / 50;
        byte[] buf = new byte[n * 4];
        long next = System.nanoTime();
        try {
            while (!stopping) {
                next += 20_000_000L;
                long wait = next - System.nanoTime();
                if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                long end = clock.getAsLong();
                long first = end - (long) n * 1_000_000L / track.rate();
                long b = beepUs;
                for (int i = 0; i < n; i++) {
                    long t = first + (long) i * 1_000_000L / track.rate();
                    short v = 0;
                    if (b >= 0 && t >= b && t < b + BEEP_MS * 1000L) {
                        v = (short) (Math.sin(2 * Math.PI * hz * sample / track.rate()) * 16_000);
                    }
                    sample++;
                    buf[i * 4] = buf[i * 4 + 2] = (byte) v;
                    buf[i * 4 + 1] = buf[i * 4 + 3] = (byte) (v >> 8);
                }
                track.write(buf, 0, buf.length, end);
            }
        } catch (Exception ignored) {
            // Stopped.
        }
    }

    public void stop() {
        stopping = true;
        try {
            thread.join(1_000);
            track.finish(clock.getAsLong());
        } catch (Exception ignored) {}
    }

    public SoundTrack track() {
        return track;
    }
}
