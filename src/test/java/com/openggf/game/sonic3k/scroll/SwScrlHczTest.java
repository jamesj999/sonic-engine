package com.openggf.game.sonic3k.scroll;

import com.openggf.data.Rom;
import com.openggf.level.scroll.compose.ScrollValueTable;
import com.openggf.level.scroll.compose.WaterlineBlendComposer;
import com.openggf.level.scroll.ZoneScrollHandler;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.negWord;
import static com.openggf.level.scroll.M68KMath.packScrollWords;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static com.openggf.level.scroll.M68KMath.unpackFG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Hydrocity Zone scroll handler.
 *
 * <p>Verifies both HCZ1 (water equilibrium parallax) and HCZ2 (scatter-fill
 * parallax) against the deform math from the S3K disassembly.
 */
public class SwScrlHczTest {

    // ---- HCZ1 constants (from disassembly) ----
    private static final int EQUILIBRIUM_Y = 0x610;
    private static final int BG_Y_OFFSET = 0x190;
    private static final int WATERLINE_THRESHOLD = 0x60;

    // ==== HCZ1 Tests ====

    @Test
    public void hcz1BgYCalculationMatchesDisassembly() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // At equilibrium: bgY = 0/4 + 0x190 = 0x190
        handler.update(buf, 0x100, EQUILIBRIUM_Y, 0, 0);
        assertEquals((short) BG_Y_OFFSET, handler.getVscrollFactorBG(), "At equilibrium");

        // Above equilibrium: cameraY = 0x410 â†’ delta = -0x200 â†’ bgY = -0x80 + 0x190 = 0x110
        handler.update(buf, 0x100, 0x410, 0, 0);
        short delta = (short) (0x410 - EQUILIBRIUM_Y);
        short expected = (short) ((short) (delta >> 2) + BG_Y_OFFSET);
        assertEquals(expected, handler.getVscrollFactorBG(), "Above equilibrium");

        // Below equilibrium: cameraY = 0x810 â†’ delta = 0x200 â†’ bgY = 0x80 + 0x190 = 0x210
        handler.update(buf, 0x100, 0x810, 0, 0);
        delta = (short) (0x810 - EQUILIBRIUM_Y);
        expected = (short) ((short) (delta >> 2) + BG_Y_OFFSET);
        assertEquals(expected, handler.getVscrollFactorBG(), "Below equilibrium");
    }

    @Test
    public void hcz1CaveBandsAreSymmetrical() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];
        int cameraX = 0x800;

        // Use equilibrium Y so d2=0 â†’ clean per-line split, and bgY=0x190
        // which positions visible area well into the BG such that cave bands
        // are visible (bands 0-12 total 416 pixels, bgY=400 means we see some)
        handler.update(buf, cameraX, EQUILIBRIUM_Y, 0, 0);

        // Compute expected cave band values:
        // base = cameraX << 14, step = cameraX << 11
        int d0orig = ((short) cameraX) << 16;
        int base = d0orig >> 2;
        int step = d0orig >> 5;

        // 7 speed levels (fastest to slowest)
        short[] expectedLevels = new short[7];
        int val = base;
        for (int i = 0; i < 7; i++) {
            expectedLevels[i] = (short) (val >> 16);
            val -= step;
        }

        // Verify symmetry: word[i] == word[12-i] for all i in 0-6
        // FG scroll is constant so we can extract BG from the buffer.
        // At bgY=0x190=400: first band (64px) starts at 0, so visible portion
        // starts at pixel 400. Bands 0-5 total 64+8+8+5+5+6=96.
        // Bands 0-5 end at pixel 96. Since bgY=400, bands 0-5 are entirely above screen.
        // Band 6 (240px) spans 96-336. At bgY=400, still above screen.
        // Band 7 (6px) spans 336-342. Still above.
        // Bands 7-12 total 6+5+5+8+8+48=80. Span 336-416. At bgY=400, visible: 400-416=16px visible.
        // Band 10 (8px, word 10) starts at 352, spans 352-360
        // Band 11 (8px, word 11) starts at 360, spans 360-368
        // Band 12 (48px, word 12) starts at 368, spans 368-416
        // At bgY=400: band 12 partially visible (400-416 = 16 lines visible)
        // Then per-line band starts at pixel 416.

        // Actually with bgY=400, band 12 (48px from pixel 368-416) is partially visible:
        // 16 lines of band 12 visible, then all 192 per-line, but screen is only 224 lines.
        // So: lines 0-15 = band 12 (word[12] = level 0 = fastest),
        //     lines 16-207 = per-line section (words 13-204),
        //     lines 208-223 = padding from per-line or beyond
        // Since d2=0 and post-fill: words 13-108 = d3 (fastest), words 109-204 = d4 (slowest)

        // Band 12 = word[12] = word[0] = level 0 (fastest, quarter-speed base)
        short fgScroll = negWord(cameraX);
        short bg12 = negWord(expectedLevels[0]);
        assertEquals(bg12, unpackBG(buf[0]), "Band 12 scroll value");
        assertEquals(bg12, unpackBG(buf[15]), "Band 12 at line 15");

        // Per-line section starts at line 16: d3 fill (words 13-108 = fastest = word[0])
        // So it should have the same negated value as d3 = word[0]
        short d3bg = negWord(expectedLevels[0]);
        assertEquals(d3bg, unpackBG(buf[16]), "Per-line d3 zone at line 16");

        // d4 zone (words 109-204 = slowest = word[6]) starts at per-line offset 96
        // Line 16 + 96 = line 112
        short d4bg = negWord(expectedLevels[6]);
        assertEquals(d4bg, unpackBG(buf[112]), "Per-line d4 zone at line 112");

        // d3 and d4 should differ (different speed levels)
        assertNotEquals(d3bg, d4bg, "d3 != d4");
    }

    @Test
    public void hcz1ProducesParallaxBandsWhenCaveVisible() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // Position camera so cave bands are visible (low bgY).
        // cameraY=0x610 â†’ bgY=0x190=400. Need lower bgY to see more bands.
        // cameraY=0x210 â†’ delta=-0x400, bgY=-0x100+0x190=0x90=144
        // At bgY=144: first band is 64px, 144-64=80 remaining
        // Band 1 (8px): 80-8=72; Band 2 (8px): 72-8=64; Band 3 (5px): 64-5=59
        // Band 4 (5px): 59-5=54; Band 5 (6px): 54-6=48
        // Band 6 (240px): 48-240=-192 â†’ 192 visible lines
        // So: band 0 = entirely above, bands 1-5 above, band 6 partially visible
        // Actually: bgY=144 < band0(64), so 144-64=80, not negative yet...
        // Band 0: 64px, remaining=80. Band 1: 8px, remaining=72.
        // Band 2: 8px, remaining=64. Band 3: 5px, remaining=59.
        // Band 4: 5px, remaining=54. Band 5: 6px, remaining=48.
        // Band 6: 240px, 48-240 = -192 â†’ 192 visible lines, then 224-192=32 left
        // Band 7: 6px, Band 8: 5px, Band 9: 5px, Band 10: 8px, Band 11: 8px = 32px
        // That fills the remaining 32 lines.
        int cameraX = 0x600;
        handler.update(buf, cameraX, 0x210, 0, 0);

        // Band 6 (word[6]) = slowest cave level, 192 visible lines
        // Band 7 (word[7]) = same as word[5] (level 5)
        // These should be different
        short bg6 = unpackBG(buf[0]);    // Band 6
        short bg7 = unpackBG(buf[192]);  // Band 7

        assertNotEquals(bg6, bg7, "Band 6 and 7 should have different BG scroll");

        // Band 6 is slowest, Band 7 is faster (higher absolute scroll)
        // Verify we have multiple distinct BG values (indicating parallax)
        assertTrue(uniqueBgValues(buf) >= 3, "Multiple parallax bands visible");
    }

    @Test
    public void hcz1PerLineSectionShowsGradientWhenFarAboveWater() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // Camera far above water â†’ d2 <= -0x60 â†’ forward fill with gradient.
        // cameraY = 0: delta=-0x610, quarterDelta=-0x184, d2=-0x184-(-0x610)=0x48C
        // Wait, that's positive. Let me recalculate.
        // d2 = quarterDelta - delta = (-0x184) - (-0x610) = 0x48C (positive!)
        // Positive d2 means water BELOW equilibrium from the camera's perspective.
        // So for the gradient (forward fill, d2 <= -THRESHOLD), we need d2 very negative.
        // d2 < 0 means camera is below equilibrium (deeper underwater).
        // cameraY = 0x900: delta=0x900-0x610=0x2F0, qd=0xBC, d2=0xBC-0x2F0=-0x234
        // d2 = -564, which is < -96. Forward fill!

        int cameraX = 0x400;
        handler.update(buf, cameraX, 0x900, 0, 0);

        // The per-line section should contain a gradient (multiple unique BG values)
        // when visible. With bgY = 0x2F0/4 + 0x190 = 0xBC + 0x190 = 0x24C = 588.
        // Bands 0-12 total 416 pixels. At bgY=588: all bands above + some per-line.
        // Per-line starts at offset 416, visible from 588-416 = 172 into the per-line.
        // So we see per-line words starting from about word 13+172 = word 185.
        // Forward fill gradient exists in words 13-108.
        // But visible starts at word 185, which is in the d4 (flat) zone.
        // Hmm, this position shows flat d4. Let me pick a better cameraY.

        // For forward fill to be visible, we need the visible portion of per-line
        // to fall within words 13-108. BgY must be around 416+0 to 416+95 (= 416-511).
        // bgY = 416+48 = 464 â†’ need (cameraY-0x610)/4 + 0x190 = 464
        // â†’ cameraY-0x610 = (464-0x190)*4 = (464-400)*4 = 256 â†’ cameraY = 0x710
        // d2 = 64/4 - 256/... wait let me compute directly.
        // cameraY=0x710: delta=0x100, qd=0x40, d2=0x40-0x100=-0xC0=-192
        // d2=-192 < -96 â†’ forward fill!
        // bgY = 0x40 + 0x190 = 0x1D0 = 464
        // Visible per-line starts at 464-416=48 words into per-line â†’ word 13+48=61
        // word 61 is in the forward fill gradient range (13-108). Good!

        handler.update(buf, cameraX, 0x710, 0, 0);

        // With the forward fill active, words 13-108 have a gradient.
        // The per-line band visible region should show varying BG values.
        // The cave bands (0-12) are fully above screen at bgY=464.
        // All 224 visible lines are in the per-line section.
        int distinctValues = uniqueBgValues(buf);
        assertTrue(distinctValues > 5, "Gradient should produce multiple distinct BG values, got " + distinctValues);
    }

    @Test
    public void hcz1WaterlineOffsetD2ComputedCorrectly() {
        // Verify d2 = quarterDelta - delta matches expected sign conventions.
        // Camera deeper (cameraY=0x700): delta=0xF0, d2=0x3C-0xF0=-0xB4<0
        // Camera higher (cameraY=0x400): delta=-0x210, d2=-0x84-(-0x210)=0x18C>0
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // Below equilibrium (d2 < -0x60): forward fill creates gradient.
        // bgY = 0xF0/4 + 0x190 = 0x1CC = 460. Per-line visible from pixel 460-416=44.
        // Visible starts at word 13+44 = 57, which is in the forward fill gradient.
        // word[13+k] = cameraX - k * (cameraX/128). At k=44: 1024 - 44*8 = 672.
        handler.update(buf, 0x400, 0x700, 0, 0);
        short expectedGradient = negWord((short) 672);
        assertEquals(expectedGradient, unpackBG(buf[0]), "Forward fill gradient at line 0");

        // Verify gradient decreases across scanlines (each word is 8 less)
        short bg0 = unpackBG(buf[0]);
        short bg10 = unpackBG(buf[10]);
        // Gradient values decrease (in un-negated space) â†’ negated values increase
        assertTrue(bg10 > bg0, "Gradient increases across lines");

        // Above equilibrium (d2 > 0, very large): backward fill gradient survives.
        // bgY = -0x84 + 0x190 = 0x10C = 268. Bands 0-12 total 416px.
        // Per-line not visible (268 < 416). Only cave bands visible â†’ parallax.
        handler.update(buf, 0x400, 0x400, 0, 0);
        assertTrue(uniqueBgValues(buf) > 1, "Cave bands should show parallax");
    }

    @Test
    public void hcz1WithoutWaterlineLookupKeepsFallbackGapAtVisibleWaterline() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // cameraY = $611 -> delta = 1, quarterDelta = 0, d2 = -1.
        // bgY = $190 (400), so band 12 consumes lines 0-15 and per-line word 108
        // lands on visible line 111. Without lookup data, HCZ1 leaves that slot
        // on the fallback path rather than remapping it.
        handler.update(buf, 0x400, 0x611, 0, 0);

        assertEquals((short) 0, unpackBG(buf[111]), "Visible fallback gap should remain unblended");
        assertEquals(negWord((short) 0x0040), unpackBG(buf[112]), "Next scanline should come from the slow-water band");
    }

    @Test
    public void hcz1WithWaterlineLookupShowsVisibleWaterlineBlend() {
        SwScrlHcz handler = new SwScrlHcz(buildWaterlineLookup((byte) 0));
        int[] buf = new int[VISIBLE_LINES];

        // Same d2 = -1 case as the fallback test, but the lookup remaps word 108
        // from word 109 before the post-fill runs.
        handler.update(buf, 0x400, 0x611, 0, 0);

        assertEquals(negWord((short) 0x0108), unpackBG(buf[111]), "Lookup-backed waterline should become visible");
        assertEquals(negWord((short) 0x0040), unpackBG(buf[112]), "Slow-water band remains intact below the waterline");
    }

    @Test
    public void waterlineBlendComposerAppliesLookupAndFallbackAroundMidpoint() {
        ScrollValueTable table = ScrollValueTable.ofLength(206);
        table.set(109, (short) 0x0108);
        WaterlineBlendComposer composer = new WaterlineBlendComposer(13, 109, 205, 96);

        composer.apply(table, (short) -1, (short) 0x0400, (short) 0x0200, buildWaterlineLookup((byte) 0));
        assertEquals((short) 0x0108, table.get(108), "Lookup should backfill the scanline above the midpoint");
        assertEquals((short) 0x0200, table.get(109), "Below-water fill should still own the midpoint and lower band");

        ScrollValueTable fallback = ScrollValueTable.ofLength(206);
        fallback.set(109, (short) 0x0108);
        composer.apply(fallback, (short) -1, (short) 0x0400, (short) 0x0200, null);
        assertEquals((short) 0, fallback.get(108), "Fallback path should leave the lookup-only slot untouched");
        assertEquals((short) 0x0200, fallback.get(109), "Fallback still applies the below-water fill");
    }

    @Test
    public void hcz1AllLinesHaveValidFgScroll() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];
        int cameraX = 0x1234;

        handler.update(buf, cameraX, EQUILIBRIUM_Y, 0, 0);

        short expectedFg = negWord(cameraX);
        for (int i = 0; i < VISIBLE_LINES; i++) {
            assertEquals(expectedFg, unpackFG(buf[i]), "FG scroll at line " + i);
        }
    }

    // ==== HCZ2 Tests ====

    @Test
    public void hcz2BgYIsQuarterCameraY() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        handler.update(buf, 0x100, 0x400, 0, 1);
        assertEquals((short) (0x400 >> 2), handler.getVscrollFactorBG(), "BG Y = cameraY/4");

        handler.update(buf, 0x100, 0x800, 0, 1);
        assertEquals((short) (0x800 >> 2), handler.getVscrollFactorBG(), "BG Y = cameraY/4");
    }

    @Test
    public void hcz2ProducesMultipleParallaxBands() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        handler.update(buf, 0x800, 0x200, 1, 1);

        // Should have multiple distinct BG scroll values from the 7 speed levels
        int distinct = uniqueBgValues(buf);
        assertTrue(distinct > 3, "HCZ2 should have multiple parallax bands, got " + distinct);
    }

    @Test
    public void hcz2ScatterFillMatchesDisassemblySpeedLevels() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];
        int cameraX = 0x1000;

        handler.update(buf, cameraX, 0x100, 0, 1);

        // Compute expected speed levels from disassembly math:
        // d0 = cameraX << 15 (half speed in 16.16)
        // d1 = d0 >> 3 (step = 1/8)
        int d0 = ((short) cameraX) << 16;
        d0 >>= 1;
        int d1 = d0 >> 3;

        // 7 speed levels
        short[] levels = new short[7];
        int val = d0;
        for (int i = 0; i < 7; i++) {
            levels[i] = (short) (val >> 16);
            val -= d1;
        }

        // BG Y = 0x100/4 = 0x40 = 64
        // HCZ2 deform heights: 8, 8, 0x90, 0x10, 8, 0x30, ...
        // At bgY=64: band 0 (8px) â†’ remaining=56, band 1 (8px) â†’ remaining=48
        // Band 2 (0x90=144px): 48-144 = -96 â†’ 96 visible lines
        // Band 2 uses HScroll word 2.
        // From scatter-fill: word 2 is in group 6 (slowest) â†’ levels[6]
        short fgScroll = negWord(cameraX);
        short expectedBg2 = negWord(levels[6]);
        assertEquals(expectedBg2, unpackBG(buf[0]), "Band 2 (word 2, speed 6)");

        // Band 3 (0x10=16px, word 3) starts at line 96.
        // Word 3 is in group 5 â†’ levels[5]
        short expectedBg3 = negWord(levels[5]);

        // Wait, let me check the scatter-fill mapping more carefully.
        // Group 0 (fastest): byte offsets 0x0A,0x14,0x1E,0x2C â†’ words 5,10,15,22
        // Group 1: 0x0C,0x16,0x20 â†’ words 6,11,16
        // Group 2: 0x00,0x08,0x0E,0x18,0x22,0x2A â†’ words 0,4,7,12,17,21
        // Group 3: 0x02,0x10,0x1A,0x24 â†’ words 1,8,13,18
        // Group 4: 0x12,0x1C â†’ words 9,14
        // Group 5: 0x06,0x28 â†’ words 3,20
        // Group 6 (slowest): 0x04,0x26 â†’ words 2,19
        //
        // So word 2 â†’ group 6 â†’ levels[6] âœ“
        // Word 3 â†’ group 5 â†’ levels[5] âœ“

        assertEquals(expectedBg3, unpackBG(buf[96]), "Band 3 (word 3, speed 5)");
        assertNotEquals(expectedBg2, expectedBg3, "Different speed levels produce different scroll");
    }

    @Test
    public void hcz2FgScrollIsNegatedCameraX() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];
        int cameraX = 0xABCD;

        handler.update(buf, cameraX, 0x200, 0, 1);

        short expectedFg = negWord(cameraX);
        for (int i = 0; i < VISIBLE_LINES; i++) {
            assertEquals(expectedFg, unpackFG(buf[i]), "FG scroll at line " + i);
        }
    }

    // ==== HCZ2 Screen Shake Tests ====

    @Test
    public void hcz2BgYIncludesShakeCompensation() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // Without shake: bgY = cameraY / 4
        handler.setScreenShakeOffset(0);
        handler.update(buf, 0x100, 0x400, 0, 1);
        short noShake = handler.getVscrollFactorBG();
        assertEquals((short) (0x400 >> 2), noShake, "No shake: BG Y = cameraY/4");

        // With shake of 8: bgY = (0x400 - 8) / 4 + 8 = 0xFE + 8 = 0x106
        handler.setScreenShakeOffset(8);
        handler.update(buf, 0x100, 0x400, 0, 1);
        short withShake = handler.getVscrollFactorBG();
        short expected = (short) ((((short) (0x400 - 8)) >> 2) + 8);
        assertEquals(expected, withShake, "With shake: BG Y = (camY - shake)/4 + shake");
        assertNotEquals(noShake, withShake, "Shake changes BG Y");
    }

    @Test
    public void hcz2ShakeOffsetAffectsFgVScroll() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        handler.setScreenShakeOffset(0);
        handler.update(buf, 0x100, 0x400, 0, 1);
        assertEquals((short) 0, handler.getVscrollFactorFG(), "No shake: FG VScroll = 0");

        handler.setScreenShakeOffset(4);
        handler.update(buf, 0x100, 0x400, 0, 1);
        assertEquals((short) (0x400 + 4), handler.getVscrollFactorFG(), "With shake: FG VScroll = camY + shake");
    }

    @Test
    public void hcz2ShakeDoesNotAffectHcz1() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        // Set shake and run HCZ1 (actId=0) â€” shake should not affect Act 1
        handler.setScreenShakeOffset(10);
        handler.update(buf, 0x100, EQUILIBRIUM_Y, 0, 0);
        assertEquals((short) 0, handler.getVscrollFactorFG(), "HCZ1 ignores shake: FG VScroll = 0");
    }

    // ==== HCZ2 Wall-Chase Mode Tests ====

    @Test
    public void hcz2DefaultPhaseIsNormal() {
        SwScrlHcz handler = new SwScrlHcz();
        assertEquals(SwScrlHcz.Hcz2BgPhase.NORMAL, handler.getHcz2BgPhase());
    }

    @Test
    public void hcz2WallChaseProducesUniformScroll() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];
        int cameraX = 0x800;
        int cameraY = 0x600;

        handler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.WALL_CHASE);
        handler.setWallChaseOffsetX(0x40);
        handler.update(buf, cameraX, cameraY, 0, 1);

        // BG Y = camY - $500 (sonic3k.asm HCZ2_WallMove line ~106052)
        assertEquals((short) (cameraY - 0x500), handler.getVscrollFactorBG(), "Wall chase BG Y");

        // All 224 lines should have identical scroll values (PlainDeformation)
        short expectedFg = negWord(cameraX);
        // BG X = -(camX - $200 + wallOffset) (sonic3k.asm line ~106058)
        short expectedBg = negWord(cameraX - 0x200 + 0x40);
        for (int i = 0; i < VISIBLE_LINES; i++) {
            assertEquals(expectedFg, unpackFG(buf[i]), "Uniform FG at line " + i);
            assertEquals(expectedBg, unpackBG(buf[i]), "Uniform BG at line " + i);
        }
    }

    @Test
    public void hcz2NormalModeHasParallaxBandsWallChaseDoesNot() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];
        int cameraX = 0x800;
        int cameraY = 0x200;

        // Normal mode: multiple parallax bands
        handler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.NORMAL);
        handler.update(buf, cameraX, cameraY, 0, 1);
        int normalDistinct = uniqueBgValues(buf);
        assertTrue(normalDistinct > 3, "Normal mode has multiple BG speeds, got " + normalDistinct);
        short finalLineScroll = unpackBG(buf[VISIBLE_LINES - 1]);
        boolean differsFromFinalLine = false;
        for (int packedScroll : buf) {
            if (unpackBG(packedScroll) != finalLineScroll) {
                differsFromFinalLine = true;
                break;
            }
        }
        assertTrue(differsFromFinalLine,
                "HCZ2 normal priority replay cannot substitute the final scanline for every band");

        // Wall-chase mode: single uniform BG value
        handler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.WALL_CHASE);
        handler.update(buf, cameraX, cameraY, 0, 1);
        int chaseDistinct = uniqueBgValues(buf);
        assertEquals(1, chaseDistinct, "Wall chase is uniform");
    }

    @Test
    public void hcz2WallChaseOffsetMovesBackground() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf1 = new int[VISIBLE_LINES];
        int[] buf2 = new int[VISIBLE_LINES];
        int cameraX = 0x800;
        int cameraY = 0x600;

        handler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.WALL_CHASE);

        // Wall offset 0
        handler.setWallChaseOffsetX(0);
        handler.update(buf1, cameraX, cameraY, 0, 1);
        short bg1 = unpackBG(buf1[0]);

        // Wall offset -0x200 (wall has advanced)
        handler.setWallChaseOffsetX(-0x200);
        handler.update(buf2, cameraX, cameraY, 0, 1);
        short bg2 = unpackBG(buf2[0]);

        assertNotEquals(bg1, bg2, "Different wall offsets produce different BG scroll");
    }

    @Test
    public void hcz2WallChaseExposesBgCameraForCollisionParity() {
        SwScrlHcz handler = new SwScrlHcz();

        handler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.WALL_CHASE);
        handler.setWallChaseOffsetX(-0x120);
        handler.primeBgCollisionState(0x840, 0x690);

        assertEquals(0x840 - 0x200 - 0x120, handler.getBgCameraX(),
                "HCZ2 wall-chase BG collisions must use Camera_X_pos_BG_copy");
        assertEquals((short) (0x690 - 0x500), handler.getVscrollFactorBG(),
                "HCZ2 wall-chase BG collisions must use Camera_Y_pos_BG_copy");
    }

    // ==== Provider routing test ====

    @Test
    public void providerRoutesHczToDedicatedHandler() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());

        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneConstants.ZONE_HCZ);
        assertNotNull(handler);
        assertTrue(handler instanceof SwScrlHcz, "Should be SwScrlHcz");
    }

    // ==== Scroll offset tracking ====

    @Test
    public void scrollTrackingBoundsAreReasonable() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        handler.update(buf, 0x800, EQUILIBRIUM_Y, 0, 0);

        assertTrue(handler.getMinScrollOffset() <= handler.getMaxScrollOffset(), "min <= max");
        // BG-FG offset shouldn't be extreme for reasonable camera positions
        assertTrue(handler.getMinScrollOffset() > -5000, "Min offset reasonable");
        assertTrue(handler.getMaxScrollOffset() < 5000, "Max offset reasonable");
    }

    @Test
    public void hcz2ScrollTrackingBoundsAreReasonable() {
        SwScrlHcz handler = new SwScrlHcz();
        int[] buf = new int[VISIBLE_LINES];

        handler.update(buf, 0x800, 0x200, 0, 1);

        assertTrue(handler.getMinScrollOffset() <= handler.getMaxScrollOffset(), "min <= max");
        assertTrue(handler.getMinScrollOffset() > -5000, "Min offset reasonable");
        assertTrue(handler.getMaxScrollOffset() < 5000, "Max offset reasonable");
    }

    // ==== Helper methods ====

    private int uniqueBgValues(int[] hScroll) {
        Set<Short> values = new HashSet<>();
        for (int packed : hScroll) {
            values.add(unpackBG(packed));
        }
        return values.size();
    }

    private static byte[] buildWaterlineLookup(byte value) {
        byte[] data = new byte[97 * 96];
        for (int i = 0; i < data.length; i++) {
            data[i] = value;
        }
        return data;
    }
}
