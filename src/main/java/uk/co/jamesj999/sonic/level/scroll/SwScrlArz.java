package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_ARZ (Aquatic Ruin Zone scroll routine).
 * Reference: s2.asm SwScrl_ARZ at ROM $C7xx
 *
 * ARZ uses a multi-speed horizontal parallax with 16 row segments.
 * The background is divided into bands that scroll at different speeds
 * to create depth from the waterfall/temple scenery.
 *
 * Key mechanics:
 * - BG X position uses a "smoothing" algorithm: target follows camera *
 * 0x0119/256
 * - Actual BG X ("fast") catches up to target by max ±16 pixels/frame
 * - Rows 1-3 and 12-16 use the smoothed "fast" X position
 * - Rows 4-11 use graduated speeds based on fixed-point accumulation
 *
 * Fixed-point camera model (16.16 format):
 * - arzBgXPos: Target BG X (16.16), updated by diffX * 0x0119
 * - arzBgXPosFast: Actual BG X (16.16), catches up to target with ±16 clamp
 * - arzBgYPos: BG Y (16.16), updated by diffY with act-dependent factor
 *
 * Row heights (from ROM offset 0xD5CE):
 * Row 0: 0xB0 (176 pixels) - ruins top
 * Row 1: 0x70 (112 pixels)
 * Row 2: 0x30 (48 pixels)
 * ... etc
 */
public class SwScrlArz implements ZoneScrollHandler {

    private final ParallaxTables tables;

    // Pre-defined row heights (matching ROM data at 0xD5CE)
    private static final int[] ROW_HEIGHTS = {
            0xB0, 0x70, 0x30, 0x60, 0x15, 0x0C, 0x0E, 0x06,
            0x0C, 0x1F, 0x30, 0xC0, 0xF0, 0xF0, 0xF0, 0xF0
    };

    private static final int TOTAL_ROW_HEIGHT = 1728; // Sum of all row heights

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;

    // 16.16 fixed-point background camera accumulators
    private long arzBgXPos; // Target BG X position (16.16 in long to avoid overflow)
    private long arzBgXPosFast; // Actual BG X position (16.16)
    private int arzBgYPos; // BG Y position (16.16)

    // Previous camera positions for diff calculation
    private int lastCameraX;
    private int lastCameraY;
    private boolean initialized;
    private int currentAct;

    // Pre-allocated array for row scroll speeds
    private final int[] rowScrollPx = new int[16];

    public SwScrlArz(ParallaxTables tables) {
        this.tables = tables;
        this.initialized = false;
    }

    /**
     * Initialize ARZ background cameras.
     * Matches InitCam_ARZ at ROM $C38C.
     */
    public void init(int actId, int cameraX, int cameraY) {
        currentAct = actId;
        lastCameraX = cameraX;
        lastCameraY = cameraY;

        // Initial BG X: (Camera_X_pos * $0119) >> 8
        // In 16.16: (cameraX * 0x0119) is effectively 24.8, shift to 16.16
        long initialBgX = (long) cameraX * 0x0119L;
        arzBgXPos = initialBgX << 8;
        arzBgXPosFast = arzBgXPos;

        // Initial BG Y (act-dependent)
        if (actId == 0) {
            // Act 1: Camera_Y_pos - $180
            arzBgYPos = (cameraY - 0x180) << 16;
        } else {
            // Act 2: (Camera_Y_pos - $E0) >> 1
            arzBgYPos = ((cameraY - 0xE0) >> 1) << 16;
        }

        initialized = true;
    }

    @Override
    public void update(int[] horizScrollBuf,
            int cameraX,
            int cameraY,
            int frameCounter,
            int actId) {

        if (!initialized || actId != currentAct) {
            init(actId, cameraX, cameraY);
        }

        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // ==================== Step 1: Calculate Camera Diffs ====================
        int diffX = cameraX - lastCameraX;
        int diffY = cameraY - lastCameraY;

        lastCameraX = cameraX;
        lastCameraY = cameraY;

        // ==================== Step 2: Update BG X (Smoothing Algorithm)
        // ====================
        // diffX in 8.8: diffX << 8
        // d4_fixed = (diffX << 8) * 0x0119 = diffX * 0x0119 << 8
        // This gives us the delta to add to the 16.16 accumulator
        long d4_fixed = (long) diffX * 0x0119L << 8;
        arzBgXPos += d4_fixed;

        // Smoothing: Actual position catches up to target by max ±16 pixels/frame
        long targetBGX = arzBgXPos >> 16;
        long currentBGX = arzBgXPosFast >> 16;
        long delta = targetBGX - currentBGX;

        // Clamp delta to [-16, +16]
        if (delta < -16)
            delta = -16;
        if (delta > 16)
            delta = 16;

        arzBgXPosFast += (delta << 16);

        // ==================== Step 3: Update BG Y ====================
        // d5_fixed = diffY << 15 (for act 2) or diffY << 16 (for act 1)
        int d5_fixed = diffY << 15;
        if (currentAct == 0) {
            d5_fixed <<= 1; // Act 1 scrolls faster vertically
        }
        arzBgYPos += d5_fixed;

        // Update vscrollFactorBG for external use
        vscrollFactorBG = (short) (arzBgYPos >> 16);

        // ==================== Step 4: Determine Starting Row ====================
        int bgY = arzBgYPos >> 16;
        // Normalize bgY to [0, TOTAL_ROW_HEIGHT)
        bgY %= TOTAL_ROW_HEIGHT;
        if (bgY < 0)
            bgY += TOTAL_ROW_HEIGHT;

        int currentRowIndex = 0;
        int remainingInRow = 0;

        int tempY = bgY;
        for (int i = 0; i < 16; i++) {
            int h = ROW_HEIGHTS[i];
            if (tempY < h) {
                currentRowIndex = i;
                remainingInRow = h - tempY;
                break;
            }
            tempY -= h;
        }

        // ==================== Step 5: Calculate Row Scroll Speeds ====================
        // The key fix: Use 16.16 fixed-point throughout to maintain precision
        //
        // Original algorithm:
        // q = ((Camera_X_pos << 4) / 10)
        // baseFixed = q << 12 (this is 20.12 fixed point)
        //
        // The problem: when cameraX is large, the integer division loses precision.
        // Fix: Use proper 32-bit (or 64-bit) fixed-point arithmetic.
        //
        // What we want: scroll values that are fractions of cameraX
        // The formula (cameraX << 4) / 10 effectively gives cameraX * 1.6
        // But we need to preserve fractional precision.
        //
        // Using 16.16 fixed point:
        // baseFixed_16_16 = (cameraX << 16) * 16 / 10 = (cameraX << 16) * 1.6
        // = cameraX * 0x19999 (approximately 1.6 in 16.16)
        //
        // However, to match original: q = (cameraX * 16) / 10, baseFixed = q << 12
        // Let's compute this more precisely using longs to avoid overflow

        // Original algorithm in 20.12 format:
        // q = (cameraX << 4) / 10 = cameraX * 1.6
        // baseFixed = q << 12 (20.12 format)
        // rowSpeed = baseFixed >> 16 = (cameraX * 1.6) >> 4 = cameraX * 0.1
        //
        // To match this in 16.16 format:
        // We want (baseFixed >> 16) to equal cameraX * 0.1
        // So baseFixed = cameraX * 0.1 * 65536 = cameraX * 6553.6
        //
        // Using long arithmetic: baseFixed = (cameraX << 16) * 16 / 10 / 16
        // = (cameraX << 16) / 10
        // = cameraX * 6553.6 (approx)
        long baseFixed = ((long) cameraX << 16) / 10; // cameraX * 0.1 in 16.16 format

        // Fill row speeds using the graduated multipliers
        // Row 3 (index 3): 1 * baseFixed
        // Row 4 (index 4): 3 * baseFixed
        // Row 5-9: 4, 5, 6, 7, 8 * baseFixed
        // Row 10: 9 * baseFixed
        long d1 = baseFixed;
        rowScrollPx[3] = (int) (d1 >> 16); // Row 4: 1x baseFixed = cameraX * 0.1

        d1 = d1 + d1 + baseFixed; // 3 * baseFixed
        for (int i = 4; i <= 9; i++) {
            rowScrollPx[i] = (int) (d1 >> 16);
            d1 += baseFixed;
        }
        rowScrollPx[10] = (int) (d1 >> 16); // Row 11

        // Rows 0-2 and 11-15 use the smoothed "fast" BG X position
        // This matches the original ROM: Camera_ARZ_BG_X_pos (smoothed via 0x0119
        // factor)
        // The value is cameraX * 0x0119 / 256 ≈ cameraX * 1.097
        int fastSpeed = (int) (arzBgXPosFast >> 16);
        rowScrollPx[0] = fastSpeed;
        rowScrollPx[1] = fastSpeed;
        rowScrollPx[2] = fastSpeed;
        rowScrollPx[11] = fastSpeed;
        rowScrollPx[12] = fastSpeed;
        rowScrollPx[13] = fastSpeed;
        rowScrollPx[14] = fastSpeed;
        rowScrollPx[15] = fastSpeed;

        // ==================== Step 6: Fill Scroll Buffer ====================
        short fgScroll = negWord(cameraX);
        int currentLine = 0;
        int rowIdx = currentRowIndex;
        int pixelsInRow = remainingInRow;

        while (currentLine < VISIBLE_LINES) {
            int speed = rowScrollPx[rowIdx];
            int count = Math.min(pixelsInRow, VISIBLE_LINES - currentLine);

            for (int k = 0; k < count; k++) {
                short bgScroll = negWord(speed);
                horizScrollBuf[currentLine++] = packScrollWords(fgScroll, bgScroll);

                int offset = bgScroll - fgScroll;
                if (offset < minScrollOffset)
                    minScrollOffset = offset;
                if (offset > maxScrollOffset)
                    maxScrollOffset = offset;
            }

            rowIdx = (rowIdx + 1) % 16;
            pixelsInRow = ROW_HEIGHTS[rowIdx];
        }
    }

    @Override
    public short getVscrollFactorBG() {
        return vscrollFactorBG;
    }

    @Override
    public int getMinScrollOffset() {
        return minScrollOffset;
    }

    @Override
    public int getMaxScrollOffset() {
        return maxScrollOffset;
    }

    /**
     * Reset state for zone/act change.
     */
    public void reset() {
        initialized = false;
        arzBgXPos = 0;
        arzBgXPosFast = 0;
        arzBgYPos = 0;
        lastCameraX = 0;
        lastCameraY = 0;
    }

    // ==================== Test Access Methods ====================

    /**
     * Get the target BG X position in pixels for testing.
     */
    public int getBgXTarget() {
        return (int) (arzBgXPos >> 16);
    }

    /**
     * Get the actual BG X position in pixels for testing.
     */
    public int getBgXActual() {
        return (int) (arzBgXPosFast >> 16);
    }

    /**
     * Get the BG Y position in pixels for testing.
     */
    public int getBgY() {
        return arzBgYPos >> 16;
    }
}
