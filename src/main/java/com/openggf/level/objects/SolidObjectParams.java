package com.openggf.level.objects;

/**
 * Collision box an object presents to the solid-contact system.
 *
 * <p>Prefer {@link #of} over {@code new}: the solid path asks every solid object
 * for its params several times per player per frame, and the values come from a
 * small discrete set — usually per-type constants, sometimes a per-mapping-frame
 * table. Constructing a fresh record each time made this the largest single
 * allocation source left in the engine (~180 MB across 27,000 replayed frames).
 * {@link #of} returns a shared instance for equal values instead.
 *
 * <p>Sharing is sound because the record is immutable and compared by value;
 * nothing in the engine compares these by identity or keys an
 * {@code IdentityHashMap} on one.
 */
public record SolidObjectParams(int halfWidth, int airHalfHeight, int groundHalfHeight,
                                int offsetX, int offsetY) {

    public SolidObjectParams(int halfWidth, int airHalfHeight, int groundHalfHeight) {
        this(halfWidth, airHalfHeight, groundHalfHeight, 0, 0);
    }

    /** Shared-instance factory. See the class note for why this is preferred. */
    public static SolidObjectParams of(int halfWidth, int airHalfHeight, int groundHalfHeight) {
        return of(halfWidth, airHalfHeight, groundHalfHeight, 0, 0);
    }

    /** Shared-instance factory. See the class note for why this is preferred. */
    public static SolidObjectParams of(int halfWidth, int airHalfHeight, int groundHalfHeight,
                                       int offsetX, int offsetY) {
        Interner interner = INTERNER.get();
        SolidObjectParams cached =
                interner.get(halfWidth, airHalfHeight, groundHalfHeight, offsetX, offsetY);
        if (cached != null) {
            return cached;
        }
        SolidObjectParams built = new SolidObjectParams(
                halfWidth, airHalfHeight, groundHalfHeight, offsetX, offsetY);
        interner.put(built);
        return built;
    }

    /**
     * Per-thread cache. Thread-confined for the same reason the solid-routine
     * profile cache is: a shared table racing across threads could expose a
     * written key before its value is visible and hand back a mismatched
     * collision box, which is not an acceptable failure mode here. Duplicates
     * across threads are harmless because the records are value-equal.
     */
    private static final ThreadLocal<Interner> INTERNER = ThreadLocal.withInitial(Interner::new);

    /**
     * Direct-mapped table of five-int keys.
     *
     * <p>The key is compared field by field on every hit, so the index hash only
     * has to distribute well — it never has to be collision-free. A collision, a
     * cold table, or an object whose box genuinely varies every frame costs an
     * ordinary construction rather than a wrong answer.
     */
    private static final class Interner {
        private static final int CAPACITY = 512;
        private static final int MASK = CAPACITY - 1;

        private final int[] keys = new int[CAPACITY * 5];
        private final SolidObjectParams[] values = new SolidObjectParams[CAPACITY];

        SolidObjectParams get(int halfWidth, int airHalfHeight, int groundHalfHeight,
                              int offsetX, int offsetY) {
            int slot = slotFor(halfWidth, airHalfHeight, groundHalfHeight, offsetX, offsetY);
            SolidObjectParams candidate = values[slot];
            if (candidate == null) {
                return null;
            }
            int base = slot * 5;
            if (keys[base] == halfWidth
                    && keys[base + 1] == airHalfHeight
                    && keys[base + 2] == groundHalfHeight
                    && keys[base + 3] == offsetX
                    && keys[base + 4] == offsetY) {
                return candidate;
            }
            return null;
        }

        void put(SolidObjectParams params) {
            int slot = slotFor(params.halfWidth(), params.airHalfHeight(),
                    params.groundHalfHeight(), params.offsetX(), params.offsetY());
            int base = slot * 5;
            keys[base] = params.halfWidth();
            keys[base + 1] = params.airHalfHeight();
            keys[base + 2] = params.groundHalfHeight();
            keys[base + 3] = params.offsetX();
            keys[base + 4] = params.offsetY();
            values[slot] = params;
        }

        private static int slotFor(int halfWidth, int airHalfHeight, int groundHalfHeight,
                                   int offsetX, int offsetY) {
            int hash = halfWidth;
            hash = hash * 31 + airHalfHeight;
            hash = hash * 31 + groundHalfHeight;
            hash = hash * 31 + offsetX;
            hash = hash * 31 + offsetY;
            // Spread the low bits: box dimensions are small numbers that would
            // otherwise cluster into the first handful of slots.
            hash ^= hash >>> 15;
            hash *= 0x2545F491;
            hash ^= hash >>> 13;
            return hash & MASK;
        }
    }
}
