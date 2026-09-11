package com.openggf.control;

/**
 * Logical A/B/C action bits and conversion helpers for Mega Drive controller bytes.
 */
public final class InputActionMasks {
    public static final int ACTION_A = 0x01;
    public static final int ACTION_B = 0x02;
    public static final int ACTION_C = 0x04;
    public static final int ACTION_ALL = ACTION_A | ACTION_B | ACTION_C;

    private static final int MD_BUTTON_A = 0x40;
    private static final int MD_BUTTON_B = 0x10;
    private static final int MD_BUTTON_C = 0x20;

    private InputActionMasks() {
    }

    public static int sanitizeActionMask(int mask) {
        return mask & ACTION_ALL;
    }

    public static int toMegaDriveButtonBits(int actionMask) {
        int sanitized = sanitizeActionMask(actionMask);
        int mdBits = 0;
        if ((sanitized & ACTION_A) != 0) {
            mdBits |= MD_BUTTON_A;
        }
        if ((sanitized & ACTION_B) != 0) {
            mdBits |= MD_BUTTON_B;
        }
        if ((sanitized & ACTION_C) != 0) {
            mdBits |= MD_BUTTON_C;
        }
        return mdBits;
    }
}
