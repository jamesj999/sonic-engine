package com.openggf.level.objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for the shared width-driven helpers added to
 * {@link AbstractObjectInstance}:
 * <ul>
 *   <li>{@code viewportWidth()} — derived from cameraBounds.right() - left()</li>
 *   <li>{@code viewportHeight()} — derived from cameraBounds.bottom() - top()</li>
 *   <li>{@code isInRangeAt(int objectX)} — width-driven despawn check for a custom X</li>
 * </ul>
 *
 * <h3>isInRangeAt parity</h3>
 * {@code isInRangeAt(getX())} must produce the same result as {@code isInRange()}.
 * The limit is {@code 128 + viewportWidth() + 192}:
 * <ul>
 *   <li>Native (320 px): 640 — byte-identical to ROM {@code cmpi.w #$280,d0}</li>
 *   <li>ULTRA_21_9 (528 px): 848</li>
 * </ul>
 *
 * See docs/status/known-discrepancies.md entry #14.
 */
class TestObjectRangeHelpers {

    /**
     * Minimal concrete stub reusing the same construction pattern as
     * {@link TestObjectViewportWindowWidth.SimpleObject} — only static
     * {@code cameraBounds} is exercised, no ObjectServices needed.
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
        int checkViewportWidth()  { return viewportWidth(); }
        int checkViewportHeight() { return viewportHeight(); }
        boolean checkIsInRangeAt(int objectX) { return isInRangeAt(objectX); }
        boolean checkIsInRange()              { return isInRange(); }
    }

    @BeforeEach
    void setUp() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
    }

    @AfterEach
    void tearDown() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    // -------------------------------------------------------------------------
    // viewportWidth()
    // -------------------------------------------------------------------------

    @Test
    void viewportWidth_native_returns320() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        SimpleObject obj = new SimpleObject(0, 0);
        assertEquals(320, obj.checkViewportWidth(),
                "viewportWidth() must return right - left = 320 at native");
    }

    @Test
    void viewportWidth_widescreen_returns528() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 528, 224, 0);
        SimpleObject obj = new SimpleObject(0, 0);
        assertEquals(528, obj.checkViewportWidth(),
                "viewportWidth() must return right - left = 528 at ULTRA_21_9");
    }

    @Test
    void viewportWidth_withNonZeroCameraLeft_returnsCorrectWidth() {
        // Camera scrolled right: left=128, right=448 → width still 320
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0);
        SimpleObject obj = new SimpleObject(0, 0);
        assertEquals(320, obj.checkViewportWidth(),
                "viewportWidth() must return right - left regardless of scroll offset");
    }

    // -------------------------------------------------------------------------
    // viewportHeight()
    // -------------------------------------------------------------------------

    @Test
    void viewportHeight_native_returns224() {
        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        SimpleObject obj = new SimpleObject(0, 0);
        assertEquals(224, obj.checkViewportHeight(),
                "viewportHeight() must return bottom - top = 224 (fixed)");
    }

    @Test
    void viewportHeight_widescreen_stillReturns224() {
        // Height is always 224 regardless of aspect ratio
        AbstractObjectInstance.updateCameraBounds(0, 0, 528, 224, 0);
        SimpleObject obj = new SimpleObject(0, 0);
        assertEquals(224, obj.checkViewportHeight(),
                "viewportHeight() must return 224 at widescreen (height is fixed)");
    }

    // -------------------------------------------------------------------------
    // isInRangeAt(int objectX) — native parity
    // -------------------------------------------------------------------------

    /**
     * Native (width 320): limit = 128 + 320 + 192 = 640.
     * Camera left = 128 → screenAligned = (128 - 128) & 0xFF80 = 0.
     * Object at x=640 → objAligned = 640 & 0xFF80 = 640; dist = 640.
     * 640 <= 640 → in range (bhi is unsigned strictly-greater-than).
     */
    @Test
    void isInRangeAt_native_exactlyAtLimit_isInRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0); // width 320
        SimpleObject obj = new SimpleObject(0, 0);
        assertTrue(obj.checkIsInRangeAt(640),
                "isInRangeAt: chunk-dist 640 (ROM limit at native) must be in range");
    }

    /**
     * Native: one chunk (128 px) past the limit → out of range.
     * dist = 768 > 640.
     */
    @Test
    void isInRangeAt_native_oneChunkPastLimit_isOutOfRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0); // width 320
        SimpleObject obj = new SimpleObject(0, 0);
        assertFalse(obj.checkIsInRangeAt(768),
                "isInRangeAt: chunk-dist 768 (past ROM 640 limit) must be out of range");
    }

    // -------------------------------------------------------------------------
    // isInRangeAt(int objectX) — widescreen
    // -------------------------------------------------------------------------

    /**
     * Widescreen (528 px): limit = 128 + 528 + 192 = 848.
     * An object at x=768 (dist 768 ≤ 848) that was out-of-range at native must
     * now be in range.
     */
    @Test
    void isInRangeAt_widescreen_distPastNativeLimit_isInRange() {
        // Width = 528, camera left = 128 → screenAligned = 0, limit = 848
        AbstractObjectInstance.updateCameraBounds(128, 0, 656, 224, 0); // width 528
        SimpleObject obj = new SimpleObject(0, 0);
        assertTrue(obj.checkIsInRangeAt(768),
                "isInRangeAt: dist 768 must be in range at widescreen limit 848");
    }

    /**
     * Widescreen: one chunk past the widescreen limit → out of range.
     * dist = 896 > 848.
     */
    @Test
    void isInRangeAt_widescreen_pastWidescreenLimit_isOutOfRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 656, 224, 0); // width 528
        SimpleObject obj = new SimpleObject(0, 0);
        assertFalse(obj.checkIsInRangeAt(896),
                "isInRangeAt: dist 896 must be out of range at widescreen limit 848");
    }

    // -------------------------------------------------------------------------
    // isInRangeAt(getX()) == isInRange() parity
    // -------------------------------------------------------------------------

    /**
     * At native width with camera left=128, isInRangeAt(getX()) must equal isInRange()
     * for an object within range (x=640).
     */
    @Test
    void isInRangeAt_equalsIsInRange_whenObjectXUsed_native_inRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0);
        SimpleObject obj = new SimpleObject(640, 0);
        assertEquals(obj.checkIsInRange(), obj.checkIsInRangeAt(obj.getX()),
                "isInRangeAt(getX()) must equal isInRange() at native for in-range object");
    }

    /**
     * At native width with camera left=128, isInRangeAt(getX()) must equal isInRange()
     * for an object out of range (x=768).
     */
    @Test
    void isInRangeAt_equalsIsInRange_whenObjectXUsed_native_outOfRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0);
        SimpleObject obj = new SimpleObject(768, 0);
        assertEquals(obj.checkIsInRange(), obj.checkIsInRangeAt(obj.getX()),
                "isInRangeAt(getX()) must equal isInRange() at native for out-of-range object");
    }

    /**
     * At widescreen width, isInRangeAt(getX()) must equal isInRange() for an object
     * that is in range under the widescreen limit but was out-of-range natively (x=768).
     */
    @Test
    void isInRangeAt_equalsIsInRange_whenObjectXUsed_widescreen() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 656, 224, 0); // width 528
        SimpleObject obj = new SimpleObject(768, 0);
        assertEquals(obj.checkIsInRange(), obj.checkIsInRangeAt(obj.getX()),
                "isInRangeAt(getX()) must equal isInRange() at widescreen for any object X");
    }

    /**
     * When objectX differs from getX() — the core use-case (spawnX/origX/baseX) —
     * isInRangeAt can differ from isInRange() but must apply the same logic.
     * At native (limit 640), a custom coord at 640 (in range) while getX() is out-of-range
     * at 768: verify the custom coord is in range.
     */
    @Test
    void isInRangeAt_customCoord_differentFromGetX_nativeInRange() {
        AbstractObjectInstance.updateCameraBounds(128, 0, 448, 224, 0);
        // getX() is 768 (out of range natively) but we test a custom coord at 640 (in range)
        SimpleObject obj = new SimpleObject(768, 0);
        assertFalse(obj.checkIsInRange(),     "isInRange() on getX()=768 must be false");
        assertTrue(obj.checkIsInRangeAt(640), "isInRangeAt(640) must be true at native limit 640");
    }
}
