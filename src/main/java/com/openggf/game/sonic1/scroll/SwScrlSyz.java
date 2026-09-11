package com.openggf.game.sonic1.scroll;

import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import static com.openggf.level.scroll.M68KMath.*;

/**
 * JP1-accurate implementation of Deform_SYZ (Spring Yard Zone scroll routine).
 * Reference: s1disasm/_inc/DeformLayers (JP1).asm - Deform_SYZ
 *
 * SYZ uses a 33-entry scroll buffer (v_bgscroll_buffer) where each entry covers
 * 16 pixels of BG height. The buffer is indexed by bgscreenposy and applied to
 * the 224-line h-scroll table via the shared Bg_Scroll_X routine.
 *
 * Buffer layout (33 entries = 528px, wrapping around 512px BG):
 * <ul>
 *   <li>Clouds (8 entries, 128px): interpolation from 50% toward ~12.5%</li>
 *   <li>Mountains (5 entries, 80px): flat at 12.5%</li>
 *   <li>Buildings (6 entries, 96px): flat at 25%</li>
 *   <li>Bush/ground (14 entries, 224px): interpolation from 50% toward ~100%</li>
 * </ul>
 *
 * Horizontal scroll is position-based (computed from screenposx each frame),
 * not delta-accumulated. Vertical scroll uses delta-accumulated Bg_Scroll_Y
 * at scrshifty * 48/256 (~18.75%).
 */
public class SwScrlSyz extends AbstractZoneScrollHandler {

    private static final int BUFFER_SIZE = 33;
    private static final int LINES_PER_ENTRY = 16;

    // Delta-accumulated BG Y position (16.16 fixed point)
    private long bgYPos;

    private int lastCameraY;
    private boolean initialized = false;

    // Scroll buffer: 33 word entries, each covering 16px of BG height
    private final short[] scrollBuffer = new short[BUFFER_SIZE];

    private final ScrollEffectComposer composer = new ScrollEffectComposer();

    public void init(int cameraX, int cameraY) {
        // BgScroll_SYZ: bgscreenposy = screenposy * 48 / 256
        int initialBgY = (cameraY * 48) >> 8;
        bgYPos = (long) initialBgY << 16;
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

        // Vertical scroll: delta-accumulated at scrshifty * 48/256
        int deltaY = cameraY - lastCameraY;
        lastCameraY = cameraY;
        bgYPos += (long) deltaY * 48 * 256;
        int bgY = (int) (bgYPos >> 16);
        composer.setVscrollFactorBG((short) bgY);

        // Build scroll buffer (position-based, computed from screenposx each frame)
        buildScrollBuffer(cameraX);

        // Apply buffer to h-scroll table via Bg_Scroll_X pattern
        applyScrollBuffer(horizScrollBuf, cameraX, bgY);

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        vscrollFactorBG = composer.getVscrollFactorBG();
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
    }

    /**
     * Build the 33-entry scroll buffer matching Deform_SYZ (JP1).
     * All horizontal scroll values are computed from the current screenposx
     * using position-based formulas (not delta-accumulated).
     */
    private void buildScrollBuffer(int cameraX) {
        // d2 = neg.w screenposx
        short d2 = negWord(cameraX);
        int bufIdx = 0;

        // === Cloud section: 8 entries, interpolating from 50% toward ~12.5% ===
        // d0 = (d2 >> 3) - d2 = d2/8 - d2 = range from FG to 12.5%
        // Per-entry increment = range * 8 / 8 * 4096 (via asl/divs/asl pattern)
        // Start value: d2 >> 1 (50%)
        {
            short d0w = (short) (d2 >> 3);    // asr.w #3,d0
            d0w = (short) (d0w - d2);         // sub.w d2,d0
            int d0 = d0w;                     // ext.l
            d0 <<= 3;                         // asl.l #3
            d0 = (short) (d0 / 8);            // divs.w #8 (quotient in low word)
            d0 = (int) (short) d0;            // ext.l
            d0 <<= 12;                        // asl.l #4 + asl.l #8

            short startVal = (short) (d2 >> 1);  // asr.w #1
            bufIdx = interpolateEntries(bufIdx, 8, startVal, d0);
        }

        // === Mountain section: 5 entries flat at 12.5% ===
        {
            short val = (short) (d2 >> 3);    // asr.w #3
            for (int i = 0; i < 5; i++) {
                scrollBuffer[bufIdx++] = val;
            }
        }

        // === Building section: 6 entries flat at 25% ===
        {
            short val = (short) (d2 >> 2);    // asr.w #2
            for (int i = 0; i < 6; i++) {
                scrollBuffer[bufIdx++] = val;
            }
        }

        // === Bush section: 14 entries, interpolating from 50% toward ~100% ===
        // d0 = d2 - (d2 >> 1) = d2/2 (range from 50% to 100%)
        // Per-entry increment = range * 16 / 14 * 4096
        // Start value: d2 >> 1 (50%)
        {
            short d2half = (short) (d2 >> 1);    // asr.w #1
            short d0w = (short) (d2 - d2half);   // sub.w d1,d0
            int d0 = d0w;                        // ext.l
            d0 <<= 4;                            // asl.l #4
            d0 = (short) (d0 / 14);              // divs.w #$E
            d0 = (int) (short) d0;               // ext.l
            d0 <<= 12;                           // asl.l #4 + asl.l #8

            bufIdx = interpolateEntries(bufIdx, 14, d2half, d0);
        }
    }

    /**
     * Replicate 68k swap/add/swap fixed-point accumulation for interpolated sections.
     * <pre>
     *   moveq #0,d3 ; move.w startVal,d3
     *   .loop:
     *     move.w d3,(a1)+      ; write integer part
     *     swap d3              ; fraction to low word
     *     add.l increment,d3   ; accumulate
     *     swap d3              ; integer back to low word
     *     dbf d1,.loop
     * </pre>
     */
    private int interpolateEntries(int bufIdx, int count, short startVal, int increment) {
        // d3 = 0x0000 | (startVal & 0xFFFF) — high word is 0 (fraction), low word is value
        int d3 = startVal & 0xFFFF;

        for (int i = 0; i < count; i++) {
            scrollBuffer[bufIdx++] = (short) d3;
            // swap d3; add.l increment; swap d3
            d3 = swap16(d3);
            d3 += increment;
            d3 = swap16(d3);
        }
        return bufIdx;
    }

    /** Swap high and low 16-bit halves of a 32-bit value (68k SWAP instruction). */
    private static int swap16(int val) {
        return ((val & 0xFFFF) << 16) | ((val >>> 16) & 0xFFFF);
    }

    /**
     * Apply the scroll buffer to the 224-line h-scroll table.
     * Matches the shared Bg_Scroll_X routine from DeformLayers (JP1).asm.
     *
     * Reads 15 consecutive entries from the buffer (indexed by bgscreenposy),
     * writing 16 scanlines per entry. The first entry is partially written
     * based on the sub-16px vertical offset.
     */
    private void applyScrollBuffer(int[] horizScrollBuf, int cameraX, int bgY) {
        short fgScroll = negWord(cameraX);

        int entryIdx = (bgY & 0x1F0) >> 4;
        int subPixelOffset = bgY & 0xF;

        int lineIdx = 0;
        for (int block = 0; block < 15 && lineIdx < VISIBLE_LINES; block++) {
            short bgScroll = scrollBuffer[entryIdx % BUFFER_SIZE];
            entryIdx++;

            int linesToWrite = LINES_PER_ENTRY;
            if (block == 0) {
                linesToWrite -= subPixelOffset;
            }
            int writeCount = Math.min(linesToWrite, VISIBLE_LINES - lineIdx);
            composer.fillPackedScrollWords(lineIdx, writeCount, fgScroll, bgScroll);
            lineIdx += writeCount;
        }
    }

}
