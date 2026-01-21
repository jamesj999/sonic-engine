package uk.co.jamesj999.sonic.level.scroll;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import uk.co.jamesj999.sonic.data.Rom;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * Tests for SwScrlCnz - Casino Night Zone scroll handler.
 * Verifies ROM-accurate parallax calculations matching the original 68000 behavior.
 *
 * Reference: SwScrl_CNZ, SwScrl_CNZ_GenerateScrollValues, SwScrl_RippleData
 */
public class SwScrlCnzTest {

    private SwScrlCnz handler;
    private MockCnzRom mockRom;

    /**
     * Expected CNZ row heights from ROM offset 0xD156 (10 bytes).
     * Hex: 10 10 10 10 10 10 10 10 00 F0
     * The 0x00 entry marks the special rippling segment (still 16 lines tall).
     * The 0xF0 (240) is the remainder segment.
     */
    private static final byte[] CNZ_ROW_HEIGHTS = {
            0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x00, (byte) 0xF0
    };

    /**
     * Expected CNZ ripple data from ROM offset 0xC682 (66 bytes).
     * This is the first 66 bytes of SwScrl_RippleData.
     */
    private static final byte[] CNZ_RIPPLE_DATA = {
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

    @Before
    public void setUp() throws IOException {
        mockRom = new MockCnzRom();
        ParallaxTables tables = new ParallaxTables(mockRom);
        handler = new SwScrlCnz(tables);
    }

    // ==================== Row Heights Table Tests ====================

    @Test
    public void testRowHeightsLoaded() {
        int[] heights = handler.getRowHeights();
        assertEquals("CNZ must have exactly 10 row heights", 10, heights.length);

        // Verify the values match expected
        for (int i = 0; i < 10; i++) {
            assertEquals("Row height " + i, CNZ_ROW_HEIGHTS[i] & 0xFF, heights[i]);
        }
    }

    @Test
    public void testFirst8SegmentsAre16Lines() {
        int[] heights = handler.getRowHeights();
        for (int i = 0; i < 8; i++) {
            assertEquals("Segment " + i + " should be 16 lines", 16, heights[i]);
        }
    }

    @Test
    public void testRippleSegmentMarker() {
        int[] heights = handler.getRowHeights();
        assertEquals("Segment 8 should have height 0 (ripple marker)", 0, heights[8]);
    }

    @Test
    public void testFinalSegmentHeight() {
        int[] heights = handler.getRowHeights();
        assertEquals("Segment 9 should have height 240", 240, heights[9]);
    }

    // ==================== Scroll Value Generation Tests ====================

    /**
     * Test scroll value generation at Camera_X_pos = 256.
     * Expected values from spec: [256, 228, 200, 172, 144, 116, 88, 16, 16, 32]
     */
    @Test
    public void testScrollValuesAtCameraX256() {
        short[] values = handler.generateScrollValuesForTest((short) 256);

        int[] expected = {256, 228, 200, 172, 144, 116, 88, 16, 16, 32};

        for (int i = 0; i < 10; i++) {
            assertEquals("scrollValues[" + i + "] at cameraX=256", expected[i], values[i]);
        }
    }

    /**
     * Test scroll value generation at Camera_X_pos = 1024.
     * Expected values from spec: [1024, 912, 800, 688, 576, 464, 352, 64, 64, 128]
     */
    @Test
    public void testScrollValuesAtCameraX1024() {
        short[] values = handler.generateScrollValuesForTest((short) 1024);

        int[] expected = {1024, 912, 800, 688, 576, 464, 352, 64, 64, 128};

        for (int i = 0; i < 10; i++) {
            assertEquals("scrollValues[" + i + "] at cameraX=1024", expected[i], values[i]);
        }
    }

    /**
     * Test scroll value generation at Camera_X_pos = 0.
     * All values should be 0.
     */
    @Test
    public void testScrollValuesAtCameraX0() {
        short[] values = handler.generateScrollValuesForTest((short) 0);

        for (int i = 0; i < 10; i++) {
            assertEquals("scrollValues[" + i + "] at cameraX=0", 0, values[i]);
        }
    }

    /**
     * Test scroll value generation with negative camera X.
     * The algorithm should handle signed 16-bit values correctly.
     */
    @Test
    public void testScrollValuesAtNegativeCameraX() {
        short[] values = handler.generateScrollValuesForTest((short) -256);

        // Values should be negatives of the cameraX=256 case
        int[] expected = {-256, -228, -200, -172, -144, -116, -88, -16, -16, -32};

        for (int i = 0; i < 10; i++) {
            assertEquals("scrollValues[" + i + "] at cameraX=-256", expected[i], values[i]);
        }
    }

    // ==================== Vertical Scroll Tests ====================

    /**
     * Test that Camera_BG_Y_pos = Camera_Y_pos >>> 6 (1/64 speed).
     */
    @Test
    public void testVerticalScrollFactor() {
        int[] hScroll = new int[VISIBLE_LINES];

        // Test cameraY = 0
        handler.update(hScroll, 0, 0, 0, 0);
        assertEquals("vscrollFactorBG at cameraY=0", (short) 0, handler.getVscrollFactorBG());

        // Test cameraY = 64 -> bgY = 64 >>> 6 = 1
        handler.update(hScroll, 0, 64, 0, 0);
        assertEquals("vscrollFactorBG at cameraY=64", (short) 1, handler.getVscrollFactorBG());

        // Test cameraY = 128 -> bgY = 128 >>> 6 = 2
        handler.update(hScroll, 0, 128, 0, 0);
        assertEquals("vscrollFactorBG at cameraY=128", (short) 2, handler.getVscrollFactorBG());

        // Test cameraY = 1024 -> bgY = 1024 >>> 6 = 16
        handler.update(hScroll, 0, 1024, 0, 0);
        assertEquals("vscrollFactorBG at cameraY=1024", (short) 16, handler.getVscrollFactorBG());
    }

    // ==================== Per-Scanline Buffer Tests ====================

    @Test
    public void testBufferFillsAll224Lines() {
        int[] hScroll = new int[VISIBLE_LINES];
        handler.update(hScroll, 256, 0, 0, 0);

        // Verify all 224 lines are filled (non-zero when cameraX != 0)
        for (int i = 0; i < VISIBLE_LINES; i++) {
            assertTrue("Line " + i + " should be filled", hScroll[i] != 0);
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
            assertEquals("Line " + i + " FG scroll", expectedFg, fg);
        }
    }

    @Test
    public void testSegment0ScrollValue() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        handler.update(hScroll, cameraX, 0, 0, 0);

        // At bgY=0, we start at segment 0
        // scrollValues[0] = 256 (at cameraX=256)
        // BG scroll = -scrollValues[0] = -256
        short bg0 = unpackBG(hScroll[0]);
        assertEquals("Line 0 BG scroll (segment 0)", (short) -256, bg0);

        // First 16 lines should all be segment 0
        for (int i = 0; i < 16; i++) {
            short bg = unpackBG(hScroll[i]);
            assertEquals("Line " + i + " BG scroll (segment 0)", (short) -256, bg);
        }
    }

    @Test
    public void testSegment1ScrollValue() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        handler.update(hScroll, cameraX, 0, 0, 0);

        // Lines 16-31 should be segment 1
        // scrollValues[1] = 228 (at cameraX=256)
        // BG scroll = -228
        for (int i = 16; i < 32; i++) {
            short bg = unpackBG(hScroll[i]);
            assertEquals("Line " + i + " BG scroll (segment 1)", (short) -228, bg);
        }
    }

    @Test
    public void testSegmentTransitions() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        handler.update(hScroll, cameraX, 0, 0, 0);

        // Expected BG scroll values for each segment (at cameraX=256):
        // scrollValues = [256, 228, 200, 172, 144, 116, 88, 16, 16, 32]
        int[] expectedBgScroll = {-256, -228, -200, -172, -144, -116, -88, -16, -16, -32};

        // Verify segment transitions at expected line boundaries
        // Segment 0: lines 0-15, Segment 1: lines 16-31, etc.
        int[] segmentStarts = {0, 16, 32, 48, 64, 80, 96, 112, 128, 144};

        for (int seg = 0; seg < 9; seg++) {
            int line = segmentStarts[seg];
            short bg = unpackBG(hScroll[line]);

            if (seg == 8) {
                // Segment 8 is the ripple segment - value includes ripple offset
                // Base value is -16, ripple adds small offsets (typically 0-3)
                assertTrue("Segment 8 (ripple) start (line " + line + ") should be near -16",
                        Math.abs(bg + 16) <= 5);
            } else {
                assertEquals("Segment " + seg + " start (line " + line + ")",
                        (short) expectedBgScroll[seg], bg);
            }
        }
    }

    // ==================== Ripple Segment Tests ====================

    @Test
    public void testRippleSegmentLocation() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        handler.update(hScroll, cameraX, 0, 0, 0);

        // Ripple segment (segment 8) starts at line 128
        // Base scroll value = scrollValues[8] = 16, so base BG = -16
        // Ripple adds small offsets to each line

        // Lines 128-143 are the ripple segment
        // They should have varying BG scroll (base + ripple offset)
        short line128Bg = unpackBG(hScroll[128]);
        short line129Bg = unpackBG(hScroll[129]);

        // The ripple data should cause slight variation
        // (with our mock data, it's subtle)
        // At minimum, verify these lines are close to base value of -16
        assertTrue("Line 128 BG should be near -16",
                Math.abs(line128Bg + 16) <= 5);
        assertTrue("Line 129 BG should be near -16",
                Math.abs(line129Bg + 16) <= 5);
    }

    @Test
    public void testRippleAnimationChangesWithFrame() {
        int[] hScroll1 = new int[VISIBLE_LINES];
        int[] hScroll2 = new int[VISIBLE_LINES];
        int cameraX = 256;

        // Update at different frame counters (8 frames apart triggers index change)
        handler.update(hScroll1, cameraX, 0, 0, 0);
        handler.update(hScroll2, cameraX, 0, 8, 0);

        // At least one line in the ripple segment should differ between frames
        boolean foundDifference = false;
        for (int i = 128; i < 144; i++) {
            short bg1 = unpackBG(hScroll1[i]);
            short bg2 = unpackBG(hScroll2[i]);
            if (bg1 != bg2) {
                foundDifference = true;
                break;
            }
        }

        assertTrue("Ripple animation should vary with frameCounter", foundDifference);
    }

    // ==================== Starting Segment Tests (based on bgY) ====================

    @Test
    public void testStartingSegmentAtBgY8() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        // cameraY = 8 * 64 = 512 -> bgY = 512 >>> 6 = 8
        handler.update(hScroll, cameraX, 512, 0, 0);

        // At bgY=8, we're 8 lines into segment 0 (which is 16 lines tall)
        // So we have 8 lines remaining in segment 0, then segment 1 starts at line 8

        // Lines 0-7: segment 0 (scrollValues[0] = 256)
        short bg0 = unpackBG(hScroll[0]);
        assertEquals("Line 0 BG at bgY=8", (short) -256, bg0);

        // Line 8: segment 1 (scrollValues[1] = 228)
        short bg8 = unpackBG(hScroll[8]);
        assertEquals("Line 8 BG at bgY=8", (short) -228, bg8);
    }

    @Test
    public void testStartingSegmentAtBgY20() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        // cameraY = 20 * 64 = 1280 -> bgY = 1280 >>> 6 = 20
        handler.update(hScroll, cameraX, 1280, 0, 0);

        // At bgY=20, we're 4 lines into segment 1 (segment 0 is 16 lines)
        // So we have 12 lines remaining in segment 1

        // Lines 0-11: segment 1 (scrollValues[1] = 228)
        short bg0 = unpackBG(hScroll[0]);
        assertEquals("Line 0 BG at bgY=20", (short) -228, bg0);

        // Line 12: segment 2 (scrollValues[2] = 200)
        short bg12 = unpackBG(hScroll[12]);
        assertEquals("Line 12 BG at bgY=20", (short) -200, bg12);
    }

    // ==================== 68000 Algorithm Detail Tests ====================

    /**
     * Verify the exact 68000 algorithm produces correct intermediate values.
     * This tests the swap and accumulator operations.
     */
    @Test
    public void testScrollGenerationAlgorithmDetails() {
        // At cameraX = 256:
        // d0 = (256 >> 3) - 256 = 32 - 256 = -224
        // d0_32 = -224 (sign extended)
        // d0_32 <<= 13 = -224 * 8192 = -1835008
        //
        // d3 starts at 256 (low word), 0 (high word)
        //
        // Iteration 0: store low word (256), swap, add -1835008, swap back
        //   d3 = 0x00000100
        //   swap -> 0x01000000
        //   add -1835008 (0xFFE40000) -> 0x00E40000
        //   swap -> 0x0000E4 = 228 (low word before store is next iteration)
        // Wait, let me re-check...
        //
        // Actually the algorithm stores FIRST, then updates.
        // So values[0] = 256, values[1] = 228, etc.

        short[] values = handler.generateScrollValuesForTest((short) 256);

        // The delta between consecutive values should be -28 (for the first 7)
        // 256 - 228 = 28, 228 - 200 = 28, etc.
        for (int i = 0; i < 6; i++) {
            int delta = values[i] - values[i + 1];
            assertEquals("Delta between values[" + i + "] and values[" + (i + 1) + "]", 28, delta);
        }
    }

    /**
     * Test values[7], [8], [9] are set correctly (they bypass the loop).
     */
    @Test
    public void testFinalScrollValues() {
        short[] values = handler.generateScrollValuesForTest((short) 256);

        // values[9] = cameraX >> 3 = 256 >> 3 = 32
        assertEquals("values[9] = X >> 3", 32, values[9]);

        // values[7] = values[8] = cameraX >> 4 = 256 >> 4 = 16
        assertEquals("values[7] = X >> 4", 16, values[7]);
        assertEquals("values[8] = X >> 4", 16, values[8]);
    }

    // ==================== Offset Tracking Tests ====================

    @Test
    public void testMinMaxScrollOffset() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        handler.update(hScroll, cameraX, 0, 0, 0);

        int minOffset = handler.getMinScrollOffset();
        int maxOffset = handler.getMaxScrollOffset();

        // Min offset should be when BG scrolls slowest (highest scroll value)
        // Max offset should be when BG scrolls fastest (lowest scroll value)
        // At cameraX=256:
        // FG = -256, BG ranges from -256 to -16
        // offset = BG - FG = BG + 256
        // Min: -256 + 256 = 0
        // Max: -16 + 256 = 240

        assertTrue("minOffset should be <= 0", minOffset <= 0);
        assertTrue("maxOffset should be >= 0", maxOffset >= 0);
    }

    // ==================== Mock ROM ====================

    static class MockCnzRom extends Rom {
        public MockCnzRom() {
            super();
        }

        @Override
        public byte[] readBytes(long offset, int count) throws IOException {
            if (offset == ParallaxTables.SWSCRL_CNZ_ROW_HEIGHTS_ADDR) {
                return CNZ_ROW_HEIGHTS.clone();
            }
            if (offset == ParallaxTables.SWSCRL_RIPPLE_DATA_ADDR) {
                // Return enough ripple data for CNZ
                byte[] ripple = new byte[count];
                int copyLen = Math.min(count, CNZ_RIPPLE_DATA.length);
                System.arraycopy(CNZ_RIPPLE_DATA, 0, ripple, 0, copyLen);
                return ripple;
            }
            // Return empty data for other tables
            return new byte[count];
        }
    }
}
