package uk.co.jamesj999.sonic.graphics;

/**
 * Sprite priority buckets (0-7), matching Sonic 2's display lists.
 */
public final class RenderPriority {
    public static final int MIN = 0;
    public static final int MAX = 7;
    public static final int HIGH_START = 4;
    public static final int PLAYER_DEFAULT = 2;

    private RenderPriority() {
    }

    public static int clamp(int value) {
        if (value < MIN) {
            return MIN;
        }
        if (value > MAX) {
            return MAX;
        }
        return value;
    }

    public static boolean isHigh(int bucket) {
        return bucket >= HIGH_START;
    }
}
