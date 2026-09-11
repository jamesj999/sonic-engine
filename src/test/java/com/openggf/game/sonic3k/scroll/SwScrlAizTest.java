package com.openggf.game.sonic3k.scroll;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.asrWord;
import static com.openggf.level.scroll.M68KMath.negWord;
import static com.openggf.level.scroll.M68KMath.packScrollWords;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static com.openggf.level.scroll.M68KMath.unpackFG;

@RequiresRom(SonicGame.SONIC_3K)
public class SwScrlAizTest {

    private static final int INTRO_DEFORM_BANDS = 0x25;
    private static final int INTRO_DEFORM_CAP = 0x580;
    private static final int DEFORM_ORIGIN_X = 0x1300;
    private static final short[] AIZ_FINE_HAZE_FG_DEFORM = {
            0, 0, 1, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 1, 1, 0, 0
    };

    private SwScrlAiz handler;
    private Camera camera;
    private Sonic3kLevelEventManager eventsManager;
    private HeadlessTestFixture fixture;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        camera = fixture.camera();
        handler = new SwScrlAiz();
        camera.setLevelStarted(true);
        eventsManager = (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        eventsManager.resetState();
        resetIntroScrollState();
    }

    @AfterEach
    public void tearDown() {
        camera.setLevelStarted(true);
        eventsManager.resetState();
        resetIntroScrollState();
    }

    @Test
    public void introModeMatchesApplyDeformationForCameraSource() {
        camera.setLevelStarted(false);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x400;
        int cameraY = 0x3C0;

        handler.update(buffer, cameraX, cameraY, 0, 0);

        int[] expected = buildExpectedIntroBuffer(cameraX, cameraY, cameraX);
        assertEquals((short) cameraY, handler.getVscrollFactorBG());
        assertPackedEquals(expected, buffer);
    }

    @Test
    public void introModeUsesEventsFg1WhileNegative() {
        activateIntroScrollState();
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x200;
        int cameraY = 0x3C0;
        int source = AizPlaneIntroInstance.getIntroScrollOffset();
        assertTrue(source < 0);

        handler.update(buffer, cameraX, cameraY, 0, 0);

        int[] expected = buildExpectedIntroBuffer(cameraX, cameraY, source);
        assertEquals((short) cameraY, handler.getVscrollFactorBG());
        assertPackedEquals(expected, buffer);
    }

    @Test
    public void normalModeUsesPerBandParallaxAndHalfVerticalCamera() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x200;
        int cameraY = 0x80;

        handler.update(buffer, cameraX, cameraY, 0, 0);

        // vscrollFactorBG = cameraY / 2
        assertEquals(asrWord(cameraY, 1), handler.getVscrollFactorBG());

        // Compute expected band values from AIZ1_Deform formula
        short fgScroll = negWord(cameraX);
        int relative = (short) (cameraX - DEFORM_ORIGIN_X);
        long base = ((long) relative << 16) >> 5;

        // Tree bands (wave=0 on first call): d0 = base/2, loop adds wave+base
        long d0 = base >> 1;
        short[] treeBands = new short[6];
        for (int i = 5; i >= 0; i--) {
            // d3 (wave) = 0 on first frame
            treeBands[i] = (short) (d0 >> 16);
            d0 += base;
        }

        // bgY=64: band 0 (208px) â†’ 144 visible lines, band 1 (32px), band 2 (48px)
        short bg0 = negWord(treeBands[0]);
        short bg1 = negWord(treeBands[1]);
        short bg2 = negWord(treeBands[2]);

        int expected0 = packScrollWords(fgScroll, bg0);
        int expected1 = packScrollWords(fgScroll, bg1);
        int expected2 = packScrollWords(fgScroll, bg2);

        // Band 0: lines 0-143
        assertEquals(expected0, buffer[0], "Band 0 start");
        assertEquals(expected0, buffer[143], "Band 0 end");
        // Band 1: lines 144-175
        assertEquals(expected1, buffer[144], "Band 1 start");
        assertEquals(expected1, buffer[175], "Band 1 end");
        // Band 2: lines 176-223
        assertEquals(expected2, buffer[176], "Band 2 start");
        assertEquals(expected2, buffer[223], "Band 2 end");

        // Different bands have different scroll speeds
        assertNotEquals(buffer[143], buffer[144], "Bands 0 and 1 differ");
        assertNotEquals(buffer[175], buffer[176], "Bands 1 and 2 differ");
    }

    @Test
    public void mountainBandsUseCorrectMultipliers() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];
        // cameraX=0x1400: relative=256, base=256*2048=524288 (base>>16=8)
        // cameraY=640: bgY=320, which skips bands 0-2 fully and part of band 3
        int cameraX = 0x1400;
        int cameraY = 640;

        handler.update(buffer, cameraX, cameraY, 0, 0);

        short fgScroll = negWord(cameraX);
        int relative = (short) (cameraX - DEFORM_ORIGIN_X);
        long base = ((long) relative << 16) >> 5;

        // --- Band 7: per-line gradient (lines 64-76) ---
        // 13 distinct values: base*9/8 through base*21/8
        long inc = base >> 3;
        long d0 = base;
        for (int line = 64; line <= 76; line++) {
            d0 += inc;
            short expectedBg = negWord((short) (d0 >> 16));
            short actualBg = unpackBG(buffer[line]);
            assertEquals(expectedBg, actualBg, "Band 7 per-line at scanline " + line);
        }

        // Verify band 7 lines are distinct (per-line gradient, not repeated)
        assertNotEquals(unpackBG(buffer[64]), unpackBG(buffer[65]), "Band 7 lines differ");

        // --- Band 8 (lines 77-91): base*14 ---
        long d1 = base + base;
        long mountain = (d1 << 3) - d1; // base*14
        short bg8 = negWord((short) (mountain >> 16));
        for (int line = 77; line <= 91; line++) {
            assertEquals(bg8, unpackBG(buffer[line]), "Band 8 at line " + line);
        }

        // --- Band 9 (lines 92-97): base*16 ---
        mountain += d1;
        short bg9 = negWord((short) (mountain >> 16));
        assertEquals(bg9, unpackBG(buffer[92]), "Band 9");

        // --- Band 10 (lines 98-111): base*18 ---
        mountain += d1;
        short bg10 = negWord((short) (mountain >> 16));
        assertEquals(bg10, unpackBG(buffer[98]), "Band 10");

        // --- Band 11 (lines 112-191): base*20 ---
        mountain += d1;
        short bg11 = negWord((short) (mountain >> 16));
        assertEquals(bg11, unpackBG(buffer[112]), "Band 11 start");
        assertEquals(bg11, unpackBG(buffer[191]), "Band 11 end");

        // --- Band 12 (lines 192-223): base*18 (same as band 10) ---
        assertEquals(bg10, unpackBG(buffer[192]), "Band 12 = base*18");
        assertEquals(bg10, unpackBG(buffer[223]), "Band 12 end");

        // Mountain bands are much faster than tree bands
        // base*14 >> 16 = 112, which is 14x the base speed of 8
        assertEquals((short) -112, bg8, "Band 8 speed = base*14");
        assertEquals((short) -160, bg11, "Band 11 speed = base*20");
    }

    @Test
    public void aiz1StartingInsideFlaggedPerLineBandConsumesCorrectVisibleGradientSlice() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x1400;
        int cameraY = 0x30A; // bgY = 0x185, five lines into the flagged 13-line band

        handler.update(buffer, cameraX, cameraY, 0, 0);

        assertEquals(asrWord(cameraY, 1), handler.getVscrollFactorBG());

        int relative = (short) (cameraX - DEFORM_ORIGIN_X);
        long base = ((long) relative << 16) >> 5;
        long increment = base >> 3;
        long value = base;
        short[] flaggedValues = new short[13];
        for (int i = 0; i < flaggedValues.length; i++) {
            value += increment;
            flaggedValues[i] = negWord((short) (value >> 16));
        }

        for (int line = 0; line < 8; line++) {
            assertEquals(flaggedValues[5 + line], unpackBG(buffer[line]), "Flagged band line " + line);
        }
        assertNotEquals(unpackBG(buffer[0]), unpackBG(buffer[1]), "Flagged lines must remain per-line distinct");

        long d1 = base + base;
        long mountain = (d1 << 3) - d1; // base*14
        short bg8 = negWord((short) (mountain >> 16));
        for (int line = 8; line <= 22; line++) {
            assertEquals(bg8, unpackBG(buffer[line]), "Band 8 should follow immediately after flagged slice");
        }
    }

    @Test
    public void aiz1HighYPastAllBandsClampsToLastMountainBand() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x1400;
        int cameraY = 0x800; // bgY = 0x400, below all authored AIZ1 bands

        handler.update(buffer, cameraX, cameraY, 0, 0);

        int relative = (short) (cameraX - DEFORM_ORIGIN_X);
        long base = ((long) relative << 16) >> 5;
        long d1 = base + base;
        long mountain = (d1 << 3) - d1; // base*14
        mountain += d1; // base*16
        mountain += d1; // base*18 (band 10)
        short expectedBg = negWord((short) (mountain >> 16)); // final authored band 12 = base*18

        for (int line = 0; line < VISIBLE_LINES; line++) {
            assertEquals(expectedBg, unpackBG(buffer[line]), "High-Y clamp mismatch at scanline " + line);
        }
    }

    @Test
    public void fireTransitionExposesPerColumnVScrollWave() {
        eventsManager.initLevel(0, 0);
        Sonic3kAIZEvents events = eventsManager.getAizEvents();
        assertNotNull(events);

        events.setEventsFg5(true);
        events.update(0, 0);
        assertTrue(events.isFireTransitionActive());

        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, 0x2F10, 0x200, 8, 0);

        short[] perColumn = handler.getPerColumnVScrollBG();
        assertNotNull(perColumn);
        assertEquals(20, perColumn.length);

        boolean hasVariation = false;
        short first = perColumn[0];
        for (int i = 1; i < perColumn.length; i++) {
            if (perColumn[i] != first) {
                hasVariation = true;
                break;
            }
        }
        assertTrue(hasVariation, "Expected non-flat per-column VScroll during AIZ fire transition");
    }

    @Test
    public void fireTransitionUsesPlainDeformationInsteadOfAiz1ParallaxBands() {
        Sonic3kLevelEventManager eventsManager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        eventsManager.initLevel(0, 0);
        Sonic3kAIZEvents events = eventsManager.getAizEvents();
        assertNotNull(events);

        events.setEventsFg5(true);
        events.update(0, 0);
        assertTrue(events.isFireTransitionActive());

        int cameraX = 0x2F10;
        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, cameraX, 0x200, 8, 0);

        int expected = packScrollWords(negWord(cameraX), negWord(events.getFireTransitionBgX()));
        assertEquals(expected, buffer[0]);
        assertEquals(expected, buffer[VISIBLE_LINES - 1]);
    }

    @Test
    public void resumedAct2FireContinuationStillUsesPlainFireScrollMode() {
        Sonic3kLevelEventManager eventsManager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        eventsManager.initLevel(0, 0);
        Sonic3kAIZEvents act1Events = eventsManager.getAizEvents();
        assertNotNull(act1Events);
        AizPlaneIntroInstance.setMainLevelPhaseActive(true);
        camera.setX((short) 0x2F10);

        for (int i = 0; i < 100_000 && !act1Events.isFireOverlayTilesLoaded(); i++) {
            HardwareBoundaryPump.service(HardwareServiceBoundary.VINT_SERVICE);
            HardwareBoundaryPump.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            act1Events.update(0, i);
            HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
        }
        assertTrue(act1Events.isFireOverlayTilesLoaded(),
                "AIZ1 loc_1C5C6 stages flame art before the boss exit signal");
        act1Events.setEventsFg5(true);
        for (int i = 0; i < 100_000 && !act1Events.isAct2TransitionRequested(); i++) {
            HardwareBoundaryPump.service(HardwareServiceBoundary.VINT_SERVICE);
            HardwareBoundaryPump.service(HardwareServiceBoundary.PRE_MAIN_LOOP);
            GameServices.level().getObjectManager().advanceVblaCounter();
            act1Events.update(0, i);
            HardwareBoundaryPump.service(HardwareServiceBoundary.POST_OBJECTS);
        }
        assertTrue(act1Events.getFireOverlayTileCount() > 0);

        eventsManager.initLevel(0, 1);
        Sonic3kAIZEvents act2Events = eventsManager.getAizEvents();
        assertNotNull(act2Events);
        assertTrue(act2Events.isFireTransitionScrollActive());

        int cameraX = 0x0010;
        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, cameraX, 0x180, 0, 1);

        int expected = packScrollWords(negWord(cameraX), negWord(act2Events.getFireTransitionBgX()));
        assertEquals(expected, buffer[0]);
        assertEquals(expected, buffer[VISIBLE_LINES - 1]);
    }

    @Test
    public void aiz2UsesScatteredSpeedParallaxWithDifferentBands() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x2000;
        int cameraY = 0x80;

        handler.update(buffer, cameraX, cameraY, 0, 1); // actId=1 â†’ AIZ2

        // AIZ2 BG Y = cameraY/2 + shake (shake=0 in tests)
        assertEquals(asrWord(cameraY, 1), handler.getVscrollFactorBG());

        // Compute expected AIZ2 speed levels
        short relX = (short) cameraX;
        long base = (long) relX << 15;
        long d1 = base >> 5;
        long d2 = d1;
        d1 += d1;
        d1 += d2; // d1 = 3 * (relX << 10)

        short[] speedValues = new short[7];
        long d0 = base;
        for (int i = 0; i < 7; i++) {
            speedValues[i] = (short) (d0 >> 16);
            d0 += d1;
        }

        // Speed 0 = relX/2 (slowest), speed 6 = fastest
        assertEquals((short) (relX / 2), speedValues[0], "Speed 0 = cameraX/2");

        // AIZ2_SPEED_MAP: band 0 â†’ speed 3, band 9 â†’ speed 0 (sky)
        int[] speedMap = {3, 4, 5, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3,
                          4, 5, 6, 5, 4, 3, 2, 1, 0, 1, 2, 3};
        short[] values = new short[25];
        for (int i = 0; i < 25; i++) {
            values[i] = speedValues[speedMap[i]];
        }

        // AIZ2_DEFORM_HEIGHTS: first band = 0x10 (16 lines)
        // bgY=64 â†’ skip 64 pixels worth of bands:
        //   band 0 = 0x10 (16px): 64-16 = 48 remaining
        //   band 1 = 0x20 (32px): 48-32 = 16 remaining
        //   band 2 = 0x38 (56px): 16-56 < 0 â†’ partial (40 visible lines)
        short fgScroll = negWord(cameraX);

        // Band 2 (speed 5): 40 visible lines at start.
        // AIZ2 BG heat haze is active (aizEvents == null && actId > 0 defaults to haze-on).
        // bgHazePhase = 0 for frameCounter=0, bgY=64.  Per-line offsets from AIZ_BG_HAZE_DEFORM.
        short bg2 = negWord(values[2]);
        assertEquals((short)(bg2 - 2), unpackBG(buffer[0]), "Band 2 at line 0");   // haze[0]=-2
        assertEquals((short)(bg2 + 1), unpackBG(buffer[39]), "Band 2 at line 39"); // haze[7]=+1

        // Band 3 (speed 6, height 0x58=88): starts at line 40
        short bg3 = negWord(values[3]);
        assertEquals((short)(bg3 + 2), unpackBG(buffer[40]), "Band 3 at line 40"); // haze[8]=+2

        // Band 3 is faster (speed 6) than band 2 (speed 5)
        assertNotEquals(bg2, bg3, "Speed 5 != speed 6");
    }

    @Test
    public void directAiz2StartupUsesAct2DeformWhileLevelStartIsPending() {
        camera.setLevelStarted(false);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x2000;
        int cameraY = 0x80;

        handler.update(buffer, cameraX, cameraY, 0, 1);

        assertEquals(asrWord(cameraY, 1), handler.getVscrollFactorBG(),
                "AIZ2_BackgroundInit must use the Act 2 half-speed BG Y during title-card startup");
        assertNotEquals(unpackBG(buffer[0]), unpackBG(buffer[100]),
                "direct AIZ2 entry must build the scattered Act 2 parallax bands, not AIZ1 intro scroll");
    }

    @Test
    public void aiz2DiffersFromAiz1AtSamePosition() {
        camera.setLevelStarted(true);
        int cameraX = 0x2000;
        int cameraY = 0x80;

        int[] act1Buffer = new int[VISIBLE_LINES];
        handler.update(act1Buffer, cameraX, cameraY, 0, 0);

        // Reset handler for clean state
        handler = new SwScrlAiz();
        int[] act2Buffer = new int[VISIBLE_LINES];
        handler.update(act2Buffer, cameraX, cameraY, 0, 1);

        // BG scroll values should differ between AIZ1 and AIZ2
        boolean differs = false;
        for (int i = 0; i < VISIBLE_LINES; i++) {
            if (unpackBG(act1Buffer[i]) != unpackBG(act2Buffer[i])) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "AIZ1 and AIZ2 BG parallax should differ");
    }

    @Test
    public void postBurnFineHazeUsesAiz2ForegroundDeltaTable() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];
        int cameraX = 0x2E20;
        int cameraY = 0x180;
        int frameCounter = 19;

        handler.update(buffer, cameraX, cameraY, frameCounter, 0);

        // Fine haze is FG-only in this phase; no fire-transition column VScroll should be active.
        assertNull(handler.getPerColumnVScrollBG());

        int phase = ((frameCounter + (cameraY << 1)) & 0x3E) >> 1;
        short baseFg = negWord(cameraX);
        for (int line = 0; line < VISIBLE_LINES; line++) {
            short actualFg = (short) (buffer[line] >>> 16);
            short expectedFg = (short) (baseFg + AIZ_FINE_HAZE_FG_DEFORM[(phase + line) & 0x1F]);
            assertEquals(expectedFg, actualFg, "FG haze mismatch at scanline " + line);
        }
    }

    @Test
    public void postBurnFineHazeUpdatesScrollBoundsFromFinalPackedOutput() {
        camera.setLevelStarted(true);
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x2E20, 0x180, 19, 0);

        int expectedMin = Integer.MAX_VALUE;
        int expectedMax = Integer.MIN_VALUE;
        for (int packed : buffer) {
            int offset = unpackBG(packed) - unpackFG(packed);
            expectedMin = Math.min(expectedMin, offset);
            expectedMax = Math.max(expectedMax, offset);
        }

        assertEquals(expectedMin, handler.getMinScrollOffset(), "Min offset should match final haze-adjusted buffer");
        assertEquals(expectedMax, handler.getMaxScrollOffset(), "Max offset should match final haze-adjusted buffer");
    }

    private int[] buildExpectedIntroBuffer(int cameraX, int cameraY, int source) {
        return buildExpectedIntroBufferDeform(cameraX, cameraY, source);
    }

    private int[] buildExpectedIntroBufferDeform(int cameraX, int cameraY, int source) {
        short fgScroll = negWord(cameraX);
        short[] bands = buildIntroBandValues(source);
        int[] buffer = new int[VISIBLE_LINES];

        int[] segments = new int[INTRO_DEFORM_BANDS];
        segments[0] = 0x3E0;
        for (int i = 1; i < segments.length; i++) {
            segments[i] = 4;
        }

        int remainingY = (short) cameraY;
        int line = 0;
        int segmentIndex = 0;
        int valueIndex = 0;

        while (segmentIndex < segments.length) {
            int next = remainingY - segments[segmentIndex];
            if (next >= 0) {
                remainingY = next;
                segmentIndex++;
                if (valueIndex < INTRO_DEFORM_BANDS - 1) {
                    valueIndex++;
                }
                continue;
            }

            line = fillSegment(buffer, line, -next, fgScroll, negWord(bands[valueIndex]));
            segmentIndex++;
            if (valueIndex < INTRO_DEFORM_BANDS - 1) {
                valueIndex++;
            }

            while (line < VISIBLE_LINES && segmentIndex < segments.length) {
                int count = Math.min(segments[segmentIndex], VISIBLE_LINES - line);
                line = fillSegment(buffer, line, count, fgScroll, negWord(bands[valueIndex]));
                segmentIndex++;
                if (valueIndex < INTRO_DEFORM_BANDS - 1) {
                    valueIndex++;
                }
            }
            break;
        }

        short fallback = negWord(bands[Math.min(valueIndex, INTRO_DEFORM_BANDS - 1)]);
        while (line < VISIBLE_LINES) {
            buffer[line++] = packScrollWords(fgScroll, fallback);
        }
        return buffer;
    }

    private int fillSegment(int[] buffer, int start, int count, short fgScroll, short bgScroll) {
        int end = Math.min(VISIBLE_LINES, start + Math.max(0, count));
        int packed = packScrollWords(fgScroll, bgScroll);
        for (int i = start; i < end; i++) {
            buffer[i] = packed;
        }
        return end;
    }

    private short[] buildIntroBandValues(int source) {
        short[] bands = new short[INTRO_DEFORM_BANDS];
        int d0 = (short) source;
        d0 >>= 1;

        if (d0 >= INTRO_DEFORM_CAP) {
            short value = (short) d0;
            for (int i = 0; i < INTRO_DEFORM_BANDS; i++) {
                bands[i] = value;
            }
            return bands;
        }

        bands[0] = (short) d0;
        int accum = (d0 - INTRO_DEFORM_CAP) << 16;
        int step = accum >> 5;
        for (int i = 1; i < INTRO_DEFORM_BANDS; i++) {
            accum += step;
            bands[i] = (short) ((accum >> 16) + INTRO_DEFORM_CAP);
        }
        return bands;
    }

    private void assertPackedEquals(int[] expected, int[] actual) {
        for (int i = 0; i < VISIBLE_LINES; i++) {
            assertEquals(expected[i], actual[i], "Mismatch at scanline " + i);
        }
    }

    private void activateIntroScrollState() {
        AizPlaneIntroInstance intro = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0));
        intro.setServices(new TestObjectServices().withCamera(camera));
        // First update: routine 0 â†’ init (resets introScrollOffset to 0, advances to routine 2)
        intro.update(0, null);
        // Second update: scrollVelocity runs before routine 2, sets introScrollOffset < 0
        intro.update(1, null);
    }

    private void resetIntroScrollState() {
        AizPlaneIntroInstance.resetIntroPhaseState();
    }
}
