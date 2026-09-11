package com.openggf.game.sonic1.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import static com.openggf.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of Deform_SLZ (Star Light Zone scroll routine).
 * Reference: s1disasm/_inc/DeformLayers.asm - Deform_SLZ + Deform_SLZ_2
 *
 * SLZ has a night sky background with banded parallax creating a pseudo-3D effect.
 * Uses ScrollBlock2 (accumulates both X and Y without block tracking for X).
 *
 * <p>BG camera:
 * <ul>
 *   <li>BG X: 50% speed (scrshiftx &lt;&lt; 7 = 128/256)</li>
 *   <li>BG Y: 50% speed + 0xC0 offset (BgScroll_SLZ: screenposy/2 + 0xC0)</li>
 * </ul>
 *
 * <p>Deform_SLZ_2 builds a scroll buffer with 4 bands:
 * <ol>
 *   <li>28 lines: Perspective interpolation from full speed to 1/8 speed</li>
 *   <li>5 lines: 1/8 speed</li>
 *   <li>5 lines: 1/4 speed</li>
 *   <li>30 lines: 1/2 speed</li>
 * </ol>
 * Total: 68 entries in the scroll buffer.
 *
 * <p>The main Deform_SLZ routine tiles this 68-entry buffer across the screen
 * at 16-line intervals, indexed by the BG Y scroll position.
 */
public class SwScrlSlz extends AbstractZoneScrollHandler {

    private static final int SCROLL_BUFFER_SIZE = 68;
    private static final int LINES_PER_GROUP = 16;

    // Persistent BG camera (16.16 fixed point)
    private long bgXPos;
    private long bgYPos;

    private int lastCameraX;
    private int lastCameraY;
    private boolean initialized = false;

    // Pre-allocated scroll buffer (Deform_SLZ_2 output)
    private final short[] scrollBuffer = new short[SCROLL_BUFFER_SIZE];

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    public void init(int cameraX, int cameraY) {
        // BgScrollSpeed sets bgscreenposx = screenposx
        bgXPos = (long) cameraX << 16;
        // BgScroll_SLZ: bgscreenposy = screenposy / 2 + 0xC0
        bgYPos = (long) ((cameraY >> 1) + 0xC0) << 16;
        lastCameraX = cameraX;
        lastCameraY = cameraY;
        initialized = true;
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

        int deltaX = cameraX - lastCameraX;
        int deltaY = cameraY - lastCameraY;
        lastCameraX = cameraX;
        lastCameraY = cameraY;

        // ScrollBlock2: d4 = scrshiftx << 7, d5 = scrshifty << 7
        bgXPos += (long) deltaX * 128 * 256;
        bgYPos += (long) deltaY * 128 * 256;

        int bgY = (int) (bgYPos >> 16);
        composer.setVscrollFactorBG((short) bgY);

        // FG scroll (high word of packed h-scroll entries)
        short fgScroll = negWord(cameraX);

        // ==================== Build scroll buffer (Deform_SLZ_2) ====================
        // d2.w = neg.w screenposx (16-bit negation, matching move.w/neg.w)
        short d2 = negWord(cameraX);

        // Band 1: 28 lines (0x1B + 1) - perspective interpolation
        // From d2 (full speed) to d2>>3 (1/8 speed)
        //
        // Disasm (Deform_SLZ_2):
        //   d0.w = d2 >> 3         ; asr.w #3,d0
        //   d0.w = d0 - d2         ; sub.w d2,d0 → (d2>>3) - d2
        //   ext.l d0               ; sign-extend to long
        //   d0 <<= 4              ; asl.l #4
        //   d0 = d0 / 28          ; divs.w #$1C (32/16 signed, quotient in low word)
        //   ext.l d0               ; sign-extend quotient, discard remainder
        //   d0 <<= 4              ; asl.l #4
        //   d0 <<= 8              ; asl.l #8  → total <<12 after division
        //
        // d3 = {0, d2}, loop: write d3.w, swap, add.l d0, swap
        {
            short shifted = (short) (d2 >> 3);              // asr.w #3,d0
            short diff = (short) (shifted - d2);            // sub.w d2,d0
            int d0 = diff;                                  // ext.l d0
            d0 <<= 4;                                       // asl.l #4
            d0 = (short) (d0 / 28);                         // divs.w #$1C; ext.l (quotient only)
            int increment = d0 << 12;                        // asl.l #4; asl.l #8

            // d3 = moveq #0,d3; move.w d2,d3 → high word 0, low word d2
            int d3 = d2 & 0xFFFF;
            for (int i = 0; i < 28; i++) {
                scrollBuffer[i] = (short) d3;               // move.w d3,(a1)+
                // swap d3; add.l d0,d3; swap d3
                d3 = ((d3 << 16) | ((d3 >>> 16) & 0xFFFF)); // swap
                d3 += increment;                              // add.l
                d3 = ((d3 << 16) | ((d3 >>> 16) & 0xFFFF)); // swap
            }
        }

        // Band 2: 5 lines - 1/8 speed (asr.w #3,d0)
        {
            short val = (short) (d2 >> 3);
            for (int i = 28; i < 33; i++) {
                scrollBuffer[i] = val;
            }
        }

        // Band 3: 5 lines - 1/4 speed (asr.w #2,d0)
        {
            short val = (short) (d2 >> 2);
            for (int i = 33; i < 38; i++) {
                scrollBuffer[i] = val;
            }
        }

        // Band 4: 30 lines (0x1D + 1) - 1/2 speed (asr.w #1,d0)
        {
            short val = (short) (d2 >> 1);
            for (int i = 38; i < 68; i++) {
                scrollBuffer[i] = val;
            }
        }

        // ==================== Tile scroll buffer across screen ====================
        // The scroll buffer represents a virtual 68-entry background strip.
        // The BG Y position determines which part is visible.
        //
        // Disasm (Deform_SLZ):
        //   d0 = bgscreenposy - 0xC0
        //   d0 &= 0x3F0           ; always a multiple of 16
        //   d0 >>= 3              ; BYTE offset into word array (0, 2, 4, ..., 126)
        //   lea (a2,d0.w),a2      ; advance pointer by d0 bytes
        //
        // Since each buffer entry is a word (2 bytes), the word index = byte_offset / 2.
        // Use >> 4 (not >> 3) to convert directly to an array index.

        int yOffset = ((bgY - 0xC0) & 0x3F0) >> 4;
        int subAlign = bgY & 0xF; // Sub-16-line alignment

        int lineIndex = 0;

        // First partial group (0 to 16-subAlign lines)
        int firstGroupLines = LINES_PER_GROUP - subAlign;
        if (firstGroupLines > 0 && firstGroupLines < LINES_PER_GROUP) {
            int bufIdx = yOffset % SCROLL_BUFFER_SIZE;
            short bgScroll = scrollBuffer[bufIdx];
            int writeCount = Math.min(firstGroupLines, VISIBLE_LINES - lineIndex);
            composer.fillPackedScrollWords(lineIndex, writeCount, fgScroll, bgScroll);
            lineIndex += writeCount;
            yOffset++;
        }

        // Full 16-line groups
        while (lineIndex < VISIBLE_LINES) {
            int bufIdx = yOffset % SCROLL_BUFFER_SIZE;
            short bgScroll = scrollBuffer[bufIdx];
            int writeCount = Math.min(LINES_PER_GROUP, VISIBLE_LINES - lineIndex);
            composer.fillPackedScrollWords(lineIndex, writeCount, fgScroll, bgScroll);
            lineIndex += writeCount;
            yOffset++;
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

}
