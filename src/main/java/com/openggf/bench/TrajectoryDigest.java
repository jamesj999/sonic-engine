package com.openggf.bench;

/**
 * Rolling hash of the gameplay trajectory a benchmark run produced.
 *
 * <p>A cross-JVM timing comparison is only valid if every runtime executed the
 * same work. If one JVM diverges mid-replay — a different floating-point path, a
 * different iteration order leaking into gameplay — the "faster" runtime may
 * simply have simulated a shorter or simpler route, and the whole table is
 * meaningless. Each run therefore folds the player and camera state of every
 * compared frame into a digest; matching digests across runtimes mean the
 * timings describe identical work, and a mismatch invalidates the comparison
 * rather than quietly skewing it.
 *
 * <p>This is a determinism check for the benchmark, not a correctness check for
 * the engine — trace-replay tests own accuracy. It is deliberately cheap: FNV-1a
 * over a handful of ints per frame, no allocation.
 */
public final class TrajectoryDigest {

    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private long hash = FNV_OFFSET_BASIS;
    private int observations;

    /** Folds one compared frame's observable state into the digest. */
    public void observe(int frame, int centreX, int centreY, int rings, int cameraX, int cameraY) {
        mix(frame);
        mix(centreX);
        mix(centreY);
        mix(rings);
        mix(cameraX);
        mix(cameraY);
        observations++;
    }

    private void mix(int value) {
        for (int shift = 0; shift < 32; shift += 8) {
            hash ^= (value >>> shift) & 0xFF;
            hash *= FNV_PRIME;
        }
    }

    /** Frames folded in so far. */
    public int observations() {
        return observations;
    }

    /** Digest as a fixed-width hex string, suitable for report comparison. */
    public String hex() {
        return String.format("%016x", hash);
    }
}
