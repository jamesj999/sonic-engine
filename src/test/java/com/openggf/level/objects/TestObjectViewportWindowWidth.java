package com.openggf.level.objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that {@link AbstractObjectInstance#isInRange()} and
 * {@link AbstractObjectInstance#isChkObjectVisible()} scale their window with
 * the configured viewport width rather than hardcoding 320.
 *
 * <h3>Native parity</h3>
 * At native viewport width (320 px) the results are byte-identical to the ROM:
 * <ul>
 *   <li>{@code isInRange} limit = 128 + 320 + 192 = 640 (ROM {@code cmpi.w #$280,d0})</li>
 *   <li>{@code isChkObjectVisible} uses {@code 0 <= dx < 320} and {@code 0 <= dy < 224}</li>
 * </ul>
 *
 * <h3>Widescreen extension</h3>
 * At widescreen viewport width (e.g. ULTRA_21_9 = 528 px) the limits widen:
 * <ul>
 *   <li>{@code isInRange} limit = 128 + 528 + 192 = 848</li>
 *   <li>{@code isChkObjectVisible} uses {@code 0 <= dx < 528}</li>
 * </ul>
 *
 * This is a deliberate divergence documented in docs/status/known-discrepancies.md; at
 * NATIVE_4_3 (320 px) both methods reproduce the ROM constants exactly.
 */
class TestObjectViewportWindowWidth {

    /**
     * Minimal concrete stub whose X/Y coordinates are set directly.
     * No ObjectServices wiring needed — the tested methods only read
     * the static {@link AbstractObjectInstance#cameraBounds} field.
     */
    private static final class SimpleObject extends AbstractObjectInstance {
        private int x;
        private int y;

        SimpleObject(int x, int y) {
            super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "TestObject");
            this.x = x;
            this.y = y;
        }

        void setXY(int x, int y) {
            this.x = x;
            this.y = y;
        }

        @Override
        public int getX() { return x; }

        @Override
        public int getY() { return y; }

        @Override
        public void appendRenderCommands(List<com.openggf.graphics.GLCommand> commands) {}

        // Expose protected methods for testing
        boolean checkInRange() { return isInRange(); }
        boolean checkChkObjectVisible() { return isChkObjectVisible(); }
    }

    @BeforeEach
    void setUp() {
        // Start every test with a clean native-width viewport.
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        // Restore native defaults so subsequent tests in the suite see a clean state.
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    // -------------------------------------------------------------------------
    // isInRange — native parity
    // -------------------------------------------------------------------------

    /**
     * Native: object whose chunk-aligned distance from (screenX - 128) is exactly 640
     * must be IN range (bhi is unsigned strictly-greater-than, so {@code <= 640} stays).
     * Camera left = 128: screenAligned = (128 - 128) &amp; 0xFF80 = 0.
     * objAligned for x=640: 640 &amp; 0xFF80 = 0x0280 = 640. dist = 640 - 0 = 640.
     * 640 &lt;= 640 → in range.
     */
    @Test
    void isInRange_native_exactlyAtLimit_isInRange() {
        // camera.left = 128 → screenAligned = (128 - 128) & 0xFF80 = 0
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0); // width 320
        SimpleObject obj = new SimpleObject(640, 0); // objAligned = 640 & 0xFF80 = 640; dist = 640
        assertTrue(obj.checkInRange(), "Object at chunk-dist 640 (the ROM limit) must be in range");
    }

    /**
     * Native: one chunk (128 px) past the limit → out of range.
     * dist = 768 > 640.
     */
    @Test
    void isInRange_native_oneChunkPastLimit_isOutOfRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0); // width 320
        SimpleObject obj = new SimpleObject(768, 0); // objAligned = 768; dist = 768
        assertFalse(obj.checkInRange(), "Object at chunk-dist 768 (past the ROM 640 limit) must be out of range");
    }

    // -------------------------------------------------------------------------
    // isInRange — widescreen
    // -------------------------------------------------------------------------

    /**
     * Widescreen (ULTRA_21_9 = 528 px): limit = 128 + 528 + 192 = 848.
     * An object at chunk-dist 656 (which is 16 past the old 640 limit) must now be IN range.
     * Camera left = 128 → screenAligned = 0. objAligned = 656 & 0xFF80 = 640 (rounded down).
     * We need dist=656 exactly, so use x=656 which aligns to 640 (dist=640, in range trivially).
     * Use x=768 to get dist=768, which was the out-of-range case at native but should be in range
     * at widescreen (768 <= 848).
     */
    @Test
    void isInRange_widescreen_distPastNativeLimit_isInRangeWithWiderViewport() {
        // Width = 528 (ULTRA_21_9), limit = 128 + 528 + 192 = 848
        AbstractObjectInstance.updateCameraBounds(128, 0, 656, 224, 0); // width 528
        // x=768 → objAligned = 768 & 0xFF80 = 768, screenAligned = 0, dist = 768
        // 768 <= 848 → in range (was out at native 640 limit)
        SimpleObject obj = new SimpleObject(768, 0);
        assertTrue(obj.checkInRange(), "At widescreen width 528 (limit 848), dist 768 must be in range");
    }

    /**
     * Widescreen: one chunk past the widescreen limit (dist = 896 > 848) → out of range.
     */
    @Test
    void isInRange_widescreen_pastWidescreenLimit_isOutOfRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 656, 224, 0); // width 528
        // x=896 → objAligned = 896 & 0xFF80 = 896, dist = 896
        // 896 > 848 → out of range
        SimpleObject obj = new SimpleObject(896, 0);
        assertFalse(obj.checkInRange(), "At widescreen width 528 (limit 848), dist 896 must be out of range");
    }

    // -------------------------------------------------------------------------
    // isChkObjectVisible — native parity
    // -------------------------------------------------------------------------

    /**
     * Native: object at dx = 319 (just inside the 320-wide viewport) must be visible.
     */
    @Test
    void isChkObjectVisible_native_justInsideRight_isVisible() {
        // cameraBounds set to native 320 in setUp()
        SimpleObject obj = new SimpleObject(319, 0); // dx = 319 - 0 = 319
        assertTrue(obj.checkChkObjectVisible(), "dx=319 (within [0,320)) must be visible at native width");
    }

    /**
     * Native: object at dx = 320 (at the exclusive right bound) must NOT be visible.
     */
    @Test
    void isChkObjectVisible_native_atExclusiveRightBound_isNotVisible() {
        SimpleObject obj = new SimpleObject(320, 0); // dx = 320 - 0 = 320; 320 >= 320 → false
        assertFalse(obj.checkChkObjectVisible(), "dx=320 (exclusive upper bound) must be invisible at native width");
    }

    /**
     * Native: object at dy = 223 (just inside the 224-tall viewport) must be visible.
     */
    @Test
    void isChkObjectVisible_native_justInsideBottom_isVisible() {
        SimpleObject obj = new SimpleObject(0, 223); // dy = 223 < 224
        assertTrue(obj.checkChkObjectVisible(), "dy=223 (within [0,224)) must be visible at native height");
    }

    /**
     * Native: object at dy = 224 (exclusive bottom) must NOT be visible.
     */
    @Test
    void isChkObjectVisible_native_atExclusiveBottomBound_isNotVisible() {
        SimpleObject obj = new SimpleObject(0, 224); // dy = 224 >= 224 → false
        assertFalse(obj.checkChkObjectVisible(), "dy=224 (exclusive lower bound) must be invisible");
    }

    // -------------------------------------------------------------------------
    // isChkObjectVisible — widescreen
    // -------------------------------------------------------------------------

    /**
     * Widescreen (528 px wide): dx = 400 is within [0, 528) — was invisible under
     * the old hardcoded 320 but must now be visible.
     */
    @Test
    void isChkObjectVisible_widescreen_dxPastNativeWidth_isVisible() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 528, 224, 0);
        SimpleObject obj = new SimpleObject(400, 0); // dx = 400; 400 < 528 → visible
        assertTrue(obj.checkChkObjectVisible(),
                "At widescreen width 528, dx=400 (past old 320 limit) must be visible");
    }

    /**
     * Widescreen (528 px wide): dx = 528 is at the exclusive right bound — must NOT be visible.
     */
    @Test
    void isChkObjectVisible_widescreen_atExclusiveWidescreenRightBound_isNotVisible() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 528, 224, 0);
        SimpleObject obj = new SimpleObject(528, 0); // dx = 528; 528 >= 528 → false
        assertFalse(obj.checkChkObjectVisible(),
                "At widescreen width 528, dx=528 (exclusive right bound) must be invisible");
    }

    /**
     * Widescreen (528 px wide): dx slightly past native (321) must be visible.
     * This is the key regression case: old code used dx >= 320 to reject this.
     */
    @Test
    void isChkObjectVisible_widescreen_dxJustPastNativeWidth_isVisible() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 528, 224, 0);
        SimpleObject obj = new SimpleObject(321, 0); // dx = 321; was invisible under old 320 check
        assertTrue(obj.checkChkObjectVisible(),
                "At widescreen width 528, dx=321 (just past old native limit) must be visible");
    }

    // -------------------------------------------------------------------------
    // ObjectManager.outOfRangeLimit — pure function (native and widescreen)
    // -------------------------------------------------------------------------

    /**
     * Native: 128 + 320 + 192 = 640.
     * Reproduces the ROM {@code cmpi.w #$280,d0} constant exactly.
     */
    @Test
    void outOfRangeLimit_native320_returns640() {
        assertEquals(640, ObjectManager.outOfRangeLimit(320),
                "Native viewport width 320 must yield the ROM constant 640");
    }

    /**
     * Widescreen ULTRA_21_9 (528 px): 128 + 528 + 192 = 848.
     * The despawn window widens with the viewport to prevent visible objects
     * near the right edge from being incorrectly removed.
     */
    @Test
    void outOfRangeLimit_widescreen528_returns848() {
        assertEquals(848, ObjectManager.outOfRangeLimit(528),
                "ULTRA_21_9 viewport width 528 must yield 848 (128 + 528 + 192)");
    }

    @Test
    void s3kTwoAxisPlacementKeepsNativeObjPosLoadAheadAtWidescreen() {
        ObjectPlacementController placement = new ObjectPlacementController(List.of(), () -> 528);
        placement.setTwoAxisCursorPlacement(true);

        assertEquals(0x280, placement.getLoadAhead(),
                "S3K's X cursor must keep the ROM $280 allocation edge at widescreen");
    }
}
