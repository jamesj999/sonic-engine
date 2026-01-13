package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_CPZ (Chemical Plant Zone scroll
 * routine).
 * Reference: s2.asm SwScrl_CPZ at ROM $D27C
 *
 * CPZ uses a two-layer parallax with an underwater split:
 * - BG1 (upper region): X scrolls at 1/8 camera, Y scrolls at 1/4 camera
 * - BG2 (lower "underwater" region): X scrolls at 1/2 camera, Y same as BG1
 *
 * The screen is processed in 16-line blocks (14 blocks for 224 lines).
 * Each block uses either BG1 or BG2 based on lineBlockIndex comparison:
 * - lineBlockIndex < 18: Use BG1 X (slow)
 * - lineBlockIndex > 18: Use BG2 X (fast)
 * - lineBlockIndex == 18: Seam block with ripple effect
 *
 * Key ROM data loaded:
 * - SwScrl_RippleData at $C682 (66 bytes): Small offsets (0..3) for wavy seam
 * - CPZ_CameraSections at $DDD0 (65 bytes): Camera selection map per block row
 *
 * Fixed-point camera model (16.16 format):
 * - bg1X_16_16 += (cameraXDiff_subpx << 5) -> effective 1/8 ratio
 * - bg2X_16_16 += (cameraXDiff_subpx << 7) -> effective 1/2 ratio
 * - bgY_16_16 += (cameraYDiff_subpx << 6) -> effective 1/4 ratio
 *
 * Ripple phase advances every 8 frames (matching EHZ behavior).
 */
public class SwScrlCpz implements ZoneScrollHandler {

    private static final int LINES_PER_BLOCK = 16;
    private static final int SEAM_BLOCK_INDEX = 18; // The water boundary block

    private final ParallaxTables tables;

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;

    // 16.16 fixed-point background camera accumulators
    // These track the cumulative BG position across frames
    private int bg1X_16_16; // BG1 X position (upper region, 1/8 speed)
    private int bg2X_16_16; // BG2 X position (lower region, 1/2 speed)
    private int bgY_16_16; // BG Y position (shared, 1/4 speed)

    // Previous camera positions for diff calculation
    private int lastCameraX;
    private int lastCameraY;
    private boolean initialized;

    // Ripple phase counter - decrements once every 8 frames
    // Equivalent to TempArray_LayerDef ripple counter in original
    private int ripplePhase;
    private int frameCounterForRipple; // Tracks frames for 8-frame decrement

    public SwScrlCpz(ParallaxTables tables) {
        this.tables = tables;
        this.initialized = false;
        this.ripplePhase = 0;
        this.frameCounterForRipple = 0;
    }

    /**
     * Initialize CPZ background cameras.
     * Matches InitCam_CPZ at ROM $C372:
     * - Camera_BG_Y_pos = Camera_Y_pos >> 2
     * - Camera_BG2_X_pos = Camera_X_pos >> 1
     * - Camera_BG_X_pos = Camera_X_pos >> 2 (but we use 1/8 for BG1)
     *
     * Note: The implementation uses 16.16 fixed point for subpixel accuracy.
     */
    public void init(int cameraX, int cameraY) {
        // Convert to 16.16 fixed point
        // BG1 X: 1/8 of camera X
        bg1X_16_16 = (cameraX >> 3) << 16;
        // BG2 X: 1/2 of camera X
        bg2X_16_16 = (cameraX >> 1) << 16;
        // BG Y: 1/4 of camera Y
        bgY_16_16 = (cameraY >> 2) << 16;

        lastCameraX = cameraX;
        lastCameraY = cameraY;
        initialized = true;

        ripplePhase = 0;
        frameCounterForRipple = 0;
    }

    @Override
    public void update(int[] horizScrollBuf,
            int cameraX,
            int cameraY,
            int frameCounter,
            int actId) {

        if (!initialized) {
            init(cameraX, cameraY);
        }

        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // ==================== Step 1: Calculate Camera Diffs ====================
        // Diffs in subpixels (1/256 pixel units, which is camera diff << 8)
        int cameraXDiff = cameraX - lastCameraX;
        int cameraYDiff = cameraY - lastCameraY;

        // Convert to 1/256 pixel units (8.8 fixed point as subpixels)
        int cameraXDiff_subpx = cameraXDiff << 8;
        int cameraYDiff_subpx = cameraYDiff << 8;

        lastCameraX = cameraX;
        lastCameraY = cameraY;

        // ==================== Step 2: Update BG Cameras (16.16 Fixed Point)
        // ====================
        // These shift values produce the correct parallax ratios:
        // bg1X: << 5 means += diff / 8 (since diff is already in 1/256, result
        // accumulates at 1/8 rate)
        // bg2X: << 7 means += diff / 2
        // bgY: << 6 means += diff / 4
        //
        // Derivation:
        // cameraXDiff_subpx is (cameraXDiff << 8)
        // bg1X_16_16 += (cameraXDiff_subpx << 5) = (cameraXDiff << 13)
        // After extracting integer: bg1X_16_16 >> 16 = cameraXDiff / 8
        bg1X_16_16 += (cameraXDiff_subpx << 5);
        bg2X_16_16 += (cameraXDiff_subpx << 7);
        bgY_16_16 += (cameraYDiff_subpx << 6);
        // Note: BG2 Y is same as BG1 Y (bgY_16_16), no separate tracking needed

        // ==================== Step 3: Update Ripple Phase ====================
        // Ripple phase advances (decrements) once every 8 frames
        // This matches EHZ behavior and fixes the "too fast" bug
        frameCounterForRipple++;
        if (frameCounterForRipple >= 8) {
            frameCounterForRipple = 0;
            ripplePhase--; // Decrement (wraps naturally with & 0x1F mask)
        }

        // ==================== Step 4: Extract Integer Pixel Values
        // ====================
        int bg1Xpx = bg1X_16_16 >> 16;
        int bg2Xpx = bg2X_16_16 >> 16;
        int bgYpx = bgY_16_16 >> 16;

        // Set vscrollFactorBG for external use (renderer vertical scroll)
        vscrollFactorBG = (short) bgYpx;

        // ==================== Step 5: Calculate Block Position ====================
        // Position within the current 16-pixel block (0-15)
        int lineInBlock = bgYpx & 0xF;
        // How many lines remain in this block before moving to the next
        int remainingInBlock = LINES_PER_BLOCK - lineInBlock;
        // Current block index (0..63, wraps every 1024px)
        int currentBlockIdx = ((bgYpx & 0x3F0) >> 4);

        // ==================== Step 6: Build Per-Scanline Scroll Buffer
        // ====================
        // FG scroll is constant for all lines
        short fgScroll = negWord(cameraX);

        int screenLine = 0;

        while (screenLine < VISIBLE_LINES) {
            // Wrap block index to 0..63 range
            int blockIdx = currentBlockIdx & 0x3F;

            // How many lines to fill for this block (limited by screen end)
            int linesToFill = Math.min(remainingInBlock, VISIBLE_LINES - screenLine);

            // Determine BG X scroll for this block
            short bgScroll;

            if (blockIdx < SEAM_BLOCK_INDEX) {
                // Above seam: use BG1 (slow scroll)
                bgScroll = negWord(bg1Xpx);
                // Fill linesToFill lines with same value
                int packed = packScrollWords(fgScroll, bgScroll);
                trackOffset(fgScroll, bgScroll);
                for (int i = 0; i < linesToFill; i++) {
                    horizScrollBuf[screenLine++] = packed;
                }
            } else if (blockIdx > SEAM_BLOCK_INDEX) {
                // Below seam: use BG2 (fast scroll)
                bgScroll = negWord(bg2Xpx);
                // Fill linesToFill lines with same value
                int packed = packScrollWords(fgScroll, bgScroll);
                trackOffset(fgScroll, bgScroll);
                for (int i = 0; i < linesToFill; i++) {
                    horizScrollBuf[screenLine++] = packed;
                }
            } else {
                // Seam block (blockIdx == 18): Apply ripple effect
                // Base is BG1 X, add ripple offset per scanline
                int baseBg1Xpx = bg1Xpx;
                int rippleStart = ripplePhase & 0x1F; // 0..31 index into ripple data

                // lineInBlock tells us where in the block we start (for first iteration)
                // For the seam block, we need to track the actual position within the block
                int posInBlock = LINES_PER_BLOCK - remainingInBlock; // 0-15 position we start at

                for (int i = 0; i < linesToFill; i++) {
                    // Get ripple offset from ROM data
                    // Use position within block + rippleStart for the ripple index
                    int ripple = 0;
                    if (tables != null) {
                        int rippleIdx = (rippleStart + posInBlock + i) & 0x3F;
                        ripple = tables.getRippleByte(rippleIdx) & 0xFF; // Unsigned 0..3
                    }

                    // Apply ripple: bgScrollXpx = baseBg1Xpx + ripple
                    bgScroll = negWord(baseBg1Xpx + ripple);
                    horizScrollBuf[screenLine++] = packScrollWords(fgScroll, bgScroll);
                    trackOffset(fgScroll, bgScroll);
                }
            }

            // Move to next block - reset to full 16 lines
            currentBlockIdx++;
            remainingInBlock = LINES_PER_BLOCK;
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

    /**
     * Reset state for zone change.
     */
    public void reset() {
        initialized = false;
        bg1X_16_16 = 0;
        bg2X_16_16 = 0;
        bgY_16_16 = 0;
        lastCameraX = 0;
        lastCameraY = 0;
        ripplePhase = 0;
        frameCounterForRipple = 0;
    }

    // ==================== Test Access Methods ====================

    /**
     * Get BG1 X position in pixels for testing.
     */
    public int getBg1Xpx() {
        return bg1X_16_16 >> 16;
    }

    /**
     * Get BG2 X position in pixels for testing.
     */
    public int getBg2Xpx() {
        return bg2X_16_16 >> 16;
    }

    /**
     * Get BG Y position in pixels for testing.
     */
    public int getBgYpx() {
        return bgY_16_16 >> 16;
    }

    /**
     * Get current ripple phase for testing.
     */
    public int getRipplePhase() {
        return ripplePhase;
    }
}
