package com.openggf.audio;

public interface AudioStream {
    /**
     * Fills the buffer with audio samples.
     * @param buffer The buffer to fill.
     * @return The number of samples read (can be less than buffer length if stream ends).
     */
    int read(short[] buffer);

    /**
     * Fills the first {@code length} elements of the buffer with audio samples,
     * consuming exactly {@code length} samples of stream state even when the
     * buffer is larger.
     * <p>
     * Implementations that produce frames of a caller-chosen size should
     * override this to bound generation natively: the default exists as a
     * safety fallback and allocates an exactly-sized temporary buffer per call
     * whenever {@code length != buffer.length}.
     * <p>
     * When fewer than {@code length} samples are returned (stream end), the
     * tail of {@code buffer} is left untouched — callers that consume the full
     * {@code length} range must pre-zero it.
     * @param buffer The buffer to fill.
     * @param length The number of samples to produce.
     * @return The number of samples read (can be less than {@code length} if the stream ends).
     */
    default int read(short[] buffer, int length) {
        if (length == buffer.length) {
            return read(buffer);
        }
        short[] bounded = new short[length];
        int read = read(bounded);
        System.arraycopy(bounded, 0, buffer, 0, Math.min(read, length));
        return read;
    }

    default boolean isComplete() {
        return false;
    }
}
