package com.openggf.game.sonic3k.scroll;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.level.WaterSystem;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;

import java.util.Arrays;
import java.util.logging.Logger;

import static com.openggf.level.scroll.M68KMath.*;

/**
 * Angel Island Zone (AIZ) scroll handler.
 *
 * Intro mode implements AIZ1_IntroDeform + ApplyDeformation semantics:
 * - Builds HScroll_table+$28 values from Events_fg_1 / Camera_X_pos_copy
 * - Applies ROM segment heights from AIZ1_IntroDeformArray
 * - Writes negated BG values into the per-scanline hscroll buffer
 */
public class SwScrlAiz extends AbstractZoneScrollHandler {

    private static final Logger LOG = Logger.getLogger(SwScrlAiz.class.getName());

    private static final int INTRO_DEFORM_BANDS = 0x25;
    private static final int INTRO_DEFORM_CAP = 0x580;
    private static final int INTRO_DEFORM_TERMINATOR = 0x7FFF;
    private static final int[] INTRO_DEFORM_SEGMENTS = buildIntroDeformSegments();

    /** Bit 15 flag in AIZ1_DeformArray: band uses one scroll value per scanline. */
    private static final int PER_LINE_FLAG = 0x8000;

    /** AIZ1_DeformArray heights. $800D = per-line flag | 13 scanlines. */
    private static final int[] AIZ1_DEFORM_HEIGHTS = {
            0xD0, 0x20, 0x30, 0x30, 0x10, 0x10, 0x10,
            0x800D, 0x0F, 0x06, 0x0E, 0x50, 0x20
    };

    /** AIZ2_BGDeformArray heights (24 bands, no per-line flags). */
    private static final int[] AIZ2_DEFORM_HEIGHTS = {
            0x10, 0x20, 0x38, 0x58, 0x28, 0x40, 0x38, 0x18,
            0x18, 0x90, 0x48, 0x10, 0x18, 0x20, 0x38, 0x58,
            0x28, 0x40, 0x38, 0x18, 0x18, 0x90, 0x48, 0x10
    };

    /**
     * Speed level for each of the 25 scroll table entries, derived from
     * AIZ2_BGDeformMake scatter pattern. Creates a wave: speeds increase
     * from center (index 9,21 = speed 0) outward (index 3,15 = speed 6).
     */
    private static final int[] AIZ2_SPEED_MAP = {
            3, 4, 5, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3,
            4, 5, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3
    };

    /** Number of distinct speed levels in AIZ2 deform. */
    private static final int AIZ2_SPEED_LEVELS = 7;

    /** Total words in the flat HScroll_table value array ($008-$038). */
    private static final int FLAT_VALUE_COUNT = 25;
    private static final DeformationPlan.ScrollValueTransform NEGATE_WORD = value -> negWord(value);

    /** Origin X for AIZ1_Deform base calculation (subi.w #$1300,d0). */
    private static final int DEFORM_ORIGIN_X = 0x1300;
    // AIZ2_SOZ1_LRZ3_FGDeformDelta base pattern (32-word cycle, mask 0x3E).
    // Subtle 0-1px shimmer used above water (heat haze).
    private static final short[] AIZ_FINE_HAZE_FG_DEFORM = {
            0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0
    };

    /**
     * AIZ1_WaterFGDeformDelta: smooth sinusoidal wave used below the water surface.
     * 64-word cycle (mask 0x7E), amplitude +/-1px. Phase = frameCounter + waterLevel*2.
     * ROM reference: s3.asm line ~69128.
     */
    private static final short[] AIZ_WATER_FG_DEFORM = {
             1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1,  0,  0,  0,  0,
             0,  0,  0,  0,  0,  0, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
            -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,  0,  0,  0,  0,
             0,  0,  0,  0,  0,  0,  1,  1,  1,  1,  1,  1,  1,  1,  1,  1
    };

    /**
     * AIZ2_ALZ_BGDeformDelta: noisy +/-2px shimmer for BG above water.
     * 32-word cycle (mask 0x3E). Phase = (frameCounter>>1) + Camera_Y_pos_BG_copy*2.
     * ROM reference: sonic3k.asm line 105652.
     */
    private static final short[] AIZ_BG_HAZE_DEFORM = {
            -2,  1,  2,  2, -1,  2,  2,  1,  2, -1, -2, -2, -2,  1, -1, -1,
            -1,  0, -2,  0,  0,  0, -2,  0, -2,  2,  0, -2,  2,  2, -1, -2
    };

    /**
     * AIZ1_WaterBGDeformDelta: smooth sinusoidal wave for BG below water.
     * 64-word cycle (mask 0x7E). Phase = (frameCounter>>1) + waterBgY*2.
     * ROM reference: sonic3k.asm line 104254.
     */
    private static final short[] AIZ_WATER_BG_DEFORM = {
             0,  0, -1, -1, -1, -1, -1, -1,  0,  0,  0,  1,  1,  1,  1,  1,
             1,  0,  0,  0, -1, -1, -1, -1, -1, -1,  0,  0,  0,  1,  1,  1,
             1,  1,  1,  0,  0,  0, -1, -1, -1, -1, -1, -1,  0,  0,  0,  1,
             1,  1,  1,  1,  1,  0, -1, -2, -2, -1,  0,  2,  2,  2,  2,  0
    };

    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable introBandValues = ScrollValueTable.ofLength(INTRO_DEFORM_BANDS);
    private final ScrollValueTable deformValues = ScrollValueTable.ofLength(FLAT_VALUE_COUNT);
    private final short[] aiz2SpeedValues = new short[AIZ2_SPEED_LEVELS];

    /**
     * Persistent wave accumulator (ROM: HScroll_table+$03C, advances $2000/frame).
     * Derived from the frame counter (not the update-call count) so it rewinds
     * correctly — a call-count accumulator drifts, snapping back only at keyframe
     * boundaries. The AIZ1 deform path runs continuously once the main level is
     * active, so anchoring at its first frame reproduces the ROM value exactly.
     */
    private int waveBaseFrame;
    private boolean waveBaseFrameSet;

    @Override
    public void init(int actId, int cameraX, int cameraY) {
        // Re-arm the wave anchor on zone/act (re)entry so a reused handler
        // re-derives the canopy wave from the new start frame.
        waveBaseFrameSet = false;
    }

    @Override
    public void update(int[] horizScrollBuf,
                       int cameraX,
                       int cameraY,
                       int frameCounter,
                       int actId) {
        composer.reset();
        composer.setVscrollFactorFG((short) cameraY);
        composer.setShakeOffsetX(0);
        composer.setShakeOffsetY(0);

        short fgScroll = negWord(cameraX);
        AizZoneRuntimeState aizState = resolveAizState();
        if (aizState != null) {
            composer.setShakeOffsetY(aizState.getScreenShakeOffsetY());
        }
        FireCurtainRenderState curtainState = aizState != null
                ? aizState.getFireCurtainRenderState(VISIBLE_LINES)
                : FireCurtainRenderState.inactive();
        boolean fireTransition = aizState != null && aizState.isFireTransitionScrollActive();
        int bgSourceX = fireTransition ? aizState.getFireTransitionBgX() : cameraX;

        // ROM mode gate: only AIZ1 dispatches AIZ1_IntroDeform before the $1400
        // transition. A direct AIZ2 load also keeps Level_started_flag clear
        // during its title card, but AIZ2_BackgroundInit dispatches AIZ2_Deform.
        boolean introMode = false;
        try {
            introMode = actId == 0
                    && !GameServices.camera().isLevelStarted()
                    && !AizPlaneIntroInstance.isMainLevelPhaseActive();
        } catch (Exception e) {
            LOG.fine(() -> "SwScrlAiz.update: " + e.getMessage());
        }

        if (introMode) {
            // AIZ1_IntroDeform:
            // d0 = Events_fg_1; if non-negative, use Camera_X_pos_copy.
            int introOffset = AizPlaneIntroInstance.getIntroScrollOffset();
            int source = introOffset < 0 ? introOffset : cameraX;
            buildIntroBandValues(source);
            composer.setVscrollFactorBG(wordOf(cameraY));
            writeIntroScroll(fgScroll, cameraY);
        } else {
            if (fireTransition) {
                // ROM fire-transition path uses PlainDeformation, not AIZ1_Deform:
                // FG scroll stays tied to the camera, BG scroll is flat and driven by
                // Camera_X_pos_BG_copy while the column VScroll wave adds the wobble.
                composer.setVscrollFactorBG(wordOf(aizState.getFireTransitionBgY()));
                writePlainDeformation(fgScroll, negWord(bgSourceX));
            } else if (actId > 0) {
                // AIZ2_Deform: scattered-speed BG parallax with shake-compensated Y.
                // BG vertical scroll = (cameraY - shake) / 2 + shake.
                short shakeY = (short) composer.getShakeOffsetY();
                composer.setVscrollFactorBG((short) (asrWord(cameraY, 1) + shakeY));
                // ROM: AIZ2BGE_Normal applies a one-time BG Y offset when the
                // battleship sequence approaches. Add it to the BG vertical scroll.
                if (aizState != null && aizState.getBattleshipBgYOffset() != 0) {
                    composer.setVscrollFactorBG((short) (composer.getVscrollFactorBG() + aizState.getBattleshipBgYOffset()));
                }
                // During battleship auto-scroll, use the smooth (non-wrapping) X for
                // BG parallax to avoid visible background jumps on camera wrap-back.
                int bgDeformX = cameraX;
                if (aizState != null && aizState.isBattleshipAutoScrollActive()) {
                    bgDeformX = aizState.getBattleshipSmoothScrollX();
                }
                computeAiz2Deform(fgScroll, bgDeformX);
            } else {
                // AIZ1_Deform: multi-band BG parallax with per-band speeds.
                // BG vertical scroll = camera Y / 2.
                composer.setVscrollFactorBG(asrWord(cameraY, 1));
                computeAiz1Deform(fgScroll, bgSourceX, frameCounter);
            }
        }

        if (composer.getShakeOffsetY() != 0) {
            composer.setVscrollFactorFG((short) (cameraY + composer.getShakeOffsetY()));
        }

        // Fine post-burn haze (AIZ2 style) is a subtle per-line FG deformation.
        // Keep it separate from AIZTrans_WavyFlame, which is a temporary BG transition effect.
        boolean fineHeatHazeActive = !fireTransition
                && ((aizState != null && aizState.isPostFireHazeActive())
                || (aizState == null && actId > 0)
                || cameraX >= 0x2E00);
        if (fineHeatHazeActive) {
            int waterScreenY = resolveWaterScreenY(actId, cameraY);
            applyFgDeformation(cameraX, cameraY, frameCounter, waterScreenY);
            composer.recalculateTrackedOffsets();
        }

        if (fireTransition) {
            applyFireWaveVScroll(curtainState.columnWaveOffsetsPx());
        }

        composer.copyPackedScrollWordsTo(horizScrollBuf);
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
        vscrollFactorBG = composer.getVscrollFactorBG();
    }

    /**
     * AIZ1_Deform: compute multi-band BG scroll values and distribute across scanlines.
     *
     * <p>ROM reference: s3.asm AIZ1_Deform (line ~70272) + ApplyDeformation.
     *
     * <p>Builds a flat 25-word value array mirroring HScroll_table+$008...$038:
     * <ul>
     *   <li>Bands 0-5 (6 words): tree canopy with wave motion</li>
     *   <li>Band 6 (1 word): transition at base speed</li>
     *   <li>Band 7 (13 words): per-line gradient from base*9/8 to base*21/8</li>
     *   <li>Bands 8-12 (5 words): mountain/sky at base*14, 16, 18, 20, 18</li>
     * </ul>
     */
    private void computeAiz1Deform(short fgScroll, int cameraX, int frameCounter) {
        // base = (cameraX - $1300) << 11 in 16.16 fixed-point
        int relative = (short) (cameraX - DEFORM_ORIGIN_X);
        long base = ((long) relative << 16) >> 5; // = relative << 11

        deformValues.clear();

        // --- Bands 0-5: tree canopy with wave (HScroll_table+$008...$012) ---
        // Wave accumulator persists across frames (ROM: HScroll_table+$03C += $2000),
        // derived from the frame counter for rewind-safety. Anchored (offset 0,
        // read-then-increment) on the first AIZ1 deform frame; computed as long to
        // preserve the ROM's unbounded 32-bit accumulation.
        if (!waveBaseFrameSet) {
            waveBaseFrame = frameCounter;
            waveBaseFrameSet = true;
        }
        long d3 = (long) (frameCounter - waveBaseFrame) * 0x2000;

        // ROM: d0 starts at base/2, each iteration adds d3 (wave) before write,
        //      then adds base (d2) after write.
        long d0 = base >> 1; // base / 2
        for (int i = 5; i >= 0; i--) {
            d0 += d3;
            deformValues.set(i, (short) (d0 >> 16));
            d0 += base;
        }

        // --- Band 6: transition (HScroll_table+$014) ---
        deformValues.set(6, (short) (base >> 16));

        // --- Band 7: per-line gradient (HScroll_table+$016...$02E, 13 words) ---
        // ROM: d0 = base, d2 = base/8, loop 13x: d0 += d2, store
        long increment = base >> 3; // base / 8
        d0 = base;
        for (int i = 0; i < 13; i++) {
            d0 += increment;
            deformValues.set(7 + i, (short) (d0 >> 16));
        }

        // --- Bands 8-12: mountain/sky (HScroll_table+$030...$038, 5 words) ---
        // ROM: d1 = base*2, d0 = d1*8 - d1 = base*14, then d0 += d1 each step
        long d1 = base + base;          // base*2
        d0 = (d1 << 3) - d1;            // base*16 - base*2 = base*14
        deformValues.set(20, (short) (d0 >> 16)); // band 8: base*14
        d0 += d1;
        deformValues.set(21, (short) (d0 >> 16)); // band 9: base*16
        d0 += d1;
        deformValues.set(22, (short) (d0 >> 16)); // band 10: base*18
        d0 += d1;
        deformValues.set(23, (short) (d0 >> 16)); // band 11: base*20
        deformValues.set(24, deformValues.get(22)); // band 12: base*18 (same as band 10)

        // Distribute values across scanlines using AIZ1_DeformArray heights.
        DeformationPlan.applyFlaggedTableBands(
                composer,
                composer.getVscrollFactorBG(),
                fgScroll,
                deformValues,
                AIZ1_DEFORM_HEIGHTS,
                0,
                NEGATE_WORD);
    }

    /**
     * AIZ2_Deform: compute scattered-speed BG scroll values and distribute.
     *
     * <p>ROM reference: Lockon S3/Screen Events.asm AIZ2_Deform + AIZ2_BGDeformMake.
     *
     * <p>Uses Events_fg_1 (≈ cameraX) as base, computes 7 speed levels, then
     * scatters them into a 25-word table using AIZ2_BGDeformMake indices.
     * The resulting wave pattern has slowest scrolling (speed 0) at the
     * distant sky bands and fastest (speed 6) at the nearest tree bands.
     */
    private void computeAiz2Deform(short fgScroll, int cameraX) {
        // ROM: d0 = Events_fg_1 << 15 (signed 16.16 fixed-point)
        short relX = (short) cameraX;
        long base = (long) relX << 15;

        // ROM: d1 = base >> 5 (= relX << 10); d2 = d1; d1 += d1; d1 += d2
        //      → d1 = 3 * (relX << 10) = relX * 0xC00
        long d1 = base >> 5;
        long d2 = d1;
        d1 += d1;
        d1 += d2;

        // Compute 7 speed level values (speed 0 = slowest, speed 6 = fastest)
        long d0 = base;
        for (int i = 0; i < AIZ2_SPEED_LEVELS; i++) {
            aiz2SpeedValues[i] = (short) (d0 >> 16);
            d0 += d1;
        }

        // Scatter into 25-entry flat value array using BGDeformMake pattern
        deformValues.clear();
        for (int i = 0; i < FLAT_VALUE_COUNT; i++) {
            deformValues.set(i, aiz2SpeedValues[AIZ2_SPEED_MAP[i]]);
        }

        // Distribute across scanlines using AIZ2_BGDeformArray heights.
        DeformationPlan.applyTableBands(
                composer,
                composer.getVscrollFactorBG(),
                fgScroll,
                deformValues,
                AIZ2_DEFORM_HEIGHTS,
                0,
                NEGATE_WORD);
    }

    private void buildIntroBandValues(int source) {
        int d0 = (short) source;
        d0 >>= 1;

        if (d0 >= INTRO_DEFORM_CAP) {
            short value = (short) d0;
            for (int i = 0; i < INTRO_DEFORM_BANDS; i++) {
                introBandValues.set(i, value);
            }
            return;
        }

        introBandValues.set(0, (short) d0);

        int accum = (d0 - INTRO_DEFORM_CAP) << 16;
        int step = accum >> 5;
        for (int i = 1; i < INTRO_DEFORM_BANDS; i++) {
            accum += step;
            introBandValues.set(i, (short) ((accum >> 16) + INTRO_DEFORM_CAP));
        }
    }

    private void writePlainDeformation(short fgScroll, short bgScroll) {
        composer.fillPackedScrollWords(0, VISIBLE_LINES, fgScroll, bgScroll);
    }

    private void writeIntroScroll(short fgScroll, int cameraY) {
        DeformationPlan.applyTableBands(
                composer,
                cameraY,
                fgScroll,
                introBandValues,
                INTRO_DEFORM_SEGMENTS,
                0,
                NEGATE_WORD);
    }

    private static int[] buildIntroDeformSegments() {
        // AIZ1_IntroDeformArray: $3E0, then 36 entries of 4, then $7FFF.
        int[] segments = new int[INTRO_DEFORM_BANDS + 1];
        segments[0] = 0x3E0;
        for (int i = 1; i < INTRO_DEFORM_BANDS; i++) {
            segments[i] = 4;
        }
        segments[INTRO_DEFORM_BANDS] = INTRO_DEFORM_TERMINATOR;
        return segments;
    }

    @Override
    public short[] getPerLineVScrollBG() {
        return composer.getPerLineVScrollBG();
    }

    @Override
    public short[] getPerColumnVScrollBG() {
        return composer.getPerColumnVScrollBG();
    }

    private void applyFireWaveVScroll(int[] columnWaveOffsetsPx) {
        if (columnWaveOffsetsPx == null || columnWaveOffsetsPx.length == 0) {
            composer.clearPerColumnVScrollBG();
            return;
        }
        short[] fireWaveColumns = composer.writablePerColumnVScrollBG(Sonic3kAIZEvents.FIRE_WAVE_COLUMN_COUNT);
        Arrays.fill(fireWaveColumns, (short) 0);
        for (int i = 0; i < fireWaveColumns.length; i++) {
            fireWaveColumns[i] = (short) (i < columnWaveOffsetsPx.length ? columnWaveOffsetsPx[i] : 0);
        }
    }

    /**
     * Apply per-line FG and BG deformation, splitting at the water surface.
     *
     * <p>ROM reference: AIZ2_ApplyDeform (Lockon S3/Screen Events.asm line 789).
     *
     * <p><b>FG deformation</b> (MakeFGDeformArray):
     * <ul>
     *   <li>Above water: {@code AIZ2_SOZ1_LRZ3_FGDeformDelta} — subtle 0/1px heat shimmer,
     *       32-word cycle, phase = {@code (frameCounter + cameraY*2) & 0x3E}.</li>
     *   <li>Below water: {@code AIZ1_WaterFGDeformDelta} — smooth +/-1px sinusoidal ripple,
     *       64-word cycle, phase = {@code (frameCounter + waterLevel*2) & 0x7E}.</li>
     * </ul>
     *
     * <p><b>BG deformation</b> (ApplyFGandBGDeformation):
     * <ul>
     *   <li>Above water: {@code AIZ2_ALZ_BGDeformDelta} — noisy +/-2px shimmer,
     *       32-word cycle, phase = {@code ((frameCounter>>1) + bgY*2) & 0x3E}.</li>
     *   <li>Below water: {@code AIZ1_WaterBGDeformDelta} — smooth sinusoidal ripple,
     *       64-word cycle, phase = {@code ((frameCounter>>1) + waterBgY*2) & 0x7E}.</li>
     * </ul>
     *
     * @param waterScreenY screen-relative water Y (0..223 = split, &ge;224 = no water visible,
     *                     &le;0 = entirely underwater)
     */
    private void applyFgDeformation(int cameraX, int cameraY, int frameCounter, int waterScreenY) {
        short baseFg = negWord(cameraX);

        // FG heat haze phase (above water): ROM uses Camera_Y_pos_copy in the phase
        int fgHazePhase = ((frameCounter + (cameraY << 1)) & 0x3E) >> 1;

        // FG water ripple phase (below water): ROM uses Water_level in the phase
        int waterLevel = cameraY + waterScreenY; // reconstruct world-space water level
        int fgWaterPhase = ((frameCounter + (waterLevel << 1)) & 0x7E) >> 1;

        // BG heat haze phase (above water): ROM uses (frameCounter>>1) + Camera_Y_pos_BG_copy*2
        short bgY = composer.getVscrollFactorBG();
        int bgHazePhase = (((frameCounter >> 1) + (bgY << 1)) & 0x3E) >> 1;

        // BG water ripple phase (below water): ROM uses waterBgY = Water_level - cameraY + bgY
        int waterBgY = waterScreenY + bgY;
        int bgWaterPhase = (((frameCounter >> 1) + (waterBgY << 1)) & 0x7E) >> 1;

        // Determine split boundaries
        int hazeEnd = Math.min(Math.max(waterScreenY, 0), VISIBLE_LINES);

        // Above water: heat haze deformation (FG + BG)
        for (int line = 0; line < hazeEnd; line++) {
            int packed = composer.packedScrollWordAt(line);
            short bg = (short) (unpackBG(packed) + AIZ_BG_HAZE_DEFORM[(bgHazePhase + line) & 0x1F]);
            short fg = (short) (baseFg + AIZ_FINE_HAZE_FG_DEFORM[(fgHazePhase + line) & 0x1F]);
            composer.writePackedScrollWord(line, fg, bg);
        }

        // Below water: water ripple deformation (FG + BG)
        for (int line = hazeEnd; line < VISIBLE_LINES; line++) {
            int packed = composer.packedScrollWordAt(line);
            short bg = (short) (unpackBG(packed) + AIZ_WATER_BG_DEFORM[(bgWaterPhase + line) & 0x3F]);
            short fg = (short) (baseFg + AIZ_WATER_FG_DEFORM[(fgWaterPhase + line) & 0x3F]);
            composer.writePackedScrollWord(line, fg, bg);
        }
    }

    /**
     * Resolve the screen-relative water Y position for AIZ.
     * Returns a value &ge; VISIBLE_LINES when there is no water on screen.
     */
    private int resolveWaterScreenY(int actId, int cameraY) {
        try {
            WaterSystem ws = GameServices.water();
            if (ws != null && ws.hasWater(Sonic3kZoneIds.ZONE_AIZ, actId)) {
                int waterWorldY = ws.getWaterLevelY(Sonic3kZoneIds.ZONE_AIZ, actId);
                return waterWorldY - cameraY;
            }
        } catch (Exception e) {
            LOG.fine(() -> "SwScrlAiz.resolveWaterScreenY: " + e.getMessage());
        }
        return VISIBLE_LINES; // no water → all heat haze
    }

    private AizZoneRuntimeState resolveAizState() {
        try {
            return GameServices.hasRuntime()
                    ? S3kRuntimeStates.currentAiz(GameServices.zoneRuntimeRegistry()).orElse(null)
                    : null;
        } catch (Exception e) {
            LOG.fine(() -> "SwScrlAiz.resolveAizState: " + e.getMessage());
            return null;
        }
    }

    @Override
    public int getShakeOffsetX() {
        return composer.getShakeOffsetX();
    }

    @Override
    public int getShakeOffsetY() {
        return composer.getShakeOffsetY();
    }

    @Override
    public short getVscrollFactorFG() {
        return composer.getVscrollFactorFG();
    }
}
