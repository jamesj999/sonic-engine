package com.openggf.level.objects;

/**
 * The ROM object loop's slot counter, for one frame.
 *
 * <p>The counter is a live register, not a bound. S2 {@code RunObjects} loads it
 * once per frame and steps it with {@code dbf d7,RunObject}
 * (docs/s2disasm/s2.asm:29810-29822, 29840-29842); S1 and S3K walk their tables
 * the same way. A routine that writes that register therefore shortens or
 * lengthens the REST OF THAT FRAME's walk and nothing beyond it, which is why
 * this is reset at the top of every pass and never captured for rewind.
 *
 * <p>Held by {@link ObjectManager} and driven through its two public methods;
 * nothing else may write it.
 */
final class ObjectLoopSlotBudget {

    /** Last slot the walk may reach, inclusive, or {@code -1} for the whole table. */
    private int lastSlotInclusive = -1;

    /** Clears the override at the top of a pass, restoring the full walk. */
    void reset() {
        lastSlotInclusive = -1;
    }

    /**
     * Rewrites the remaining walk length from {@code sourceSlot}.
     *
     * @param sourceSlot     the writing object's own slot
     * @param remainingSlots the value left in the ROM counter when that object
     *                       returns, i.e. how many further slots {@code dbf}
     *                       steps through after it
     */
    void overrideFrom(int sourceSlot, int remainingSlots) {
        if (sourceSlot < 0 || remainingSlots < 0) {
            return;
        }
        lastSlotInclusive = sourceSlot + remainingSlots;
    }

    /** Whether the walk has already ended by the time it reaches {@code slotIndex}. */
    boolean walkEndedBefore(int slotIndex) {
        return lastSlotInclusive >= 0 && slotIndex > lastSlotInclusive;
    }

    /** Whether the walk reaches {@code slotIndex} at all this frame. */
    boolean reaches(int slotIndex) {
        return lastSlotInclusive < 0 || lastSlotInclusive >= slotIndex;
    }
}
