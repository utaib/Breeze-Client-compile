package dev.breeze.recorder;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Breeze's recording file ({@code .breezerec}), written by
 * {@link RecWriter} and read by {@link RecReader}. A recording keeps the
 * game (what the client received and what the player did), not pixels, so it
 * can be watched and exported later under other resource packs and shaders.
 *
 * <pre>
 * header   "BRZREC" u16 version  u32 length  UTF-8 JSON (RecMeta)
 * chunk*   u8 'C'  i64 firstMs  i64 lastMs  u32 records  u32 rawLength  u32 storedLength  deflate(records)
 * index    u8 'I'  u32 count  (i64 offset, i64 firstMs)*
 * trailer  i64 indexOffset  i64 durationMs  "BRZEND"
 * </pre>
 *
 * A record is {@code varint deltaMs (from the chunk's first), u8 kind,
 * varint length, bytes}. Chunks are independent (each its own deflate
 * stream), so seeking reads one chunk, and a recording cut short (the game
 * crashed, the power went) is still readable chunk by chunk up to where it
 * stopped: the reader falls back to scanning when the trailer is missing.
 * The header is rewritten at the end with the final duration; its JSON is
 * padded to a fixed room so that never moves the first chunk.
 */
public final class RecFormat {

    private RecFormat() {}

    public static final byte[] MAGIC = "BRZREC".getBytes(StandardCharsets.US_ASCII);
    public static final byte[] END = "BRZEND".getBytes(StandardCharsets.US_ASCII);
    public static final int VERSION = 1;
    /** Room for the header's JSON; the rest is spaces. */
    public static final int HEADER_ROOM = 4096;
    public static final int HEADER_SIZE = MAGIC.length + 2 + 4 + HEADER_ROOM;
    public static final byte CHUNK = 'C';
    public static final byte INDEX = 'I';
    public static final int TRAILER_SIZE = 8 + 8 + END.length;
    public static final String EXTENSION = ".breezerec";

    /** What a record holds. Ids are stored in files: never renumber. */
    public static final class Kind {
        private Kind() {}

        /** A packet the client received, as the game encodes it (id and body). */
        public static final int PACKET = 1;
        /** The player's own state for one tick (RecPlayerState). */
        public static final int SELF = 2;
        /** The connection's protocol changed (login, configuration, play): one byte, the version's own number. */
        public static final int PROTOCOL = 3;
        /** A point the player marked while recording (empty, or a UTF-8 label). */
        public static final int MARKER = 4;
        /** The open screen changed (UTF-8: a short name, empty when none). */
        public static final int SCREEN = 5;
        /**
         * One frame the player saw (1.2.3): the camera's turn at that frame,
         * {@link CameraFrames#SIZE} bytes. Recordings before it have none;
         * watching then turns the camera between the tick states.
         */
        public static final int CAMERA = 6;
    }

    public static void writeVarInt(DataOutput out, int v) throws IOException {
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    public static int readVarInt(DataInput in) throws IOException {
        int v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return v;
        }
        throw new IOException("varint too long");
    }

    static int varIntSize(int v) {
        int n = 1;
        while ((v & ~0x7F) != 0) {
            n++;
            v >>>= 7;
        }
        return n;
    }
}
