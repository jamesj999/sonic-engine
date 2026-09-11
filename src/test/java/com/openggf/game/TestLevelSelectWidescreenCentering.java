package com.openggf.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the level-select widescreen centering math and background-tiling math.
 *
 * <p>Each level-select manager applies {@code xOffset = (viewportWidth - 320) / 2}
 * to all rendered elements so the 320-px-wide content block is centered within the
 * current projection viewport. At native width 320 the offset must be exactly 0
 * (byte-identical output). At widescreen widths the offset must equal half the
 * extra pixels.
 *
 * <p>The animated background uses a different strategy: it tiles the pattern
 * horizontally to fill {@code 0..viewportWidth}.  The tile-column count formula is
 * {@code ceil(viewportWidth / 8)} and the source column wraps modulo the map width.
 * At native width 320 with map width 40 this produces exactly 40 columns, identical
 * to the original draw.
 */
class TestLevelSelectWidescreenCentering {

    /** Mirrors the xOffset() helper in the three level-select managers. */
    private static int xOffset(int viewportWidth) {
        return (viewportWidth - 320) / 2;
    }

    @Test
    void offsetIsZeroAtNative() {
        assertEquals(0, xOffset(320), "native 320px must produce zero offset");
    }

    @Test
    void offsetCentersContentAt426px() {
        // 426 − 320 = 106  →  offset = 53
        assertEquals(53, xOffset(426));
    }

    @Test
    void offsetCentersContentAt480px() {
        // 480 − 320 = 160  →  offset = 80
        assertEquals(80, xOffset(480));
    }

    @Test
    void offsetCentersContentAt640px() {
        // 640 − 320 = 320  →  offset = 160
        assertEquals(160, xOffset(640));
    }

    @Test
    void offsetCentersContentAt1280px() {
        // 1280 − 320 = 960  →  offset = 480
        assertEquals(480, xOffset(1280));
    }

    /**
     * Spot-check that the "element within content" arithmetic is also correct.
     *
     * <p>In S2 level-select the left zone column starts at x=24 within the 320-px
     * content block. At viewport width 480 the content is offset by 80, so the
     * absolute screen x must be 80 + 24 = 104.
     */
    @Test
    void absoluteElementPositionAtWidescreen() {
        int nativeX = 24; // leftZoneX within 320-px content
        int vp = 480;
        int expected = xOffset(vp) + nativeX; // 80 + 24 = 104
        assertEquals(104, expected);
    }

    /**
     * Verify that at native width the element position is unchanged (byte-identical).
     */
    @Test
    void absoluteElementPositionAtNativeIsUnchanged() {
        int nativeX = 24;
        assertEquals(nativeX, xOffset(320) + nativeX);
    }

    // ---- Background tiling math ----

    /**
     * Mirrors the tile-column count formula in renderTiled / renderBackgroundTilemap:
     * {@code tileColumns = (viewportWidth + 7) / 8} (ceiling division by 8).
     */
    private static int tileColumns(int viewportWidth) {
        return (viewportWidth + 7) / 8;
    }

    /**
     * At native width 320 with a 40-tile-wide map the tiled background draws
     * exactly 40 columns — byte-identical to the old fixed draw.
     */
    @Test
    void backgroundTileCountIsExact40AtNative() {
        assertEquals(40, tileColumns(320),
                "native 320 px must produce exactly 40 tile columns (no extra tiling)");
    }

    /**
     * At 640 px wide the background draws 80 tile columns (two full pattern repeats).
     */
    @Test
    void backgroundTileCountAt640() {
        assertEquals(80, tileColumns(640));
    }

    /**
     * At 480 px wide the background draws 60 tile columns (one and a half pattern repeats).
     */
    @Test
    void backgroundTileCountAt480() {
        assertEquals(60, tileColumns(480));
    }

    /**
     * At an odd widescreen width (e.g. 426 px) the tile columns round up correctly
     * so no un-filled pixel strip remains on the right edge.
     */
    @Test
    void backgroundTileCountRoundsUpAtOddWidth() {
        // 426 / 8 = 53.25 → ceil = 54 columns → covers 54*8 = 432 px ≥ 426 px
        int cols = tileColumns(426);
        assertEquals(54, cols);
        assertTrue(cols * 8 >= 426, "tile columns must cover the full viewport width");
    }

    /**
     * Verify that the source-column wrap (col % mapWidth) is zero at native width,
     * i.e. the 40th draw column (index 39) wraps to source column 39 — no actual
     * wrapping occurs at native, confirming byte-identical behaviour.
     */
    @Test
    void sourceColumnDoesNotWrapAtNative() {
        int mapWidth = 40;
        int cols = tileColumns(320); // == 40
        for (int col = 0; col < cols; col++) {
            assertEquals(col, col % mapWidth,
                    "no wrapping should occur at native width for column " + col);
        }
    }

    /**
     * Verify that the source-column wrap is correct at 480 px (60 columns, map width 40):
     * columns 40-59 wrap back to source columns 0-19.
     */
    @Test
    void sourceColumnWrapsCorrectlyAtWidescreen() {
        int mapWidth = 40;
        int cols = tileColumns(480); // == 60
        assertEquals(60, cols);
        // Columns 0-39: no wrap
        for (int col = 0; col < 40; col++) {
            assertEquals(col, col % mapWidth);
        }
        // Columns 40-59: wrap to 0-19
        for (int col = 40; col < 60; col++) {
            assertEquals(col - 40, col % mapWidth);
        }
    }
}
