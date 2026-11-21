package dev.breeze.recorder;

import java.nio.ByteBuffer;

/** Where a frame read back without waiting arrives (RecFrames.readLater): its RGBA bytes, size and row order. */
@FunctionalInterface
interface FrameSink {
    /** Pixels are valid only during the call; null when the frame could not be read. */
    void accept(ByteBuffer pixels, int width, int height, boolean bottomUp);
}
