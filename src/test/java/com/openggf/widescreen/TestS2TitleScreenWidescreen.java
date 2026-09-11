package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.game.sonic2.titlescreen.TitleScreenManager;
import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the Sonic 2 title-screen widescreen layout math.
 * No OpenGL, no ROM, no singletons.
 *
 * <p>Strategy per surface:
 * <ul>
 *   <li>Plane B (sky/water background): EXTEND — fill {@code ceil(viewportWidth/8)}
 *       tile columns from the 64-wide plane.</li>
 *   <li>Plane A (logo), sprites, credit text, curved occlusion: CENTER via xOffset.</li>
 *   <li>The curved-occlusion scissor maps a projection-space X span to window
 *       space against the live projection width (not a hardcoded 320).</li>
 * </ul>
 */
class TestS2TitleScreenWidescreen {

    private static final int NATIVE_WIDTH = 320;

    private static int xOffset(int viewportWidth) {
        return (viewportWidth - NATIVE_WIDTH) / 2;
    }

    private static int screenCols(int viewportWidth) {
        return (viewportWidth + 7) / 8;
    }

    // -------------------------------------------------------------------------
    // scissorXSpan — projection-space X -> window-space scissor span
    // -------------------------------------------------------------------------

    @Test
    void scissorXSpan_atNative1x_isIdentity() {
        // projWidth 320, viewport 0..320 (1x scale): span maps to itself.
        assertArrayEquals(new int[] {160, 2},
                TitleScreenManager.scissorXSpan(160, 2, 320, 0, 320));
        assertArrayEquals(new int[] {0, 2},
                TitleScreenManager.scissorXSpan(0, 2, 320, 0, 320));
    }

    @Test
    void scissorXSpan_atNative2x_scales() {
        // projWidth 320 mapped onto a 640-wide viewport at origin: scaleX = 2.
        assertArrayEquals(new int[] {320, 4},
                TitleScreenManager.scissorXSpan(160, 2, 320, 0, 640));
    }

    @Test
    void scissorXSpan_widescreen_mapsAgainstProjectionWidth() {
        // ULTRA_21_9: projWidth 528, viewport 0..528 (1x). A logo column at
        // logo-x 160 shifted by xOffset(528)=104 -> projection x 264 maps to 264.
        int ox = xOffset(528); // 104
        assertArrayEquals(new int[] {264, 2},
                TitleScreenManager.scissorXSpan(160 + ox, 2, 528, 0, 528));
    }

    @Test
    void scissorXSpan_widescreen_wrongDivisorWouldShiftRight() {
        // Guard the bug we fixed: dividing by 320 instead of the projection width
        // would over-scale. With the correct projWidth=528 the span is unscaled.
        int[] correct = TitleScreenManager.scissorXSpan(264, 2, 528, 0, 528);
        assertEquals(264, correct[0], "must map 1:1 when viewport == projection width");
    }

    @Test
    void scissorXSpan_clipsFullyOffLeft() {
        assertNull(TitleScreenManager.scissorXSpan(-5, 3, 320, 0, 320));
    }

    @Test
    void scissorXSpan_clampsRightEdgeToProjectionWidth() {
        // mdX 319, width 5 -> clipped to [319,320): one projection pixel.
        assertArrayEquals(new int[] {319, 1},
                TitleScreenManager.scissorXSpan(319, 5, 320, 0, 320));
    }

    @Test
    void scissorXSpan_appliesViewportOrigin() {
        // Letterboxed viewport with a non-zero origin: x is offset by vpX.
        int[] span = TitleScreenManager.scissorXSpan(0, 2, 320, 50, 320);
        assertEquals(50, span[0]);
        assertEquals(2, span[1]);
    }

    // -------------------------------------------------------------------------
    // xOffset — foreground centring
    // -------------------------------------------------------------------------

    @Test
    void xOffset_atNative_isZero() {
        assertEquals(0, xOffset(NATIVE_WIDTH), "byte-identical at native 320");
    }

    @Test
    void xOffset_atPresets() {
        assertEquals(40, xOffset(400));  // WIDE_16_9
        assertEquals(104, xOffset(528)); // ULTRA_21_9
        assertEquals(240, xOffset(800)); // SUPER_32_9
    }

    // -------------------------------------------------------------------------
    // Plane B extension — column count fills the viewport
    // -------------------------------------------------------------------------

    @Test
    void planeB_columnCount_atNative_is40() {
        assertEquals(40, screenCols(NATIVE_WIDTH),
                "Plane B must render exactly 40 columns at native 320 — byte-identical");
    }

    @Test
    void planeB_columnCount_fillsWiderViewports() {
        assertEquals(50, screenCols(400));
        assertEquals(66, screenCols(528));
        assertEquals(100, screenCols(800));
        for (int vw : new int[] {320, 352, 400, 528, 800}) {
            assertTrue(screenCols(vw) * 8 >= vw,
                    "Plane B columns must cover the viewport at width " + vw);
        }
    }
}
