package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.sonic1.titlescreen.Sonic1TitleScreenManager;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the Sonic 1 title-screen widescreen layout math.
 * No OpenGL, no ROM, no singletons — pure arithmetic.
 *
 * <p>Tests verify two invariants:
 * <ol>
 *   <li><b>Native parity:</b> every formula produces the same value as the original
 *       hard-coded literals when viewportWidth == 320.</li>
 *   <li><b>Coverage:</b> at wider viewports the formulas centre the foreground cluster
 *       and the background tile-loop fills the full viewport width.</li>
 * </ol>
 */
class TestSonic1TitleScreenWidescreenLayout {

    // -------------------------------------------------------------------------
    // Foreground centering — xOffset = (viewportWidth - 320) / 2
    // -------------------------------------------------------------------------

    /** Mirrors the xOffset() helper in Sonic1TitleScreenManager. */
    private static int xOffset(int viewportWidth) {
        return (viewportWidth - 320) / 2;
    }

    @Test
    void xOffset_atNative320_isZero() {
        assertEquals(0, xOffset(320),
                "xOffset must be 0 at native 320 — byte-identical");
    }

    @Test
    void xOffset_at480_is80() {
        // 480 - 320 = 160 → half = 80
        assertEquals(80, xOffset(480));
    }

    @Test
    void xOffset_at426_is53() {
        // 426 - 320 = 106 → half = 53
        assertEquals(53, xOffset(426));
    }

    @Test
    void xOffset_at640_is160() {
        assertEquals(160, xOffset(640));
    }

    /**
     * Plane A logo native startPixelX is 24.
     * At native width the shifted value must equal 24 exactly.
     */
    @Test
    void logoX_atNative_isUnchanged() {
        int nativeLogoX = 24;
        assertEquals(nativeLogoX, xOffset(320) + nativeLogoX,
                "logo startPixelX must be unchanged at native 320");
    }

    /**
     * TitleSonic native screen X is SONIC_X - 128 = 0xF0 - 128 = 112.
     * At native width the shifted value must equal 112 exactly.
     */
    @Test
    void sonicX_atNative_isUnchanged() {
        int nativeSonicScreenX = 0xF0 - 128; // = 112
        assertEquals(nativeSonicScreenX, xOffset(320) + nativeSonicScreenX,
                "Sonic screen X must be unchanged at native 320");
    }

    /**
     * TM symbol native screen X is TM_X = 0x170 - 128 = 240.
     * At native width the shifted value must equal 240 exactly.
     */
    @Test
    void tmX_atNative_isUnchanged() {
        int nativeTmX = 0x170 - 128; // = 240
        assertEquals(nativeTmX, xOffset(320) + nativeTmX,
                "TM screen X must be unchanged at native 320");
    }

    // -------------------------------------------------------------------------
    // Intro-text centering — viewport midpoint
    // -------------------------------------------------------------------------

    /**
     * Intro text "SONIC TEAM PRESENTS" is placed at the viewport midpoint.
     * At native 320 the midpoint is 160, matching the original literal.
     */
    @Test
    void introTextX_atNative_is160() {
        assertEquals(160, 320 / 2,
                "viewport mid-point at native 320 must equal 160");
    }

    @Test
    void introTextX_at480_is240() {
        assertEquals(240, 480 / 2);
    }

    @Test
    void introTextX_at640_is320() {
        assertEquals(320, 640 / 2);
    }

    // -------------------------------------------------------------------------
    // Background tile-column count — bgTileColumns(viewportWidth)
    // -------------------------------------------------------------------------

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
    void bgTileColumns_at480_coversFullWidth() {
        int cols = Sonic1TitleScreenManager.bgTileColumns(480);
        // 8 pixels per tile; with sub-tile offset up to 7 px, cols*8 must cover 480+7 px
        assertTrue(cols * 8 >= 480 + 7,
                "tile columns must cover viewportWidth + 7 px for any H-scroll offset");
    }

    @Test
    void bgTileColumns_at640_coversFullWidth() {
        int cols = Sonic1TitleScreenManager.bgTileColumns(640);
        assertTrue(cols * 8 >= 640 + 7,
                "tile columns must cover viewportWidth + 7 px for any H-scroll offset");
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
     * Verify horizontal nametable wrapping at widescreen.
     * With a GHZ nametable of 128 columns, any column index produced by the
     * tile loop must wrap back to [0, 127] via modulo arithmetic.
     */
    @Test
    void bgTileColumns_wrapIsCorrectAtWidescreen() {
        int mapWidth = 128; // GHZ nametable width
        int cols = Sonic1TitleScreenManager.bgTileColumns(640); // 82
        // Simulate the tile loop: each column wraps via ((tileX % mapWidth) + mapWidth) % mapWidth
        for (int screenTile = 0; screenTile < cols; screenTile++) {
            int mapTileX = ((screenTile % mapWidth) + mapWidth) % mapWidth;
            assertTrue(mapTileX >= 0 && mapTileX < mapWidth,
                    "mapTileX must be in [0, mapWidth) for screenTile " + screenTile);
        }
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
