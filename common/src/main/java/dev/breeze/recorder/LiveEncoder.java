package dev.breeze.recorder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The screen video's encoder: ffmpeg fed raw RGBA frames (top row first) on
 * its standard input while the game plays. The game never waits for it:
 * frames go into a short queue that a writer thread empties, and when the
 * queue is full (the encoder fell behind) the new frame is not queued; the
 * last queued frame is shown once more instead, so the video keeps the real
 * time and only loses that picture (counted as {@link #dropped}). Buffers
 * are direct and reused, a few frames in all, whatever the length.
 */
public final class LiveEncoder implements AutoCloseable {

    private static final class Entry {
        final ByteBuffer buf;
        int repeats;

        Entry(ByteBuffer buf, int repeats) {
            this.buf = buf;
            this.repeats = repeats;
        }
    }

    private final Process process;
    private final int frameBytes;
    private final int capacity;
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final ArrayDeque<ByteBuffer> free = new ArrayDeque<>();
    private int extraRepeats;
    private boolean ending;
    private final Thread writer;
    private final StringBuilder errors = new StringBuilder();
    private volatile String failure;
    private volatile long written, dropped, queuedFrames;

    /**
     * @param capacity frames that may wait for ffmpeg (the buffers made are
     *                 capacity + 3: those, the one being written and the one
     *                 being filled, and one spare)
     */
    public LiveEncoder(List<String> command, int w, int h, int capacity) throws IOException {
        this.frameBytes = w * h * 4;
        this.capacity = Math.max(1, capacity);
        for (int i = 0; i < this.capacity + 3; i++) free.add(ByteBuffer.allocateDirect(frameBytes));
        process = new ProcessBuilder(command).start();
        Thread err = new Thread(() -> {
            try (InputStream in = process.getErrorStream()) {
                byte[] b = in.readAllBytes();
                synchronized (errors) {
                    errors.append(new String(b, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {}
        }, "Breeze screen video errors");
        err.setDaemon(true);
        err.start();
        Thread out = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                in.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        }, "Breeze screen video output");
        out.setDaemon(true);
        out.start();
        writer = new Thread(this::write, "Breeze screen video writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** How many frames may wait for a frame of this many bytes: about 64 MB of them, 2 to 6. */
    public static int capacityFor(long frameBytes) {
        return (int) Math.max(2, Math.min(6, (64L << 20) / Math.max(1, frameBytes)));
    }

    public int frameBytes() {
        return frameBytes;
    }

    /** A buffer to fill with the next frame, or null when none is free (the frame is then dropped). */
    public synchronized ByteBuffer take() {
        ByteBuffer b = free.poll();
        if (b != null) b.clear();
        return b;
    }

    /** Gives back a buffer taken and not submitted. */
    public synchronized void release(ByteBuffer b) {
        if (b != null) free.add(b);
    }

    /**
     * Queues a filled frame (its position 0, limit frameBytes) to appear
     * {@code repeats} times. Never waits: a frame that does not fit is given
     * back and the last queued frame repeats in its place. 0 repeats gives
     * the buffer back.
     */
    public synchronized void submit(ByteBuffer frame, int repeats) {
        if (repeats <= 0 || failure != null || ending) {
            free.add(frame);
            return;
        }
        if (queue.size() >= capacity) {
            free.add(frame);
            dropped += repeats;
            repeatLast(repeats);
            return;
        }
        queue.add(new Entry(frame, repeats));
        queuedFrames += repeats;
        notifyAll();
    }

    /** The last frame appears {@code n} more times (no new picture for those ticks). */
    public synchronized void repeatLast(int n) {
        if (n <= 0 || ending) return;
        Entry last = queue.peekLast();
        if (last != null) last.repeats += n;
        else extraRepeats += n;
        queuedFrames += n;
        notifyAll();
    }

    private void write() {
        ByteBuffer held = null;
        try (OutputStream o = process.getOutputStream(); WritableByteChannel ch = Channels.newChannel(o)) {
            while (true) {
                Entry e;
                int extra;
                synchronized (this) {
                    while (queue.isEmpty() && extraRepeats == 0 && !ending) wait();
                    e = queue.poll();
                    extra = e == null ? extraRepeats : 0;
                    if (e == null) extraRepeats = 0;
                    if (e == null && extra == 0 && ending) break;
                }
                if (e != null) {
                    for (int i = 0; i < e.repeats; i++) put(ch, e.buf);
                    synchronized (this) {
                        if (held != null) free.add(held);
                    }
                    held = e.buf;
                    // Repeats asked for while it was written (the queue was empty).
                    synchronized (this) {
                        extra = extraRepeats;
                        extraRepeats = 0;
                    }
                }
                if (held != null) for (int i = 0; i < extra; i++) put(ch, held);
            }
        } catch (Throwable t) {
            failure = "ffmpeg stopped taking frames";
            synchronized (this) {
                queue.clear();
                notifyAll();
            }
        }
    }

    private void put(WritableByteChannel ch, ByteBuffer b) throws IOException {
        ByteBuffer d = b.duplicate();
        d.position(0).limit(frameBytes);
        while (d.hasRemaining()) ch.write(d);
        written++;
    }

    /** Frames written to ffmpeg so far. */
    public long written() {
        return written;
    }

    /** Frames whose picture was lost because the encoder was behind (their tick repeats the frame before). */
    public long dropped() {
        return dropped;
    }

    /** Frames handed over so far (written or waiting). */
    public long queued() {
        return queuedFrames;
    }

    public String failure() {
        return failure;
    }

    /** Ends the video: waits for the queued frames and for ffmpeg. Null when it worked, else what went wrong. */
    public String finish() {
        synchronized (this) {
            ending = true;
            notifyAll();
        }
        try {
            writer.join(120_000);
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "ffmpeg did not finish";
            }
            if (process.exitValue() != 0) {
                synchronized (errors) {
                    String e = errors.toString().trim();
                    return "ffmpeg failed" + (e.isEmpty() ? "" : ": " + e.lines().reduce((x, y) -> y).orElse(""));
                }
            }
            return failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "the recording was interrupted";
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            ending = true;
            queue.clear();
            notifyAll();
        }
        process.destroy();
    }
}
