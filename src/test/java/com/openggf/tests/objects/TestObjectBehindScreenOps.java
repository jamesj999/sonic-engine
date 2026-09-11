package com.openggf.tests.objects;

import com.openggf.level.objects.ObjectBehindScreenOps;
import com.openggf.level.objects.ObjectRangeOps;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link ObjectBehindScreenOps} to {@code Obj_DeleteBehindScreen}
 * (docs/s2disasm/s2.asm:72983-72992), including the two properties that make it
 * a different predicate from {@link ObjectRangeOps} rather than a tighter one:
 * it carries no constant, and it is one-sided.
 */
class TestObjectBehindScreenOps {

    /**
     * ROM: {@code sub.w (Camera_X_pos_coarse).w,d0 / bmi}. The boundary sits
     * exactly at the coarse camera, because the routine has no other term.
     */
    @Test
    void theBoundaryIsTheCoarseCameraItself() {
        int camera = 0x1000;
        assertFalse(ObjectBehindScreenOps.behindScreenX(0x0F80, camera),
                "the coarse camera block itself is not behind the screen");
        assertTrue(ObjectBehindScreenOps.behindScreenX(0x0F00, camera),
                "one $80 block behind the coarse camera is behind the screen");
        assertTrue(ObjectBehindScreenOps.behindScreenX(0x0000, camera));
    }

    /**
     * Records a measured property of this routine, not an assumption: the
     * object's {@code andi.w #$FF80,d0} is <strong>unobservable</strong> here.
     * {@code Camera_X_pos_coarse} is itself $80-aligned, so
     * {@code (x & $FF80) >= coarse} and {@code x >= coarse} always agree — the
     * quantisation only changes a verdict when there is a non-zero bound to
     * clear, as in {@link ObjectRangeOps}. Pinned so that a future change making
     * the camera term unaligned is caught rather than silently changing the
     * predicate.
     */
    @Test
    void objectQuantisationCannotChangeThisVerdict() {
        int camera = 0x1000;
        int coarseCamera = ObjectRangeOps.coarseCameraX(camera);
        for (int x = coarseCamera - 0x100; x < coarseCamera + 0x100; x++) {
            assertEquals(x < coarseCamera, ObjectBehindScreenOps.behindScreenX(x, camera),
                    "quantisation became observable at x=" + x);
        }
    }

    /**
     * There is no bound in the routine, so nothing ahead of the camera is ever
     * deleted by it — for every block up to the sign wrap. This is the property
     * a margin literal cannot express: {@link ObjectRangeOps} deletes the same
     * object, and this predicate does not.
     */
    @Test
    void nothingAheadOfTheCameraIsBehindTheScreen() {
        int camera = 0x1000;
        for (int block = 0; block < 0x100; block++) {
            int x = 0x0F80 + block * 0x80;
            assertFalse(ObjectBehindScreenOps.behindScreenX(x, camera),
                    "deleted an object $" + Integer.toHexString(block) + " blocks ahead");
        }
        // Same input, the other predicate: 640 is a bound, and this one has none.
        int wellAhead = 0x0F80 + 0x2000;   // 8192 ahead: past 640, far short of the wrap
        assertTrue(ObjectRangeOps.outOfRangeX(wellAhead, camera));
        assertFalse(ObjectBehindScreenOps.behindScreenX(wellAhead, camera));
    }

    /**
     * ROM: {@code bmi} tests bit 15 of a 16-bit register, so the verdict wraps
     * at a $8000 separation. Pinned because it is ROM behaviour, not an edge to
     * be smoothed away by wider Java arithmetic.
     */
    @Test
    void theSignTestWrapsAt16Bits() {
        int camera = 0x1000;                                  // coarse = 0x0F80
        assertFalse(ObjectBehindScreenOps.behindScreenX(0x0F80 + 0x7F80, camera));
        assertTrue(ObjectBehindScreenOps.behindScreenX(0x0F80 + 0x8000, camera));
    }
}
