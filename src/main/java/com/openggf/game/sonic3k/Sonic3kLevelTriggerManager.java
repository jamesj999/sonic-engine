package com.openggf.game.sonic3k;

import java.util.Arrays;

/**
 * Manages the S3K Level_trigger_array (ROM: $FFFFF7F0, 16 bytes).
 * <p>
 * Used by Button objects (Obj_Button, 0x33) to communicate state to other
 * level objects. Each entry is a byte with individual bits settable by
 * different buttons via subtype encoding.
 * <p>
 * Analogous to Sonic 2's ButtonVine_Trigger ($FFFFF7CA).
 */
public class Sonic3kLevelTriggerManager {
    private static final int TRIGGER_COUNT = 16;

    private static final int[] triggers = new int[TRIGGER_COUNT];

    private Sonic3kLevelTriggerManager() {
    }

    /**
     * Sets a specific bit in the trigger byte.
     * ROM: bset d3,(a3) where d3 is 0 or 7
     *
     * @param index trigger index (0-15, from subtype bits 0-3)
     * @param bit   bit number (0 or 7)
     */
    public static void setBit(int index, int bit) {
        if (index >= 0 && index < TRIGGER_COUNT) {
            triggers[index] |= (1 << bit);
        }
    }

    /**
     * Clears a specific bit in the trigger byte.
     * ROM: bclr d3,(a3)
     *
     * @param index trigger index (0-15)
     * @param bit   bit number (0 or 7)
     */
    public static void clearBit(int index, int bit) {
        if (index >= 0 && index < TRIGGER_COUNT) {
            triggers[index] &= ~(1 << bit);
        }
    }

    /**
     * Tests a specific bit in the trigger byte.
     * ROM: btst d3,(a3)
     *
     * @param index trigger index (0-15)
     * @param bit   bit number (0 or 7)
     * @return true if the specified bit is set
     */
    public static boolean testBit(int index, int bit) {
        return index >= 0 && index < TRIGGER_COUNT && (triggers[index] & (1 << bit)) != 0;
    }

    /**
     * Sets all bits in the trigger byte (byte = $FF).
     * ROM: st (a3,d0.w) — used by Blastoid on defeat to signal CollapsingBridge collapse.
     *
     * @param index trigger index (0-15, from subtype bits 0-3)
     */
    public static void setAll(int index) {
        if (index >= 0 && index < TRIGGER_COUNT) {
            triggers[index] = 0xFF;
        }
    }

    /**
     * Clears the entire trigger byte to zero.
     * ROM: move.b #0,(a3) — used by MGZDashTrigger when its 60-frame arm
     * timer expires (sonic3k.asm:51539).
     *
     * @param index trigger index (0-15)
     */
    public static void clearAll(int index) {
        if (index >= 0 && index < TRIGGER_COUNT) {
            triggers[index] = 0;
        }
    }

    /**
     * Tests if any bit in the trigger byte is set.
     * ROM: tst.b (a3) — used to gate sound effect playback
     *
     * @param index trigger index (0-15)
     * @return true if any bit is set
     */
    public static boolean testAny(int index) {
        return index >= 0 && index < TRIGGER_COUNT && triggers[index] != 0;
    }

    /**
     * Resets all triggers to zero. Called on level load.
     */
    public static void reset() {
        Arrays.fill(triggers, 0);
    }

    /**
     * Immutable rewind snapshot of {@code Level_trigger_array}. Defensively
     * copies the backing array so a captured snapshot is safe to retain
     * across frames.
     */
    public record Snapshot(int[] triggers) {
        public Snapshot {
            triggers = triggers.clone();
        }

        @Override
        public int[] triggers() {
            return triggers.clone();
        }
    }

    /** Captures the current trigger array for rewind snapshots. */
    public static Snapshot snapshot() {
        return new Snapshot(triggers);
    }

    /** Restores the trigger array from a previously captured snapshot. */
    public static void restore(Snapshot snapshot) {
        int[] source = snapshot.triggers();
        System.arraycopy(source, 0, triggers, 0, TRIGGER_COUNT);
    }
}
