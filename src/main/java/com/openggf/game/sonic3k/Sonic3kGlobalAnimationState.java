package com.openggf.game.sonic3k;

/**
 * Runtime-owned S3K animation words advanced by {@code ChangeRingFrame}.
 */
final class Sonic3kGlobalAnimationState {
    private static final int AIZ_VINE_ANGLE_STEP = 0x180;

    private int aizVineAngle;

    void advanceChangeRingFrame() {
        aizVineAngle = (aizVineAngle + AIZ_VINE_ANGLE_STEP) & 0xFFFF;
    }

    int aizVineAngleWord() {
        return aizVineAngle;
    }

    void restoreAizVineAngleWord(int value) {
        aizVineAngle = value & 0xFFFF;
    }
}
