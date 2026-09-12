package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the production S3K invincibility star orbit tables, animation tables,
 * and trailing-frame calculation. No ROM or OpenGL required.
 */
public class TestSonic3kInvincibilityStars {

    @Test
    public void trailingFramesBehind_matchesDisassemblyFormula() {
        // ROM: children have $36 values 1, 2, 3 (parent is slot 0, overwritten).
        // Formula: (starIndex+1) * 12 bytes / 4 bytes per entry = (starIndex+1) * 3 frames.
        // No child ever overlaps the parent position (0 frames behind).
        assertEquals(3, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(0));
        assertEquals(6, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(1));
        assertEquals(9, Sonic3kInvincibilityStarsObjectInstance.trailingFramesBehind(2));
    }

    @Test
    public void childCount_is3NotIncludingParent() {
        // ROM creates 4 object slots but slot 0 becomes the parent (loc_18868).
        // Only slots 1-3 are real children using Obj_188E8.
        assertEquals(3, Sonic3kInvincibilityStarsObjectInstance.CHILD_PRIMARY_ANIMS.length);
        assertEquals(3, Sonic3kInvincibilityStarsObjectInstance.CHILD_SECONDARY_ANIMS.length);
    }

    @Test
    public void orbitTable_has32Entries() {
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        assertEquals(32, table.length);
    }

    @Test
    public void orbitTable_entriesAreXYPairs() {
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        for (int[] entry : table) {
            assertEquals(2, entry.length);
        }
    }

    @Test
    public void orbitTable_isCircular() {
        int[][] table = Sonic3kInvincibilityStarsObjectInstance.S3K_ORBIT_OFFSETS;
        assertTrue(table[0][0] > 0);
        assertTrue(table[16][0] < 0);
        assertEquals(0, table[0][1]);
        assertEquals(0, table[16][1]);
    }

    @Test
    public void parentAnimationTable_hasValidFrameIndices() {
        int[] parentAnim = Sonic3kInvincibilityStarsObjectInstance.PARENT_ANIM;
        for (int frame : parentAnim) {
            assertTrue(frame >= 0 && frame <= 8, "Frame index must be 0-8 (9 mapping frames)");
        }
    }

    @Test
    public void childAnimationTables_haveValidFrameIndices() {
        int[][] childAnims = Sonic3kInvincibilityStarsObjectInstance.CHILD_PRIMARY_ANIMS;
        for (int[] anim : childAnims) {
            for (int frame : anim) {
                assertTrue(frame >= 0 && frame <= 8, "Frame index must be 0-8");
            }
        }
    }

}

