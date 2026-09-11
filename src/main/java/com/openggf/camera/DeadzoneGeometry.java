package com.openggf.camera;

import com.openggf.configuration.DeadzoneMode;

/**
 * Pure geometry for the camera horizontal scroll deadzone and focus/respawn
 * snap, generalized from the ROM native relationship (width 320: band [144,160],
 * snap/right-edge at screen-center 160). At native every value reproduces the ROM
 * constants exactly. The band is right-edge-anchored at screen-center (rightEdge),
 * extending bandWidth to the left; PROPORTIONAL widens it leftward with width.
 */
public final class DeadzoneGeometry {

    private static final int NATIVE_WIDTH = 320;
    private static final int NATIVE_BAND_WIDTH = 16;

    private DeadzoneGeometry() {
    }

    /** Right edge of the deadzone = screen centre; also the focus/respawn snap rest point. */
    public static int rightEdge(int width) {
        return width / 2;
    }

    public static int bandWidth(int width, DeadzoneMode mode) {
        if (mode == DeadzoneMode.CENTER_SCALED) {
            return NATIVE_BAND_WIDTH;
        }
        return Math.round((float) NATIVE_BAND_WIDTH * width / NATIVE_WIDTH);
    }

    public static int leftEdge(int width, DeadzoneMode mode) {
        return rightEdge(width) - bandWidth(width, mode);
    }
}
