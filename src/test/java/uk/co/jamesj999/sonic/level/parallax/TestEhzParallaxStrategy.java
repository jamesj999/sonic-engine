package uk.co.jamesj999.sonic.level.parallax;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.camera.Camera;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class TestEhzParallaxStrategy {

    private EhzParallaxStrategy strategy;
    private Camera camera;
    private byte[] rippleData;

    @Before
    public void setUp() {
        // Mock ripple data (simple pattern)
        rippleData = new byte[256];
        for(int i=0; i<rippleData.length; i++) {
            rippleData[i] = (byte)(i % 2); // 0, 1, 0, 1...
        }
        strategy = new EhzParallaxStrategy(rippleData);
        camera = mock(Camera.class);
    }

    @Test
    public void testEhzBands() {
        int[] hScroll = new int[224];

        // Setup Camera X = 100
        when(camera.getX()).thenReturn((short) 100);

        // d0 (FG) = -100
        // d2 (BG base) = -100

        strategy.update(hScroll, camera, 0, 0, 0);

        // Verify bands
        // 1. 22 lines: BG=0
        for(int i=0; i<22; i++) {
            assertBg(hScroll[i], 0, "Line " + i);
        }

        // 2. 58 lines: BG = d2 >> 6 = -100 >> 6 = -2
        // -100 = 0xFF9C
        // 0xFF9C >> 6 (arithmetic) -> 0xFFFE = -2
        for(int i=22; i<80; i++) {
            assertBg(hScroll[i], -2, "Line " + i);
        }

        // 3. 21 lines (Ripple): BG = (d2 >> 6) + ripple
        // ripple[0] = 0. BG = -2 + 0 = -2
        // ripple[1] = 1. BG = -2 + 1 = -1
        for(int i=80; i<101; i++) {
            int idx = i - 80;
            int expected = -2 + (idx % 2);
            assertBg(hScroll[i], expected, "Line " + i);
        }

        // 4. 11 lines: BG = 0
        for(int i=101; i<112; i++) {
            assertBg(hScroll[i], 0, "Line " + i);
        }

        // 5. 16 lines: BG = d2 >> 4 = -100 >> 4 = -7
        // -100 = 1111111110011100
        // >> 4 = 1111111111111001 = -7
        for(int i=112; i<128; i++) {
            assertBg(hScroll[i], -7, "Line " + i);
        }

        // 6. 16 lines: BG = (d2 >> 4) + ((d2 >> 4) >> 1)
        // -7 + (-7 >> 1) = -7 + -4 = -11
        for(int i=128; i<144; i++) {
            assertBg(hScroll[i], -11, "Line " + i);
        }

        // 7. Gradient logic follows...
        // 15 lines: d2 >> 3 = -100 >> 3 = -13
        for(int i=144; i<159; i++) {
            assertBg(hScroll[i], -13, "Line " + i);
        }
    }

    private void assertBg(int packed, int expectedBg, String msg) {
        short bg = (short)(packed & 0xFFFF);
        assertEquals(msg, expectedBg, bg);
    }
}
