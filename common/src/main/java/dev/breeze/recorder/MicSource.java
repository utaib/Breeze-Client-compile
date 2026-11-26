package dev.breeze.recorder;

import java.util.ArrayList;
import java.util.List;

/**
 * The microphone: Java's own sound input (javax.sound.sampled; DirectSound
 * on Windows, Core Audio on macOS, ALSA on Linux), read on its own thread in
 * pieces of 20 ms into a {@link SoundTrack}. The game never waits for it.
 * The device is the one chosen in the recorder's preferences, by name, or
 * the system's default input.
 */
public final class MicSource implements Runnable {

    /** Formats tried, best first: 48 kHz then 44.1 kHz, stereo then mono, 16-bit. */
    private static final float[] RATES = {48_000f, 44_100f};
    private static final int[] CHANNELS = {2, 1};

    private final javax.sound.sampled.TargetDataLine line;
    private final SoundTrack track;
    private final SoundClock clock;
    private final Thread thread;
    private volatile boolean stopping;
    private volatile String failure;

    private MicSource(javax.sound.sampled.TargetDataLine line, SoundTrack track, SoundClock clock) {
        this.line = line;
        this.track = track;
        this.clock = clock;
        thread = new Thread(this, "Breeze microphone");
        thread.setDaemon(true);
    }

    /**
     * The names of the inputs Java can record from (the preferences' list),
     * or an empty list when there are none or Java has no sound.
     */
    public static List<String> devices() {
        List<String> out = new ArrayList<>();
        try {
            for (javax.sound.sampled.Mixer.Info mi : javax.sound.sampled.AudioSystem.getMixerInfo()) {
                javax.sound.sampled.Mixer m = javax.sound.sampled.AudioSystem.getMixer(mi);
                if (format(m) != null && !out.contains(mi.getName())) out.add(mi.getName());
            }
        } catch (Throwable ignored) {
            // No sound system (java.desktop missing, or no driver): no inputs.
        }
        return out;
    }

    /** The first format the mixer (null: the default input) can record, or null. */
    private static javax.sound.sampled.AudioFormat format(javax.sound.sampled.Mixer m) {
        for (float r : RATES) {
            for (int c : CHANNELS) {
                javax.sound.sampled.AudioFormat f = new javax.sound.sampled.AudioFormat(r, 16, c, true, false);
                javax.sound.sampled.DataLine.Info info = new javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine.class, f);
                boolean ok = m == null ? javax.sound.sampled.AudioSystem.isLineSupported(info) : m.isLineSupported(info);
                if (ok) return f;
            }
        }
        return null;
    }

    /**
     * Opens the input named {@code device} (empty: the default) and starts
     * writing it into {@code pcm}/{@code info}. Throws with a plain reason
     * when it cannot.
     */
    public static MicSource start(String device, java.nio.file.Path pcm, java.nio.file.Path info, SoundClock clock) throws Exception {
        javax.sound.sampled.Mixer mixer = null;
        if (device != null && !device.isEmpty()) {
            for (javax.sound.sampled.Mixer.Info mi : javax.sound.sampled.AudioSystem.getMixerInfo()) {
                if (mi.getName().equals(device)) {
                    javax.sound.sampled.Mixer m = javax.sound.sampled.AudioSystem.getMixer(mi);
                    if (format(m) != null) {
                        mixer = m;
                        break;
                    }
                }
            }
            if (mixer == null) throw new Exception("the microphone \"" + device + "\" is not connected");
        }
        javax.sound.sampled.AudioFormat f = format(mixer);
        if (f == null) throw new Exception("no microphone was found");
        javax.sound.sampled.DataLine.Info li = new javax.sound.sampled.DataLine.Info(javax.sound.sampled.TargetDataLine.class, f);
        javax.sound.sampled.TargetDataLine line = (javax.sound.sampled.TargetDataLine)
                (mixer == null ? javax.sound.sampled.AudioSystem.getLine(li) : mixer.getLine(li));
        int frameBytes = f.getFrameSize();
        // A quarter second of room in the driver; read in 20 ms pieces.
        line.open(f, frameBytes * (int) (f.getSampleRate() / 4));
        SoundTrack track = new SoundTrack(pcm, info, (int) f.getSampleRate(), f.getChannels());
        MicSource s = new MicSource(line, track, clock);
        line.start();
        s.thread.start();
        return s;
    }

    @Override
    public void run() {
        int frameBytes = track.channels() * 2;
        byte[] buf = new byte[frameBytes * (track.rate() / 50)];
        try {
            while (!stopping) {
                int n = line.read(buf, 0, buf.length);
                if (n <= 0) continue;
                track.write(buf, 0, n - n % frameBytes, clock.nowUs());
            }
        } catch (Throwable t) {
            failure = String.valueOf(t.getMessage());
        }
    }

    /** Stops reading and ends the track (silence to now when the input went quiet). */
    public void stop() {
        stopping = true;
        try {
            line.stop();
            line.close();
        } catch (Throwable ignored) {}
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            track.finish(clock.nowUs());
        } catch (Exception e) {
            if (failure == null) failure = e.getMessage();
        }
    }

    public String failure() {
        return failure;
    }

    public SoundTrack track() {
        return track;
    }
}
