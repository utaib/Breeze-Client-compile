package dev.breeze.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Properties;

/**
 * One sound track of a recording while it is made (the game's sound or the
 * microphone): raw 16-bit little-endian samples in {@code <name>.pcm}, and
 * beside it {@code <name>.info} saying the rate, the channels and the moment
 * of the first sample (microseconds since 1970, the clock the video's start
 * is stamped with), so the track is put in line with the video when the two
 * are joined. Written straight to the file as it comes, so a game that stops
 * without saving leaves a track that can still be used.
 *
 * Sound that did not come (a source that sends nothing while silent, or a
 * moment the system lost) is filled with silence by the clock: after each
 * piece the file holds as many samples as the time since the first, give or
 * take {@link #GAP_MS}, so the track never slides against the video.
 *
 * The Breeze launcher writes the game's track in the same form
 * (game_sound.rs); the mod writes the microphone's. Not thread safe: one
 * writer thread.
 */
public final class SoundTrack implements AutoCloseable {

    /** A hole longer than this is filled with silence. */
    public static final int GAP_MS = 60;

    private final Path pcm, info;
    private final int rate, channels, frameBytes;
    private final FileChannel out;
    private long startUs = Long.MIN_VALUE;
    private long frames, filled, lastEndUs;
    private boolean closed;

    public SoundTrack(Path pcm, Path info, int rate, int channels) throws IOException {
        this.pcm = pcm;
        this.info = info;
        this.rate = rate;
        this.channels = channels;
        this.frameBytes = 2 * channels;
        Files.createDirectories(pcm.toAbsolutePath().getParent());
        out = FileChannel.open(pcm, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        writeInfo("recording");
    }

    public int rate() {
        return rate;
    }

    public int channels() {
        return channels;
    }

    /** Sample frames in the file so far (silence put in included). */
    public long frames() {
        return frames;
    }

    /** Sample frames of silence put in for holes. */
    public long filled() {
        return filled;
    }

    /** The first sample's moment (microseconds since 1970), or Long.MIN_VALUE before any sound. */
    public long startUs() {
        return startUs;
    }

    /**
     * A piece of sound whose last sample was heard at {@code endUs}
     * (microseconds since 1970): {@code len} bytes of whole sample frames.
     */
    public void write(byte[] b, int off, int len, long endUs) throws IOException {
        int n = len / frameBytes;
        if (n <= 0 || closed) return;
        if (startUs == Long.MIN_VALUE) {
            startUs = endUs - n * 1_000_000L / rate;
            writeInfo("recording");
        }
        gap(endUs, n);
        ByteBuffer buf = ByteBuffer.wrap(b, off, n * frameBytes);
        while (buf.hasRemaining()) out.write(buf);
        frames += n;
        lastEndUs = endUs;
    }

    /** Silence for a hole before a piece of n frames ending at endUs (also used at the end). */
    private void gap(long endUs, int n) throws IOException {
        long expected = (endUs - startUs) * rate / 1_000_000L;
        long missing = expected - (frames + n);
        if (missing * 1000L <= (long) GAP_MS * rate) return;
        // Keep the hole a little short: the next piece's own timing evens it out.
        silence(missing);
    }

    private void silence(long n) throws IOException {
        if (n <= 0) return;
        ByteBuffer zero = ByteBuffer.allocate((int) Math.min(n * frameBytes, 1 << 16));
        long bytes = n * frameBytes;
        while (bytes > 0) {
            zero.clear();
            zero.limit((int) Math.min(zero.capacity(), bytes));
            while (zero.hasRemaining()) bytes -= out.write(zero);
        }
        frames += n;
        filled += n;
    }

    /**
     * Ends the track at {@code endUs}: silence up to then when the source
     * sent nothing at the end, and the info file says it is complete.
     */
    public void finish(long endUs) throws IOException {
        if (closed) return;
        if (startUs != Long.MIN_VALUE && endUs > lastEndUs) {
            long expected = (endUs - startUs) * rate / 1_000_000L;
            long missing = expected - frames;
            if (missing * 1000L > (long) GAP_MS * rate) silence(missing);
        }
        closed = true;
        out.force(false);
        out.close();
        writeInfo("done");
    }

    @Override
    public void close() throws IOException {
        if (!closed) finish(Long.MIN_VALUE);
    }

    private void writeInfo(String state) throws IOException {
        Info i = new Info(rate, channels, startUs == Long.MIN_VALUE ? 0 : startUs, frames, state, null);
        i.write(info);
    }

    /** What a track's .info file says. */
    public record Info(int rate, int channels, long startUs, long frames, String state, String error) {

        public boolean done() {
            return "done".equals(state);
        }

        public boolean failed() {
            return "failed".equals(state);
        }

        public static Info read(Path f) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            } catch (Exception e) {
                return null;
            }
            try {
                return new Info(Integer.parseInt(p.getProperty("rate", "0").trim()),
                        Integer.parseInt(p.getProperty("channels", "0").trim()),
                        Long.parseLong(p.getProperty("start_us", "0").trim()),
                        Long.parseLong(p.getProperty("frames", "0").trim()),
                        p.getProperty("state", "").trim(), p.getProperty("error"));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** Written next to the file and moved over it, so a reader never sees half of it. */
        public void write(Path f) throws IOException {
            StringBuilder s = new StringBuilder();
            s.append("rate=").append(rate).append('\n');
            s.append("channels=").append(channels).append('\n');
            s.append("format=s16le\n");
            s.append("start_us=").append(startUs).append('\n');
            s.append("frames=").append(frames).append('\n');
            s.append("state=").append(state).append('\n');
            if (error != null) s.append("error=").append(error.replace('\n', ' ')).append('\n');
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, s.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "rate=%d channels=%d start_us=%d frames=%d state=%s", rate, channels, startUs, frames, state);
        }
    }
}
