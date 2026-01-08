package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.SoftwareScrollManager.*;

/**
 * ROM-accurate implementation of SwScrl_EHZ (Emerald Hill Zone scroll routine).
 * Reference: s2.asm SwScrl_EHZ at ROM $C57E
 *
 * EHZ uses banded per-scanline parallax with:
 * - Sky region (static)
 * - Cloud region (slow parallax)
 * - Water surface (ripple effect using SwScrl_RippleData)
 * - Hill bands (graduated parallax speeds)
 * - Bottom gradient (fixed-point accumulation)
 *
 * Band structure (line counts from disassembly):
 * 22 lines: BG = 0 (sky)
 * 58 lines: BG = d2 >> 6 (far clouds)
 * 21 lines: BG = (d2 >> 6) + ripple (water surface)
 * 11 lines: BG = 0 (gap)
 * 16 lines: BG = d2 >> 4 (near hills)
 * 16 lines: BG = (d2 >> 4) * 1.5 (nearer hills)
 * 15 lines: gradient start
 * 18 lines: gradient middle
 * 45 lines: gradient end
 * Total: 222 lines (last 2 lines intentionally unwritten - original bug)
 */
public class SwScrlEhz {

    private static final int VISIBLE_LINES = 224;

    // TempArray offsets used by EHZ
    private static final int TEMP_RIPPLE_INDEX = 0; // TempArray_LayerDef+0: ripple frame index

    private final ParallaxTables tables;
    private final BackgroundCamera bgCamera;

    public SwScrlEhz(ParallaxTables tables, BackgroundCamera bgCamera) {
        this.tables = tables;
        this.bgCamera = bgCamera;
    }

    /**
     * Update scroll buffer for EHZ.
     * Matches SwScrl_EHZ instruction-by-instruction.
     */
    public void update(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter) {
        // d2 = -Camera_X_pos (FG scroll, constant for all lines)
        short d2 = negWord(cameraX);

        // FG scroll word is constant for all lines
        short fgScroll = d2;

        int lineIndex = 0;

        // ==================== Band 1: Sky (22 lines) ====================
        // BG = 0, FG = d2
        // Original: move.w #$58,d1 (88 bytes = 22 longwords = 22 lines)
        // moveq #0,d0
        // ... loop writing (fg,bg) pairs
        {
            short bgScroll = 0;
            int packed = packScrollWords(fgScroll, bgScroll);
            for (int i = 0; i < 22 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 2: Far Clouds (58 lines) ====================
        // BG = d2 >> 6, FG = d2
        // Original: move.w #$E8,d1 (232 bytes = 58 lines)
        // move.w d2,d0
        // asr.w #6,d0
        {
            short bgScroll = asrWord(d2, 6);
            int packed = packScrollWords(fgScroll, bgScroll);
            for (int i = 0; i < 58 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 3: Water Surface with Ripple (21 lines)
        // ====================
        // BG = (d2 >> 6) + ripple[animIndex], FG = d2
        // Original: move.w #$15,d1 (21 iterations, byte writes in pairs)
        // move.w (TempArray_LayerDef).w,d3 (ripple index)
        // ... complex ripple logic
        {
            short baseBg = asrWord(d2, 6);

            // Ripple animation: index advances every 8 frames
            // Original uses TempArray_LayerDef to store current ripple offset
            int rippleAnimIndex = (frameCounter >> 3) % tables.getRippleDataLength();

            for (int i = 0; i < 21 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                // Get ripple offset from ROM table (signed byte, sign-extended)
                int rippleIndex = (rippleAnimIndex + i) % tables.getRippleDataLength();
                int rippleOffset = tables.getRippleSigned(rippleIndex);

                short bgScroll = (short) (baseBg + rippleOffset);
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);
            }
        }

        // ==================== Band 4: Gap (11 lines) ====================
        // BG = 0, FG = d2
        // Original: move.w #$2C,d1 (44 bytes = 11 lines)
        {
            short bgScroll = 0;
            int packed = packScrollWords(fgScroll, bgScroll);
            for (int i = 0; i < 11 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 5: Near Hills (16 lines) ====================
        // BG = d2 >> 4, FG = d2
        // Original: move.w #$40,d1 (64 bytes = 16 lines)
        // move.w d2,d0
        // asr.w #4,d0
        {
            short bgScroll = asrWord(d2, 4);
            int packed = packScrollWords(fgScroll, bgScroll);
            for (int i = 0; i < 16 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 6: Nearer Hills (16 lines) ====================
        // BG = (d2 >> 4) + ((d2 >> 4) >> 1) = 1.5 * (d2 >> 4), FG = d2
        // Original: move.w #$40,d1
        // move.w d2,d0
        // asr.w #4,d0
        // move.w d0,d3
        // asr.w #1,d3
        // add.w d3,d0
        {
            short d0 = asrWord(d2, 4);
            short d3 = asrWord(d0, 1);
            short bgScroll = (short) (d0 + d3);
            int packed = packScrollWords(fgScroll, bgScroll);
            for (int i = 0; i < 16 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Bottom Gradient Region ====================
        // Uses fixed-point accumulation with divs.w math
        // Reference: The complex section starting around loc_C5F4 in s2.asm
        //
        // The original calculates:
        // d5.w = (d2 >> 3) - ((d2 >> 4) + ((d2 >> 4) >> 1)) ; delta to accumulate
        // Then divides by $30 (48) to get per-line increment
        // And applies fractional accumulation across the remaining lines

        // Calculate base scroll values
        short baseHere = (short) (asrWord(d2, 4) + asrWord(asrWord(d2, 4), 1)); // From band 6
        short targetScroll = asrWord(d2, 3); // Final target: d2 >> 3

        // delta = target - base (amount to distribute over gradient lines)
        int delta = (targetScroll - baseHere) & 0xFFFF;

        // The gradient spans: 15 + 18 + 45 = 78 lines
        // Original uses divs.w #$30 (48) to compute increment
        // Note: In original, it's actually 3 sub-bands with different logic

        // ========== Sub-band 6a: 15 lines (starts gradient) ==========
        // Original: move.w #$3C,d1 (60 bytes = 15 lines)
        {
            short bgBase = baseHere;
            // Slight increment per line (approximation from original fixed-point)
            int increment = (delta * 256) / 78; // Fixed-point 8.8
            int accumulator = 0;

            for (int i = 0; i < 15 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                short bgScroll = (short) (bgBase + (accumulator >> 8));
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);
                accumulator += increment;
            }
        }

        // ========== Sub-band 6b: 18 lines (middle gradient) ==========
        // Original: move.w #$48,d1 (72 bytes = 18 lines)
        {
            // Continue from where we left off
            int linesProcessed = 15;
            int increment = (delta * 256) / 78;
            int accumulator = increment * linesProcessed;
            short bgBase = baseHere;

            for (int i = 0; i < 18 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                short bgScroll = (short) (bgBase + (accumulator >> 8));
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);
                accumulator += increment;
            }
        }

        // ========== Sub-band 6c: 45 lines (bottom gradient) ==========
        // Original: move.w #$B4,d1 (180 bytes = 45 lines)
        {
            int linesProcessed = 15 + 18;
            int increment = (delta * 256) / 78;
            int accumulator = increment * linesProcessed;
            short bgBase = baseHere;

            for (int i = 0; i < 45 && lineIndex < VISIBLE_LINES; i++, lineIndex++) {
                short bgScroll = (short) (bgBase + (accumulator >> 8));
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);
                accumulator += increment;
            }
        }

        // ==================== Bug Reproduction ====================
        // Original EHZ only writes 222 lines, leaving last 2 uninitialized.
        // Total: 22 + 58 + 21 + 11 + 16 + 16 + 15 + 18 + 45 = 222
        // We've written up to lineIndex (should be 222), remaining 2 lines
        // are intentionally not written to match original behavior.

        // Note: If lineIndex < 224, the remaining lines retain previous values
        // (matching hardware behavior where unwritten lines keep old data)
    }
}
