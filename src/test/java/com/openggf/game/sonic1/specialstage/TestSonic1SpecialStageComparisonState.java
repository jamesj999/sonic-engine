package com.openggf.game.sonic1.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1SpecialStageComparisonState {

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * Three passes with distinct per-field boolean codewords: a swapped
     * boolean mapping is undetectable in any pass where the two fields hold
     * equal values, so every field carries a distinct value-vector across
     * the passes (5 fields > 4 possible two-pass vectors => 3 passes).
     */
    @Test
    void captureMirrorsManagerFields() throws Exception {
        boolean[][] seeds = {
                // airborne, facingLeft, emerald, exitTriggered, finished
                {true, false, true, false, true},
                {true, true, false, false, false},
                {false, true, true, true, false},
        };
        for (boolean[] pass : seeds) {
            Sonic1SpecialStageManager manager = new Sonic1SpecialStageManager();
            set(manager, "sonicPosX", 0x12345678L);
            set(manager, "sonicPosY", 0x0ABCDEF0L);
            set(manager, "sonicVelX", 0x0123);
            set(manager, "sonicVelY", 0xFEDC);
            set(manager, "sonicInertia", 0x0456);
            set(manager, "sonicAirborne", pass[0]);
            set(manager, "sonicFacingLeft", pass[1]);
            set(manager, "ssAngle", 0x4000);
            set(manager, "ssRotate", 0x0080);
            set(manager, "bgAnimState", 6);
            set(manager, "ringsCollected", 23);
            set(manager, "emeraldCollected", pass[2]);
            set(manager, "exitTriggered", pass[3]);
            set(manager, "finished", pass[4]);
            set(manager, "currentStage", 3);

            Sonic1SpecialStageComparisonState s = manager.captureComparisonState();
            assertEquals(0x12345678L, s.sonicPosX());
            assertEquals(0x0ABCDEF0L, s.sonicPosY());
            assertEquals(0x0123, s.sonicVelX());
            assertEquals(0xFEDC, s.sonicVelY());
            assertEquals(0x0456, s.sonicInertia());
            assertEquals(pass[0], s.sonicAirborne());
            assertEquals(pass[1], s.sonicFacingLeft());
            assertEquals(0x4000, s.ssAngle());
            assertEquals(0x0080, s.ssRotate());
            assertEquals(6, s.bgAnimState());
            assertEquals(23, s.ringsCollected());
            assertEquals(pass[2], s.emeraldCollected());
            assertEquals(pass[3], s.exitTriggered());
            assertEquals(pass[4], s.finished());
            assertEquals(3, s.currentStage());
        }
    }

    @Test
    void captureIsPureRead() throws Exception {
        Sonic1SpecialStageManager manager = new Sonic1SpecialStageManager();
        set(manager, "ssAngle", 0x1234);
        assertEquals(manager.captureComparisonState(), manager.captureComparisonState());
    }
}
