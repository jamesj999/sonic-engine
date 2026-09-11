package com.openggf.game.sonic2;

import java.util.Arrays;

/**
 * Manages the ButtonVine_Trigger system used by button/vine/drawbridge objects.
 * <p>
 * ROM Reference: s2.asm ButtonVine_Trigger ($FFFFF7CA) - 16-byte array
 * <p>
 * Each entry is a byte with individual bits:
 * <ul>
 *   <li>Bit 0: Set by VineSwitch (Obj7F), Button (Obj47 with subtype bit 6 clear)</li>
 *   <li>Bit 7: Set by Button (Obj47 with subtype bit 6 set)</li>
 * </ul>
 * <p>
 * Consumers read the trigger in two ways:
 * <ul>
 *   <li>{@code btst #0,(a2,d0.w)} - Tests bit 0 specifically (MCZDrawbridge, MCZBridge, MTZLongPlatform)</li>
 *   <li>{@code tst.b (a2,d0.w)} - Tests if any bit is set (GenericPlatform Obj18 subtype)</li>
 * </ul>
 */
public class ButtonVineTriggerManager {
    /**
     * 16-entry trigger array (one byte per switch ID).
     * ROM: ButtonVine_Trigger at $FFFFF7CA
     */
    private static final int[] triggers = new int[16];

    private ButtonVineTriggerManager() {
        // Static utility class
    }

    /**
     * Sets or clears bit 0 of a trigger for the given switch ID.
     * ROM: bset/bclr #0,(ButtonVine_Trigger,d0.w)
     *
     * @param id    Switch ID (0-15, from subtype & 0x0F)
     * @param value true to set bit 0, false to clear bit 0
     */
    public static void setTrigger(int id, boolean value) {
        setBit(id, 0, value);
    }

    /**
     * Checks if bit 0 of a trigger is set.
     * ROM: btst #0,(ButtonVine_Trigger,d0.w)
     *
     * @param id Switch ID (0-15)
     * @return true if bit 0 is set
     */
    public static boolean getTrigger(int id) {
        return testBit(id, 0);
    }

    /**
     * Sets or clears a specific bit in the trigger byte.
     * ROM: bset/bclr d3,(a3) where d3 is 0 or 7
     *
     * @param id    Switch ID (0-15)
     * @param bit   Bit number (0 or 7)
     * @param value true to set, false to clear
     */
    public static void setBit(int id, int bit, boolean value) {
        if (id >= 0 && id < 16) {
            if (value) {
                triggers[id] |= (1 << bit);
            } else {
                triggers[id] &= ~(1 << bit);
            }
        }
    }

    /**
     * Tests a specific bit in the trigger byte.
     * ROM: btst d3,(a3)
     *
     * @param id  Switch ID (0-15)
     * @param bit Bit number (0 or 7)
     * @return true if the specified bit is set
     */
    public static boolean testBit(int id, int bit) {
        return id >= 0 && id < 16 && (triggers[id] & (1 << bit)) != 0;
    }

    /**
     * Tests if any bit in the trigger byte is set.
     * ROM: tst.b (a2,d0.w) - used by GenericPlatform (Obj18) subtypes
     *
     * @param id Switch ID (0-15)
     * @return true if any bit is set
     */
    public static boolean testAny(int id) {
        return id >= 0 && id < 16 && triggers[id] != 0;
    }

    /**
     * Resets all triggers to zero.
     * Called on level load to ensure clean state.
     */
    public static void reset() {
        Arrays.fill(triggers, 0);
    }

    /**
     * Immutable rewind snapshot of {@code ButtonVine_Trigger}. Defensively
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
        System.arraycopy(source, 0, triggers, 0, triggers.length);
    }
}
