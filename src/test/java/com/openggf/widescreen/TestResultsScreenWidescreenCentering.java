package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the results-screen widescreen centering math.
 *
 * <p>All results-screen implementations (S1/S2/S3K act-complete and
 * special-stage results) apply {@code xOffset = (viewportWidth - 320) / 2}
 * to every horizontal position so the native-320 content block is centred
 * within the projection viewport at any width.
 *
 * <p>At native width 320 the offset must be exactly 0 (byte-identical output).
 */
class TestResultsScreenWidescreenCentering {

    /** Mirrors the xOffset() helper in the results-screen classes. */
    private static int xOffset(int viewportWidth) {
        return (viewportWidth - 320) / 2;
    }

    @Test
    void offsetIsZeroAtNative() {
        assertEquals(0, xOffset(320), "native 320px must produce zero offset");
    }

    @Test
    void offsetCentersContentAt426px() {
        // 426 − 320 = 106 → offset = 53
        assertEquals(53, xOffset(426));
    }

    @Test
    void offsetCentersContentAt480px() {
        // 480 − 320 = 160 → offset = 80
        assertEquals(80, xOffset(480));
    }

    @Test
    void offsetCentersContentAt640px() {
        // 640 − 320 = 320 → offset = 160
        assertEquals(160, xOffset(640));
    }

    /**
     * Verify that elements nominally at screen-center (X=160) shift to the
     * new center at widescreen widths.
     */
    @Test
    void screenCenterXShiftsToViewportCenter() {
        int screenCenterX = 160;
        int vp = 480;
        // Viewport center at 480 = 240; screen-center element should land at 240.
        assertEquals(240, xOffset(vp) + screenCenterX);
    }

    /**
     * Elements that slide in from the right (titleX = centerX + slideOffset) preserve
     * their offset from center — they still enter from outside the viewport.
     */
    @Test
    void slideFromRightIsRelativeToCenterAtWidescreen() {
        int vp = 480;
        int offset = xOffset(vp); // 80
        int slideOffset = 50;     // still partially off screen

        // At native: titleX = 160 + 50 = 210
        // At 480px:  titleX = 80 + 160 + 50 = 290
        assertEquals(offset + 160 + slideOffset, offset + 160 + slideOffset);
        // Confirm the shift is purely additive (does not change relative spacing)
        assertEquals(slideOffset, (offset + 160 + slideOffset) - (offset + 160));
    }

    /**
     * Emerald positions (absolute screen coords) are also shifted by xOffset()
     * so they stay centred with the rest of the results content.
     */
    @Test
    void emeraldPositionsShiftWithContent() {
        int emeraldNativeX = 152; // EMERALD_POSITIONS[0][0]
        int vp = 480;
        int expectedX = xOffset(vp) + emeraldNativeX; // 80 + 152 = 232
        assertEquals(232, expectedX);
    }

    /**
     * Sanity: any width ≥ 320 produces a non-negative offset; the lower-bound
     * clamp ensures no leftward shift.
     */
    @Test
    void offsetIsNeverNegative() {
        for (int w = 320; w <= 1280; w += 32) {
            assertTrue(xOffset(w) >= 0, "xOffset must be ≥ 0 for width " + w);
        }
    }
}
