package com.openggf.level.rings;

/**
 * Global ROM spilled-ring spin animation (Ring_spill_anim_counter / _accum / _frame).
 * Shared across all live spilled rings — NOT per-ring. The counter doubles as the
 * decelerating-spin speed input. Ported from RingManager.LostRingPool.updatePhysics
 * (s2.asm ChangeRingFrame). One instance per gameplay session; tick once per frame.
 */
public final class SpillAnimationState {
    static final int INITIAL_COUNTER = 0xFF;
    private int counter;
    private int accum;
    private int frame;

    public void reset() { counter = INITIAL_COUNTER; accum = 0; frame = 0; }

    /**
     * ROM writes {@code #$FF} only to {@code Ring_spill_anim_counter} when an
     * attracted ring loses its lightning shield and becomes Obj_Bouncing_Ring.
     * The shared accumulator and mapping frame are deliberately preserved.
     */
    public void restartCounter() { counter = INITIAL_COUNTER; }

    /** Advance the shared spin one frame (no-op once the counter reaches 0). */
    public void tick() {
        if (counter > 0) {
            accum = (accum + counter) & 0xFFFF;   // ROM: add counter to accumulator
            frame = (accum >> 9) & 3;             // ROM: rol.w #7 / andi.w #3 → bits 10:9
            counter--;
        }
    }

    public int counter() { return counter; }
    public int accum() { return accum; }
    public int frame() { return frame; }

    // Rewind: small explicit snapshot (global state, not per-ring).
    public int[] snapshot() { return new int[] { counter, accum, frame }; }
    public void restore(int[] s) { counter = s[0]; accum = s[1]; frame = s[2]; }
}
