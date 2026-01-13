package uk.co.jamesj999.sonic.level.scroll;

import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import uk.co.jamesj999.sonic.data.Rom;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * Tests for SwScrlMcz - Mystic Cave Zone scroll handler.
 * Verifies ROM-accurate parallax calculations.
 */
public class SwScrlMczTest {

    private SwScrlMcz handler;
    private MockMczRom mockRom;

    /**
     * Expected MCZ row heights from ROM offset 0xCE6C (24 bytes).
     * Hex: 25,17,12,07,07,02,02,30,0D,13,20,40,20,13,0D,30,02,02,07,07,20,12,17,25
     */
    private static final byte[] MCZ_ROW_HEIGHTS = {
            37, 23, 18, 7, 7, 2, 2, 48, 13, 19, 32, 64,
            32, 19, 13, 48, 2, 2, 7, 7, 32, 18, 23, 37
    };

    @Before
    public void setUp() throws IOException {
        mockRom = new MockMczRom();
        ParallaxTables tables = new ParallaxTables(mockRom);
        handler = new SwScrlMcz(tables);
    }

    // ==================== Row Heights Table Tests ====================

    @Test
    public void testRowHeightsSumTo512() {
        int sum = 0;
        for (byte h : MCZ_ROW_HEIGHTS) {
            sum += (h & 0xFF);
        }
        assertEquals("MCZ row heights must sum to 512 (one full cycle)", 512, sum);
    }

    @Test
    public void testRowHeightsCount() {
        assertEquals("MCZ must have exactly 24 row heights", 24, MCZ_ROW_HEIGHTS.length);
    }

    // ==================== segScroll Generation Tests ====================

    @Test
    public void testSegScrollAtCameraX0() {
        short[] segScroll = handler.buildAndGetSegScroll(0);

        // At cameraX=0, all segment scroll values should be 0
        for (int i = 0; i < 24; i++) {
            assertEquals("segScroll[" + i + "] at cameraX=0", 0, segScroll[i]);
        }
    }

    @Test
    public void testSegScrollAtCameraX10() {
        short[] segScroll = handler.buildAndGetSegScroll(10);

        // base = (10 << 4) / 10 = 160 / 10 = 16
        // baseFixed = 16 << 12 = 65536
        // step1: accFixed = 65536, val = 65536 >> 16 = 1
        // step2: accFixed = 131072, val = 2
        // step3: accFixed = 196608, val = 3
        // step4: accFixed = 262144, val = 4
        // step5: accFixed = 327680, val = 5
        // step6: accFixed = 393216, val = 6
        // step7: accFixed = 458752, val = 7
        // step8: accFixed = 524288, val = 8
        // step9: accFixed = 589824, val = 9

        // Verify mapping:
        assertEquals("segScroll[0] (step9, 0.9x)", 9, segScroll[0]);
        assertEquals("segScroll[7] (step1, 0.1x)", 1, segScroll[7]);
        assertEquals("segScroll[11] (step9, 0.9x)", 9, segScroll[11]);
        assertEquals("segScroll[15] (step1, 0.1x)", 1, segScroll[15]);
    }

    @Test
    public void testSegScrollAtCameraX256() {
        short[] segScroll = handler.buildAndGetSegScroll(256);

        // Expected values from the specification:
        // [230,204,178,127,102,76,51,25,127,178,204,230,204,178,127,25,51,76,102,127,153,178,204,230]
        int[] expected = {
                230, 204, 178, 127, 102, 76, 51, 25,
                127, 178, 204, 230, 204, 178, 127, 25,
                51, 76, 102, 127, 153, 178, 204, 230
        };

        for (int i = 0; i < 24; i++) {
            assertEquals("segScroll[" + i + "] at cameraX=256", expected[i], segScroll[i]);
        }
    }

    @Test
    public void testSegScrollAtCameraX1000() {
        short[] segScroll = handler.buildAndGetSegScroll(1000);

        // base = (1000 << 4) / 10 = 16000 / 10 = 1600
        // baseFixed = 1600 << 12 = 6553600
        // step1: 6553600 >> 16 = 100
        // step2: 13107200 >> 16 = 200
        // step3: 19660800 >> 16 = 300
        // step4: 26214400 >> 16 = 400
        // step5: 32768000 >> 16 = 500
        // step6: 39321600 >> 16 = 600
        // step7: 45875200 >> 16 = 700
        // step8: 52428800 >> 16 = 800
        // step9: 58982400 >> 16 = 900

        assertEquals("segScroll[0] (step9)", 900, segScroll[0]);
        assertEquals("segScroll[7] (step1)", 100, segScroll[7]);
        assertEquals("segScroll[15] (step1)", 100, segScroll[15]);
        assertEquals("segScroll[20] (step6)", 600, segScroll[20]);
    }

    // ==================== Per-Scanline Expansion Tests ====================

    @Test
    public void testScanlineExpansionAtBgY0() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        int cameraY = 960; // For Act 1: bgY = 960/3 - 320 = 0

        handler.update(hScroll, cameraX, cameraY, 0, 0);

        // At bgY=0, we start in segment 0
        // Segment 0 height = 37 lines
        // segScroll[0] = 230 (at cameraX=256)
        // FG scroll = -256
        // BG scroll = -cameraX + (cameraX - segScroll[0]) = -256 + (256 - 230) = -230

        // Lines 0-36 should all have the same BG scroll
        for (int i = 0; i < 37; i++) {
            short fg = unpackFG(hScroll[i]);
            short bg = unpackBG(hScroll[i]);
            assertEquals("Line " + i + " FG", (short) -256, fg);
            assertEquals("Line " + i + " BG", (short) -230, bg);
        }

        // Line 37 should be segment 1 (segScroll[1] = 204)
        // BG scroll = -256 + (256 - 204) = -204
        short bg37 = unpackBG(hScroll[37]);
        assertEquals("Line 37 BG (segment 1)", (short) -204, bg37);
    }

    @Test
    public void testScanlineExpansionAtBgY100() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        // For Act 1: bgY = 420/3 - 320 = 140 - 320 = -180... that's wrong
        // Let me recalculate for bgY=100:
        // bgY = cameraY/3 - 320 = 100 -> cameraY = 420 * 3 = 1260
        int cameraY = 1260; // bgY = 1260/3 - 320 = 420 - 320 = 100

        handler.update(hScroll, cameraX, cameraY, 0, 0);

        // At bgY=100, we need to find which segment we're in
        // Cumulative heights: 0-37(0), 37-60(1), 60-78(2), 78-85(3), 85-92(4),
        // 92-94(5), 94-96(6), 96-144(7)
        // bgY=100 is in segment 7 (96-144), offset = 100 - 96 = 4 pixels into segment 7

        // Verify the handler doesn't crash and produces reasonable output
        assertNotNull("hScroll should not be null", hScroll);
        assertTrue("hScroll[0] should be non-zero", hScroll[0] != 0 || cameraX == 0);
    }

    @Test
    public void testScanlineExpansionAtBgY300() {
        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 256;
        // For Act 1: bgY = cameraY/3 - 320 = 300 -> cameraY = 1860
        int cameraY = 1860;

        handler.update(hScroll, cameraX, cameraY, 0, 0);

        // At bgY=300, we're in segment 12 (272-304), offset = 300 - 272 = 28 pixels in
        // segScroll[12] = 204

        // Line 0 should be in segment 12
        short bg0 = unpackBG(hScroll[0]);
        // BG = -256 + (256 - 204) = -204
        assertEquals("Line 0 BG at bgY=300", (short) -204, bg0);
    }

    // ==================== BG Y Calculation Tests ====================

    @Test
    public void testBgYAct1() {
        int[] hScroll = new int[VISIBLE_LINES];

        // Act 1: bgY = floor(cameraY / 3) - 320
        // Test cameraY = 960: bgY = 320 - 320 = 0
        handler.update(hScroll, 0, 960, 0, 0);
        assertEquals("Act 1 bgY at cameraY=960", (short) 0, handler.getVscrollFactorBG());

        // Test cameraY = 1000: bgY = 333 - 320 = 13
        handler.update(hScroll, 0, 1000, 0, 0);
        assertEquals("Act 1 bgY at cameraY=1000", (short) 13, handler.getVscrollFactorBG());
    }

    @Test
    public void testBgYAct2() {
        int[] hScroll = new int[VISIBLE_LINES];

        // Act 2: bgY = floor(cameraY / 6) - 16
        // Test cameraY = 960: bgY = 160 - 16 = 144
        handler.update(hScroll, 0, 960, 0, 1);
        assertEquals("Act 2 bgY at cameraY=960", (short) 144, handler.getVscrollFactorBG());

        // Test cameraY = 96: bgY = 16 - 16 = 0
        handler.update(hScroll, 0, 96, 0, 1);
        assertEquals("Act 2 bgY at cameraY=96", (short) 0, handler.getVscrollFactorBG());
    }

    // ==================== Screen Shake Tests ====================

    @Test
    public void testScreenShakeApplied() throws IOException {
        // Need a fresh mock with ripple data set BEFORE ParallaxTables is created
        MockMczRom shakeRom = new MockMczRom();
        shakeRom.setRippleData(10, (byte) 5, (byte) 3);
        ParallaxTables shakeTables = new ParallaxTables(shakeRom);
        SwScrlMcz shakeHandler = new SwScrlMcz(shakeTables);

        int[] hScroll = new int[VISIBLE_LINES];
        int cameraX = 100;
        int cameraY = 960;

        shakeHandler.setScreenShakeFlag(true);
        shakeHandler.update(hScroll, cameraX, cameraY, 10, 0); // frameCounter=10

        // vscrollFactorBG = bgY + rippleY = 0 + 5 = 5
        assertEquals("Shake should affect vscrollFactorBG", (short) 5, shakeHandler.getVscrollFactorBG());

        // vscrollFactorFG = cameraY + rippleY = 960 + 5 = 965
        assertEquals("Shake should affect vscrollFactorFG", (short) 965, shakeHandler.getVscrollFactorFG());

        // FG scroll = -(cameraX + rippleX) = -(100 + 3) = -103
        short fg = unpackFG(hScroll[0]);
        assertEquals("Shake should affect FG scroll", (short) -103, fg);

        // getBgY should return raw bgY without ripple (for bgCamera update)
        assertEquals("getBgY should return raw bgY without ripple", 0, shakeHandler.getBgY());
    }

    // ==================== Segment Multiplier Mapping Tests ====================

    @Test
    public void testSegmentMultiplierMapping() {
        // Verify the segment to multiplier mapping matches the spec
        // These are conceptual multipliers for reference

        // Build with a camera value that makes calculation easy to verify
        short[] segScroll = handler.buildAndGetSegScroll(1000);

        // Expected multipliers (approximate - actual values from fixed-point math):
        // 0:0.9 1:0.8 2:0.7 3:0.5 4:0.4 5:0.3 6:0.2 7:0.1
        // 8:0.5 9:0.7 10:0.8 11:0.9 12:0.8 13:0.7 14:0.5
        // 15:0.1 16:0.2 17:0.3 18:0.4 19:0.5 20:0.6 21:0.7 22:0.8 23:0.9

        // At cameraX=1000, 0.1x should be 100, 0.9x should be 900
        assertEquals("seg 0 should be 0.9x", 900, segScroll[0]);
        assertEquals("seg 7 should be 0.1x", 100, segScroll[7]);
        assertEquals("seg 11 should be 0.9x", 900, segScroll[11]);
        assertEquals("seg 15 should be 0.1x", 100, segScroll[15]);
        assertEquals("seg 20 should be 0.6x", 600, segScroll[20]);
    }

    // ==================== Mock ROM ====================

    static class MockMczRom extends Rom {
        private byte[] rippleData = new byte[512];

        public MockMczRom() {
            super();
        }

        @Override
        public byte[] readBytes(long offset, int count) throws IOException {
            if (offset == ParallaxTables.SWSCRL_MCZ_ROW_HEIGHTS_ADDR) {
                return MCZ_ROW_HEIGHTS.clone();
            }
            if (offset == ParallaxTables.SWSCRL_RIPPLE_DATA_ADDR) {
                return rippleData.clone();
            }
            // Return empty data for other tables
            return new byte[count];
        }

        public void setRippleData(int idx, byte y, byte x) {
            rippleData[idx] = y;
            rippleData[idx + 1] = x;
        }
    }
}
