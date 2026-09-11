package com.openggf.level;

import java.util.Arrays;

/**
 * Representation of a 16x16 tile, composed of 4 8x8 SEGA patterns.
 *
 * Patterns are defined using a common descriptor for SEGA patterns, which specifies properties such as how
 * the pattern is flipped. See PatternDesc for more information.
 *
 * Copy-on-write: when gameplay code mutates this chunk, it calls
 * cowEnsureWritable(currentEpoch) to clone the internal patternDescs[] array
 * if needed. This protects snapshot-captured chunk references from
 * being clobbered by subsequent mutations.
 */
public class Chunk {
    public static final int CHUNK_HEIGHT = 16;
    public static final int CHUNK_WIDTH = 16;
    public static final int PATTERNS_PER_CHUNK = 4;
    public static final int BYTES_PER_PATTERN = 2;
    public static final int CHUNK_SIZE_IN_ROM = PATTERNS_PER_CHUNK * BYTES_PER_PATTERN;

    private PatternDesc[] patternDescs;
    private int solidTileIndex;
    private int solidTileAltIndex;

    // Epoch at which patternDescs was last cloned. If currentEpoch has moved
    // forward, the next mutation will clone the array.
    private long lastTouchedEpoch = 0L;

    // Default constructor
    public Chunk() {
        this.patternDescs = new PatternDesc[PATTERNS_PER_CHUNK];
        Arrays.setAll(this.patternDescs, i -> PatternDesc.EMPTY);  // Initialize array with new PatternDesc instances
    }

    // Load chunk from Sega format (big-endian 16-bit values)
    public void fromSegaFormat(byte[] buffer, int solidTileIndex, int altSolidTileIndex) {
        if (buffer.length != CHUNK_SIZE_IN_ROM) {
            throw new IllegalArgumentException("Buffer size does not match chunk size in ROM");
        }

        for (int i = 0; i < PATTERNS_PER_CHUNK; i++) {
            int index = ((buffer[i * 2] & 0xFF) << 8) | (buffer[i * 2 + 1] & 0xFF);  // Big-endian
            patternDescs[i] = new PatternDesc(index);
        }
        this.solidTileIndex = solidTileIndex;
        this.solidTileAltIndex = altSolidTileIndex;
    }

    // Retrieves a pattern descriptor based on x and y coordinates (0-1 range)
    public PatternDesc getPatternDesc(int x, int y) {
        if (x > 1 || y > 1) {
            throw new IllegalArgumentException("Invalid pattern index");
        }
        return patternDescs[y * 2 + x];
    }

    /** Sets a pattern descriptor at the given position (0-1 range for x and y). */
    public void setPatternDesc(int x, int y, PatternDesc desc) {
        if (x > 1 || y > 1) {
            throw new IllegalArgumentException("Invalid pattern index");
        }
        patternDescs[y * 2 + x] = desc;
    }

    public int getSolidTileIndex() {
        return solidTileIndex;
    }

    public void setSolidTileIndex(int solidTileIndex) {
        this.solidTileIndex = solidTileIndex;
    }

    public int getSolidTileAltIndex() {
        return solidTileAltIndex;
    }

    public void setSolidTileAltIndex(int solidTileAltIndex) {
        this.solidTileAltIndex = solidTileAltIndex;
    }

    /**
     * Saves the chunk state (pattern descriptors + collision indices) as a compact int array.
     * Used for snapshot/restore during pre-computation of transition tilemaps.
     */
    public int[] saveState() {
        int[] state = new int[PATTERNS_PER_CHUNK + 2];
        for (int i = 0; i < PATTERNS_PER_CHUNK; i++) {
            state[i] = patternDescs[i].get();
        }
        state[PATTERNS_PER_CHUNK] = solidTileIndex;
        state[PATTERNS_PER_CHUNK + 1] = solidTileAltIndex;
        return state;
    }

    /**
     * Restores chunk state from a previously saved snapshot.
     */
    public void restoreState(int[] state) {
        for (int i = 0; i < PATTERNS_PER_CHUNK; i++) {
            patternDescs[i] = new PatternDesc(state[i]);
        }
        solidTileIndex = state[PATTERNS_PER_CHUNK];
        solidTileAltIndex = state[PATTERNS_PER_CHUNK + 1];
    }

    /**
     * Ensures this chunk's patternDescs array is writable for the current epoch.
     * On first write per epoch, clones the array. Subsequent writes within the
     * same epoch reuse the cloned array.
     *
     * Called before any gameplay mutation to protect snapshot references.
     *
     * @param currentEpoch the current snapshot epoch from the level
     */
    public void cowEnsureWritable(long currentEpoch) {
        if (lastTouchedEpoch < currentEpoch) {
            patternDescs = patternDescs.clone();
            lastTouchedEpoch = currentEpoch;
        }
    }

    /**
     * Package-private accessor for the live patternDescs array (testing only).
     * Marked as @VisibleForTesting: use only in test assertions.
     *
     * @return the internal PatternDesc array reference
     */
    PatternDesc[] patternDescsArrayForTest() {
        return patternDescs;
    }
}
