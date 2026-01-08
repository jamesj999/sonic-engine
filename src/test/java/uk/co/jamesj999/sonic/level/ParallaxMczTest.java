package uk.co.jamesj999.sonic.level;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.scroll.ParallaxTables;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class ParallaxMczTest {

    private ParallaxManager parallaxManager;
    private MockRom mockRom;

    // Expected Row Heights for MCZ (24 bytes)
    // 37,23,18,7,7,2,2,48,13,19,32,64,32,19,13,48,2,2,7,7,32,18,23,37
    private static final byte[] MCZ_ROW_HEIGHTS = {
            37, 23, 18, 7, 7, 2, 2, 48, 13, 19, 32, 64,
            32, 19, 13, 48, 2, 2, 7, 7, 32, 18, 23, 37
    };

    // Camera X=256 Expected Seg Scroll Values
    // [230, 204, 178, 127, 102, 76, 51, 25, 127, 178, 204, 230, 204, 178, 127, 25,
    // 51, 76, 102, 127, 153, 178, 204, 230]
    private static final int[] EXPECTED_SEG_SCROLL_256 = {
            230, 204, 178, 127, 102, 76, 51, 25, 127, 178, 204, 230, 204, 178, 127, 25, 51, 76, 102, 127, 153, 178, 204,
            230
    };

    @Before
    public void setUp() {
        parallaxManager = new ParallaxManager();
        mockRom = new MockRom();
        parallaxManager.load(mockRom);
    }

    @Test
    public void testTableLoad() {
        // Verify row heights loaded correctly
        // Access strictly via parallaxManager is hard as it hides tables.
        // But we can verify calculations.
    }

    @Test
    public void testBgYCalculation() {
        // Act 1 (ID 0): floor(cameraY / 3) - 320
        // Act 2 (ID 1): floor(cameraY / 6) - 16

        Camera cam = Camera.getInstance();

        // Test Act 1
        // CameraY = 960. Correct BG Y = 320 - 320 = 0
        setCamera(cam, 0, 960);
        parallaxManager.update(ParallaxManager.ZONE_MCZ, 0, cam, 0, 0);
        assertEquals(0, parallaxManager.getVscrollFactorBG());

        // CameraY = 1000. Correct BG Y = 333 - 320 = 13
        setCamera(cam, 0, 1000);
        parallaxManager.update(ParallaxManager.ZONE_MCZ, 0, cam, 0, 0);
        assertEquals(13, parallaxManager.getVscrollFactorBG());

        // Test Act 2
        // CameraY = 960. Correct BG Y = 160 - 16 = 144
        setCamera(cam, 0, 960);
        parallaxManager.update(ParallaxManager.ZONE_MCZ, 1, cam, 0, 0);
        assertEquals(144, parallaxManager.getVscrollFactorBG());
    }

    @Test
    public void testSegScrollGenerationAt256() {
        // Test exact parallax replication for Camera X = 256
        Camera cam = Camera.getInstance();
        int cameraX = 256;
        int cameraY = 960; // produces bgY=0 for Act 0
        setCamera(cam, cameraX, cameraY);

        parallaxManager.update(ParallaxManager.ZONE_MCZ, 0, cam, 0, 0);
        int[] hScroll = parallaxManager.getHScroll();

        // Verify Scanlines
        // Segment 0: Height 37. BG X Offset = cameraX - segScroll[0] = 256 - 230 = 26
        // Lines 0..36
        verifyLines(hScroll, 0, 37, -cameraX, 26);

        // Segment 1: Height 23. BG X Offset = 256 - 204 = 52
        // Lines 37..(37+23)=60 (Exclusive)
        verifyLines(hScroll, 37, 23, -cameraX, 52);

        // Segment 7: Height 48 ("conceptual multiplier x0.1"). SegScroll[7]=25.
        // BG X Offset = 256 - 25 = 231.
        // Segment 7 starts at sum(Heights 0..6).
        // Heights: 37+23+18+7+7+2+2 = 96.
        // So Lines 96..143.
        verifyLines(hScroll, 96, 48, -cameraX, 231);
    }

    @Test
    public void testShake() {
        parallaxManager.setScreenShakeFlag(true);
        // Mock Ripple Data: idx 10 => Y=5, idx 11 => X=3
        mockRom.setRippleData(10, (byte) 5, (byte) 3);

        Camera cam = Camera.getInstance();
        setCamera(cam, 100, 960); // Act 0. bgY=0 normally.

        // Frame 10 (idx = 10 & 0x3F = 10)
        parallaxManager.update(ParallaxManager.ZONE_MCZ, 0, cam, 10, 0);

        // Expect vscrollFactorBG = bgY + rippleY = 0 + 5 = 5
        assertEquals(5, parallaxManager.getVscrollFactorBG());

        // Expect vscrollFactorFG = cameraY + rippleY = 960 + 5 = 965
        assertEquals((short) 965, parallaxManager.getVscrollFactorFG()); // Cast to short for check

        // Check Horizontal Parallax Shake
        // fgScroll = -(cameraX + rippleX) = -(100 + 3) = -103
        // bgOffset = cameraX - segScroll[seg] (Should not change with shake relative to
        // cameraX)
        // For line 0 (seg 0), at camX=100:
        // base = 100<<4 / 10 = 1600/10 = 160. baseFixed = 160<<12.
        // step9 (seg 0) roughly 0.9 * 100 = 90.
        // Let's verify formula creates 90?
        // 9 * 160 = 1440. 1440 >> 16 ? No.
        // accFixed logic:
        // step 1: 1*baseFixed -> val = 160>>4*1 = 10? No. 160 is base integer.
        // baseFixed is 160 * 4096.
        // 9 * baseFixed = 9 * 160 * 4096 = 5,898,240. >> 16 = 90. So segScroll[0] = 90.
        // offset = 100 - 90 = 10.

        // BG Scroll check:
        // hScroll entry = pack(fgScroll, fgScroll + offset)
        // fg = -103. offset = 10. bg = -93.

        int[] hScroll = parallaxManager.getHScroll();
        int val = hScroll[0];
        short fg = (short) (val >> 16);
        short bg = (short) (val & 0xFFFF);

        assertEquals((short) -103, fg);
        assertEquals((short) -93, bg);
    }

    private void setCamera(Camera cam, int x, int y) {
        // Reset to 0 first (Camera is singleton)
        cam.incrementX((short) -cam.getX());
        cam.incrementY((short) -cam.getY());

        // Set to target
        cam.incrementX((short) x);
        cam.incrementY((short) y);
    }

    private void verifyLines(int[] hScroll, int startLine, int count, int expectedFg, int expectedBgOffset) {
        for (int i = 0; i < count; i++) {
            int line = startLine + i;
            if (line >= 224)
                break;
            int val = hScroll[line];
            short fg = (short) (val >> 16);
            short bg = (short) (val & 0xFFFF);
            assertEquals("Line " + line + " FG", (short) expectedFg, fg);
            assertEquals("Line " + line + " BG", (short) (expectedFg + expectedBgOffset), bg);
        }
    }

    // Mock Classes
    static class MockRom extends Rom {
        private byte[] rippleData = new byte[256];

        public MockRom() {
            super();
        }

        @Override
        public byte[] readBytes(long offset, int count) throws IOException {
            if (offset == ParallaxTables.SWSCRL_MCZ_ROW_HEIGHTS_ADDR) {
                return MCZ_ROW_HEIGHTS;
            }
            if (offset == ParallaxTables.SWSCRL_RIPPLE_DATA_ADDR) {
                return rippleData;
            }
            return new byte[count];
        }

        public void setRippleData(int idx, byte y, byte x) {
            rippleData[idx] = y;
            rippleData[idx + 1] = x;
        }
    }
}
