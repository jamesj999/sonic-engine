package com.openggf.game.profiles.solidroutine;

/**
 * Allocation-free cache of immutable solid-routine profiles, keyed by a packed
 * signature of the fields that define one.
 *
 * <p>A profile is a pure function of a handful of provider reads, and the solid
 * contact path rebuilds one per solid object per player per frame from inputs
 * that almost never change. Profiling a CNZ replay attributed ~17% of the
 * engine's total allocation to those rebuilds (357 MB across 27,000 frames)
 * across the two record types, which is pure garbage-collector pressure for
 * values that are identical frame to frame.
 *
 * <p>Interning is sound here only because the profile records are immutable and
 * compared by value — nothing in the engine compares them by identity, and no
 * {@code IdentityHashMap} is keyed by one. Returning a shared instance for equal
 * field sets is therefore indistinguishable from returning a fresh one.
 *
 * <h2>Why thread-confined rather than shared</h2>
 * A shared table would need either locking or release/acquire fences on every
 * lookup: a plain table racing across threads can expose a written key before
 * its value is visible, handing back {@code null} or a mismatched profile. This
 * is accuracy-critical code, so the cache is per-thread instead. Duplicate
 * entries across threads are harmless — the records are value-equal — and every
 * lookup stays a plain array read with no fence and no allocation.
 *
 * <h2>Direct-mapped, and always correct on a miss</h2>
 * The table is direct-mapped: a collision simply overwrites. The stored key is
 * always re-checked before a hit is returned, so a collision, an overflow, or a
 * cold table costs an ordinary construction rather than a wrong answer. That
 * keeps the failure mode "no faster than before" instead of "subtly wrong".
 */
public final class SolidRoutineProfileInterner<T> {

    /** Power of two. Distinct profiles per level are a handful; this is ample. */
    private static final int CAPACITY = 128;
    private static final int MASK = CAPACITY - 1;

    private final long[] keys = new long[CAPACITY];
    private final Object[] values = new Object[CAPACITY];

    /**
     * Returns the cached instance for {@code key}, or {@code null} on a miss.
     *
     * <p>Deliberately a plain lookup rather than a compute-if-absent taking a
     * factory: a lambda closing over the provider would allocate on every call,
     * which is the cost this class exists to remove. Callers check, build on
     * null, then {@link #put}.
     *
     * <p>{@code key} must never be zero — zero marks an empty slot.
     * {@link #signature} sets a tag bit to guarantee that.
     */
    @SuppressWarnings("unchecked")
    public T get(long key) {
        int slot = slotFor(key);
        return keys[slot] == key ? (T) values[slot] : null;
    }

    /** Stores {@code value} under {@code key}, overwriting any colliding entry. */
    public void put(long key, T value) {
        int slot = slotFor(key);
        keys[slot] = key;
        values[slot] = value;
    }

    private static int slotFor(long key) {
        // Mix so that signatures differing only in low bits (the boolean flags)
        // do not all collide into a handful of slots.
        long mixed = key * 0x9E3779B97F4A7C15L;
        return (int) (mixed >>> 57) & MASK;
    }

    /**
     * Packs a profile's defining fields into a non-zero key.
     *
     * <p>The twelve flags occupy the low bits and {@code monitorVerticalOffset}
     * the high 32, so distinct field sets always produce distinct keys — the
     * lookup is exact, not a hash that could alias two different profiles onto
     * one entry. Bit 31 is set as a tag so a key is never zero.
     */
    public static long signature(boolean topSolidOnly,
                          boolean monitorSolidity,
                          int monitorVerticalOffset,
                          boolean inclusiveRightEdge,
                          boolean stickyContactBuffer,
                          boolean usesPlatformLandingSnap,
                          boolean usesCollisionHalfWidthForTopLanding,
                          boolean usesGroundHalfHeightForTopSolidContact,
                          boolean bypassesOffscreenSolidGate,
                          boolean allowsObjectControlledSolidContacts,
                          boolean forceAirOnRideExit,
                          boolean dropOnFloor,
                          boolean carriesAirborneRiderAfterExitPlatform) {
        long flags = 0L;
        flags |= topSolidOnly ? 1L : 0L;
        flags |= monitorSolidity ? 1L << 1 : 0L;
        flags |= inclusiveRightEdge ? 1L << 2 : 0L;
        flags |= stickyContactBuffer ? 1L << 3 : 0L;
        flags |= usesPlatformLandingSnap ? 1L << 4 : 0L;
        flags |= usesCollisionHalfWidthForTopLanding ? 1L << 5 : 0L;
        flags |= usesGroundHalfHeightForTopSolidContact ? 1L << 6 : 0L;
        flags |= bypassesOffscreenSolidGate ? 1L << 7 : 0L;
        flags |= allowsObjectControlledSolidContacts ? 1L << 8 : 0L;
        flags |= forceAirOnRideExit ? 1L << 9 : 0L;
        flags |= dropOnFloor ? 1L << 10 : 0L;
        flags |= carriesAirborneRiderAfterExitPlatform ? 1L << 11 : 0L;
        flags |= 1L << 31; // tag bit: keeps the key non-zero for the all-false profile
        return flags | ((long) monitorVerticalOffset << 32);
    }
}
