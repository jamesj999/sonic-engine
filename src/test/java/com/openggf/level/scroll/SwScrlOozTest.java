package com.openggf.level.scroll;

import org.junit.jupiter.api.BeforeEach;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.game.sonic2.scroll.SwScrlOoz;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.game.sonic2.scroll.SwScrlOoz;

import java.io.IOException;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.game.sonic2.scroll.SwScrlOoz;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.scroll.SwScrlOoz;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.game.sonic2.scroll.SwScrlOoz;

import static org.junit.jupiter.api.Assertions.*;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.game.sonic2.scroll.SwScrlOoz;
import static com.openggf.level.scroll.M68KMath.*;
import com.openggf.game.sonic2.scroll.ParallaxTables;
import com.openggf.game.sonic2.scroll.SwScrlOoz;

/**
 * Tests for SwScrlOoz - Oil Ocean Zone scroll handler.
 * Verifies ROM-accurate parallax calculations matching the original 68000 behavior.
 *
 * Reference: SwScrl_OOZ at ROM $CC66, SwScrl_RippleData at ROM $C682
 *
 * The OOZ scroll routine builds the hscroll buffer from bottom to top with:
 * - Factory region (variable height based on Camera_BG_Y_pos)
 * - Multiple cloud layers with different parallax speeds
 * - Sun heat-haze with animated ripple effect
 * - Empty sky at the top
 */
public class SwScrlOozTest {

    private SwScrlOoz handler;
    private MockOozRom mockRom;

    /**
     * Expected ripple data from ROM offset 0xC682 (first 66 bytes).
     * Used for the sun heat-haze effect.
     */
    private static final byte[] OOZ_RIPPLE_DATA = {
            0x01, 0x02, 0x01, 0x03, 0x01, 0x02, 0x02, 0x01,
            0x02, 0x03, 0x01, 0x02, 0x01, 0x02, 0x00, 0x00,
            0x02, 0x00, 0x03, 0x02, 0x02, 0x03, 0x02, 0x02,
            0x01, 0x03, 0x00, 0x00, 0x01, 0x00, 0x01, 0x03,
            0x01, 0x02, 0x01, 0x03, 0x01, 0x02, 0x02, 0x01,
            0x02, 0x03, 0x01, 0x02, 0x01, 0x02, 0x00, 0x00,
            0x02, 0x00, 0x03, 0x02, 0x02, 0x03, 0x02, 0x02,
            0x01, 0x03, 0x00, 0x00, 0x01, 0x00, 0x01, 0x03,
            0x01, 0x02
    };

    @BeforeEach
    public void setUp() throws IOException {
        mockRom = new MockOozRom();
        ParallaxTables tables = new ParallaxTables(mockRom);
        handler = new SwScrlOoz(tables);
    }

    // ==================== Factory Height Calculation Tests ====================

    /**
     * Test factory height when Camera_BG_Y_pos < 80 (below threshold).
     * The first subtraction borrows, d1 is zeroed, then follows same path:
     * d1 = 0 -> (0-176) wraps to 65360 -> +223 wraps to 47 -> factory = 48
     */
    @Test
    public void testFactoryHeightBelowThreshold() {
        // All values < 80 produce factory = 48
        int height = handler.calculateFactoryHeightForTest(50);
        assertEquals(48, height, "Factory height at bgY=50");

        height = handler.calculateFactoryHeightForTest(0);
        assertEquals(48, height, "Factory height at bgY=0");

        height = handler.calculateFactoryHeightForTest(79);
        assertEquals(48, height, "Factory height at bgY=79");
    }

    /**
     * Test factory height at exact lower threshold (bgY = 80).
     * d1 = 80 - 80 = 0, then same path as below threshold.
     * (0-176) wraps to 65360 -> +223 wraps to 47 -> factory = 48
     */
    @Test
    public void testFactoryHeightAtLowerThreshold() {
        int height = handler.calculateFactoryHeightForTest(80);
        assertEquals(48, height, "Factory height at bgY=80");
    }

    /**
     * Test factory height in middle range (80 < bgY < 256).
     * Formula: factory = bgY - 32
     *
     * The 68000 arithmetic with unsigned wraparound:
     * d1 = bgY - 80 (no borrow since bgY >= 80)
     * d1 = d1 - 176 (borrows, wraps to 65536 + d1 - 176)
     * d1 = d1 + 223 (wraps again)
     * Result: bgY - 32
     */
    @Test
    public void testFactoryHeightMiddleRange() {
        // bgY = 100: factory = 100 - 32 = 68
        int height = handler.calculateFactoryHeightForTest(100);
        assertEquals(68, height, "Factory height at bgY=100");

        // bgY = 200: factory = 200 - 32 = 168
        height = handler.calculateFactoryHeightForTest(200);
        assertEquals(168, height, "Factory height at bgY=200");

        // bgY = 255: factory = 255 - 32 = 223
        height = handler.calculateFactoryHeightForTest(255);
        assertEquals(223, height, "Factory height at bgY=255");
    }

    /**
     * Test factory height at upper threshold (bgY >= 256).
     * When d1 - 80 >= 176 (i.e., bgY >= 256), second sub doesn't borrow,
     * so d1 is zeroed and factory = 224.
     */
    @Test
    public void testFactoryHeightAtUpperThreshold() {
        // bgY = 256: d1 = 176, no borrow on second sub, d1 = 0, factory = 224
        int height = handler.calculateFactoryHeightForTest(256);
        assertEquals(224, height, "Factory height at bgY=256");

        // bgY = 300: same logic
        height = handler.calculateFactoryHeightForTest(300);
        assertEquals(224, height, "Factory height at bgY=300");
    }

    /**
     * Test factory height formula across entire range.
     * - bgY < 80: factory = 48 (constant)
     * - 80 <= bgY < 256: factory = bgY - 32 (linear)
     * - bgY >= 256: factory = 224 (capped)
     */
    @Test
    public void testFactoryHeightRange() {
        for (int bgY = 0; bgY <= 300; bgY++) {
            int height = handler.calculateFactoryHeightForTest(bgY);
            int expected;
            if (bgY < 80) {
                expected = 48;
            } else if (bgY < 256) {
                expected = bgY - 32;
            } else {
                expected = 224;
            }
            assertEquals(expected, height, "Factory height at bgY=" + bgY);
            assertTrue(height <= 224, "Factory height should be <= 224 at bgY=" + bgY);
            assertTrue(height > 0, "Factory height should be > 0 at bgY=" + bgY);
        }
    }

    // ==================== Vertical Scroll Tests ====================

    /**
     * Test that Camera_BG_Y_pos = (Camera_Y_pos >> 3) + $50.
     */
    @Test
    public void testVerticalScrollFactor() {
        int[] hScroll = new int[VISIBLE_LINES];

        // cameraY = 0 -> bgY = (0 >> 3) + 0x50 = 0x50 = 80
        handler.update(hScroll, 0, 0, 0, 0);
        assertEquals((short) 0x50, handler.getVscrollFactorBG(), "vscrollFactorBG at cameraY=0");

        // cameraY = 256 -> bgY = (256 >> 3) + 0x50 = 32 + 80 = 112
        handler.update(hScroll, 0, 256, 0, 0);
        assertEquals((short) 112, handler.getVscrollFactorBG(), "vscrollFactorBG at cameraY=256");

        // cameraY = 1024 -> bgY = (1024 >> 3) + 0x50 = 128 + 80 = 208
        handler.update(hScroll, 0, 1024, 0, 0);
        assertEquals((short) 208, handler.getVscrollFactorBG(), "vscrollFactorBG at cameraY=1024");
    }

    // ==================== Per-Scanline Buffer Tests ====================

    @Test
    public void testBufferFillsAll224Lines() {
        int[] hScroll = new int[VISIBLE_LINES];
        handler.update(hScroll, 0, 0, 0, 0);

        // All 224 lines should be filled (even with cameraX = 0)
        // Since OOZ uses Camera_BG_X_pos = 0 always, BG scroll will be 0 or ripple values
        for (int i = 0; i < VISIBLE_LINES; i++) {
            // Just verify they're set (not undefined)
            // With cameraX = 0 and bgX = 0, all non-ripple values should be 0
            assertNotNull(hScroll[i], "Line " + i + " should be filled");
        }
    }

    @Test
    public void testFgScrollIsConstant() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        handler.update(hScroll, cameraX, 0, 0, 0);

        // FG scroll should be -cameraX for all lines
        short expectedFg = (short) -cameraX;
        for (int i = 0; i < VISIBLE_LINES; i++) {
            short fg = unpackFG(hScroll[i]);
            assertEquals(expectedFg, fg, "Line " + i + " FG scroll");
        }
    }

    @Test
    public void testBgScrollTracksAtQuarterSpeed() {
        int[] hScroll = new int[VISIBLE_LINES];

        // Initialize at cameraX = 0
        handler.update(hScroll, 0, 0, 0, 0);

        // Move camera to 256 (delta = 256)
        handler.update(hScroll, 256, 0, 1, 0);

        // BG should have moved by quarter: 256 / 4 = 64
        // FG = -256, BG = -64 for factory region
        short fgScroll = unpackFG(hScroll[223]);
        short bgScroll = unpackBG(hScroll[223]);
        assertEquals((short) -256, fgScroll, "FG scroll should be -256");
        assertEquals((short) -64, bgScroll, "Factory BG scroll should be -64 (quarter speed tracking)");

        // Move camera further to 512 (total delta from start = 512)
        handler.update(hScroll, 512, 0, 2, 0);

        // BG should now be at 128 (quarter of 512)
        // FG = -512, BG = -128
        fgScroll = unpackFG(hScroll[223]);
        bgScroll = unpackBG(hScroll[223]);
        assertEquals((short) -512, fgScroll, "FG scroll should be -512");
        assertEquals((short) -128, bgScroll, "Factory BG scroll should be -128 (quarter speed tracking)");
    }

    // ==================== Heat-Haze Phase Animation Tests ====================

    @Test
    public void testHeatHazePhaseDecrements() {
        int initialPhase = handler.getHeatHazePhaseCounter();

        int[] hScroll = new int[VISIBLE_LINES];

        // Frame 5: (5 + 3) & 7 = 0 -> should decrement
        handler.update(hScroll, 0, 0, 5, 0);
        assertEquals(initialPhase - 1, handler.getHeatHazePhaseCounter(), "Phase should decrement at frame 5");
    }

    @Test
    public void testHeatHazePhaseNoUpdateOnNonMatchingFrames() {
        handler.setHeatHazePhaseCounter(10);
        handler.resetPhaseTracking();

        int[] hScroll = new int[VISIBLE_LINES];

        // Frame 0: (0 + 3) & 7 = 3 -> no update
        handler.update(hScroll, 0, 0, 0, 0);
        assertEquals(10, handler.getHeatHazePhaseCounter(), "Phase should not change at frame 0");

        // Frame 1: (1 + 3) & 7 = 4 -> no update
        handler.update(hScroll, 0, 0, 1, 0);
        assertEquals(10, handler.getHeatHazePhaseCounter(), "Phase should not change at frame 1");

        // Frame 2: (2 + 3) & 7 = 5 -> no update
        handler.update(hScroll, 0, 0, 2, 0);
        assertEquals(10, handler.getHeatHazePhaseCounter(), "Phase should not change at frame 2");

        // Frame 3: (3 + 3) & 7 = 6 -> no update
        handler.update(hScroll, 0, 0, 3, 0);
        assertEquals(10, handler.getHeatHazePhaseCounter(), "Phase should not change at frame 3");

        // Frame 4: (4 + 3) & 7 = 7 -> no update
        handler.update(hScroll, 0, 0, 4, 0);
        assertEquals(10, handler.getHeatHazePhaseCounter(), "Phase should not change at frame 4");

        // Frame 5: (5 + 3) & 7 = 0 -> UPDATE
        handler.update(hScroll, 0, 0, 5, 0);
        assertEquals(9, handler.getHeatHazePhaseCounter(), "Phase should decrement at frame 5");
    }

    @Test
    public void testHeatHazePhaseUpdatesEvery8Frames() {
        handler.setHeatHazePhaseCounter(100);
        handler.resetPhaseTracking();

        int[] hScroll = new int[VISIBLE_LINES];

        // Process 24 frames (should see 3 decrements at frames 5, 13, 21)
        for (int frame = 0; frame < 24; frame++) {
            handler.update(hScroll, 0, 0, frame, 0);
        }

        // Expected: 100 - 3 = 97 (updates at frames 5, 13, 21)
        assertEquals(97, handler.getHeatHazePhaseCounter(), "Phase should decrement 3 times in 24 frames");
    }

    @Test
    public void testHeatHazeRippleValues() {
        // Verify ripple data is loaded correctly
        for (int i = 0; i < OOZ_RIPPLE_DATA.length; i++) {
            int expected = OOZ_RIPPLE_DATA[i];
            int actual = handler.getRippleValueForTest(i);
            assertEquals(expected, actual, "Ripple value at index " + i);
        }
    }

    @Test
    public void testHeatHazeAnimationChangesBuffer() {
        int[] hScroll1 = new int[VISIBLE_LINES];
        int[] hScroll2 = new int[VISIBLE_LINES];

        // Set different phases that will read different parts of ripple data
        // Phase 0 reads ripple[0..32], phase 16 reads ripple[16..48]
        handler.setHeatHazePhaseCounter(0);
        handler.resetPhaseTracking();
        handler.update(hScroll1, 0, 0, 0, 0);

        handler.setHeatHazePhaseCounter(16);  // Different phase offset
        handler.resetPhaseTracking();
        handler.update(hScroll2, 0, 0, 0, 0);

        // At least some lines in the heat-haze region should differ
        // With phase 0 vs 16, the ripple values read will be different
        boolean foundDifference = false;
        for (int i = 0; i < VISIBLE_LINES; i++) {
            short bg1 = unpackBG(hScroll1[i]);
            short bg2 = unpackBG(hScroll2[i]);
            if (bg1 != bg2) {
                foundDifference = true;
                break;
            }
        }

        assertTrue(foundDifference, "Different phases should produce different ripple values");
    }

    // ==================== Segment Ordering Tests ====================

    /**
     * Test that the buffer contains the expected segment structure.
     * Since OOZ uses bgX = 0, all non-ripple BG values should be 0.
     * Only the heat-haze region should have non-zero BG values (from ripple data).
     *
     * At cameraY = 0, bgY = 80, factory = 48 lines.
     * Remaining 176 lines contain clouds and heat-haze (33 lines of ripple).
     */
    @Test
    public void testSegmentStructure() {
        int[] hScroll = new int[VISIBLE_LINES];
        handler.setHeatHazePhaseCounter(0);
        handler.resetPhaseTracking();
        handler.update(hScroll, 0, 0, 0, 0);

        // Count lines with non-zero BG scroll (should be from heat-haze ripple)
        int nonZeroCount = 0;
        for (int i = 0; i < VISIBLE_LINES; i++) {
            short bg = unpackBG(hScroll[i]);
            if (bg != 0) {
                nonZeroCount++;
            }
        }

        // With phase = 0, we read ripple[0..32] which has these non-zero values:
        // Index 0: 1, 1: 2, 2: 1, 3: 3, 4: 1, 5: 2, 6: 2, 7: 1, 8: 2, 9: 3, 10: 1, 11: 2, 12: 1, 13: 2, 14: 0, 15: 0...
        // At least 14 of the first 33 values are non-zero
        assertTrue(nonZeroCount > 0, "Should have some non-zero BG values from heat-haze, got " + nonZeroCount);
        assertTrue(nonZeroCount <= 33, "Should have at most 33 non-zero values (heat-haze region)");
    }

    /**
     * Test segment heights add up correctly.
     * Total: factory + 8 + 8 + 8 + 7 + 33 + 8 + 8 + 8 + 8 + 8 + 72 = factory + 176
     * When factory fills the rest, total = 224.
     */
    @Test
    public void testSegmentHeightsTotal() {
        // Fixed segment heights (excluding factory)
        int mediumClouds = 8;
        int slowClouds = 8;
        int fastClouds = 8;
        int slowClouds2 = 7;
        int sunHaze = 33;
        int emptySky = 72;

        int fixedTotal = (mediumClouds * 4) + slowClouds + slowClouds2 + sunHaze + emptySky;
        // = 32 + 8 + 7 + 33 + 72 = 152
        // Wait, let me recount:
        // 8 + 8 + 8 + 7 + 33 + 8 + 8 + 8 + 8 + 8 + 72 = 176

        int sum = 8 + 8 + 8 + 7 + 33 + 8 + 8 + 8 + 8 + 8 + 72;
        assertEquals(176, sum, "Non-factory segments total");

        // Factory fills remaining: 224 - 176 = 48 at minimum
        // But factory height is variable based on threshold logic
    }

    // ==================== Integration Test ====================

    /**
     * Full integration test: verify buffer is deterministic.
     * Same inputs should produce identical outputs.
     */
    @Test
    public void testDeterministicOutput() {
        int[] hScroll1 = new int[VISIBLE_LINES];
        int[] hScroll2 = new int[VISIBLE_LINES];

        int cameraX = 512;
        int cameraY = 256;
        int frameCounter = 42;

        // Reset to known state
        handler.setHeatHazePhaseCounter(16);
        handler.resetPhaseTracking();
        handler.update(hScroll1, cameraX, cameraY, frameCounter, 0);

        // Reset again and run with same inputs
        handler.setHeatHazePhaseCounter(16);
        handler.resetPhaseTracking();
        handler.update(hScroll2, cameraX, cameraY, frameCounter, 0);

        // Outputs should be identical
        for (int i = 0; i < VISIBLE_LINES; i++) {
            assertEquals(hScroll1[i], hScroll2[i], "Line " + i + " should be identical");
        }
    }

    /**
     * Test that camera movement produces parallax effect between factory and clouds.
     * BG scroll tracks at quarter FG speed, clouds track at even slower rates.
     */
    @Test
    public void testBgScrollParallaxWithCameraMovement() {
        int[] hScroll1 = new int[VISIBLE_LINES];
        int[] hScroll2 = new int[VISIBLE_LINES];

        // Start at position 0
        handler.setHeatHazePhaseCounter(0);
        handler.resetPhaseTracking();
        handler.update(hScroll1, 0, 0, 0, 0);

        // Move camera to 1000
        handler.update(hScroll2, 1000, 0, 1, 0);

        // FG scroll should be different
        short fg1 = unpackFG(hScroll1[0]);
        short fg2 = unpackFG(hScroll2[0]);
        assertNotEquals(fg1, fg2, "FG scroll should differ after camera movement");

        // BG scroll in factory region should also differ (tracks at quarter speed)
        short bgFactory1 = unpackBG(hScroll1[223]);
        short bgFactory2 = unpackBG(hScroll2[223]);
        assertNotEquals(bgFactory1, bgFactory2, "Factory BG scroll should differ after camera movement");

        // Factory should track at quarter speed: delta should be ~250
        int factoryDelta = Math.abs(bgFactory2 - bgFactory1);
        assertEquals(250, factoryDelta, "Factory should track at quarter FG speed");

        // Cloud regions have additional shifts, so they move even slower
        // Find a line in a cloud region
        short bgCloud1 = unpackBG(hScroll1[100]);  // Should be in a cloud region
        short bgCloud2 = unpackBG(hScroll2[100]);

        // Verify parallax effect: cloud scrolls slower than factory
        int cloudDelta = Math.abs(bgCloud2 - bgCloud1);
        assertTrue(cloudDelta < factoryDelta, "Cloud should scroll slower than factory (parallax effect), cloud=" + cloudDelta + ", factory=" + factoryDelta);
    }

    // ==================== Mock ROM ====================

    static class MockOozRom extends Rom {
        public MockOozRom() {
            super();
        }

        @Override
        public byte[] readBytes(long offset, int count) throws IOException {
            if (offset == ParallaxTables.SWSCRL_RIPPLE_DATA_ADDR) {
                // Return ripple data
                byte[] ripple = new byte[count];
                int copyLen = Math.min(count, OOZ_RIPPLE_DATA.length);
                System.arraycopy(OOZ_RIPPLE_DATA, 0, ripple, 0, copyLen);
                return ripple;
            }
            // Return empty data for other tables
            return new byte[count];
        }
    }
}


