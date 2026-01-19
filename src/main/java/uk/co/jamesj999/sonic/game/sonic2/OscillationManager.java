package uk.co.jamesj999.sonic.game.sonic2;

/**
 * Tracks the global oscillating values used by multiple Sonic 2 objects.
 * Ported from OscillateNumInit/OscillateNumDo in the disassembly.
 */
public final class OscillationManager {
    private static final int OSC_COUNT = 16;

    // Osc_Data (control + 16 value/delta pairs)
    private static final int INITIAL_CONTROL = 0x007D;
    private static final int[] INITIAL_VALUES = {
            0x0080, 0x0080, 0x0080, 0x0080,
            0x0080, 0x0080, 0x0080, 0x0080,
            0x0080, 0x3848, 0x2080, 0x3080,
            0x5080, 0x7080, 0x0080, 0x4000
    };
    private static final int[] INITIAL_DELTAS = {
            0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x0000, 0x0000, 0x0000,
            0x0000, 0x00EE, 0x00B4, 0x010E,
            0x01C2, 0x0276, 0x0000, 0x00FE
    };

    // Osc_Data2 (speed, limit)
    private static final int[] SPEEDS = {
            2, 2, 2, 2,
            4, 8, 8, 4,
            2, 2, 2, 3,
            5, 7, 2, 2
    };
    private static final int[] LIMITS = {
            0x10, 0x18, 0x20, 0x30,
            0x20, 0x08, 0x40, 0x40,
            0x38, 0x38, 0x20, 0x30,
            0x50, 0x70, 0x40, 0x40
    };

    private static final int[] values = new int[OSC_COUNT];
    private static final int[] deltas = new int[OSC_COUNT];
    private static int control = INITIAL_CONTROL;
    private static int lastFrame = Integer.MIN_VALUE;

    static {
        reset();
    }

    private OscillationManager() {
    }

    public static void reset() {
        control = INITIAL_CONTROL;
        for (int i = 0; i < OSC_COUNT; i++) {
            values[i] = INITIAL_VALUES[i] & 0xFFFF;
            deltas[i] = INITIAL_DELTAS[i] & 0xFFFF;
        }
        lastFrame = Integer.MIN_VALUE;
    }

    public static void update(int frameCounter) {
        if (frameCounter == lastFrame) {
            return;
        }
        lastFrame = frameCounter;

        for (int i = 0; i < OSC_COUNT; i++) {
            int bit = OSC_COUNT - 1 - i;
            boolean decreasing = (control & (1 << bit)) != 0;
            int speed = SPEEDS[i];
            int limit = LIMITS[i];

            int value = values[i];
            int delta = deltas[i];

            if (!decreasing) {
                delta = (delta + speed) & 0xFFFF;
                value = (value + delta) & 0xFFFF;
                int highByte = (value >> 8) & 0xFF;
                if (highByte >= limit) {
                    control |= (1 << bit);
                }
            } else {
                delta = (delta - speed) & 0xFFFF;
                value = (value + delta) & 0xFFFF;
                int highByte = (value >> 8) & 0xFF;
                if (highByte < limit) {
                    control &= ~(1 << bit);
                }
            }

            values[i] = value;
            deltas[i] = delta;
        }
    }

    /**
     * Returns the byte at the given offset into Oscillating_Data.
     * Offsets follow the ROM layout: value word then delta word per oscillator.
     */
    public static int getByte(int offset) {
        if (offset < 0 || offset >= OSC_COUNT * 4) {
            return 0;
        }
        int index = offset / 4;
        int within = offset % 4;
        int word = (within < 2) ? values[index] : deltas[index];
        return ((within & 1) == 0) ? ((word >> 8) & 0xFF) : (word & 0xFF);
    }
}
