package com.openggf.game.sonic1;

import java.util.Arrays;

/**
 * Manages the f_switch state array from the Sonic 1 disassembly.
 * <p>
 * In the ROM, f_switch is a 16-byte RAM area where each byte represents
 * a switch state. Buttons (Object 0x32) set bits in this array, and
 * other objects (Chained Stompers, Glass Blocks, Platforms) read from it
 * to determine their behavior.
 * <p>
 * Each switch byte supports two independent flags via bit positions:
 * <ul>
 *   <li>Bit 0 (d3=0): Standard switch flag</li>
 *   <li>Bit 7 (d3=7): Alternate switch flag (when subtype bit 6 is set)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/32 Button.asm
 */
public final class Sonic1SwitchManager {

    private static final int SWITCH_COUNT = 16;

    private final byte[] switchState = new byte[SWITCH_COUNT];

    public Sonic1SwitchManager() {
    }

    /**
     * Set a bit in the switch state array.
     * Mirrors: bset d3,(a3) in the disassembly.
     *
     * @param switchIndex which switch (0-15), from subtype bits 0-3
     * @param bit         which bit to set (0 or 7)
     */
    public void setBit(int switchIndex, int bit) {
        if (switchIndex >= 0 && switchIndex < SWITCH_COUNT) {
            switchState[switchIndex] |= (byte) (1 << bit);
        }
    }

    /**
     * Clear a bit in the switch state array.
     * Mirrors: bclr d3,(a3) in the disassembly.
     *
     * @param switchIndex which switch (0-15), from subtype bits 0-3
     * @param bit         which bit to clear (0 or 7)
     */
    public void clearBit(int switchIndex, int bit) {
        if (switchIndex >= 0 && switchIndex < SWITCH_COUNT) {
            switchState[switchIndex] &= (byte) ~(1 << bit);
        }
    }

    /**
     * Test whether a switch byte is non-zero (any bit set).
     * Mirrors: tst.b (a3) in the disassembly.
     *
     * @param switchIndex which switch (0-15)
     * @return true if any bit is set for this switch
     */
    public boolean isPressed(int switchIndex) {
        if (switchIndex >= 0 && switchIndex < SWITCH_COUNT) {
            return switchState[switchIndex] != 0;
        }
        return false;
    }

    /**
     * Get the raw byte value for a switch index.
     *
     * @param switchIndex which switch (0-15)
     * @return the raw switch byte
     */
    public byte getRaw(int switchIndex) {
        if (switchIndex >= 0 && switchIndex < SWITCH_COUNT) {
            return switchState[switchIndex];
        }
        return 0;
    }

    public void resetState() {
        reset();
    }

    /**
     * Reset all switch states. Called on level load.
     */
    public void reset() {
        Arrays.fill(switchState, (byte) 0);
    }

    /**
     * Captures all sixteen ROM switch bytes for a rewind keyframe.
     */
    public Snapshot captureRewindState() {
        return new Snapshot(switchState);
    }

    /**
     * Restores all sixteen ROM switch bytes before the next object update.
     */
    public void restoreRewindState(Snapshot snapshot) {
        Arrays.fill(switchState, (byte) 0);
        if (snapshot == null) {
            return;
        }
        byte[] restored = snapshot.switchState();
        System.arraycopy(restored, 0, switchState, 0, Math.min(restored.length, switchState.length));
    }

    /** Immutable, defensively copied f_switch payload. */
    public record Snapshot(byte[] switchState) {
        public Snapshot {
            switchState = switchState == null
                    ? new byte[SWITCH_COUNT]
                    : Arrays.copyOf(switchState, SWITCH_COUNT);
        }

        @Override
        public byte[] switchState() {
            return switchState.clone();
        }
    }
}
