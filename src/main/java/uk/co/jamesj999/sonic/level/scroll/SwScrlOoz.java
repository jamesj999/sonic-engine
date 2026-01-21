package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_OOZ (Oil Ocean Zone scroll routine).
 * Reference: s2.asm SwScrl_OOZ at ROM $CC66, SwScrl_RippleData at ROM $C682
 *
 * OOZ uses a unique parallax system with:
 * - Vertical scroll: Camera_BG_Y_pos = (Camera_Y_pos >> 3) + $50 (from InitCam_OOZ)
 * - Per-scanline horizontal scroll built from bottom upward with 12 segments
 * - Sun heat-haze effect using SwScrl_RippleData with animated phase
 *
 * The original routine fills the hscroll buffer from bottom to top (line 223 -> 0).
 * This implementation produces identical per-scanline values.
 *
 * Segment layout (from bottom of screen upward):
 * 0. Factory (variable height) - BG = BGX (full speed)
 * 1. Medium clouds (8 lines) - BG = BGX >> 3
 * 2. Slow clouds (8 lines) - BG = BGX >> 4
 * 3. Fast clouds (8 lines) - BG = BGX >> 2
 * 4. Slow clouds (7 lines) - BG = BGX >> 4
 * 5. Sun heat-haze (33 lines) - BG = rippleData[phase + i]
 * 6. Medium clouds (8 lines) - BG = BGX >> 3
 * 7. Slow clouds (8 lines) - BG = BGX >> 4
 * 8. Fast clouds (8 lines) - BG = BGX >> 2
 * 9. Slow clouds (8 lines) - BG = BGX >> 4
 * 10. Medium clouds (8 lines) - BG = BGX >> 3
 * 11. Empty sky (72 lines) - BG = BGX (full speed)
 */
public class SwScrlOoz implements ZoneScrollHandler {

    private final ParallaxTables tables;

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;

    // Persistent heat-haze phase counter (TempArray_LayerDef equivalent)
    // In the original, this is stored as a word in TempArray_LayerDef
    private int heatHazePhaseCounter;

    // Last frame counter used for phase update detection
    private int lastFrameForPhaseUpdate = -1;

    // Camera_BG_X_pos tracking (16.16 fixed-point for subpixel accuracy)
    // Initialized to 0 by InitCam_OOZ, updated each frame at 1/4 FG speed
    private long bgXPos;
    private int lastCameraX;
    private boolean initialized;

    // Ripple data length required (66 bytes for 33 lines + 31 phase offset)
    private static final int RIPPLE_DATA_REQUIRED = 66;

    // Segment heights (fixed, from disassembly)
    private static final int MEDIUM_CLOUDS_HEIGHT = 8;
    private static final int SLOW_CLOUDS_HEIGHT = 8;
    private static final int FAST_CLOUDS_HEIGHT = 8;
    private static final int SLOW_CLOUDS_2_HEIGHT = 7;
    private static final int SUN_HAZE_HEIGHT = 33;
    private static final int EMPTY_SKY_HEIGHT = 72;

    // Default ripple data (from ROM $C682) - used if ROM loading fails
    private static final byte[] DEFAULT_RIPPLE_DATA = {
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

    public SwScrlOoz(ParallaxTables tables) {
        this.tables = tables;
        this.heatHazePhaseCounter = 0;
        this.bgXPos = 0;
        this.lastCameraX = 0;
        this.initialized = false;
    }

    /**
     * Initialize OOZ background camera.
     * Camera_BG_X_pos is initialized to maintain the 1:4 ratio with Camera_X_pos.
     * This ensures the background starts at the correct offset for the initial camera position.
     */
    public void init(int cameraX, int cameraY) {
        // Initialize Camera_BG_X_pos to cameraX / 4 (quarter speed relationship)
        // In 16.16 fixed-point: (cameraX / 4) << 16 = cameraX << 14
        bgXPos = (long) cameraX << 14;
        lastCameraX = cameraX;
        initialized = true;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {

        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // ==================== Step 1: Initialize if needed ====================
        if (!initialized) {
            init(cameraX, cameraY);
        }

        // ==================== Step 2: Update BG camera positions ====================
        // OOZ: Camera_BG_Y_pos = (Camera_Y_pos >> 3) + $50
        int cameraBgYPos = (cameraY >> 3) + 0x50;

        // Update Camera_BG_X_pos based on camera movement (tracks at 1/4 FG speed)
        // Using 16.16 fixed-point for subpixel accuracy
        int cameraDiffX = cameraX - lastCameraX;
        bgXPos += (long) cameraDiffX << 14;  // Add quarter of diff in 16.16 format (diff << 16 >> 2 = diff << 14)
        lastCameraX = cameraX;

        // Extract integer pixel component of Camera_BG_X_pos
        int cameraBgXPos = (int) (bgXPos >> 16);

        // Set vertical scroll factor
        vscrollFactorBG = (short) cameraBgYPos;

        // ==================== Step 3: Update heat-haze phase ====================
        // Phase decrements every 8 frames: when (Vint_runcount + 3) & 7 == 0
        updateHeatHazePhase(frameCounter);

        // ==================== Step 4: Calculate factory height ====================
        // The factory height is based on Camera_BG_Y_pos with specific threshold logic
        int factoryHeight = calculateFactoryHeight(cameraBgYPos);

        // ==================== Step 5: Calculate scroll values ====================
        // FG scroll is constant: -Camera_X_pos
        short fgScroll = negWord(cameraX);

        // BGX = -Camera_BG_X_pos (the tracked BG camera position)
        // "Full BG speed" = BGX (factory/sky regions)
        // Cloud layers apply additional shifts to BGX for slower parallax
        short bgxFull = negWord(cameraBgXPos);               // Full BG speed (factory/sky)
        short bgxShift2 = negWord((short)(cameraBgXPos >> 2)); // BGX >> 2 (fast clouds)
        short bgxShift3 = negWord((short)(cameraBgXPos >> 3)); // BGX >> 3 (medium clouds)
        short bgxShift4 = negWord((short)(cameraBgXPos >> 4)); // BGX >> 4 (slow clouds)

        // ==================== Step 6: Fill buffer from bottom upward ====================
        // We track remaining lines (starts at 223, counts down)
        // But we fill the array in the correct order (final result)

        // Build a temp array representing bottom-to-top order
        short[] bgScrollValues = new short[VISIBLE_LINES];
        int lineFromBottom = 0;  // 0 = bottom line (screen line 223)

        // Segment 0: Factory (variable height)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, factoryHeight, bgxFull);

        // Segment 1: Medium clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, MEDIUM_CLOUDS_HEIGHT, bgxShift3);

        // Segment 2: Slow clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, SLOW_CLOUDS_HEIGHT, bgxShift4);

        // Segment 3: Fast clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, FAST_CLOUDS_HEIGHT, bgxShift2);

        // Segment 4: Slow clouds (7 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, SLOW_CLOUDS_2_HEIGHT, bgxShift4);

        // Segment 5: Sun heat-haze (33 lines)
        lineFromBottom = fillSunHazeSegment(bgScrollValues, lineFromBottom);

        // Segment 6: Medium clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, MEDIUM_CLOUDS_HEIGHT, bgxShift3);

        // Segment 7: Slow clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, SLOW_CLOUDS_HEIGHT, bgxShift4);

        // Segment 8: Fast clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, FAST_CLOUDS_HEIGHT, bgxShift2);

        // Segment 9: Slow clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, SLOW_CLOUDS_HEIGHT, bgxShift4);

        // Segment 10: Medium clouds (8 lines)
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, MEDIUM_CLOUDS_HEIGHT, bgxShift3);

        // Segment 11: Empty sky (72 lines) - fills remainder
        lineFromBottom = fillSegment(bgScrollValues, lineFromBottom, EMPTY_SKY_HEIGHT, bgxFull);

        // ==================== Step 7: Convert to screen order and pack ====================
        // bgScrollValues is in bottom-to-top order, need to reverse to top-to-bottom
        for (int screenLine = 0; screenLine < VISIBLE_LINES; screenLine++) {
            int fromBottom = VISIBLE_LINES - 1 - screenLine;
            short bgScroll = (fromBottom < bgScrollValues.length) ? bgScrollValues[fromBottom] : bgxFull;

            horizScrollBuf[screenLine] = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);
        }
    }

    /**
     * Calculate factory height using exact 68000 unsigned 16-bit arithmetic.
     *
     * Assembly logic (translated literally with proper unsigned wraparound):
     *   d1 = Camera_BG_Y_pos (word, unsigned)
     *   subi.w #80, d1
     *   bcs.s .zeroed   ; if borrow (d1 was < 80), set d1 = 0
     *   subi.w #176, d1
     *   bcc.s .zeroed   ; if NO borrow (d1 was >= 176), set d1 = 0
     *   ; (continues with d1 containing the wrapped 16-bit result)
     *   .zeroed:
     *   addi.w #223, d1
     *   ; Result = d1 + 1 (number of lines via dbf loop)
     *
     * The key insight is that when 80 <= bgY < 256:
     * - First sub doesn't borrow, d1 = bgY - 80 (0..175)
     * - Second sub DOES borrow since result < 176, giving wrapped value
     * - Adding 223 wraps again, producing a factory height < 224
     *
     * @param cameraBgYPos Current background Y position (word)
     * @return Number of factory lines to output (0 to 224)
     */
    int calculateFactoryHeight(int cameraBgYPos) {
        // Treat as unsigned 16-bit word
        int d1 = cameraBgYPos & 0xFFFF;

        // subi.w #80,d1; bcs.s (branch if carry/borrow)
        // Check borrow BEFORE updating d1 (borrow means original d1 < 80)
        boolean borrow1 = d1 < 80;
        d1 = (d1 - 80) & 0xFFFF;  // Wrap to 16-bit unsigned

        if (borrow1) {
            // Borrow occurred (original d1 was < 80), set d1 = 0
            d1 = 0;
        }

        // subi.w #176,d1; bcc.s (branch if NO carry/borrow)
        // Check borrow BEFORE updating d1 (no borrow means current d1 >= 176)
        boolean borrow2 = d1 < 176;
        d1 = (d1 - 176) & 0xFFFF;  // Wrap to 16-bit unsigned

        if (!borrow2) {
            // NO borrow (d1 was >= 176), set d1 = 0
            d1 = 0;
        }

        // addi.w #223,d1 (with 16-bit wrap)
        d1 = (d1 + 223) & 0xFFFF;

        // The routine outputs d1+1 lines (dbf loop counts from d1 down to -1)
        int factoryLines = d1 + 1;

        // Cap to visible lines
        return Math.min(factoryLines, VISIBLE_LINES);
    }

    /**
     * Fill a segment with constant BG scroll value.
     *
     * @param buffer Output buffer (bottom-to-top order)
     * @param startLine Starting line index from bottom
     * @param height Number of lines to fill
     * @param bgScroll BG scroll value
     * @return Next line index from bottom
     */
    private int fillSegment(short[] buffer, int startLine, int height, short bgScroll) {
        int linesToFill = Math.min(height, VISIBLE_LINES - startLine);
        for (int i = 0; i < linesToFill; i++) {
            buffer[startLine + i] = bgScroll;
        }
        return startLine + linesToFill;
    }

    /**
     * Fill the sun heat-haze segment (33 lines) with ripple animation.
     *
     * Each line reads from SwScrl_RippleData at index (phase + lineIndex).
     * Phase is 0..31, lineIndex is 0..32, so max index is 63 (within 66 bytes).
     *
     * @param buffer Output buffer (bottom-to-top order)
     * @param startLine Starting line index from bottom
     * @return Next line index from bottom
     */
    private int fillSunHazeSegment(short[] buffer, int startLine) {
        int phase = heatHazePhaseCounter & 0x1F;  // 0..31
        int linesToFill = Math.min(SUN_HAZE_HEIGHT, VISIBLE_LINES - startLine);

        for (int i = 0; i < linesToFill; i++) {
            int rippleIndex = phase + i;
            int rippleValue = getRippleSigned(rippleIndex);
            buffer[startLine + i] = (short) rippleValue;
        }

        return startLine + linesToFill;
    }

    /**
     * Get signed ripple value at index.
     * Uses ROM data if available, falls back to hardcoded data.
     */
    private int getRippleSigned(int index) {
        if (tables != null && index < tables.getRippleDataLength()) {
            return tables.getRippleSigned(index);
        }
        // Fallback to hardcoded data
        if (index >= 0 && index < DEFAULT_RIPPLE_DATA.length) {
            return DEFAULT_RIPPLE_DATA[index];  // Java bytes are signed
        }
        return 0;
    }

    /**
     * Update heat-haze phase counter.
     *
     * Original logic: Every 8 frames (when (Vint_runcount + 3) & 7 == 0),
     * decrement the phase counter (stored in TempArray_LayerDef).
     *
     * @param frameCounter Current frame number (Vint_runcount equivalent)
     */
    private void updateHeatHazePhase(int frameCounter) {
        // Check if we're on an update frame
        if (((frameCounter + 3) & 7) == 0) {
            // Only update once per qualifying frame
            if (frameCounter != lastFrameForPhaseUpdate) {
                heatHazePhaseCounter--;
                lastFrameForPhaseUpdate = frameCounter;
            }
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
     * Get the current heat-haze phase counter for testing.
     */
    public int getHeatHazePhaseCounter() {
        return heatHazePhaseCounter;
    }

    /**
     * Set the heat-haze phase counter for testing.
     */
    public void setHeatHazePhaseCounter(int counter) {
        this.heatHazePhaseCounter = counter;
    }

    /**
     * Reset the phase update tracking (for testing).
     */
    public void resetPhaseTracking() {
        this.lastFrameForPhaseUpdate = -1;
    }

    /**
     * Calculate factory height for testing.
     */
    public int calculateFactoryHeightForTest(int cameraBgYPos) {
        return calculateFactoryHeight(cameraBgYPos);
    }

    /**
     * Get ripple value for testing.
     */
    public int getRippleValueForTest(int index) {
        return getRippleSigned(index);
    }
}
