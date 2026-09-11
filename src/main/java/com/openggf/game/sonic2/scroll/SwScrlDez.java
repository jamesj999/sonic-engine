package com.openggf.game.sonic2.scroll;

import com.openggf.game.GameServices;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.M68KMath;
import com.openggf.level.scroll.compose.ScrollEffectComposer;

/**
 * ROM-accurate implementation of SwScrl_DEZ (Death Egg Zone scroll routine).
 * Reference: s2.asm SwScrl_DEZ at ROM $D3CA, SwScrl_DEZ_RowHeights at ROM $D48A
 *
 * DEZ uses an elaborate star parallax with:
 * - 36 TempArray words updated each frame (persistent accumulation for star rows)
 * - 28 rows of 8-line star strips, each scrolling at its own speed
 * - 3 Earth edge rows computed via fixed-point arithmetic from star row 24's accumulator
 * - 1 transition row and 3 sky rows (static with camera)
 * - Vertical scroll: Camera_BG_Y_pos tracks at 1/1 ratio via Camera_Y_pos_diff << 8
 *
 * Row heights (ROM $D48A, 36 bytes):
 * [128, 8x28, 3, 5, 8, 16, 128, 128, 128]
 *
 * The first row (128 lines) is empty space that scrolls with the camera.
 * Stars are 28 rows of 8 lines each, with individual accumulation speeds.
 * Earth edge has 3 rows (3, 5, 8 lines) plus a 16-line transition.
 * Sky is 3 rows of 128 lines each (far more than needed).
 */
public class SwScrlDez extends AbstractZoneScrollHandler {

    private final ParallaxTables tables;
    private final BackgroundCamera bgCamera;

    // Persistent TempArray (36 words). The star/edge/transition words accumulate
    // a fixed amount each frame (ROM TempArray_LayerDef). They are derived from
    // the frame counter rather than accumulated on the update-call count so they
    // rewind correctly — a call-count accumulator drifts, snapping back only at
    // keyframe boundaries. See FrameScrollAccumulator for the same fix on cloud
    // layers; DEZ uses one shared anchor for all star words.
    private final int[] tempArray = new int[36];
    private int starBaseFrame;
    private boolean starBaseFrameSet = false;

    // Row heights from ROM ($D48A)
    // Default values matching the disassembly
    private static final int[] DEFAULT_ROW_HEIGHTS = {
            128,
            8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8,
            3, 5, 8, 16,
            128, 128, 128
    };

    // Star speeds for rows 1-24 (indices into tempArray)
    // These are the addq.w values from the disassembly
    private static final int[] STAR_SPEEDS = {
            3, 2, 4, 1, 2, 4, 3, 4, 2, 6, 3, 4,
            1, 2, 4, 3, 2, 3, 4, 1, 3, 4, 2, 1
    };

    private int[] rowHeights = DEFAULT_ROW_HEIGHTS;

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private int shakeTimer = -1;
    private int shakeOffsetX;
    private int shakeOffsetY;

    public SwScrlDez(ParallaxTables tables) {
        this(tables, null);
    }

    public SwScrlDez(ParallaxTables tables, BackgroundCamera bgCamera) {
        this.tables = tables;
        this.bgCamera = bgCamera;
        loadRowHeights();
    }

    private void loadRowHeights() {
        if (tables != null) {
            byte[] dezHeights = tables.getDezRowHeights();
            if (dezHeights != null && dezHeights.length >= 35) {
                rowHeights = new int[dezHeights.length];
                for (int i = 0; i < dezHeights.length; i++) {
                    rowHeights[i] = dezHeights[i] & 0xFF;
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

        resetScrollTracking();
        composer.reset();
        shakeOffsetX = 0;
        shakeOffsetY = 0;

        // ROM InitCameraValues seeds Camera_BG_Y_pos and InitCam_Null3 preserves
        // it. SwScrl_DEZ then reads that shared word after the generic
        // camera-delta update. Read its owner here rather than using the
        // handler's zero default.
        int effectiveBgY = bgCamera != null
                ? bgCamera.getBgYPos() & 0xFFFF
                : vscrollFactorBG & 0xFFFF;
        if (shakeTimer >= 0) {
            int rippleIndex = frameCounter & 0x3F;
            shakeOffsetY = tables != null ? tables.getRippleSigned(rippleIndex) : 0;
            shakeOffsetX = tables != null ? tables.getRippleSigned(rippleIndex + 1) : 0;
            composer.setVscrollFactorFG((short) (cameraY + shakeOffsetY));
            composer.setVscrollFactorBG((short) (effectiveBgY + shakeOffsetY));
            // fixBugs (s2.asm:27 `fixBugs = 0`): the shipped branch does NOT feed the
            // shake's Y component into the row-segment search. `add.w d3,d1` at
            // s2.asm:17566-17569 exists only under fixBugs=1, together with hoisting the
            // whole shake block from the end of SwScrl_DEZ (s2.asm:17600-17627) to the
            // start (s2.asm:17451-17480) so that d3 is live. On the shipped ROM the fill
            // uses the unshaken Camera_BG_Y_pos, which is exactly why the DEZ background
            // parallax visibly distorts while the final boss explodes; that distortion is
            // the behaviour to reproduce. Only the Vscroll factors and the camera copies
            // (above) take the shake.
            shakeTimer--;
            if (shakeTimer < 0 && GameServices.gameStateOrNull() != null) {
                GameServices.gameStateOrNull().setScreenShakeActive(false);
            }
        } else {
            composer.setVscrollFactorBG((short) effectiveBgY);
        }

        // ==================== Step 1: Vertical Scroll ====================
        // DEZ BG Y tracks via Camera_Y_pos_diff << 8 through SetHorizVertiScrollFlagsBG
        // For our purposes, the BG Y is maintained by BackgroundCamera
        // We set vscrollFactorBG from bgCamera (handled by ParallaxManager)
        // The disassembly writes Camera_BG_Y_pos to Vscroll_Factor_BG directly

        // ==================== Step 2: Update TempArray ====================
        updateTempArray(cameraX, frameCounter);

        // ==================== Step 3: Fill hscroll buffer ====================
        fillScrollBuffer(horizScrollBuf, cameraX, effectiveBgY);
    }

    /**
     * Update the TempArray (36 words) each frame.
     * This replicates the per-frame accumulation from SwScrl_DEZ.
     *
     * Assembly flow:
     * 1. Word 0 = Camera_X_pos (static with camera)
     * 2. Words 1-24: addq.w #speed,(a2)+ for each star row
     * 3. Word 24 value read into d0, d1; d0 >>= 1 stored as word 25 (half speed)
     * 4. Words 26-28: addq.w #3, #2, #4
     * 5. Words 29-31: Earth rows computed from d1 (word24 value) via fixed-point
     * 6. Word 32: addq.w #1
     * 7. Words 33-35: Camera_X_pos (sky, static)
     */
    private void updateTempArray(int cameraX, int frameCounter) {
        // Anchor the star accumulators on the first frame so their value counts
        // frames since the counters were zeroed (ROM clears TempArray at load),
        // independent of the update-call count. frames-1 offset reproduces the
        // "one increment on the first sampled frame" (increment-then-read).
        if (!starBaseFrameSet) {
            starBaseFrame = frameCounter - 1;
            starBaseFrameSet = true;
        }
        int frames = frameCounter - starBaseFrame;

        // Word 0: static with camera
        tempArray[0] = cameraX & 0xFFFF;

        // Words 1-24: star speeds accumulated over `frames` (wrapping at 16 bits).
        // int multiply wraps mod 2^32 identically to repeated addition.
        for (int i = 0; i < STAR_SPEEDS.length; i++) {
            tempArray[1 + i] = (frames * STAR_SPEEDS[i]) & 0xFFFF;
        }

        // Word 24 (index 24) was the last star row just accumulated above
        // Read it into d0 and d1
        int word24Value = tempArray[24] & 0xFFFF;

        // d1 = word24Value (moveq #0,d1; move.w d0,d1 - zero-extended to 32-bit)
        int d1 = word24Value;

        // d0 = d0 >> 1 (lsr.w #1,d0 - logical shift right, unsigned)
        int d0Half = (word24Value >>> 1) & 0xFFFF;
        // Store as word 25
        tempArray[25] = d0Half;

        // Words 26-28: more star speeds
        tempArray[26] = (frames * 3) & 0xFFFF;
        tempArray[27] = (frames * 2) & 0xFFFF;
        tempArray[28] = (frames * 4) & 0xFFFF;

        // Earth computation (words 29-31):
        // swap d1 -> d1 = word24Value << 16 (low word becomes high word, high word was 0)
        long d1_32 = ((long) d1) << 16;

        // move.l d1,d0 -> d0 = d1 (both 32-bit)
        long d0_32 = d1_32;

        // lsr.l #3,d1 -> logical shift right 3 bits
        d1_32 = d1_32 >>> 3;

        // sub.l d1,d0 -> d0 = d0 - d1
        d0_32 = d0_32 - d1_32;

        // swap d0 -> extract high word to low word
        // Store to 4(a2) = Earth row 31 (offset from current a2 position)
        tempArray[31] = (int) ((d0_32 >> 16) & 0xFFFF);

        // swap d0 back, sub.l d1, swap again
        // d0 is still the full 32-bit value, we just extracted the high word for storage
        // The assembly does: swap d0 (stored), swap d0 (restore), sub d1, swap, store
        d0_32 = d0_32 - d1_32;
        tempArray[30] = (int) ((d0_32 >> 16) & 0xFFFF);

        d0_32 = d0_32 - d1_32;
        tempArray[29] = (int) ((d0_32 >> 16) & 0xFFFF);

        // Word 32: transition row, accumulates at +1
        tempArray[32] = (frames * 1) & 0xFFFF;

        // Words 33-35: sky (static with camera)
        tempArray[33] = cameraX & 0xFFFF;
        tempArray[34] = cameraX & 0xFFFF;
        tempArray[35] = cameraX & 0xFFFF;
    }

    /**
     * Fill the hscroll buffer using TempArray values and row heights.
     * Replicates the segment loop from the disassembly (lines 17530-17567).
     *
     * Algorithm:
     * 1. Walk row heights, subtracting from bgY until we find the first visible row
     * 2. neg.w d1 to get remaining lines in that segment
     * 3. Fill 224 lines, advancing to next segment when current is exhausted
     *
     * FG scroll = negWord(cameraX) for all lines
     * BG scroll = negWord(tempArray[segmentIndex]) for each segment
     */
    private void fillScrollBuffer(int[] horizScrollBuf, int cameraX, int effectiveBgY) {
        int[] heights = rowHeights;
        int numSegments = Math.min(heights.length, tempArray.length);

        // d1 = Camera_BG_Y_pos (word)
        int d1 = effectiveBgY & 0xFFFF;

        // Find first visible segment by subtracting row heights
        int segIdx = 0;
        while (segIdx < numSegments) {
            int rowHeight = heights[segIdx] & 0xFF;
            // a2 is advanced past this segment's scroll value (addq.w #2,a2)
            segIdx++;
            d1 = (d1 - rowHeight) & 0xFFFF;
            // bcc.s = branch if carry clear = branch if result >= 0 (unsigned)
            // In 68k, sub.w sets carry if borrow occurred (src > dst before sub)
            // bcc = branch if no borrow = result was >= 0
            if (d1 >= 0x8000) {
                // Borrow occurred (result wrapped negative in unsigned 16-bit)
                break;
            }
            // If d1 is still positive (no borrow), continue to next segment
        }

        // Back up to the current segment (subq.w #2,a2 in assembly)
        segIdx--;

        // neg.w d1 = number of visible lines in this first segment
        d1 = (-d1) & 0xFFFF;
        // d1 is now the count of lines to draw for the current segment
        int linesInSegment = d1 & 0xFFFF;
        // Handle case where neg gives 0 (means full segment visible from top)
        if (linesInSegment == 0) {
            linesInSegment = heights[segIdx] & 0xFF;
        }

        // FG scroll constant for all lines
        short fgScroll = M68KMath.negWord(cameraX);

        // Get initial BG scroll from tempArray
        short bgScroll = M68KMath.negWord(tempArray[segIdx]);
        segIdx++;

        // Fill 224 lines (dbf d2,.rowLoop with d2 starting at 223)
        for (int line = 0; line < M68KMath.VISIBLE_LINES; line++) {
            composer.writePackedScrollWord(line, fgScroll, bgScroll);

            linesInSegment--;
            if (linesInSegment == 0 && line < M68KMath.VISIBLE_LINES - 1) {
                // Fetch new segment
                if (segIdx < numSegments) {
                    linesInSegment = heights[segIdx] & 0xFF;
                    bgScroll = M68KMath.negWord(tempArray[segIdx]);
                    segIdx++;
                }
            }
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    /**
     * Set the BG Y scroll factor.
     * Called by ParallaxManager after BackgroundCamera computes the value.
     */
    public void setVscrollFactorBG(short value) {
        this.vscrollFactorBG = value;
    }

    /**
     * ROM: ObjC7 writes Screen_Shaking_Flag=1 and DEZ_Shake_Timer to drive
     * SwScrl_DEZ's ripple offsets during stomp and ending rumble sequences.
     */
    public void triggerScreenShake(int frames) {
        this.shakeTimer = Math.max(0, frames);
    }

    public int getDezShakeTimer() {
        return shakeTimer;
    }

    @Override
    public short getVscrollFactorFG() {
        return composer.getVscrollFactorFG();
    }

    @Override
    public short getVscrollFactorBG() {
        return composer.getVscrollFactorBG();
    }

    @Override
    public int getShakeOffsetX() {
        return shakeOffsetX;
    }

    @Override
    public int getShakeOffsetY() {
        return shakeOffsetY;
    }

    // ==================== Test Access Methods ====================

    /**
     * Get the current TempArray values for testing.
     */
    public int[] getTempArray() {
        return tempArray.clone();
    }
}
