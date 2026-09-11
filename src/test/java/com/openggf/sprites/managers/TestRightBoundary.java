package com.openggf.sprites.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestRightBoundary {

    @Test
    void nativeStrictBoundaryIs0x128PastMaxX() {
        // strict (S3K / boss / end-of-level): maxX + width - 24, no +64
        assertEquals(1000 + 320 - 24, RightBoundary.compute(1000, 320, 24, 64, true));
    }

    @Test
    void nativeNormalBoundaryAddsRightExtra() {
        // normal: maxX + width - 24 + 64
        assertEquals(1000 + 320 - 24 + 64, RightBoundary.compute(1000, 320, 24, 64, false));
    }

    @Test
    void widescreenWidensBoundaryByWidthDelta() {
        assertEquals(1000 + 400 - 24, RightBoundary.compute(1000, 400, 24, 64, true));
        assertEquals(1000 + 400 - 24 + 64, RightBoundary.compute(1000, 400, 24, 64, false));
    }
}
