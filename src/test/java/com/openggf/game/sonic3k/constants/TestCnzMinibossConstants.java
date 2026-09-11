package com.openggf.game.sonic3k.constants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the CNZ Act 1 miniboss literal set against accidental drift.
 *
 * <p>ROM sources (all sonic3k.asm, S&K-side < 0x200000):
 * <ul>
 *   <li>line 144824: {@code move.w #$31E0,d0} — camera trigger X</li>
 *   <li>line 144832: {@code move.w #$1C0,(Camera_min_Y_pos).w} — arena min Y</li>
 *   <li>line 144834: {@code addi.w #$80,d0} — arena X span 0x31E0..0x3260</li>
 *   <li>line 144836: {@code move.w #$2B8,(Camera_max_Y_pos).w} — arena max Y</li>
 *   <li>line 144844: {@code moveq #$5D,d0} then {@code Load_PLC} — PLC id</li>
 *   <li>line 144888: {@code move.b #6,collision_property(a0)} — hit count</li>
 *   <li>line 144891: {@code move.w #$80,y_vel(a0)} — descent velocity</li>
 *   <li>line 144919: {@code move.w #$100,x_vel(a0)} — swing velocity magnitude</li>
 *   <li>line 144892: {@code move.w #$11F,$2E(a0)} — init wait</li>
 *   <li>line 144907: {@code move.w #$90,$2E(a0)} — go2 wait</li>
 *   <li>line 144920: {@code move.w #$9F,$2E(a0)} — go3 wait (swing duration)</li>
 *   <li>line 144937: {@code move.w #$13F,$2E(a0)} — direction-change wait</li>
 * </ul>
 */
class TestCnzMinibossConstants {

    @Test
    void plcIdMatchesRom() {
        assertEquals(0x5D, Sonic3kConstants.PLC_CNZ_MINIBOSS,
                "ROM sonic3k.asm:144844 reads `moveq #$5D,d0`");
    }

    @Test
    void arenaBounds() {
        assertEquals(0x31E0, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X,
                "ARENA_MIN_X: sonic3k.asm:144833 writes 0x31E0 to Camera_min_X_pos");
        assertEquals(0x3260, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X,
                "ARENA_MAX_X: sonic3k.asm:144834 `addi.w #$80,d0` yields 0x31E0+0x80=0x3260");
        assertEquals(0x01C0, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y,
                "ARENA_MIN_Y: sonic3k.asm:144832 writes 0x01C0 to Camera_min_Y_pos");
        assertEquals(0x02B8, Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y,
                "ARENA_MAX_Y: sonic3k.asm:144836 writes 0x02B8 to Camera_max_Y_pos");
    }

    @Test
    void stateMachineLiterals() {
        assertEquals(0x04,  Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT,
                "HIT_COUNT must model the real $45 boss counter, not collision_property(a0)");
        assertEquals(0x80,  Sonic3kConstants.CNZ_MINIBOSS_INIT_Y_VEL,
                "INIT_Y_VEL: sonic3k.asm:144891 `move.w #$80,y_vel(a0)` (descent)");
        assertEquals(0x100, Sonic3kConstants.CNZ_MINIBOSS_SWING_X_VEL,
                "SWING_X_VEL: sonic3k.asm:144919 `move.w #$100,x_vel(a0)` (swing magnitude)");
        assertEquals(0x11F, Sonic3kConstants.CNZ_MINIBOSS_INIT_WAIT,
                "INIT_WAIT: sonic3k.asm:144892 `move.w #$11F,$2E(a0)`");
        assertEquals(0x90,  Sonic3kConstants.CNZ_MINIBOSS_GO2_WAIT,
                "GO2_WAIT: sonic3k.asm:144907 `move.w #$90,$2E(a0)`");
        assertEquals(0x9F,  Sonic3kConstants.CNZ_MINIBOSS_SWING_WAIT,
                "SWING_WAIT: sonic3k.asm:144920 `move.w #$9F,$2E(a0)` (swing duration)");
        assertEquals(0x13F, Sonic3kConstants.CNZ_MINIBOSS_CHANGEDIR_WAIT,
                "CHANGEDIR_WAIT: sonic3k.asm:144937 `move.w #$13F,$2E(a0)`");
    }

    @Test
    void collisionMetadataAndRealHitCounterAreSeparateRomLiterals() throws Exception {
        assertEquals(0x06, intConstant("CNZ_MINIBOSS_COLLISION_PROPERTY"),
                "collision_property(a0) remains ROM value 6 for collision metadata");
        assertEquals(0x04, intConstant("CNZ_MINIBOSS_REAL_HITS"),
                "$45(a0) is the real four-hit CNZ miniboss damage counter");
    }

    @Test
    void arenaBoundsAreWellFormed() {
        assertTrue(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X
                        < Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X,
                "ARENA_MIN_X must be < ARENA_MAX_X (sonic3k.asm:144833-144834)");
        assertTrue(Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_Y
                        < Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_Y,
                "ARENA_MIN_Y must be < ARENA_MAX_Y (sonic3k.asm:144832,144836)");
        assertEquals(0x80,
                Sonic3kConstants.CNZ_MINIBOSS_ARENA_MAX_X
                        - Sonic3kConstants.CNZ_MINIBOSS_ARENA_MIN_X,
                "ARENA span must be 0x80: sonic3k.asm:144834 `addi.w #$80,d0` "
                        + "derives MAX_X from MIN_X; if either drifts, this breaks");
    }

    private static int intConstant(String name) throws Exception {
        return Sonic3kConstants.class.getField(name).getInt(null);
    }
}
