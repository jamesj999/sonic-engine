package uk.co.jamesj999.sonic.level;

import java.util.Arrays;

/**
 * Representation of a 16x16 tile, composed of 4 8x8 SEGA patterns.
 *
 * Patterns are defined using a common descriptor for SEGA patterns, which specifies properties such as how
 * the pattern is flipped. See PatternDesc for more information.
 */
public class Chunk {
    public static final int CHUNK_HEIGHT = 16;
    public static final int CHUNK_WIDTH = 16;
    public static final int PATTERNS_PER_CHUNK = 4;
    public static final int BYTES_PER_PATTERN = 2;
    public static final int CHUNK_SIZE_IN_ROM = PATTERNS_PER_CHUNK * BYTES_PER_PATTERN;

    private final PatternDesc[] patternDescs;

    // Default constructor
    public Chunk() {
        this.patternDescs = new PatternDesc[PATTERNS_PER_CHUNK];
        Arrays.setAll(this.patternDescs, i -> PatternDesc.EMPTY);  // Initialize array with new PatternDesc instances
    }

    // Load chunk from Sega format (big-endian 16-bit values)
    public void fromSegaFormat(byte[] buffer) {
        if (buffer.length != CHUNK_SIZE_IN_ROM) {
            throw new IllegalArgumentException("Buffer size does not match chunk size in ROM");
        }

        for (int i = 0; i < PATTERNS_PER_CHUNK; i++) {
            int index = ((buffer[i * 2] & 0xFF) << 8) | (buffer[i * 2 + 1] & 0xFF);  // Big-endian
            if (index != 0) {
                patternDescs[i] = new PatternDesc(index);
            }

        }
    }

    // Retrieves a pattern descriptor based on x and y coordinates (0-1 range)
    public PatternDesc getPatternDesc(int x, int y) {
        if (x > 1 || y > 1) {
            throw new IllegalArgumentException("Invalid pattern index");
        }
        return patternDescs[y * 2 + x];
    }
}
