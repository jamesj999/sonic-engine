package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_CNZ (Casino Night Zone scroll routine).
 * Reference: s2.asm SwScrl_CNZ, SwScrl_CNZ_GenerateScrollValues, SwScrl_RippleData
 *
 * CNZ uses a unique parallax system with:
 * - Vertical scroll: Camera_BG_Y_pos = Camera_Y_pos >>> 6 (1/64 speed)
 * - 10 horizontal scroll segments with segment-based speeds
 * - One special "rippling" segment (16 lines) with per-line animation
 *
 * Row heights table (ROM 0xD156, 10 bytes):
 * [16, 16, 16, 16, 16, 16, 16, 16, 0, 240]
 * - First 8 entries: 16 lines each
 * - Entry 8 (0): Special rippling segment marker (still 16 lines tall)
 * - Entry 9 (240): Remainder (loop exits after 224 lines anyway)
 *
 * Scroll value generation (10 values) uses exact 68000 fixed-point arithmetic:
 * - Values 0-6: Accumulated gradient from Camera_X_pos
 * - Values 7-8: X >> 4 (1/16 speed)
 * - Value 9: X >> 3 (1/8 speed)
 */
public class SwScrlCnz implements ZoneScrollHandler {

    private final ParallaxTables tables;

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;

    // Row heights from ROM (0xD156)
    // [16, 16, 16, 16, 16, 16, 16, 16, 0, 240]
    // The 0 entry is a marker for the rippling segment (height is still 16)
    private static final int[] DEFAULT_ROW_HEIGHTS = {16, 16, 16, 16, 16, 16, 16, 16, 0, 240};

    // Ripple data length required (66 bytes for 32 index + 16 lines + some overlap)
    private static final int RIPPLE_DATA_REQUIRED = 66;

    // Pre-allocated array for 10 scroll values (TempArray_LayerDef equivalent)
    private final short[] scrollValues = new short[10];

    // Loaded row heights (allow ROM override)
    private int[] rowHeights = DEFAULT_ROW_HEIGHTS;

    public SwScrlCnz(ParallaxTables tables) {
        this.tables = tables;
        // Load row heights from tables if available
        loadRowHeights();
    }

    private void loadRowHeights() {
        if (tables != null) {
            byte[] cnzHeights = tables.getCnzRowHeights();
            if (cnzHeights != null && cnzHeights.length >= 10) {
                rowHeights = new int[10];
                for (int i = 0; i < 10; i++) {
                    rowHeights[i] = cnzHeights[i] & 0xFF;
                }
            }
        }
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {

        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // ==================== Step 1: Vertical Scroll ====================
        // CNZ: Camera_BG_Y_pos = Camera_Y_pos >>> 6 (unsigned logical shift)
        // Treat cameraY as 16-bit unsigned, then logical shift right 6
        int cameraBgYPos = (cameraY & 0xFFFF) >>> 6;
        vscrollFactorBG = (short) cameraBgYPos;

        // ==================== Step 2: Generate 10 Scroll Values ====================
        generateScrollValues((short) cameraX);

        // ==================== Step 3: Determine Starting Segment ====================
        // Use Camera_BG_Y_pos to find which segment is at the top of screen
        int bgY = cameraBgYPos;
        int segmentIndex = 0;

        // Find starting segment by subtracting heights
        // Loop until y goes negative (meaning we're partway through current segment)
        while (segmentIndex < rowHeights.length) {
            int height = getSegmentHeight(segmentIndex);
            if (bgY < height) {
                break;
            }
            bgY -= height;
            segmentIndex++;
        }

        // bgY now contains how many lines into current segment we are
        // So lines remaining in this segment = height - bgY
        int currentSegment = segmentIndex % rowHeights.length;
        int linesRemainingInSegment = getSegmentHeight(currentSegment) - bgY;
        if (linesRemainingInSegment <= 0) {
            // Move to next segment
            currentSegment = (currentSegment + 1) % rowHeights.length;
            linesRemainingInSegment = getSegmentHeight(currentSegment);
        }

        // ==================== Step 4: Fill Scroll Buffer ====================
        short fgScroll = negWord(cameraX);
        int currentLine = 0;

        while (currentLine < VISIBLE_LINES) {
            int height = rowHeights[currentSegment];

            if (height == 0) {
                // Special rippling segment (16 lines)
                fillRippleSegment(horizScrollBuf, currentLine, fgScroll,
                        scrollValues[currentSegment], frameCounter);
                currentLine += 16;
                linesRemainingInSegment = 0;
            } else {
                // Normal segment - use the scroll value for this segment
                short bgScroll = negWord(scrollValues[currentSegment]);
                int packed = packScrollWords(fgScroll, bgScroll);
                trackOffset(fgScroll, bgScroll);

                int linesToFill = Math.min(linesRemainingInSegment, VISIBLE_LINES - currentLine);
                for (int i = 0; i < linesToFill; i++) {
                    horizScrollBuf[currentLine++] = packed;
                }
                linesRemainingInSegment -= linesToFill;
            }

            // Move to next segment if we've exhausted current one
            if (linesRemainingInSegment <= 0) {
                currentSegment = (currentSegment + 1) % rowHeights.length;
                linesRemainingInSegment = getSegmentHeight(currentSegment);
            }
        }
    }

    /**
     * Get the actual height for a segment (0 means ripple segment, still 16 lines).
     */
    private int getSegmentHeight(int index) {
        int h = rowHeights[index % rowHeights.length];
        return (h == 0) ? 16 : h;
    }

    /**
     * Generate 10 scroll values using exact 68000 arithmetic.
     * Reference: SwScrl_CNZ_GenerateScrollValues
     *
     * Algorithm (emulating 68000 operations):
     * 1. d0 = (X >> 3) - X (arithmetic shift, signed 16-bit result)
     * 2. Sign-extend d0 to 32-bit
     * 3. d0 <<= 13 (done as <<5 then <<8 in original)
     * 4. Maintain 32-bit accumulator d3, low 16 bits initially = X
     * 5. For 7 iterations:
     *    - Store low word of d3 into values[i]
     *    - Swap high/low halves of d3
     *    - d3 += d0 (32-bit add)
     *    - Swap d3 back
     * 6. values[9] = X >> 3
     * 7. values[7] = values[8] = X >> 4
     *
     * @param cameraX Camera X position (16-bit signed)
     */
    private void generateScrollValues(short cameraX) {
        // Step 1: d0 = (X >> 3) - X (signed arithmetic)
        short xShift3 = (short) (cameraX >> 3);  // asr.w #3,d0
        int d0 = (short) (xShift3 - cameraX);     // sub.w d2,d0 (result is signed word)

        // Step 2: Sign-extend to 32-bit (ext.l d0)
        int d0_32 = d0;  // Java int already sign-extends from short

        // Step 3: d0 <<= 13 (lsl.l #5,d0 then lsl.l #8,d0)
        d0_32 <<= 13;

        // Step 4: Initialize d3 with cameraX in low word, 0 in high word
        // In 68k: d3 contains X in low 16 bits
        int d3 = cameraX & 0xFFFF;  // Unsigned extension to 32-bit for accumulator

        // Step 5: Generate values[0..6] via 7 iterations
        for (int i = 0; i < 7; i++) {
            // Store low word of d3
            scrollValues[i] = (short) d3;

            // Swap d3 (exchange high and low words)
            d3 = swap32(d3);

            // d3 += d0 (32-bit add with wrap)
            d3 += d0_32;

            // Swap d3 back
            d3 = swap32(d3);
        }

        // Step 6: values[9] = X >> 3
        scrollValues[9] = xShift3;

        // Step 7: values[7] = values[8] = (X >> 3) >> 1 = X >> 4
        short xShift4 = (short) (cameraX >> 4);
        scrollValues[7] = xShift4;
        scrollValues[8] = xShift4;
    }

    /**
     * Swap high and low 16-bit halves of a 32-bit value.
     * Equivalent to 68k SWAP instruction.
     */
    private static int swap32(int value) {
        return ((value & 0xFFFF) << 16) | ((value >>> 16) & 0xFFFF);
    }

    /**
     * Fill the rippling segment (16 lines) with per-line animation.
     * Reference: SwScrl_CNZ ripple handling
     *
     * The ripple animation index changes every 8 frames:
     * idx = (-((Vint_runcount + 3) >> 3)) & 0x1F
     *
     * For each of 16 lines:
     * - Read signed byte from RippleData[idx + lineOffset]
     * - Add to base BG scroll
     *
     * @param horizScrollBuf Output buffer
     * @param startLine Starting line index
     * @param fgScroll Foreground scroll value (constant)
     * @param baseScrollValue Base scroll value for this segment
     * @param frameCounter Current frame counter (Vint_runcount)
     */
    private void fillRippleSegment(int[] horizScrollBuf, int startLine,
                                    short fgScroll, short baseScrollValue,
                                    int frameCounter) {
        // Base background scroll (negated)
        short baseBgScroll = negWord(baseScrollValue);

        // Calculate ripple animation index
        // idx = (-((frameCounter + 3) >> 3)) & 0x1F
        int idx = (-(((frameCounter + 3) >> 3))) & 0x1F;

        // Fill 16 lines with ripple effect
        int linesToFill = Math.min(16, VISIBLE_LINES - startLine);
        for (int i = 0; i < linesToFill; i++) {
            int rippleOffset = 0;
            if (tables != null) {
                // Read signed byte from ripple data
                rippleOffset = tables.getRippleSigned((idx + i) & 0x3F);  // Mask to stay in bounds
            }

            // Add ripple offset to BG scroll
            short bgScroll = (short) (baseBgScroll + rippleOffset);
            horizScrollBuf[startLine + i] = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);
        }
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

    // ==================== Test Access Methods ====================

    /**
     * Get the generated scroll values for testing.
     * Returns a copy of the internal array.
     */
    public short[] getScrollValues() {
        return scrollValues.clone();
    }

    /**
     * Generate scroll values for a given camera X and return them.
     * For testing purposes.
     */
    public short[] generateScrollValuesForTest(short cameraX) {
        generateScrollValues(cameraX);
        return scrollValues.clone();
    }

    /**
     * Get the row heights being used.
     */
    public int[] getRowHeights() {
        return rowHeights.clone();
    }
}
