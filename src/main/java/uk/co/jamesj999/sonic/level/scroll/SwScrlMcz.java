package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_MCZ (Mystic Cave Zone scroll routine).
 * Reference: s2.asm SwScrl_MCZ
 *
 * MCZ uses per-segment horizontal parallax with segment heights loaded from
 * ROM.
 * The background is divided into 24 vertical segments that tile every 512
 * pixels.
 *
 * Segment structure (loaded from ROM offset 0xCE6C):
 * Index: start..end (height), conceptual multiplier
 * 0: 0.. 37 (37) x0.9
 * 1: 37.. 60 (23) x0.8
 * 2: 60.. 78 (18) x0.7
 * 3: 78.. 85 ( 7) x0.5
 * 4: 85.. 92 ( 7) x0.4
 * 5: 92.. 94 ( 2) x0.3
 * 6: 94.. 96 ( 2) x0.2
 * 7: 96..144 (48) x0.1
 * 8: 144..157 (13) x0.5
 * 9: 157..176 (19) x0.7
 * 10:176..208 (32) x0.8
 * 11:208..272 (64) x0.9
 * 12:272..304 (32) x0.8
 * 13:304..323 (19) x0.7
 * 14:323..336 (13) x0.5
 * 15:336..384 (48) x0.1
 * 16:384..386 ( 2) x0.2
 * 17:386..388 ( 2) x0.3
 * 18:388..395 ( 7) x0.4
 * 19:395..402 ( 7) x0.5
 * 20:402..434 (32) x0.6
 * 21:434..452 (18) x0.7
 * 22:452..475 (23) x0.8
 * 23:475..512 (37) x0.9
 */
public class SwScrlMcz implements ZoneScrollHandler {

    /** MCZ segment cycle height (sum of all 24 segment heights). */
    private static final int MCZ_CYCLE_HEIGHT = 512;

    private final ParallaxTables tables;

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;
    private short vscrollFactorFG;
    private int bgY; // Raw background Y position (without ripple)

    // Pre-allocated segment scroll array
    private final short[] segScroll = new short[24];

    // State
    private boolean screenShakeFlag = false;

    public SwScrlMcz(ParallaxTables tables) {
        this.tables = tables;
    }

    public void setScreenShakeFlag(boolean flag) {
        this.screenShakeFlag = flag;
    }

    @Override
    public void update(int[] horizScrollBuf,
            int cameraX,
            int cameraY,
            int frameCounter,
            int actId) {

        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // ==================== Step 1: Calculate BG Y (Act Dependent)
        // ====================
        // Original uses DIVU.W for unsigned division
        // Act 1 (actId == 0): bgY = floor(cameraY / 3) - 320 ($140)
        // Act 2 (actId != 0): bgY = floor(cameraY / 6) - 16 ($10)
        int bgY;
        if (actId == 0) {
            // Act 1: unsigned division by 3, then subtract 320
            bgY = floorUnsignedDiv(cameraY, 3) - 320;
        } else {
            // Act 2: unsigned division by 6, then subtract 16
            bgY = floorUnsignedDiv(cameraY, 6) - 16;
        }

        // ==================== Step 2: Screen Shake (Boss) ====================
        int rippleX = 0;
        int rippleY = 0;

        if (screenShakeFlag && tables != null) {
            int idx = frameCounter & 0x3F;
            byte[] rippleData = tables.getRippleData();
            if (rippleData != null && rippleData.length >= 66) {
                // ROM[0xC682 + idx] = rippleY (signed byte)
                // ROM[0xC682 + idx + 1] = rippleX (signed byte)
                rippleY = rippleData[idx]; // Java bytes are already signed
                rippleX = rippleData[idx + 1];
            }
        }

        // Store raw bgY for bgCamera update (without ripple)
        this.bgY = bgY;

        // Apply shake to vertical scroll factors
        vscrollFactorBG = (short) (bgY + rippleY);
        vscrollFactorFG = (short) (cameraY + rippleY);

        // ==================== Step 3: Build 24 Segment Scroll Values
        // ====================
        // Uses fixed-point accumulation matching 68000 behavior
        // base = floorSigned( ( (int32)cameraX << 4 ) / 10 )
        // baseFixed = base << 12
        buildSegmentScrollValues(cameraX);

        // ==================== Step 4: Expand to Scanlines ====================
        if (tables == null) {
            fillFallback(horizScrollBuf, cameraX);
            return;
        }

        byte[] rowHeights = tables.getMczRowHeights();
        if (rowHeights == null || rowHeights.length < 24) {
            fillFallback(horizScrollBuf, cameraX);
            return;
        }

        // Foreground X scroll (constant per scanline, includes horizontal shake)
        short fgScroll = negWord(cameraX + rippleX);

        // Treat bgY as vertical position in 512-pixel cycle
        // The parallax bands are based on the UNSHAKEN camera Y
        int yInCycle = bgY % MCZ_CYCLE_HEIGHT;
        if (yInCycle < 0) {
            yInCycle += MCZ_CYCLE_HEIGHT;
        }

        // Find the starting segment by walking through segment heights
        int seg = 0;
        while (seg < 24) {
            int h = rowHeights[seg] & 0xFF;
            if (yInCycle < h) {
                break;
            }
            yInCycle -= h;
            seg++;
        }

        // Safety clamp (shouldn't happen if heights sum to 512)
        if (seg >= 24) {
            seg = 0;
            yInCycle = 0;
        }

        // remainingInSeg = how many pixels left in current segment
        int remainingInSeg = (rowHeights[seg] & 0xFF) - yInCycle;

        // Fill 224 screen lines
        for (int screenLine = 0; screenLine < VISIBLE_LINES; screenLine++) {
            // BG scroll = -(segScroll[seg])
            // Note: We track segScroll as the camera-relative offset, so
            // bgScroll = fgScroll + (cameraX - segScroll[seg])
            short segScrollVal = segScroll[seg];
            short bgScroll = (short) (fgScroll + (cameraX - segScrollVal));

            horizScrollBuf[screenLine] = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);

            remainingInSeg--;
            if (remainingInSeg == 0) {
                seg++;
                if (seg >= 24) {
                    seg = 0; // Wrap around
                }
                remainingInSeg = rowHeights[seg] & 0xFF;
            }
        }
    }

    /**
     * Build 24 segment scroll values using fixed-point accumulation.
     *
     * Algorithm from original ROM:
     * base = ((cameraX << 4) / 10) [signed division]
     * baseFixed = base << 12
     *
     * Then accumulate 9 steps and map to segments:
     * step1 -> segScroll[15], segScroll[7]
     * step2 -> segScroll[16], segScroll[6]
     * step3 -> segScroll[17], segScroll[5]
     * step4 -> segScroll[18], segScroll[4]
     * step5 -> segScroll[19], segScroll[3], segScroll[8], segScroll[14]
     * step6 -> segScroll[20]
     * step7 -> segScroll[21], segScroll[2], segScroll[9], segScroll[13]
     * step8 -> segScroll[22], segScroll[1], segScroll[10], segScroll[12]
     * step9 -> segScroll[23], segScroll[0], segScroll[11]
     */
    private void buildSegmentScrollValues(int cameraX) {
        // base = floorSigned( (cameraX << 4) / 10 )
        // Java integer division truncates toward zero for positive, floors for negative
        // which matches 68000 DIVS behavior
        int shifted = cameraX << 4;
        int base = shifted / 10;

        // baseFixed = base << 12 (32-bit accumulator)
        int baseFixed = base << 12;

        // accumulator starts at baseFixed (step 1 value)
        int accFixed = baseFixed;

        // Process 9 steps
        for (int step = 1; step <= 9; step++) {
            // Extract the word value (signed 16-bit from bits 16-31 of accumulator)
            short stepWord = (short) (accFixed >> 16);

            // Map to segment indices based on original ROM mapping
            switch (step) {
                case 1:
                    segScroll[15] = stepWord;
                    segScroll[7] = stepWord;
                    break;
                case 2:
                    segScroll[16] = stepWord;
                    segScroll[6] = stepWord;
                    break;
                case 3:
                    segScroll[17] = stepWord;
                    segScroll[5] = stepWord;
                    break;
                case 4:
                    segScroll[18] = stepWord;
                    segScroll[4] = stepWord;
                    break;
                case 5:
                    segScroll[19] = stepWord;
                    segScroll[3] = stepWord;
                    segScroll[8] = stepWord;
                    segScroll[14] = stepWord;
                    break;
                case 6:
                    segScroll[20] = stepWord;
                    break;
                case 7:
                    segScroll[21] = stepWord;
                    segScroll[2] = stepWord;
                    segScroll[9] = stepWord;
                    segScroll[13] = stepWord;
                    break;
                case 8:
                    segScroll[22] = stepWord;
                    segScroll[1] = stepWord;
                    segScroll[10] = stepWord;
                    segScroll[12] = stepWord;
                    break;
                case 9:
                    segScroll[23] = stepWord;
                    segScroll[0] = stepWord;
                    segScroll[11] = stepWord;
                    break;
            }

            accFixed += baseFixed;
        }
    }

    /**
     * Unsigned integer division that floors (truncates toward negative infinity).
     * Matches 68000 DIVU.W behavior for positive dividends.
     */
    private static int floorUnsignedDiv(int dividend, int divisor) {
        if (divisor == 0)
            return 0;
        // For positive dividend, regular integer division is correct
        if (dividend >= 0) {
            return dividend / divisor;
        }
        // For negative dividend, we need floor division
        // dividend = divisor * quotient + remainder
        // We want the largest q such that divisor * q <= dividend
        return (dividend - divisor + 1) / divisor;
    }

    private void trackOffset(short fgScroll, short bgScroll) {
        int offset = bgScroll - fgScroll;
        if (offset < minScrollOffset) {
            minScrollOffset = offset;
        }
        if (offset > maxScrollOffset) {
            maxScrollOffset = offset;
        }
    }

    private void fillFallback(int[] horizScrollBuf, int cameraX) {
        short fgScroll = negWord(cameraX);
        short bgScroll = asrWord(cameraX, 1); // 0.5x scroll as fallback
        int packed = packScrollWords(fgScroll, bgScroll);

        for (int i = 0; i < VISIBLE_LINES; i++) {
            horizScrollBuf[i] = packed;
        }

        minScrollOffset = bgScroll - fgScroll;
        maxScrollOffset = minScrollOffset;
    }

    @Override
    public short getVscrollFactorBG() {
        return vscrollFactorBG;
    }

    public short getVscrollFactorFG() {
        return vscrollFactorFG;
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
     * Get the raw background Y position (without screen shake ripple).
     * Used by ParallaxManager to update bgCamera for proper vertical scrolling.
     */
    public int getBgY() {
        return bgY;
    }

    // ==================== Test Access Methods ====================

    /**
     * Get the segment scroll array for testing.
     * Returns a copy to prevent external modification.
     */
    public short[] getSegScrollForTest() {
        return segScroll.clone();
    }

    /**
     * Build segment scroll values and return for testing.
     * Does not require full update().
     */
    public short[] buildAndGetSegScroll(int cameraX) {
        buildSegmentScrollValues(cameraX);
        return segScroll.clone();
    }
}
