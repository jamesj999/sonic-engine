package com.openggf.graphics.pipeline;

/**
 * Pure parameters for the centered-320 "safe-area" projection used to pillarbox /
 * center native-320 UI inside a wider viewport. At native width (320) pad=0, so
 * the safe-area ortho equals the scene ortho [0,320] — a no-op.
 */
public final class SafeAreaProjection {

    public static final int NATIVE_WIDTH = 320;

    private SafeAreaProjection() {
    }

    /** Horizontal padding (px) on each side: (width - 320) / 2, clamped at 0. */
    public static int pad(int width) {
        return Math.max(0, (width - NATIVE_WIDTH) / 2);
    }

    /** ortho2D left bound so native x=0 maps to screen x=pad. */
    public static float orthoLeft(int width) {
        return -pad(width);
    }

    /** ortho2D right bound so native x=320 maps to screen x=pad+320. */
    public static float orthoRight(int width) {
        return width - pad(width);
    }
}
