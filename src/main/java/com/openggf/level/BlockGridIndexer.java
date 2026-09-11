package com.openggf.level;

/**
 * Block-grid index math for chunk-desc lookups, extracted from
 * {@link LevelManager}.
 *
 * <p>Block pixel sizes are 128 (S2/S3K) or 256 (S1) in practice, so the
 * block-grid div/mod on the hot collision-lookup path reduce to shift/mask.
 * A non-power-of-two size keeps the historical div/mod fallback. Inputs to
 * {@link #blockIndex(int)} / {@link #blockLocal(int)} must already be
 * wrapped, non-negative coordinates — the domain where shift/mask is exactly
 * equivalent to div/mod.
 */
final class BlockGridIndexer {

    private final int blockPixelSize;
    private final int shift;  // log2(blockPixelSize), or -1 when not a power of two
    private final int mask;   // blockPixelSize - 1; only meaningful when shift >= 0

    BlockGridIndexer(int blockPixelSize) {
        this.blockPixelSize = blockPixelSize;
        this.shift = (blockPixelSize > 0 && (blockPixelSize & (blockPixelSize - 1)) == 0)
                ? Integer.numberOfTrailingZeros(blockPixelSize)
                : -1;
        this.mask = blockPixelSize - 1;
    }

    /** Block-grid index of a wrapped (non-negative) pixel coordinate. */
    int blockIndex(int wrapped) {
        return shift >= 0 ? wrapped >> shift : wrapped / blockPixelSize;
    }

    /** Pixel offset within the block of a wrapped (non-negative) coordinate. */
    int blockLocal(int wrapped) {
        return shift >= 0 ? wrapped & mask : wrapped % blockPixelSize;
    }

    /**
     * Wraps a pixel coordinate into {@code [0, size)}. For {@code size > 0}
     * (callers guard non-positive dimensions before calling), exactly
     * equivalent to {@code ((v % size) + size) % size} for every int input
     * (including negatives and {@link Integer#MIN_VALUE}); uses a single mask
     * when {@code size} is a power of two, which level pixel dimensions
     * (block-count multiples of 128/256) frequently are.
     */
    static int wrapCoordinate(int v, int size) {
        if ((size & (size - 1)) == 0) {
            return v & (size - 1);
        }
        return ((v % size) + size) % size;
    }
}
