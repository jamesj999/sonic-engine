package com.openggf.game.sonic2.bumpers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class TestCNZBumperManager {
    @Test
    void angleBounceForcesSurfaceWhenWordDeltaExceedsThreshold() {
        assertEquals(0x08, CNZBumperManager.resolveAngleBounceOutAngle(0xFD, 0x08));
    }

    @Test
    void angleBounceReflectsWhenWordDeltaIsWithinThreshold() {
        assertEquals(0x00, CNZBumperManager.resolveAngleBounceOutAngle(0x10, 0x08));
        assertEquals(0x0B, CNZBumperManager.resolveAngleBounceOutAngle(0x05, 0x08));
    }

    @Test
    void romWindowStartsAtCameraMinusEightAndExcludesOldBehindMargin() {
        int cameraX = 0x2015;

        assertEquals(0x200D, CNZBumperManager.romWindowStartForCamera(cameraX));
        assertEquals(0x215D, CNZBumperManager.romWindowEndExclusiveForCamera(cameraX));
        assertFalse(0x1EC0 >= CNZBumperManager.romWindowStartForCamera(cameraX)
                && 0x1EC0 < CNZBumperManager.romWindowEndExclusiveForCamera(cameraX));
    }
}
