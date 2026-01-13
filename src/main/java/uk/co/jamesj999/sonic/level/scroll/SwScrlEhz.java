package uk.co.jamesj999.sonic.level.scroll;

import static uk.co.jamesj999.sonic.level.scroll.M68KMath.*;

/**
 * ROM-accurate implementation of SwScrl_EHZ (Emerald Hill Zone scroll routine).
 * Reference: s2.asm SwScrl_EHZ at ROM $C57E
 *
 * EHZ uses banded per-scanline parallax with:
 * - Sky region (static)
 * - Cloud region (slow parallax)
 * - Water surface (now treated as cloud extension, no shimmer)
 * - Hill bands (graduated parallax speeds)
 * - Bottom gradient (interpolated parallax for ground depth)
 *
 * Band structure (line counts):
 * 22 lines: BG = 0 (sky)
 * 58 lines: BG = d2 >> 6 (far clouds)
 * 21 lines: BG = d2 >> 6 (water/clouds extension)
 * 11 lines: BG = 0 (gap)
 * 16 lines: BG = d2 >> 4 (near hills)
 * 16 lines: BG = (d2 >> 4) * 1.5 (nearer hills)
 * 15 lines: gradient 0.25 -> 0.50 speed
 * 18 lines: gradient 0.50 -> 0.75 speed
 * 45 lines: gradient 0.75 -> 1.00 speed
 * Total: 222 lines (last 2 lines intentionally unwritten - original bug)
 */
public class SwScrlEhz implements ZoneScrollHandler {

    private final ParallaxTables tables;

    // Scroll tracking for LevelManager bounds
    private int minScrollOffset;
    private int maxScrollOffset;
    private short vscrollFactorBG;

    public SwScrlEhz(ParallaxTables tables) {
        this.tables = tables;
    }

    @Override
    public void update(int[] horizScrollBuf,
            int cameraX,
            int cameraY,
            int frameCounter,
            int actId) {
        minScrollOffset = Integer.MAX_VALUE;
        maxScrollOffset = Integer.MIN_VALUE;

        // d2 = -Camera_X_pos (FG scroll, constant for all lines)
        short d2 = negWord(cameraX);

        // FG scroll word is constant for all lines
        short fgScroll = d2;

        // Vscroll_Factor_BG for EHZ is 0 (BG doesn't scroll vertically independently)
        vscrollFactorBG = 0;

        int lineIndex = 0;

        // ==================== Band 1: Sky (22 lines) ====================
        // BG = 0, FG = d2
        {
            short bgScroll = 0;
            int packed = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll); // Track once per constant region
            int limit = Math.min(VISIBLE_LINES, lineIndex + 22);
            for (; lineIndex < limit; lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 2: Far Clouds (58 lines) ====================
        // BG = d2 >> 6, FG = d2
        {
            short bgScroll = asrWord(d2, 6);
            int packed = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);
            int limit = Math.min(VISIBLE_LINES, lineIndex + 58);
            for (; lineIndex < limit; lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 3: Water Surface (21 lines) ====================
        // Water surface with ripple effect using SwScrl_RippleData
        {
            short baseBgScroll = asrWord(d2, 6);
            int limit = Math.min(VISIBLE_LINES, lineIndex + 21);

            // Ripple animation speed (1 byte every 8 frames) - Slowed down per user
            // feedback
            // Using continuous counter, masking lookup to first 32 bytes to avoid
            // distortion
            int rippleIndex = (frameCounter >> 3);

            for (; lineIndex < limit; lineIndex++) {
                int wobble = tables.getRippleSigned(rippleIndex & 0x1F);
                short bgScroll = (short) (baseBgScroll + wobble);

                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);
                trackOffset(fgScroll, bgScroll);

                rippleIndex++;
            }
        }

        // ==================== Band 4: Gap (11 lines) ====================
        // BG = 0, FG = d2
        {
            short bgScroll = 0;
            int packed = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);
            int limit = Math.min(VISIBLE_LINES, lineIndex + 11);
            for (; lineIndex < limit; lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 5: Near Hills (16 lines) ====================
        // BG = d2 >> 4, FG = d2
        {
            short bgScroll = asrWord(d2, 4);
            int packed = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);
            int limit = Math.min(VISIBLE_LINES, lineIndex + 16);
            for (; lineIndex < limit; lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Band 6: Nearer Hills (16 lines) ====================
        // BG = (d2 >> 4) * 1.5, FG = d2
        {
            short d0 = asrWord(d2, 4);
            short d3 = asrWord(d0, 1);
            short bgScroll = (short) (d0 + d3);
            int packed = packScrollWords(fgScroll, bgScroll);
            trackOffset(fgScroll, bgScroll);
            int limit = Math.min(VISIBLE_LINES, lineIndex + 16);
            for (; lineIndex < limit; lineIndex++) {
                horizScrollBuf[lineIndex] = packed;
            }
        }

        // ==================== Bottom Gradient Region ====================
        // Reverting to previous ParallaxManager logic which interpolated from 0.25 to
        // 1.0
        // FG speed. Using 'd2' (FG scroll) as base.

        // ========== Sub-band 6a (Segment 7): 15 lines (0.25 -> 0.50) ==========
        {
            // Start: 0.25 * d2 = d2 >> 2. In fixed point (<<16): (d2<<16) >> 2 = d2 << 14
            int startFixed = (d2 << 14);
            int endFixed = (d2 << 15);
            int count = 15;
            int increment = (endFixed - startFixed) / count;

            int bgFixed = startFixed;
            int limit = Math.min(VISIBLE_LINES, lineIndex + count);

            for (; lineIndex < limit; lineIndex++) {
                short bgScroll = (short) (bgFixed >> 16);
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);

                // Check offset for every line in gradient (value changes)
                int offset = bgScroll - fgScroll;
                if (offset < minScrollOffset)
                    minScrollOffset = offset;
                if (offset > maxScrollOffset)
                    maxScrollOffset = offset;

                bgFixed += increment;
            }
        }

        // ========== Sub-band 6b (Segment 8): 18 lines (0.50 -> 0.75) ==========
        {
            // Start: 0.50 * d2 = d2 >> 1
            // End: 0.75 * d2 = 0.50 + 0.25 = (d2 >> 1) + (d2 >> 2)
            int startFixed = (d2 << 15);
            int endFixed = startFixed + (d2 << 14);
            int count = 18;
            int increment = (endFixed - startFixed) / count;

            int bgFixed = startFixed;
            int limit = Math.min(VISIBLE_LINES, lineIndex + count);

            for (; lineIndex < limit; lineIndex++) {
                short bgScroll = (short) (bgFixed >> 16);
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);

                int offset = bgScroll - fgScroll;
                if (offset < minScrollOffset)
                    minScrollOffset = offset;
                if (offset > maxScrollOffset)
                    maxScrollOffset = offset;

                bgFixed += increment;
            }
        }

        // ========== Sub-band 6c (Segment 9): 45 lines (0.75 -> 1.00) ==========
        {
            // Start: 0.75 * d2
            // End: 1.00 * d2
            int endFixed = (d2 << 16);
            int startFixed = (endFixed >> 1) + (endFixed >> 2);
            int count = 45;
            int increment = (endFixed - startFixed) / count;

            int bgFixed = startFixed;
            int limit = Math.min(VISIBLE_LINES, lineIndex + count);

            for (; lineIndex < limit; lineIndex++) {
                short bgScroll = (short) (bgFixed >> 16);
                horizScrollBuf[lineIndex] = packScrollWords(fgScroll, bgScroll);

                int offset = bgScroll - fgScroll;
                if (offset < minScrollOffset)
                    minScrollOffset = offset;
                if (offset > maxScrollOffset)
                    maxScrollOffset = offset;

                bgFixed += increment;
            }
        }

        // ==================== Bug Reproduction ====================
        // Original EHZ only writes 222 lines, leaving last 2 uninitialized.
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
}
