package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies S2 invincibility stars data tables and constants against Obj35 disassembly.
 * In same package as InvincibilityStarsObjectInstance for package-private access.
 */
public class TestInvincibilityStarsS2Data {

    // â”€â”€ Orbit offset table (byte_1DB42) â”€â”€

    @Test
    public void orbitTableMatchesDisassemblyByte1DB42() {
        // Each dc.w: high byte = X (signed), low byte = Y (signed)
        int[][] expected = {
                // dc.w $F00, $F03, $E06, $D08, $B0B, $80D, $60E, $30F
                { 15, 0 }, { 15, 3 }, { 14, 6 }, { 13, 8 },
                { 11, 11 }, { 8, 13 }, { 6, 14 }, { 3, 15 },
                // dc.w $10, -$3F1, -$6F2, -$8F3, -$BF5, -$DF8, -$EFA, -$FFD
                { 0, 16 }, { -4, 15 }, { -7, 14 }, { -9, 13 },
                { -12, 11 }, { -14, 8 }, { -15, 6 }, { -16, 3 },
                // dc.w $F000, -$F04, -$E07, -$D09, -$B0C, -$80E, -$60F, -$310
                { -16, 0 }, { -16, -4 }, { -15, -7 }, { -14, -9 },
                { -12, -12 }, { -9, -14 }, { -7, -15 }, { -4, -16 },
                // dc.w -$10, $3F0, $6F1, $8F2, $BF4, $DF7, $EF9, $FFC
                { -1, -16 }, { 3, -16 }, { 6, -15 }, { 8, -14 },
                { 11, -12 }, { 13, -9 }, { 14, -7 }, { 15, -4 }
        };
        assertEquals(32, InvincibilityStarsObjectInstance.ORBIT_OFFSETS.length);
        for (int i = 0; i < 32; i++) {
            assertArrayEquals(expected[i], InvincibilityStarsObjectInstance.ORBIT_OFFSETS[i], "Entry " + i);
        }
    }

    @Test
    public void orbitTableEntry24HasRomAsymmetry() {
        // dc.w -$10 = 0xFFF0: high byte $FF = -1, low byte $F0 = -16
        assertEquals(-1, InvincibilityStarsObjectInstance.ORBIT_OFFSETS[24][0]);
        assertEquals(-16, InvincibilityStarsObjectInstance.ORBIT_OFFSETS[24][1]);
    }

    // â”€â”€ Initial angles â”€â”€

    @Test
    public void initialAnglesMatchDisassembly() {
        // Star 0: move.b #4,objoff_34(a0) after init loop
        // Stars 1-3: from off_1D992 word high bytes ($00, $16, $2C)
        assertArrayEquals(new int[] { 4, 0x00, 0x16, 0x2C },
                InvincibilityStarsObjectInstance.INITIAL_ANGLES);
    }

    // â”€â”€ Rotation speeds â”€â”€

    @Test
    public void parentRotationSpeedMatchesState2() {
        // State 2: moveq #$12,d0
        assertEquals(0x12, InvincibilityStarsObjectInstance.PARENT_ROTATION_BYTES);
    }

    @Test
    public void trailRotationSpeedMatchesState4() {
        // State 4: moveq #2,d0
        assertEquals(0x02, InvincibilityStarsObjectInstance.TRAIL_ROTATION_BYTES);
    }

    // â”€â”€ Position history lag â”€â”€

    @Test
    public void trailLagFramesMatchDisassembly() {
        // starIndex * 12 bytes / 4 bytes per entry = starIndex * 3 frames
        assertArrayEquals(new int[] { 0, 3, 6, 9 },
                InvincibilityStarsObjectInstance.TRAIL_LAG_FRAMES);
    }

    // â”€â”€ Animation tables â”€â”€

    @Test
    public void parentAnimationMatchesByte1DB82() {
        // byte_1DB82: dc.b 8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6, $FF
        int[] expected = { 8, 5, 7, 6, 6, 7, 5, 8, 6, 7, 7, 6 };
        assertArrayEquals(expected, InvincibilityStarsObjectInstance.PARENT_ANIM);
    }

    @Test
    public void trail1AnimationMatchesByte1DB8F() {
        // byte_1DB8F primary (10 frames): 8,7,6,5,4,3,4,5,6,7,$FF
        assertArrayEquals(new int[] { 8, 7, 6, 5, 4, 3, 4, 5, 6, 7 },
                InvincibilityStarsObjectInstance.TRAIL_PRIMARY[0]);
        // byte_1DB8F secondary (offset $0B): 3,4,5,6,7,8,7,6,5,4
        assertArrayEquals(new int[] { 3, 4, 5, 6, 7, 8, 7, 6, 5, 4 },
                InvincibilityStarsObjectInstance.TRAIL_SECONDARY[0]);
    }

    @Test
    public void trail2AnimationMatchesByte1DBA4() {
        // byte_1DBA4 primary (12 frames): 8,7,6,5,4,3,2,3,4,5,6,7,$FF
        assertArrayEquals(new int[] { 8, 7, 6, 5, 4, 3, 2, 3, 4, 5, 6, 7 },
                InvincibilityStarsObjectInstance.TRAIL_PRIMARY[1]);
        // byte_1DBA4 secondary (offset $0D): 2,3,4,5,6,7,8,7,6,5,4,3
        assertArrayEquals(new int[] { 2, 3, 4, 5, 6, 7, 8, 7, 6, 5, 4, 3 },
                InvincibilityStarsObjectInstance.TRAIL_SECONDARY[1]);
    }

    @Test
    public void trail3AnimationMatchesByte1DBBD() {
        // byte_1DBBD primary (12 frames): 7,6,5,4,3,2,1,2,3,4,5,6,$FF
        assertArrayEquals(new int[] { 7, 6, 5, 4, 3, 2, 1, 2, 3, 4, 5, 6 },
                InvincibilityStarsObjectInstance.TRAIL_PRIMARY[2]);
        // byte_1DBBD secondary (offset $0D): 1,2,3,4,5,6,7,6,5,4,3,2
        assertArrayEquals(new int[] { 1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2 },
                InvincibilityStarsObjectInstance.TRAIL_SECONDARY[2]);
    }

    @Test
    public void primaryAndSecondaryAnimTablesHaveSameLength() {
        for (int i = 0; i < 3; i++) {
            assertEquals(InvincibilityStarsObjectInstance.TRAIL_PRIMARY[i].length, InvincibilityStarsObjectInstance.TRAIL_SECONDARY[i].length, "Star " + (i + 1) + " primary/secondary length mismatch");
        }
    }
}


