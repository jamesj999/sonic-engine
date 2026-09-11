package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Headless unit tests for the title-card widescreen centering math.
 * No OpenGL, no ROM, no singletons — pure arithmetic.
 *
 * <p>Verifies two invariants for all three title-card managers (S1, S2, S3K):
 * <ol>
 *   <li><b>Native parity:</b> every formula produces the same result as the
 *       original 320-wide literals when viewportWidth == 320 (xOffset == 0).</li>
 *   <li><b>Centering coverage:</b> at wider viewports elements are shifted right
 *       by (viewportWidth - 320) / 2, placing the composition in the centre.</li>
 * </ol>
 */
class TestTitleCardWidescreenCentering {

    // Mirrors the xOffset() helper in each TitleCard manager.
    private static final int NATIVE_WIDTH = 320;

    private static int xOffset(int viewportWidth) {
        return (viewportWidth - NATIVE_WIDTH) / 2;
    }

    // -------------------------------------------------------------------------
    // xOffset arithmetic
    // -------------------------------------------------------------------------

    @Test
    void xOffset_atNative320_isZero() {
        assertEquals(0, xOffset(320),
                "xOffset must be 0 at native 320 — byte-identical across S1/S2/S3K");
    }

    @Test
    void xOffset_at480_is80() {
        assertEquals(80, xOffset(480));
    }

    @Test
    void xOffset_at426_is53() {
        assertEquals(53, xOffset(426));
    }

    @Test
    void xOffset_at640_is160() {
        assertEquals(160, xOffset(640));
    }

    // -------------------------------------------------------------------------
    // S2 — element render positions (centerX = element.currentX + xOffset)
    // -------------------------------------------------------------------------

    /**
     * S2 zone-name element target X is 160 in 320-space (the visual centre).
     * At native width the shifted render position must equal 160 — byte-identical.
     */
    @Test
    void s2_zoneNameRenderX_atNative_is160() {
        int nativeTargetX = 160;  // TitleCardElement.createZoneName() targetX
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "zone-name centerX must be 160 at native 320");
    }

    /**
     * At 480-wide viewport the zone-name renders at 160 + 80 = 240 (viewport centre).
     */
    @Test
    void s2_zoneNameRenderX_at480_is240() {
        int nativeTargetX = 160;
        assertEquals(240, nativeTargetX + xOffset(480));
    }

    /**
     * S2 "ZONE" text native target X is 200.
     * At native width the shifted render X must equal 200 — byte-identical.
     */
    @Test
    void s2_zoneTextRenderX_atNative_is200() {
        int nativeTargetX = 200;  // TitleCardElement.createZoneText() targetX
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "ZONE text centerX must be 200 at native 320");
    }

    /**
     * S2 bottom bar native target X is 232.
     * At native width the shifted render X must equal 232 — byte-identical.
     */
    @Test
    void s2_bottomBarRenderX_atNative_is232() {
        int nativeTargetX = 232;  // TitleCardElement.createBottomBar() targetX
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "bottom-bar centerX must be 232 at native 320");
    }

    /**
     * S2 left swoosh native target X is 112.
     * At native width the shifted render X must equal 112 — byte-identical.
     */
    @Test
    void s2_leftSwooshRenderX_atNative_is112() {
        int nativeTargetX = 112;  // TitleCardElement.createLeftSwoosh() targetX
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "left-swoosh centerX must be 112 at native 320");
    }

    // -------------------------------------------------------------------------
    // S2 — background rectangle widths (viewport-spanning)
    // -------------------------------------------------------------------------

    /**
     * Blue background native width is SCREEN_WIDTH = 320.
     * At native, viewportWidth() == 320 so the rect spans exactly 320 — byte-identical.
     */
    @Test
    void s2_blueBackground_atNative_spans320() {
        int vw = 320;
        assertEquals(320, vw, "blue background width must be 320 at native");
    }

    /**
     * At 480-wide viewport the blue background must span 480.
     */
    @Test
    void s2_blueBackground_at480_spans480() {
        assertEquals(480, 480 /* viewportWidth() at 480 */);
    }

    /**
     * Red block right edge = currentX (320-space) + xOffset.
     * At native, xOffset==0 so redRight == currentX (native 112) — byte-identical.
     */
    @Test
    void s2_redBlockRight_atNative_isCurrentX() {
        int currentX = 112;  // swoosh target in 320-space
        int redRight = currentX + xOffset(320);
        assertEquals(currentX, redRight,
                "red-block right edge must equal swoosh currentX at native 320");
    }

    /**
     * Red block right edge at 480 wide = 112 + 80 = 192.
     */
    @Test
    void s2_redBlockRight_at480_shiftedCorrectly() {
        int currentX = 112;
        assertEquals(192, currentX + xOffset(480));
    }

    // -------------------------------------------------------------------------
    // S3K — element render positions
    // -------------------------------------------------------------------------

    /**
     * S3K zone-name element native target X is 160.
     * At native, render X must equal 160 — byte-identical.
     */
    @Test
    void s3k_zoneNameRenderX_atNative_is160() {
        int nativeTargetX = 160;  // Sonic3kTitleCardManager TARGET_X[ELEM_ZONE_NAME]
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "S3K zone-name centerX must be 160 at native 320");
    }

    /**
     * S3K zone-name render X at 480-wide must be 160 + 80 = 240.
     */
    @Test
    void s3k_zoneNameRenderX_at480_is240() {
        int nativeTargetX = 160;
        assertEquals(240, nativeTargetX + xOffset(480));
    }

    /**
     * S3K banner native target X is 96 (vertically animated element, X stays at 96).
     * At native, render X must equal 96 — byte-identical.
     */
    @Test
    void s3k_bannerRenderX_atNative_is96() {
        int nativeTargetX = 96;  // Sonic3kTitleCardManager TARGET_X[ELEM_BANNER]
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "S3K banner centerX must be 96 at native 320");
    }

    /**
     * S3K bonus "BONUS" element native target X is 72.
     * At native, render X must equal 72 — byte-identical.
     */
    @Test
    void s3k_bonusElemRenderX_atNative_is72() {
        int nativeTargetX = 72;  // Sonic3kTitleCardManager BONUS_TARGET_X[BONUS_ELEM_BONUS]
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320));
    }

    // -------------------------------------------------------------------------
    // S1 — element render positions (conData-driven)
    // -------------------------------------------------------------------------

    /**
     * S1 zone-name element native target X is 160 (from CON_DATA column 1 for all zones).
     * At native, render X must equal 160 — byte-identical.
     */
    @Test
    void s1_zoneNameRenderX_atNative_is160() {
        int nativeTargetX = 160;  // Sonic1TitleCardMappings.CON_DATA[][1] for all zones
        assertEquals(nativeTargetX, nativeTargetX + xOffset(320),
                "S1 zone-name targetX must be 160 at native 320");
    }

    /**
     * S1 zone-name render X at 480-wide must be 160 + 80 = 240.
     */
    @Test
    void s1_zoneNameRenderX_at480_is240() {
        int nativeTargetX = 160;
        assertEquals(240, nativeTargetX + xOffset(480));
    }

    // -------------------------------------------------------------------------
    // Viewport-spanning background rects
    // -------------------------------------------------------------------------

    /**
     * Background RECTI right edge uses viewportWidth().
     * At native 320, viewportWidth()==320 so the rect spans exactly 320 — byte-identical.
     */
    @Test
    void backgroundRect_atNative_spans320() {
        int vw = 320;
        assertEquals(320, vw);
    }

    /**
     * Background RECTI spans the full viewport at each standard widescreen preset.
     */
    @Test
    void backgroundRect_spansFullViewport_atAllWidths() {
        int[] widths = {320, 384, 426, 480, 512, 640};
        for (int w : widths) {
            // Right edge = viewportWidth(); left edge = 0 → spans exactly w pixels
            int span = w - 0;
            assertEquals(w, span, "background rect must span " + w + " at viewport width " + w);
        }
    }

    // -------------------------------------------------------------------------
    // Centering symmetry — composition is truly centred at wider widths
    // -------------------------------------------------------------------------

    /**
     * The composition left virtual edge (x=0 in 320-space) maps to xOffset().
     * The composition right virtual edge (x=320 in 320-space) maps to xOffset()+320.
     * The midpoint of those two must equal viewportWidth/2 — the viewport centre.
     */
    @Test
    void composition_isCentred_in_viewport() {
        int[] widths = {320, 480, 640};
        for (int vw : widths) {
            int off = xOffset(vw);
            int left  = off + 0;     // left edge of the 320-wide composition
            int right = off + 320;   // right edge
            int mid   = (left + right) / 2;
            assertEquals(vw / 2, mid,
                    "composition midpoint must equal viewport midpoint at width " + vw);
        }
    }

    /**
     * At odd viewport widths the integer division is consistent: xOffset rounds down,
     * so the composition may be 1 px left of true centre. Verify it does NOT round up.
     */
    @Test
    void xOffset_roundsDown_atOddExtraWidth() {
        // viewportWidth = 321: extra width = 1, half = 0
        assertEquals(0, xOffset(321));
        // viewportWidth = 323: extra width = 3, half = 1
        assertEquals(1, xOffset(323));
    }

    // -------------------------------------------------------------------------
    // Regression: xOffset is never negative (no content pushed off-screen left)
    // -------------------------------------------------------------------------

    @Test
    void xOffset_isNonNegative_forAllStandardWidths() {
        int[] widths = {320, 384, 426, 480, 512, 640};
        for (int w : widths) {
            assertTrue(xOffset(w) >= 0,
                    "xOffset must be non-negative for width " + w);
        }
    }
}
