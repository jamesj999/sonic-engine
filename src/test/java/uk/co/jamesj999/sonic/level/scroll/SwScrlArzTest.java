package uk.co.jamesj999.sonic.level.scroll;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for SwScrlArz (Aquatic Ruin Zone parallax scrolling).
 * Specifically tests for smooth scrolling at high X positions where
 * the previous implementation had precision issues causing jittery movement.
 */
public class SwScrlArzTest {

    private SwScrlArz handler;
    private int[] horizScrollBuf;

    @Before
    public void setUp() {
        // Create handler with null tables (uses hardcoded row heights)
        handler = new SwScrlArz(null);
        horizScrollBuf = new int[224];
    }

    @Test
    public void testInitialization() {
        handler.init(0, 1000, 500);

        // After init, BG X target should be approximately cameraX * 0x0119 / 256
        // 1000 * 0x0119 / 256 = 1000 * 281 / 256 ≈ 1097
        int targetX = handler.getBgXTarget();
        assertTrue("Target X should be around 1097, got: " + targetX,
                targetX > 1000 && targetX < 1200);
    }

    @Test
    public void testSmoothScrollingAtHighX() {
        // This test verifies that scrolling is smooth even at high X positions
        // (where the old implementation had precision issues)

        int startX = 8000;
        handler.init(0, startX, 100);
        handler.update(horizScrollBuf, startX, 100, 0, 0);

        // Collect BG scroll values for middle rows (rows 4-11 which use graduated
        // speeds)
        int lastBg = -1;
        int maxJump = 0;

        for (int x = startX; x < startX + 100; x++) {
            handler.update(horizScrollBuf, x, 100, 0, 0);

            // Get BG scroll from line 50 (should be in a graduated row)
            int packed = horizScrollBuf[50];
            short bg = (short) (packed & 0xFFFF);

            if (lastBg != -1) {
                int jump = Math.abs(bg - lastBg);
                if (jump > maxJump)
                    maxJump = jump;
            }
            lastBg = bg;
        }

        // With proper fixed-point arithmetic, max jump should be 1-2 pixels max
        // The old implementation could jump 5+ pixels at high X positions
        assertTrue("Background scroll should be smooth (max jump <= 3 pixels), got max jump: " + maxJump,
                maxJump <= 3);
    }

    @Test
    public void testSmoothScrollingAtVeryHighX() {
        // This test specifically targets the jitter bug that occurred at far-right
        // positions due to insufficient GPU texture precision (R16F vs R32F).
        // At X=25000+, the scroll values are large enough that half-float precision
        // caused visible per-frame jumps in the background.

        int startX = 25000;
        handler.init(0, startX, 100);
        handler.update(horizScrollBuf, startX, 100, 0, 0);

        int lastBg = -1;
        int maxJump = 0;

        for (int x = startX; x < startX + 100; x++) {
            handler.update(horizScrollBuf, x, 100, 0, 0);

            // Get BG scroll from line 50 (in a graduated row)
            int packed = horizScrollBuf[50];
            short bg = (short) (packed & 0xFFFF);

            if (lastBg != -1) {
                int jump = Math.abs(bg - lastBg);
                if (jump > maxJump)
                    maxJump = jump;
            }
            lastBg = bg;
        }

        // Even at X=25000+, jumps should not exceed 3 pixels per frame
        assertTrue("Scroll jitter at high X (max jump <= 3 pixels), got: " + maxJump,
                maxJump <= 3);
    }

    @Test
    public void testConsistentScrollRatioAtDifferentPositions() {
        // The scroll ratio should be consistent regardless of X position

        // Test at low X
        handler.init(0, 100, 100);
        handler.update(horizScrollBuf, 100, 100, 0, 0);
        int packedLow = horizScrollBuf[50];
        short fgLow = (short) (packedLow >> 16);
        short bgLow = (short) (packedLow & 0xFFFF);
        double ratioLow = (double) bgLow / fgLow;

        // Test at high X
        handler.init(0, 10000, 100);
        handler.update(horizScrollBuf, 10000, 100, 0, 0);
        int packedHigh = horizScrollBuf[50];
        short fgHigh = (short) (packedHigh >> 16);
        short bgHigh = (short) (packedHigh & 0xFFFF);
        double ratioHigh = (double) bgHigh / fgHigh;

        // The ratios should be similar (within 10%)
        double ratioDiff = Math.abs(ratioLow - ratioHigh) / ratioLow;
        assertTrue("Scroll ratio should be consistent at different X positions. " +
                "Low: " + ratioLow + ", High: " + ratioHigh + ", Diff: " + (ratioDiff * 100) + "%",
                ratioDiff < 0.1);
    }

    @Test
    public void testRowSpeedProgression() {
        // Verify that the handler produces valid scroll values at high camera positions
        handler.init(0, 5000, 400); // bgY = 400 - 384 = 16, near start of row 0
        handler.update(horizScrollBuf, 5000, 400, 0, 0);

        // Check that the handler produces valid scroll values (non-zero for this
        // camera)
        int scroll0 = (short) (horizScrollBuf[0] & 0xFFFF);
        assertTrue("Scroll should be non-zero for cameraX=5000", scroll0 != 0);

        // Verify FG scroll is correct
        short fgScroll = (short) (horizScrollBuf[0] >> 16);
        assertEquals("FG scroll should be -cameraX", (short) -5000, fgScroll);
    }

    @Test
    public void testAct1VsAct2YScroll() {
        // Act 1 and Act 2 have different Y scroll formulas

        // Act 1: bgY = cameraY - 0x180
        handler.init(0, 1000, 1000);
        handler.update(horizScrollBuf, 1000, 1000, 0, 0);
        int bgYAct1 = handler.getBgY();

        // Act 2: bgY = (cameraY - 0xE0) / 2
        handler.init(1, 1000, 1000);
        handler.update(horizScrollBuf, 1000, 1000, 0, 1);
        int bgYAct2 = handler.getBgY();

        // Expected values:
        // Act 1: 1000 - 0x180 = 1000 - 384 = 616
        // Act 2: (1000 - 0xE0) / 2 = (1000 - 224) / 2 = 388

        assertTrue("Act 1 bgY should be around 616, got: " + bgYAct1,
                Math.abs(bgYAct1 - 616) < 10);
        assertTrue("Act 2 bgY should be around 388, got: " + bgYAct2,
                Math.abs(bgYAct2 - 388) < 10);
    }
}
