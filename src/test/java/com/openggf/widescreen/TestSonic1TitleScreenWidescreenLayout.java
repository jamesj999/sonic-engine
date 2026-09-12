package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import org.junit.jupiter.api.Test;

/**
 * Tests the production Sonic 1 title-screen background tile-column calculation
 * for native parity and widescreen coverage.
 */
class TestSonic1TitleScreenWidescreenLayout {

    /**
     * At native 320 the tile-column count must equal 42 — matching the original
     * hard-coded literal (byte-identical background draw).
     *
     * <p>Formula: {@code (viewportWidth + 7) / 8 + 2}
     * At 320: {@code (320 + 7) / 8 + 2 = 40 + 2 = 42}.
     */
    @Test
    void bgTileColumns_atNative320_is42() {
        assertEquals(42, Sonic1TitleScreenManager.bgTileColumns(320),
                "bgTileColumns(320) must equal 42 — byte-identical to original literal");
    }

    @Test
    void bgTileColumns_at426_coversFullWidth() {
        int cols = Sonic1TitleScreenManager.bgTileColumns(426);
        assertTrue(cols * 8 >= 426 + 7);
    }

    /**
     * Verify the exact formula value at 480 px:
     * {@code (480 + 7) / 8 + 2 = 487 / 8 + 2 = 60 + 2 = 62}.
     */
    @Test
    void bgTileColumns_at480_is62() {
        assertEquals(62, Sonic1TitleScreenManager.bgTileColumns(480));
    }

    /**
     * Verify the exact formula value at 640 px:
     * {@code (640 + 7) / 8 + 2 = 647 / 8 + 2 = 80 + 2 = 82}.
     */
    @Test
    void bgTileColumns_at640_is82() {
        assertEquals(82, Sonic1TitleScreenManager.bgTileColumns(640));
    }

    /**
     * Verify that tile columns are always greater than zero (no empty draw at any width).
     */
    @Test
    void bgTileColumns_isPositiveForAllStandardWidths() {
        int[] widths = {320, 384, 426, 480, 512, 640};
        for (int w : widths) {
            assertTrue(Sonic1TitleScreenManager.bgTileColumns(w) > 0,
                    "bgTileColumns must be positive for width " + w);
        }
    }
}
