package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_HTZ (Hill Top Zone scroll routine).
 * Reference: s2.asm SwScrl_HTZ at ROM $C892 (lines 15779-15971)
 *
 * HTZ uses an elaborate parallax system with:
 * - First 128 lines: All scroll at the same rate (Camera_X >> 3)
 * - Cloud animation counter at TempArray_LayerDef+$22, incremented by 4/frame
 * - Remaining 96 lines: Complex gradient parallax for animated clouds
 *
 * The scroll routine calculates 16 values stored to TempArray_LayerDef (indices 0-15)
 * which Dynamic_HTZ uses to determine cloud tile offsets.
 *
 * Segment layout (lines 128-223, 96 lines total):
 * - 3 lines at scroll value 1
 * - 5 lines at scroll value 2
 * - 7 lines at scroll value 3
 * - 8 lines at scroll value 4
 * - 10 lines at scroll value 5
 * - 15 lines at scroll value 6
 * - 48 lines in 3 groups of 16 at values 7, 8, 9
 */
public class SwScrlHtz implements ZoneScrollHandler {

    private final ParallaxTables tables;
    private final BackgroundCamera bgCamera;

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;
    private short vscrollFactorFG;

    // Screen shake mode flag
    private boolean screenShakeActive = false;

    // Cloud animation counter (TempArray_LayerDef+$22 equivalent)
    // Incremented by 4 each frame
    private int cloudCounter = 0;

    // TempArray_LayerDef values for Dynamic_HTZ cloud art streaming
    // 16 word values at offsets 0-30 (indices 0-15)
    private final short[] tempArrayLayerDef = new short[16];

    private static final int VISIBLE_LINES = 224;
    private static final int STATIC_LINES = 128;  // First 128 lines use constant scroll

    public SwScrlHtz(ParallaxTables tables, BackgroundCamera bgCamera) {
        this.tables = tables;
        this.bgCamera = bgCamera;
    }

    /**
     * Initialize HTZ scroll state.
     * Called when entering HTZ to reset the cloud counter.
     */
    public void init() {
        cloudCounter = 0;
        screenShakeActive = false;
        for (int i = 0; i < 16; i++) {
            tempArrayLayerDef[i] = 0;
        }
    }

    /**
     * Set the screen shake mode flag.
     * When true, uses simplified scroll with ripple-based shake effect.
     */
    public void setScreenShakeActive(boolean active) {
        this.screenShakeActive = active;
    }

    public boolean isScreenShakeActive() {
        return screenShakeActive;
    }

    /**
     * Get the TempArray_LayerDef values for Dynamic_HTZ cloud art streaming.
     * Returns a copy of the 16 word values.
     */
    public short[] getTempArrayLayerDef() {
        return tempArrayLayerDef.clone();
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {

        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // Set vertical scroll factor from BG camera
        vscrollFactorBG = (short) bgCamera.getBgYPos();
        vscrollFactorFG = (short) cameraY;

        if (screenShakeActive) {
            updateScreenShake(horizScrollBuf, cameraX, cameraY, frameCounter);
        } else {
            updateNormal(horizScrollBuf, cameraX, cameraY, frameCounter);
        }
    }

    /**
     * Normal HTZ parallax scrolling (no screen shake).
     * Reference: s2.asm SwScrl_HTZ lines 15779-15971
     *
     * This is a line-by-line translation of the 68000 assembly.
     */
    private void updateNormal(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter) {
        // Line 15795-15800: move.w (Camera_X_pos).w,d0 / neg.w d0 / move.w d0,d2 / swap d0 / move.w d2,d0 / asr.w #3,d0
        short d0 = (short) -cameraX;
        short d2 = d0;  // d2 = -Camera_X_pos (FG scroll)
        // d0 is now packed: high word = d2 (FG), low word = d2 >> 3 (BG)
        int d0_long = ((d2 & 0xFFFF) << 16) | ((d2 >> 3) & 0xFFFF);

        // Lines 15802-15805: Fill first 128 lines with constant scroll
        for (int i = 0; i < STATIC_LINES; i++) {
            horizScrollBuf[i] = d0_long;
        }
        trackOffsetFromPacked(d0_long);

        // Line 15808: move.l d0,d4 (save the packed value for later)
        int d4 = d0_long;

        // Line 15809-15810: Read cloud counter and increment by 4
        // move.w (TempArray_LayerDef+$22).w,d0 / addq.w #4,(TempArray_LayerDef+$22).w
        short cloudScrollValue = (short) cloudCounter;
        cloudCounter = (cloudCounter + 4) & 0xFFFF;

        // Line 15813: sub.w d0,d2 (delta = -cameraX - cloudScrollValue)
        d2 = (short) (d2 - cloudScrollValue);

        // Lines 15820-15835: Calculate increment value d0
        // This complex calculation creates d0 which is the per-"step" increment
        // move.w d2,d0
        d0 = d2;
        // moveq #0,d1; move.w d0,d1 - d1 = delta in low word
        int d1_full = d0 & 0xFFFF;
        // asr.w #1,d0
        d0 = (short) (d0 >> 1);
        // swap d1 - d1 = delta << 16
        d1_full = d1_full << 16;
        // asr.l #4,d1 - d1 = delta << 12 (preserving fractional bits)
        d1_full = d1_full >> 4;
        // swap d1 - d1.low = integer part, d1.high = fractional part
        d1_full = ((d1_full & 0xFFFF) << 16) | ((d1_full >> 16) & 0xFFFF);
        // sub.w d1,d0 (d0 = d0 - d1.low, the integer part)
        d0 = (short) (d0 - (short) (d1_full & 0xFFFF));
        // ext.l d0
        int d0_ext = d0;  // sign-extended to 32 bits
        // asl.l #8,d0
        d0_ext = d0_ext << 8;
        // divs.w #112,d0 (112 = 256*44/100)
        if (d0_ext != 0) {
            d0_ext = d0_ext / 112;
        }
        // ext.l d0
        d0_ext = (short) d0_ext;  // sign-extend the quotient
        // asl.l #8,d0 (multiply by 256 for fixed-point)
        d0_ext = d0_ext << 8;

        // Line 15847: lea (TempArray_LayerDef).w,a2
        int a2_idx = 0;

        // Lines 15849-15860: Initialize d3 accumulator
        // For fixBugs version: move.l d1,d3 (d1 is the full 32-bit fixed-point value)
        long d3 = d1_full;  // Use long for 32-bit with overflow handling

        // Lines 15862-15867: First 3 entries (rept 3)
        // Each: swap d3; add.l d0,d3; swap d3; move.w d3,(a2)+
        for (int i = 0; i < 3; i++) {
            d3 = swap32(d3);
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = swap32(d3);
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
        }

        // Line 15868: move.w d3,(a2)+ (4th entry, no add)
        tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);

        // Lines 15869-15872: swap d3; add.l d0,d3; add.l d0,d3; swap d3
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);

        // Lines 15874-15883: Loop 4 times, storing d3 3 times, then adding d0 3 times
        for (int loop = 0; loop < 4; loop++) {
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
            tempArrayLayerDef[a2_idx++] = (short) (d3 & 0xFFFF);
            d3 = swap32(d3);
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = swap32(d3);
        }

        // Now output to Horiz_Scroll_Buf for lines 128-223

        // Lines 15886-15887: add.l d0,d0 twice (multiply d0 by 4)
        d0_ext = d0_ext << 2;  // d0 *= 4

        int line = STATIC_LINES;  // Start at line 128

        // Lines 15888-15900: Do 8 lines (3 + 5)
        // move.w d3,d4; move.l d4,(a1)+ (3 times)
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 3 && line < VISIBLE_LINES; i++, line++) {
            horizScrollBuf[line] = d4;
            trackOffsetFromPacked(d4);
        }
        // swap d3; add.l d0,d3; swap d3; move.w d3,d4; move.l d4,(a1)+ (5 times)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 5 && line < VISIBLE_LINES; i++, line++) {
            horizScrollBuf[line] = d4;
            trackOffsetFromPacked(d4);
        }

        // Lines 15902-15910: Do 7 lines
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 7 && line < VISIBLE_LINES; i++, line++) {
            horizScrollBuf[line] = d4;
            trackOffsetFromPacked(d4);
        }

        // Lines 15912-15921: Do 8 lines (2 adds)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 8 && line < VISIBLE_LINES; i++, line++) {
            horizScrollBuf[line] = d4;
            trackOffsetFromPacked(d4);
        }

        // Lines 15923-15932: Do 10 lines (2 adds)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 10 && line < VISIBLE_LINES; i++, line++) {
            horizScrollBuf[line] = d4;
            trackOffsetFromPacked(d4);
        }

        // Lines 15934-15944: Do 15 lines (3 adds)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);
        d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
        for (int i = 0; i < 15 && line < VISIBLE_LINES; i++, line++) {
            horizScrollBuf[line] = d4;
            trackOffsetFromPacked(d4);
        }

        // Lines 15946-15966: Do 48 lines in 3 groups of 16 (3 adds before first, 4 adds between)
        d3 = swap32(d3);
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
        d3 = swap32(d3);

        for (int group = 0; group < 3; group++) {
            d4 = (d4 & 0xFFFF0000) | ((int) d3 & 0xFFFF);
            for (int i = 0; i < 16 && line < VISIBLE_LINES; i++, line++) {
                horizScrollBuf[line] = d4;
                trackOffsetFromPacked(d4);
            }
            // 4 adds between groups
            d3 = swap32(d3);
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = (d3 + d0_ext) & 0xFFFFFFFFL;
            d3 = swap32(d3);
        }
    }

    /**
     * Swap high and low words of a 32-bit value.
     * Equivalent to 68000 SWAP instruction.
     */
    private long swap32(long value) {
        int v = (int) value;
        return ((v & 0xFFFF) << 16) | ((v >> 16) & 0xFFFF);
    }

    /**
     * HTZ screen shake mode scrolling.
     * Reference: s2.asm HTZ_Screen_Shake lines 15975-16029
     */
    private void updateScreenShake(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter) {
        // Apply screen shake using ripple data
        int shakeOffset = 0;
        int shakeOffsetH = 0;

        if (tables != null) {
            int rippleIndex = frameCounter & 0x3F;
            shakeOffset = tables.getRippleSigned(rippleIndex);
            if (rippleIndex + 1 < tables.getRippleDataLength()) {
                shakeOffsetH = tables.getRippleSigned(rippleIndex + 1);
            }
        }

        // Update vscroll factors with shake
        vscrollFactorFG = (short) (cameraY + shakeOffset);
        vscrollFactorBG = (short) (bgCamera.getBgYPos() + shakeOffset);

        // FG scroll with horizontal shake
        short fgScroll = negWord(cameraX + shakeOffsetH);

        // BG scroll with horizontal shake
        short bgScroll = negWord(bgCamera.getBgXPos() + shakeOffsetH);

        int packed = packScrollWords(fgScroll, bgScroll);

        // Fill all 224 lines with same value (no parallax during shake)
        for (int i = 0; i < VISIBLE_LINES; i++) {
            horizScrollBuf[i] = packed;
        }

        trackOffsetFromPacked(packed);
    }

    private void trackOffsetFromPacked(int packed) {
        short fg = (short) (packed >> 16);
        short bg = (short) packed;
        int offset = bg - fg;
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
}
