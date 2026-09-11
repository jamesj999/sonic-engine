package com.openggf.tests.objects;

import com.openggf.level.objects.ObjectRangeOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ObjectRangeOps} to the ROM comparison it models, including the
 * three properties that a symmetric pixel-distance margin cannot express:
 * $80-block granularity, unsigned (asymmetric) comparison, and X-axis only.
 *
 * <p>Reference: Sonic 1 {@code out_of_range} (docs/s1disasm/Macros.asm:278-293)
 * and Sonic 2 {@code MarkObjGone} (docs/s2disasm/s2.asm:30215-30226), with
 * {@code Camera_X_pos_coarse} from s2.asm:33033-33036.
 */
class TestObjectRangeOps {

    /** ROM: Sonic 1 #128+320+192, Sonic 2 #$80+384+$80. */
    @Test
    void boundIsTheRomConstant() {
        assertEquals(640, ObjectRangeOps.OUT_OF_RANGE_BOUND);
        assertEquals(128 + 320 + 192, ObjectRangeOps.OUT_OF_RANGE_BOUND);
        assertEquals(0x80 + 384 + 0x80, ObjectRangeOps.OUT_OF_RANGE_BOUND);
    }

    /** ROM: move.w camera,d1 / subi.w #128,d1 / andi.w #$FF80,d1. */
    @Test
    void coarseCameraSubtractsABlockThenQuantises() {
        assertEquals(0x0000, ObjectRangeOps.coarseCameraX(0x0080));
        assertEquals(0x0000, ObjectRangeOps.coarseCameraX(0x00FF));
        assertEquals(0x0080, ObjectRangeOps.coarseCameraX(0x0100));
        // Sub-block camera movement does not move the coarse term at all.
        assertEquals(ObjectRangeOps.coarseCameraX(0x0100),
                ObjectRangeOps.coarseCameraX(0x017F));
    }

    /**
     * Granularity: the ROM quantises the object too, so a pixel step inside a
     * block cannot change the verdict. A pixel-distance margin would.
     */
    @Test
    void verdictIsConstantWithinA128PixelBlock() {
        int camera = 0x1000;
        int blockStart = 0x1000 + 640;
        for (int x = blockStart; x < blockStart + 0x80; x++) {
            assertEquals(ObjectRangeOps.outOfRangeX(blockStart, camera),
                    ObjectRangeOps.outOfRangeX(x, camera),
                    "verdict changed inside a $80 block at x=" + x);
        }
    }

    /**
     * Asymmetry: the comparison is unsigned, so anything left of the coarse
     * camera wraps and is out of range immediately. A symmetric band would keep
     * it alive for the same distance on both sides.
     */
    @Test
    void leftOfTheCoarseCameraIsImmediatelyOutOfRange() {
        int camera = 0x1000;
        assertFalse(ObjectRangeOps.outOfRangeX(0x0F80, camera),
                "the coarse camera block itself is in range");
        assertTrue(ObjectRangeOps.outOfRangeX(0x0F00, camera),
                "one block left of the coarse camera wraps unsigned");
        assertTrue(ObjectRangeOps.outOfRangeX(0x0000, camera));
    }

    /** The far edge: in range up to and including the bound, out beyond it. */
    @Test
    void farEdgeIsTheBoundInclusive() {
        int camera = 0x1080;                       // coarse camera = 0x1000
        assertEquals(0x1000, ObjectRangeOps.coarseCameraX(camera));
        assertFalse(ObjectRangeOps.outOfRangeX(0x1000 + 640, camera));
        assertTrue(ObjectRangeOps.outOfRangeX(0x1000 + 640 + 0x80, camera));
    }

    /** Y is not an input: the ROM test reads the X axis only. */
    @Test
    void thereIsNoYTerm() {
        // Expressed structurally: the only inputs are object x and camera x.
        assertFalse(ObjectRangeOps.outOfRangeX(0x1000, 0x1000));
        assertTrue(ObjectRangeOps.outOfRangeX(0x1000 + 1024, 0x1000));
    }
}
