package com.openggf.game.sonic2.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

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
 *
 * fixBugs (s2.asm:27 `fixBugs = 0`, blocks at s2.asm:17329-17339 and 17349-17370):
 * the shipped (fixBugs=0) branch loops `screen_height/block_height + 1` blocks so a
 * partially-offscreen top block still leaves a full block for the bottom, which writes
 * up to 16 longwords past the 224 visible entries of Horiz_Scroll_Buf. Those extra
 * longwords land in the reserved `ds.l 16` slack that the HorizontalScrollBuffer struct
 * declares for exactly this overrun (s2.constants.asm:1191-1195), so nothing else in
 * RAM is touched and no visible line reads them. The engine therefore fills exactly
 * VISIBLE_LINES and stops: behaviourally identical to the shipped branch, not an
 * implementation of the fixBugs=1 branch (which instead splits the run into a
 * remainder pass so no overrun occurs at all). The same pair of conditionals guards
 * the unused SwScrl_HPZ_Continued at s2.asm:17944-17984.
 *
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
public class SwScrlCpz extends AbstractZoneScrollHandler {

    private static final int LINES_PER_BLOCK = 16;
    private static final int SEAM_BLOCK_INDEX = 18; // The water boundary block

    private final ParallaxTables tables;

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
    // Equivalent to TempArray_LayerDef ripple counter in original.
    // Derived from the frame counter each update() (see update()) rather than a
    // running per-call decrement, so it rewinds correctly. Retained as a field
    // only to back getRipplePhase().
    private int ripplePhase;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    public SwScrlCpz(ParallaxTables tables) {
        this.tables = tables;
        this.initialized = false;
        this.ripplePhase = 0;
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

        resetScrollTracking();
        composer.reset();

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
        // Ripple phase advances (decrements) once every 8 frames. Derived from
        // the frame counter (= -floor((frameCounter+1)/8)) instead of a running
        // per-call decrement, so held-rewind re-derivation reproduces the exact
        // phase for any frame instead of drifting off the update-call count. The
        // +1 keeps the historical cadence (decrements at frames 7, 15, 23, ...).
        ripplePhase = -((frameCounter + 1) / 8);

        // ==================== Step 4: Extract Integer Pixel Values
        // ====================
        int bg1Xpx = bg1X_16_16 >> 16;
        int bg2Xpx = bg2X_16_16 >> 16;
        int bgYpx = bgY_16_16 >> 16;

        // Set vscrollFactorBG for external use (renderer vertical scroll)
        composer.setVscrollFactorBG((short) bgYpx);

        // ==================== Step 5: Calculate Block Position ====================
        // Position within the current 16-pixel block (0-15)
        int lineInBlock = bgYpx & 0xF;
        // How many lines remain in this block before moving to the next
        int remainingInBlock = LINES_PER_BLOCK - lineInBlock;
        // Current block index (unwrapped to support deep levels)
        int currentBlockIdx = (bgYpx >> 4);

        // ==================== Step 6: Build Per-Scanline Scroll Buffer
        // ====================
        // FG scroll is constant for all lines
        short fgScroll = M68KMath.negWord(cameraX);

        int screenLine = 0;

        while (screenLine < M68KMath.VISIBLE_LINES) {
            // Use current block index directly for logic
            int blockIdx = currentBlockIdx;

            // How many lines to fill for this block (limited by screen end)
            int linesToFill = Math.min(remainingInBlock, M68KMath.VISIBLE_LINES - screenLine);

            // Determine BG X scroll for this block
            short bgScroll;

            if (blockIdx < SEAM_BLOCK_INDEX) {
                // Above seam: use BG1 (slow scroll)
                bgScroll = M68KMath.negWord(bg1Xpx);
                composer.fillPackedScrollWords(screenLine, linesToFill, fgScroll, bgScroll);
                screenLine += linesToFill;
            } else if (blockIdx > SEAM_BLOCK_INDEX) {
                // Below seam: use BG2 (fast scroll)
                bgScroll = M68KMath.negWord(bg2Xpx);
                composer.fillPackedScrollWords(screenLine, linesToFill, fgScroll, bgScroll);
                screenLine += linesToFill;
            } else {
                // Seam block (blockIdx == 18): Apply ripple effect
                int baseBg1Xpx = bg1Xpx;
                int rippleStart = ripplePhase & 0x1F; // 0..31 index into ripple data

                int posInBlock = LINES_PER_BLOCK - remainingInBlock;

                for (int i = 0; i < linesToFill; i++) {
                    int ripple = 0;
                    if (tables != null) {
                        int rippleIdx = (rippleStart + posInBlock + i) & 0x3F;
                        ripple = tables.getRippleByte(rippleIdx) & 0xFF;
                    }

                    bgScroll = M68KMath.negWord(baseBg1Xpx + ripple);
                    composer.writePackedScrollWord(screenLine++, fgScroll, bgScroll);
                }
            }

            // Move to next block - reset to full 16 lines
            currentBlockIdx++;
            remainingInBlock = LINES_PER_BLOCK;
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
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
