package uk.co.jamesj999.sonic.audio;

public interface AudioStream {
    /**
     * Fills the buffer with audio samples.
     * @param buffer The buffer to fill.
     * @return The number of samples read (can be less than buffer length if stream ends).
     */
    int read(short[] buffer);

    default boolean isComplete() {
        return false;
    }
}
