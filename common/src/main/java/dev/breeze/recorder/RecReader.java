package dev.breeze.recorder;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a recording written by {@link RecWriter}: its header, its chunks
 * through the index (or by scanning, when it was cut short), and the records
 * from any moment on. One chunk is held in memory at a time.
 */
public final class RecReader implements AutoCloseable {

    /** One record: when (ms from the start), what kind, and its bytes. */
    public record Rec(long timeMs, int kind, byte[] data) {}

    /** Where a chunk is and the time it starts and ends. */
    public record ChunkRef(long offset, long firstMs, long lastMs) {}

    private final RandomAccessFile file;
    private final RecMeta meta;
    private final List<ChunkRef> chunks;
    private final boolean complete;
    private long trailerDurationMs;

    public RecReader(Path path) throws IOException {
        file = new RandomAccessFile(path.toFile(), "r");
        try {
            // The index before the header: the writer puts the final header
            // down before the index, so a file whose index is found already
            // has its final header (read the other way round, a recording
            // finishing at that moment could read as complete but unfinished).
            List<ChunkRef> fromIndex = readIndex();
            meta = readHeader(file);
            if (fromIndex != null && meta.durationMs <= 0) meta.durationMs = trailerDurationMs;
            complete = fromIndex != null;
            chunks = complete ? fromIndex : scan();
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    /** Only the header (the launcher's and the game's lists read nothing else). */
    public static RecMeta readMeta(Path path) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            return readHeader(f);
        }
    }

    private static RecMeta readHeader(RandomAccessFile f) throws IOException {
        byte[] magic = new byte[RecFormat.MAGIC.length];
        f.seek(0);
        f.readFully(magic);
        if (!Arrays.equals(magic, RecFormat.MAGIC)) throw new IOException("not a Breeze recording");
        int version = f.readUnsignedShort();
        if (version > RecFormat.VERSION) throw new IOException("made by a newer Breeze (format " + version + ")");
        int n = f.readInt();
        if (n < 0 || n > RecFormat.HEADER_ROOM) throw new IOException("damaged recording header");
        byte[] json = new byte[n];
        f.readFully(json);
        return RecMeta.fromJson(new String(json, StandardCharsets.UTF_8));
    }

    public RecMeta meta() {
        return meta;
    }

    /** Whether the recording ended normally (its index was found). */
    public boolean complete() {
        return complete;
    }

    public List<ChunkRef> chunks() {
        return chunks;
    }

    /** The recording's length: from the header, or the last record's time when it was cut short. */
    public long durationMs() {
        if (meta.durationMs > 0) return meta.durationMs;
        return chunks.isEmpty() ? 0 : chunks.get(chunks.size() - 1).lastMs();
    }

    private List<ChunkRef> readIndex() throws IOException {
        long size = file.length();
        if (size < RecFormat.HEADER_SIZE + RecFormat.TRAILER_SIZE) return null;
        file.seek(size - RecFormat.TRAILER_SIZE);
        long indexAt = file.readLong();
        long duration = file.readLong();
        byte[] end = new byte[RecFormat.END.length];
        file.readFully(end);
        if (!Arrays.equals(end, RecFormat.END) || indexAt < RecFormat.HEADER_SIZE || indexAt >= size) return null;
        file.seek(indexAt);
        if (file.readByte() != RecFormat.INDEX) return null;
        int count = file.readInt();
        if (count < 0 || count > (size / 29)) return null;
        List<ChunkRef> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long offset = file.readLong();
            long first = file.readLong();
            out.add(new ChunkRef(offset, first, 0));
        }
        // Each chunk's end time is in its own head.
        List<ChunkRef> full = new ArrayList<>(count);
        for (ChunkRef c : out) {
            file.seek(c.offset() + 1 + 8);
            full.add(new ChunkRef(c.offset(), c.firstMs(), file.readLong()));
        }
        trailerDurationMs = duration;
        return full;
    }

    /** Chunks one after another from the header, up to the first that is not whole. */
    private List<ChunkRef> scan() throws IOException {
        List<ChunkRef> out = new ArrayList<>();
        long at = RecFormat.HEADER_SIZE;
        long size = file.length();
        while (at + 29 <= size) {
            file.seek(at);
            if (file.readByte() != RecFormat.CHUNK) break;
            long first = file.readLong();
            long last = file.readLong();
            file.readInt();
            file.readInt();
            int stored = file.readInt();
            if (stored < 0 || at + 29 + stored > size) break;
            out.add(new ChunkRef(at, first, last));
            at += 29 + stored;
        }
        return out;
    }

    /** The records of one chunk, in order. */
    public List<Rec> readChunk(ChunkRef c) throws IOException {
        file.seek(c.offset());
        if (file.readByte() != RecFormat.CHUNK) throw new IOException("damaged chunk");
        long first = file.readLong();
        file.readLong();
        int records = file.readInt();
        int raw = file.readInt();
        int stored = file.readInt();
        if (raw < 0 || stored < 0 || raw > 64 * 1024 * 1024) throw new IOException("damaged chunk");
        byte[] z = new byte[stored];
        file.readFully(z);
        byte[] data = new byte[raw];
        Inflater inf = new Inflater();
        try {
            inf.setInput(z);
            int n = 0;
            while (n < raw && !inf.finished()) {
                int got = inf.inflate(data, n, raw - n);
                if (got == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += got;
            }
            if (n != raw) throw new IOException("damaged chunk");
        } catch (DataFormatException e) {
            throw new IOException("damaged chunk", e);
        } finally {
            inf.end();
        }
        List<Rec> out = new ArrayList<>(records);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        try {
            for (int i = 0; i < records; i++) {
                long t = first + RecFormat.readVarInt(in);
                int kind = in.readUnsignedByte();
                int len = RecFormat.readVarInt(in);
                if (len < 0 || len > raw) throw new IOException("damaged record");
                byte[] b = new byte[len];
                in.readFully(b);
                out.add(new Rec(t, kind, b));
            }
        } catch (EOFException e) {
            throw new IOException("damaged chunk", e);
        }
        return out;
    }

    /** The index of the chunk holding the moment timeMs (the last chunk starting at or before it). */
    public int chunkAt(long timeMs) {
        int lo = 0, hi = chunks.size() - 1, best = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (chunks.get(mid).firstMs() <= timeMs) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return best;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
