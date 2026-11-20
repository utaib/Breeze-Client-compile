package dev.breeze.recorder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;

/**
 * Writes a recording while the game runs. The game's threads only append
 * records to an in-memory chunk (a copy of the bytes, nothing else); a full
 * chunk (512 KB, or 4 seconds) is handed to one background thread that
 * compresses and writes it. So the cost on the render and network threads is
 * a memory copy, and memory stays bounded: at most {@link #MAX_PENDING}
 * bytes wait for the disk. If the disk falls that far behind, the recording
 * stops with a reason rather than growing without limit or losing records in
 * the middle.
 */
public final class RecWriter implements AutoCloseable {

    public static final int CHUNK_BYTES = 512 * 1024;
    public static final long CHUNK_MS = 4_000;
    public static final long MAX_PENDING = 48L * 1024 * 1024;

    private record Sealed(byte[] data, int length, int records, long firstMs, long lastMs) {}

    private static final Sealed STOP = new Sealed(new byte[0], 0, 0, 0, 0);

    private final RandomAccessFile file;
    private final RecMeta meta;
    private final BlockingQueue<Sealed> queue = new ArrayBlockingQueue<>(256);
    private final AtomicLong pending = new AtomicLong();
    private final Thread thread;
    private final List<long[]> index = new ArrayList<>();

    // The chunk being filled (guarded by this).
    private byte[] buf = new byte[CHUNK_BYTES + 64 * 1024];
    private int len;
    private int records;
    private long firstMs = -1;
    private long lastMs;

    private volatile String failure;
    private volatile boolean closed;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong rawBytes = new AtomicLong();
    private final AtomicLong totalRecords = new AtomicLong();

    public RecWriter(Path path, RecMeta meta) throws IOException {
        this.meta = meta;
        this.file = new RandomAccessFile(path.toFile(), "rw");
        file.setLength(0);
        writeHeader();
        written.set(file.length());
        thread = new Thread(this::run, "Breeze recorder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        thread.start();
    }

    /** Why the recording stopped on its own, or null while it is fine. */
    public String failure() {
        return failure;
    }

    public long bytesOnDisk() {
        return written.get();
    }

    public long rawBytes() {
        return rawBytes.get();
    }

    public long records() {
        return totalRecords.get();
    }

    public long pendingBytes() {
        return pending.get();
    }

    /**
     * Adds a record at timeMs (since the recording started; never earlier
     * than the record before). False when the recording has stopped.
     */
    public boolean record(int kind, long timeMs, byte[] data, int off, int length) {
        if (closed || failure != null) return false;
        synchronized (this) {
            if (closed) return false;
            if (firstMs < 0) firstMs = timeMs;
            if (timeMs < lastMs) timeMs = lastMs;
            long delta = timeMs - firstMs;
            if (delta > Integer.MAX_VALUE) delta = Integer.MAX_VALUE;
            int need = 5 + 1 + 5 + length;
            if (len + need > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + need));
            len = putVarInt(buf, len, (int) delta);
            buf[len++] = (byte) kind;
            len = putVarInt(buf, len, length);
            System.arraycopy(data, off, buf, len, length);
            len += length;
            records++;
            lastMs = timeMs;
            totalRecords.incrementAndGet();
            if (len >= CHUNK_BYTES || lastMs - firstMs >= CHUNK_MS) seal();
        }
        return failure == null;
    }

    public boolean record(int kind, long timeMs, byte[] data) {
        return record(kind, timeMs, data, 0, data.length);
    }

    /** Hands the current chunk to the writer thread (holding this). */
    private void seal() {
        if (records == 0) return;
        Sealed s = new Sealed(buf, len, records, firstMs, lastMs);
        if (pending.addAndGet(len) > MAX_PENDING || !queue.offer(s)) {
            pending.addAndGet(-len);
            failure = "The disk could not keep up, so the recording stopped. Everything up to here is saved.";
        } else {
            rawBytes.addAndGet(len);
        }
        buf = new byte[CHUNK_BYTES + 64 * 1024];
        len = 0;
        records = 0;
        firstMs = -1;
    }

    /** Sends what is buffered to the disk soon (when recording pauses, for instance). */
    public void flush() {
        synchronized (this) {
            seal();
        }
    }

    /** Ends the recording: the last chunk, the index and the final header. Waits for the disk. */
    @Override
    public void close() throws IOException {
        close(lastMs);
    }

    public void close(long durationMs) throws IOException {
        synchronized (this) {
            if (closed) return;
            seal();
            closed = true;
        }
        meta.durationMs = Math.max(durationMs, 0);
        try {
            queue.put(STOP);
            thread.join(60_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) throw new IOException("the recorder's writer did not finish");
        if (failure == null || failure.startsWith("The disk could not keep up")) {
            meta.finished = failure == null;
            finish();
        }
        file.close();
    }

    private void run() {
        Deflater deflater = new Deflater(6);
        byte[] out = new byte[CHUNK_BYTES];
        try {
            while (true) {
                Sealed s = queue.poll(1, TimeUnit.SECONDS);
                if (s == null) continue;
                if (s == STOP) return;
                deflater.reset();
                deflater.setInput(s.data(), 0, s.length());
                deflater.finish();
                ByteArrayOutputStream z = new ByteArrayOutputStream(s.length() / 3 + 64);
                while (!deflater.finished()) {
                    int n = deflater.deflate(out);
                    z.write(out, 0, n);
                }
                long offset = file.length();
                ByteArrayOutputStream head = new ByteArrayOutputStream(32);
                DataOutputStream h = new DataOutputStream(head);
                h.writeByte(RecFormat.CHUNK);
                h.writeLong(s.firstMs());
                h.writeLong(s.lastMs());
                h.writeInt(s.records());
                h.writeInt(s.length());
                h.writeInt(z.size());
                file.seek(offset);
                file.write(head.toByteArray());
                file.write(z.toByteArray());
                synchronized (index) {
                    index.add(new long[]{offset, s.firstMs()});
                }
                written.set(file.length());
                pending.addAndGet(-s.length());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failure = "The recording could not be written (" + t.getMessage() + "), so it stopped.";
        } finally {
            deflater.end();
        }
    }

    private void finish() throws IOException {
        long indexAt = file.length();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeByte(RecFormat.INDEX);
        synchronized (index) {
            o.writeInt(index.size());
            for (long[] e : index) {
                o.writeLong(e[0]);
                o.writeLong(e[1]);
            }
        }
        o.writeLong(indexAt);
        o.writeLong(meta.durationMs);
        o.write(RecFormat.END);
        file.seek(indexAt);
        file.write(b.toByteArray());
        writeHeader();
        written.set(file.length());
    }

    private void writeHeader() throws IOException {
        byte[] json = meta.toJson().getBytes(StandardCharsets.UTF_8);
        if (json.length > RecFormat.HEADER_ROOM) throw new IOException("recording header too large");
        byte[] room = new byte[RecFormat.HEADER_ROOM];
        Arrays.fill(room, (byte) ' ');
        System.arraycopy(json, 0, room, 0, json.length);
        ByteArrayOutputStream b = new ByteArrayOutputStream(RecFormat.HEADER_SIZE);
        DataOutputStream o = new DataOutputStream(b);
        o.write(RecFormat.MAGIC);
        o.writeShort(RecFormat.VERSION);
        o.writeInt(json.length);
        o.write(room);
        file.seek(0);
        file.write(b.toByteArray());
    }

    private static int putVarInt(byte[] b, int at, int v) {
        while ((v & ~0x7F) != 0) {
            b[at++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        b[at++] = (byte) v;
        return at;
    }
}
