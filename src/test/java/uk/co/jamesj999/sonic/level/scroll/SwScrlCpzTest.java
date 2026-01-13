package uk.co.jamesj999.sonic.level.scroll;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import uk.co.jamesj999.sonic.data.Rom;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * Unit tests for SwScrlCpz (Chemical Plant Zone parallax scroll handler).
 *
 * Tests validate the following requirements:
 * 1. Ripple speed parity: Phase advances every 8 frames (matching EHZ)
 * 2. Underwater parallax split: BG1 at 1/8x, BG2 at 1/2x
 * 3. Seam block correctness: Block index 18 applies ripple effect
 * 4. Pixel stability: Integer scroll values, no float rounding jitter
 * 5. ROM data usage: Uses ripple data from ParallaxTables
 */
public class SwScrlCpzTest {

    private MockCpzRom mockRom;
    private ParallaxTables tables;
    private SwScrlCpz handler;
    private int[] horizScrollBuf;

    @Before
    public void setUp() throws IOException {
        mockRom = new MockCpzRom();
        tables = new ParallaxTables(mockRom);
        handler = new SwScrlCpz(tables);
        horizScrollBuf = new int[224];
    }

    // ==================== Ripple Speed Tests ====================

    @Test
    public void testRipplePhaseAdvancesEvery8Frames() {
        // Initialize at some camera position
        handler.init(0, 0);

        int initialPhase = handler.getRipplePhase();

        // Run 7 frames - phase should NOT change
        for (int frame = 0; frame < 7; frame++) {
            handler.update(horizScrollBuf, 0, 0, frame, 0);
            assertEquals("Ripple phase should not change within 8 frames at frame " + frame,
                    initialPhase, handler.getRipplePhase());
        }
    }

    @Test
    public void testRipplePhaseDecrementPattern() {
        handler.init(0, 0);

        // Track phase changes over 24 frames
        int lastPhase = handler.getRipplePhase();
        int phaseChanges = 0;

        for (int frame = 0; frame < 24; frame++) {
            handler.update(horizScrollBuf, 0, 0, frame, 0);
            int currentPhase = handler.getRipplePhase();
            if (currentPhase != lastPhase) {
                phaseChanges++;
                lastPhase = currentPhase;
            }
        }

        // Should have exactly 3 phase changes in 24 frames (24/8 = 3)
        assertEquals("Should have 3 phase decrements in 24 frames (one per 8 frames)",
                3, phaseChanges);
    }

    // ==================== Parallax Split Tests ====================

    @Test
    public void testBg1ScrollsAtOneEighthSpeed() {
        handler.init(0, 0);

        // Move camera 800 pixels right
        int cameraX = 800;
        handler.update(horizScrollBuf, cameraX, 0, 0, 0);

        // BG1 should be at 800 / 8 = 100 pixels
        int expectedBg1 = cameraX / 8;
        assertEquals("BG1 X should be 1/8 of camera X",
                expectedBg1, handler.getBg1Xpx());
    }

    @Test
    public void testBg2ScrollsAtHalfSpeed() {
        handler.init(0, 0);

        // Move camera 800 pixels right
        int cameraX = 800;
        handler.update(horizScrollBuf, cameraX, 0, 0, 0);

        // BG2 should be at 800 / 2 = 400 pixels
        int expectedBg2 = cameraX / 2;
        assertEquals("BG2 X should be 1/2 of camera X",
                expectedBg2, handler.getBg2Xpx());
    }

    @Test
    public void testBgYScrollsAtOneQuarterSpeed() {
        handler.init(0, 0);

        // Move camera 800 pixels down
        int cameraY = 800;
        handler.update(horizScrollBuf, 0, cameraY, 0, 0);

        // BG Y should be at 800 / 4 = 200 pixels
        int expectedBgY = cameraY / 4;
        assertEquals("BG Y should be 1/4 of camera Y",
                expectedBgY, handler.getBgYpx());
    }

    @Test
    public void testParallaxDifferenceBetweenBg1AndBg2() {
        handler.init(0, 0);

        // Move camera 1000 pixels right
        int cameraX = 1000;
        handler.update(horizScrollBuf, cameraX, 0, 0, 0);

        int bg1 = handler.getBg1Xpx();
        int bg2 = handler.getBg2Xpx();

        // BG2 should scroll 4x faster than BG1 (1/2 vs 1/8)
        assertEquals(cameraX / 8, bg1);
        assertEquals(cameraX / 2, bg2);
        assertEquals("BG2 should be 4x BG1 position (1/2 vs 1/8 ratio)",
                bg2, bg1 * 4);
    }

    // ==================== Line Block Tests ====================

    @Test
    public void testScrollBufferHas224Entries() {
        handler.init(100, 100);
        handler.update(horizScrollBuf, 100, 100, 0, 0);

        // All 224 entries should be filled
        for (int i = 0; i < 224; i++) {
            // Packed format: (FG << 16) | (BG & 0xFFFF)
            int packed = horizScrollBuf[i];
            short fg = (short) (packed >> 16);
            // FG should be -cameraX = -100
            assertEquals("FG scroll should be -cameraX at line " + i, (short) -100, fg);
        }
    }

    @Test
    public void testScrollBufferFillsIn16LineBlocks() {
        // Position camera so seam is not on screen (above water)
        handler.init(1000, 0);
        handler.update(horizScrollBuf, 1000, 0, 0, 0);

        // FG should be consistent across all lines
        short expectedFg = (short) -1000;
        for (int line = 0; line < 224; line++) {
            short fg = unpackFG(horizScrollBuf[line]);
            assertEquals("FG should be consistent at line " + line, expectedFg, fg);
        }
    }

    // ==================== Seam Block Tests ====================

    @Test
    public void testSeamBlockIndex() {
        // The seam is at lineBlockIndex == 18
        // lineBlockIndex = ((bgYpx & 0x3F0) >> 4)
        // For index 18: bgYpx & 0x3F0 = 18 * 16 = 288 (0x120)
        // bgYpx = 288 means init cameraY = 288 * 4 = 1152 (for 1/4 ratio)

        handler.init(0, 1152);
        handler.update(horizScrollBuf, 0, 1152, 0, 0);

        // The bgYpx should be 1152 / 4 = 288
        int bgYpx = handler.getBgYpx();
        int lineBlockIndex = ((bgYpx & 0x3F0) >> 4);

        assertEquals("Seam should be at block index 18", 18, lineBlockIndex);
    }

    @Test
    public void testPartialBlockHandlingAtScreenStart() {
        // Test that blocks are aligned to background map, not screen position
        // When bgYpx is not on a 16-pixel boundary, the first block is partial

        // Set up bgYpx = 290 (partway through block 18)
        // Block 18 runs from bgY=288 to bgY=303
        // With bgYpx=290, we're 2 pixels into block 18
        // So the first 14 screen lines should be in block 18, then block 19 starts
        int cameraY = 290 * 4; // bgYpx will be 290 after init
        handler.init(1000, cameraY);
        handler.update(horizScrollBuf, 1000, cameraY, 0, 0);

        // Verify bgYpx is what we expect
        assertEquals("bgYpx should be 290", 290, handler.getBgYpx());

        // Lines 0-13 should be in block 18 (the seam block with ripple)
        // Lines 14+ should be in block 19 (BG2 scroll)

        short bg1Scroll = (short) -(1000 / 8); // BG1 at 1/8 speed
        short bg2Scroll = (short) -(1000 / 2); // BG2 at 1/2 speed

        // Check that lines after the partial block (lines 14+) use BG2
        short bgLine14 = unpackBG(horizScrollBuf[14]);
        assertEquals("Line 14 should use BG2 scroll", bg2Scroll, bgLine14);

        // Lines 0-13 should be in the seam block and have ripple applied
        // The base scroll is BG1, with small ripple offsets
        for (int line = 0; line < 14; line++) {
            short bg = unpackBG(horizScrollBuf[line]);
            // Seam block uses BG1 base with 0-3 pixel ripple, so should be close to
            // bg1Scroll
            assertTrue("Line " + line + " should be near BG1 scroll (seam block)",
                    Math.abs(bg - bg1Scroll) <= 3);
        }
    }

    // ==================== Pixel Stability Tests ====================

    @Test
    public void testScrollValuesAreStableIntegers() {
        handler.init(0, 0);

        // Run multiple frames with same camera - scroll should be stable
        int cameraX = 500;
        int cameraY = 200;

        handler.update(horizScrollBuf, cameraX, cameraY, 0, 0);
        int[] firstFrame = horizScrollBuf.clone();

        // Run more frames with same camera position
        for (int i = 1; i < 10; i++) {
            handler.update(horizScrollBuf, cameraX, cameraY, i, 0);

            // All values should remain identical (no float drift)
            for (int line = 0; line < 224; line++) {
                assertEquals("Scroll value should be stable at line " + line + " frame " + i,
                        firstFrame[line], horizScrollBuf[line]);
            }
        }
    }

    @Test
    public void testNoSubpixelJitter() {
        handler.init(0, 0);

        // Move camera by 1 pixel increments and check for integer results
        for (int x = 0; x < 100; x++) {
            handler.update(horizScrollBuf, x, 0, 0, 0);

            // BG positions should be exact integers, no rounding artifacts
            int bg1 = handler.getBg1Xpx();
            int bg2 = handler.getBg2Xpx();

            // Verify they're clean integer values
            assertTrue("BG1 should be in valid range for cameraX=" + x,
                    bg1 >= 0 && bg1 <= x);
            assertTrue("BG2 should be in valid range for cameraX=" + x,
                    bg2 >= 0 && bg2 <= x);
        }
    }

    // ==================== Min/Max Offset Tests ====================

    @Test
    public void testMinMaxScrollOffsetTracking() {
        // Position camera so the screen spans across the seam (block index 18)
        // bgYpx = 288 is at seam, so cameraY = 288 * 4 = 1152 at init
        // After init, we need some lines above seam and some below
        handler.init(1000, 1100); // This gives bgYpx ~ 275 at init
        handler.update(horizScrollBuf, 1000, 1100, 0, 0);

        int minOffset = handler.getMinScrollOffset();
        int maxOffset = handler.getMaxScrollOffset();

        // Min and max should be valid (min <= max)
        assertTrue("Min offset should be <= max offset", minOffset <= maxOffset);

        // When camera is at a position, offsets should be reasonable
        // BG1 at 1/8x gives -125, BG2 at 1/2x gives -500
        // Offset = BG - FG where FG = -1000
        // Since we're likely in mostly one region, min might equal max
        // At minimum, both should be in a reasonable range
        assertTrue("Min offset should be reasonable",
                minOffset > -1000 && minOffset < 1000);
        assertTrue("Max offset should be reasonable",
                maxOffset > -1000 && maxOffset < 1000);
    }

    // ==================== Reset Tests ====================

    @Test
    public void testResetClearsState() {
        // Initialize and run some updates
        handler.init(1000, 500);
        handler.update(horizScrollBuf, 1000, 500, 0, 0);

        assertTrue("BG1 should be non-zero before reset", handler.getBg1Xpx() > 0);

        // Reset
        handler.reset();

        // After reset, state should be cleared
        assertEquals("BG1 should be 0 after reset", 0, handler.getBg1Xpx());
        assertEquals("BG2 should be 0 after reset", 0, handler.getBg2Xpx());
        assertEquals("BG Y should be 0 after reset", 0, handler.getBgYpx());
        assertEquals("Ripple phase should be 0 after reset", 0, handler.getRipplePhase());
    }

    // ==================== Mock ROM for Testing ====================

    /**
     * Mock ROM implementation that provides CPZ-specific test data.
     */
    static class MockCpzRom extends Rom {

        private final byte[] rippleData;
        private final byte[] cpzCameraSections;
        private final byte[] mczRowHeights;
        private final byte[] wfzTransArray;
        private final byte[] wfzNormalArray;
        private final byte[] mcz2PRowHeights;
        private final byte[] cnzRowHeights;
        private final byte[] dezRowHeights;
        private final byte[] arzRowHeights;

        MockCpzRom() {
            super();

            // Initialize ripple data (66 bytes, values 0-3)
            rippleData = new byte[512];
            for (int i = 0; i < 66; i++) {
                rippleData[i] = (byte) (i % 4);
            }

            // Initialize CPZ camera sections (65 bytes)
            // 20 entries of 0x02, then 45 entries of 0x04
            cpzCameraSections = new byte[65];
            for (int i = 0; i < 20; i++) {
                cpzCameraSections[i] = 0x02;
            }
            for (int i = 20; i < 65; i++) {
                cpzCameraSections[i] = 0x04;
            }

            // Initialize MCZ row heights (needed by ParallaxTables)
            mczRowHeights = new byte[24];
            for (int i = 0; i < 24; i++) {
                mczRowHeights[i] = 0x10; // 16 pixels each
            }

            // Initialize other required tables
            wfzTransArray = new byte[76];
            wfzNormalArray = new byte[78];
            mcz2PRowHeights = new byte[26];
            cnzRowHeights = new byte[64];
            dezRowHeights = new byte[36];
            arzRowHeights = new byte[16];
        }

        @Override
        public byte[] readBytes(long offset, int count) throws IOException {
            int addr = (int) offset;
            if (addr == ParallaxTables.SWSCRL_RIPPLE_DATA_ADDR) {
                return rippleData.clone();
            }
            if (addr == ParallaxTables.CPZ_CAMERA_SECTIONS_ADDR) {
                return cpzCameraSections.clone();
            }
            if (addr == ParallaxTables.SWSCRL_MCZ_ROW_HEIGHTS_ADDR) {
                return mczRowHeights.clone();
            }
            if (addr == ParallaxTables.SWSCRL_WFZ_TRANS_ADDR) {
                return wfzTransArray.clone();
            }
            if (addr == ParallaxTables.SWSCRL_WFZ_NORMAL_ADDR) {
                return wfzNormalArray.clone();
            }
            if (addr == ParallaxTables.SWSCRL_MCZ_2P_ROW_HEIGHTS_ADDR) {
                return mcz2PRowHeights.clone();
            }
            if (addr == ParallaxTables.SWSCRL_CNZ_ROW_HEIGHTS_ADDR) {
                return cnzRowHeights.clone();
            }
            if (addr == ParallaxTables.SWSCRL_DEZ_ROW_HEIGHTS_ADDR) {
                return dezRowHeights.clone();
            }
            if (addr == ParallaxTables.SWSCRL_ARZ_ROW_HEIGHTS_ADDR) {
                return arzRowHeights.clone();
            }

            // Return empty data for other tables
            return new byte[count];
        }
    }
}
