package com.openggf.game.sonic3k.scroll;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.session.SessionManager;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openggf.level.scroll.M68KMath.VISIBLE_LINES;
import static com.openggf.level.scroll.M68KMath.unpackBG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwScrlLbzTest {

    @BeforeEach
    void clearRuntimeState() {
        SessionManager.clear();
    }

    @Test
    void providerRoutesLbzToDedicatedHandler() throws Exception {
        Sonic3kScrollHandlerProvider provider = new Sonic3kScrollHandlerProvider();
        provider.load(new Rom());

        ZoneScrollHandler handler = provider.getHandler(Sonic3kZoneIds.ZONE_LBZ);

        assertNotNull(handler);
        assertTrue(handler instanceof SwScrlLbz, "LBZ should not use the generic S3K fallback scroll handler");
    }

    @Test
    void act1UsesRomBackgroundScrollBands() {
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x2000, 0x0400, 0, 0);

        assertEquals((short) 0x0040, handler.getVscrollFactorBG(),
                "LBZ1_Deform sets Camera_Y_pos_BG_copy = Camera_Y_pos_copy / 16");
        assertEquals(0x020A, handler.getBgCameraX(),
                "LBZ1_Deform exposes Camera_X_pos_BG_copy = Camera_X_pos_copy / 16 + $0A");
        assertEquals((short) -0x020A, unpackBG(buffer[0]),
                "visible output starts from HScroll_table+$008 = Camera_X_pos_copy / 16 + $0A");
        assertEquals((short) -0x0404, unpackBG(buffer[144]),
                "line 144 reaches the next generated LBZ1 scroll value after the BG-Y skip");
    }

    @Test
    void act1ScreenShakeOffsetsForegroundAndBackgroundVScroll() {
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.setScreenShakeOffset(3);
        handler.update(buffer, 0x2000, 0x0400, 0, 0);

        assertEquals((short) 0x0403, handler.getVscrollFactorFG(),
                "ShakeScreen_Setup adds Screen_shake_offset to Camera_Y_pos_copy for the foreground plane.");
        assertEquals((short) 0x0043, handler.getVscrollFactorBG(),
                "LBZ1 applies Screen_shake_offset after computing the normal background parallax factor.");
        assertEquals(3, handler.getShakeOffsetY(),
                "The same shake offset must propagate to sprite/camera rendering.");
    }

    @Test
    void continuousShakeSamplesPriorPublishedLevelCounter() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_AND_TAILS);
        state.setLbz1KnucklesBombShakeActive(true);
        GameServices.zoneRuntimeRegistry().install(state);
        SwScrlLbz handler = new SwScrlLbz();

        handler.update(new int[VISIBLE_LINES], 0x3B60, 0x0148, 37, 0);

        assertEquals(1, handler.getShakeOffsetY(),
                "ShakeScreen_Setup reads ScreenShakeArray2[(Level_frame_counter-1)&$3F].");
    }

    @Test
    void act2UsesRomWaterlineBackgroundCameraAndParallaxBands() {
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x2010, 0x05F0, 0, 1);

        assertEquals((short) 0x02C0, handler.getVscrollFactorBG(),
                "LBZ2_Deform bases Camera_Y_pos_BG_copy at $2C0 when the waterline delta is neutral");
        assertEquals(0x1008, handler.getBgCameraX(),
                "LBZ2_Deform exposes Camera_X_pos_BG_copy = Camera_X_pos_copy / 2");
        assertNotEquals(unpackBG(buffer[0]), unpackBG(buffer[VISIBLE_LINES - 1]),
                "LBZ2 should produce real multi-band parallax, not a flat fallback background");
    }

    @Test
    void act2PublishesRomEventsBgValuesForAnimatedTiles() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        GameServices.zoneRuntimeRegistry().install(state);
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x2010, 0x05F0, 0, 1);

        assertEquals(0, state.lbz2WaterlinePhase(),
                "LBZ2_Deform writes Events_bg+$10 for AnimateTiles_LBZ2 waterline art");
        assertEquals(0x0E07, state.lbz2ScrollArtPhaseSource(),
                "LBZ2_Deform writes Events_bg+$12 after subtracting the final underwater step");
        assertEquals(0x1008, state.publishedBgCameraX(),
                "LBZ2_Deform writes Camera_X_pos_BG_copy for animated-tile phase subtraction");
        assertEquals(0x0F, state.lbz2ScrollArtPhase());
    }

    @Test
    void act2WaterlineLookupIsAnchoredAtRomSurfaceTableIndex() {
        SwScrlLbz handler = new SwScrlLbz(zeroWaterlineLookup());
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x2000, 0x05B0, 0, 1);

        short surfaceSource = handler.getLbz2HScrollWordForTest(79);
        assertEquals((short) 0x0470, surfaceSource,
                "The remap source should be HScroll_table+$09E after the ROM below-water gradient fill.");
        assertEquals(surfaceSource, handler.getLbz2HScrollWordForTest(80),
                "LBZ2_Deform positive waterline deltas remap forward from HScroll_table+$09E.");
        assertEquals(surfaceSource, handler.getLbz2HScrollWordForTest(115),
                "The visible dynamic waterline span should be remapped before the underwater bands begin.");
    }

    @Test
    void act2WaterlinePhaseSubtractsScreenShakeBeforeEquilibriumMath() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.requestScreenShakeOffset(8);
        GameServices.zoneRuntimeRegistry().install(state);
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x2010, 0x05F0, 0, 1);

        assertEquals(4, state.lbz2WaterlinePhase(),
                "ROM LBZ2_Deform subtracts Screen_shake_offset before writing Events_bg+$10.");
        assertEquals((short) 0x02C4, handler.getVscrollFactorBG(),
                "BG vscroll should use the shaken-relative value and then add Screen_shake_offset back.");
        assertEquals((short) 0x05F8, handler.getVscrollFactorFG(),
                "LBZ2_ScreenEvent adds Screen_shake_offset to Camera_Y_pos_copy, so Plane A must shake too.");
    }

    @Test
    void act2ScreenShakeMovesDeformationBandLookupWithBackgroundVScroll() {
        SwScrlLbz baselineHandler = new SwScrlLbz();
        int[] baseline = new int[VISIBLE_LINES];
        baselineHandler.update(baseline, 0x2010, 0x05F0, 0, 1);

        SwScrlLbz shakenHandler = new SwScrlLbz();
        shakenHandler.setScreenShakeOffset(8);
        int[] shaken = new int[VISIBLE_LINES];
        shakenHandler.update(shaken, 0x2010, 0x05F0, 0, 1);

        assertNotEquals(unpackBG(baseline[92]), unpackBG(baseline[96]),
                "The fixture must straddle an LBZ2_BGDeformArray band boundary.");
        assertEquals(unpackBG(baseline[96]), unpackBG(shaken[92]),
                "ROM LBZ2_Deform writes shaken Camera_Y_pos_BG_copy before ApplyDeformation, "
                        + "so a +8 screen shake moves the visible band boundary up by four BG pixels.");
    }

    @Test
    void act2DeathEggScreenShakeIsNotAppliedTwiceToBackgroundVScroll() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.setLaunchActive(true);
        state.requestScreenShakeOffset(8);
        GameServices.zoneRuntimeRegistry().install(state);
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, 0x4380, 0x05F0, 0, 1);

        assertEquals((short) 0x02C4, handler.getVscrollFactorBG(),
                "LBZ2_DeathEggDeform already adds Screen_shake_offset when writing Camera_Y_pos_BG_copy; "
                        + "the generic shake pass must not add it a second time.");
        assertEquals((short) 0x05F8, handler.getVscrollFactorFG(),
                "Death Egg launch foreground/window-plane terrain still uses shaken Camera_Y_pos_copy.");
    }

    @Test
    void act2HScrollTableMatchesLbz2DeformForVisibleWaterlineCase() {
        byte[] waterlineData = zeroWaterlineLookup();

        assertLbz2TableMatchesReference(0x2000, 0x05B0, 0, 0, waterlineData);
        assertLbz2TableMatchesReference(0x2000, 0x0630, 0, 0, waterlineData);
    }

    @Test
    void act2DeathEggDeformOnlyActivatesDuringLaunchMode() {
        TestEnvironment.activeGameplayMode();
        SwScrlLbz normalHandler = new SwScrlLbz();
        int[] normal = new int[VISIBLE_LINES];
        normalHandler.update(normal, 0x4380, 0x0668, 8, 1);

        SwScrlLbz repeatHandler = new SwScrlLbz();
        int[] repeat = new int[VISIBLE_LINES];
        repeatHandler.update(repeat, 0x4380, 0x0668, 8, 1);

        assertEquals(unpackBG(normal[0]), unpackBG(repeat[0]),
                "inactive LBZ2 output must remain stable across handler instances");
        assertEquals(unpackBG(normal[VISIBLE_LINES - 1]), unpackBG(repeat[VISIBLE_LINES - 1]),
                "normal LBZ2 deform should not drift when launch state is absent");

        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.setLaunchActive(true);
        state.setFgAccum(0x0002_0000);
        state.setBgAccum(0x0001_8000);
        GameServices.zoneRuntimeRegistry().install(state);

        SwScrlLbz launchHandler = new SwScrlLbz();
        int[] launch = new int[VISIBLE_LINES];
        launchHandler.update(launch, 0x4380, 0x0668, 8, 1);

        assertNotEquals(unpackBG(normal[0]), unpackBG(launch[0]),
                "launch-active LBZ2 should use the Death Egg deformation bands");
        assertEquals(repeatHandler.getVscrollFactorBG(), normalHandler.getVscrollFactorBG(),
                "normal LBZ2 vscroll baseline remains unchanged while inactive");
    }

    @Test
    void act2DeathEggWindowPlatformAppliesDetachScrollToScrollA() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.setLaunchActive(true);
        state.setFgAccum(0x0010_0000);
        state.setBgAccum(0x0004_0000);
        GameServices.zoneRuntimeRegistry().install(state);

        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, 0x4380, 0x0668, 8, 1);

        assertEquals((short) 0x0668, handler.getVscrollFactorFG(),
                "ROM LBZ2BGE_PlatformDetach seeds V_scroll_value from Camera_Y_pos_copy before "
                        + "Events_bg+$16 detach scrolling begins");

        state.setDetachScroll(5);
        handler.update(buffer, 0x4380, 0x0668, 9, 1);

        assertEquals((short) 0x066D, handler.getVscrollFactorFG(),
                "ROM LBZ2BGE_PlatformDetach adds Events_bg+$16 to Scroll A. The copied VDP window masks "
                        + "the standing platform while the exposed Death Egg/top band detaches.");
    }

    @Test
    void act2DeathEggDeformUsesPersistentWrapLatchAndDeathEggRowCopy() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.setLaunchActive(true);
        state.setFgAccum(0x0A00_0000);
        state.setBgLaunchSpeed(-1);
        GameServices.zoneRuntimeRegistry().install(state);

        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, 0x4380, 0x0668, 12, 1);

        int firstLatch = state.getDeathEggDeformWrapLatch();
        assertTrue(firstLatch >= 0x200,
                "Death Egg deform should persistently accumulate $100 wrap steps while adjusted BG Y is negative");
        assertEquals(0x7FFF, state.lbz2WaterlinePhase(),
                "LBZ2_DeathEggDeform forces Events_bg+$10 to $7FFF once the BG launch speed is negative");
        assertEquals(handler.getLbz2HScrollWordForTest(8), handler.getLbz2HScrollWordForTest(0),
                "Death Egg deform copies HScroll_table+$010 over the first four longs");
        assertEquals(handler.getLbz2HScrollWordForTest(15), handler.getLbz2HScrollWordForTest(7),
                "Death Egg row copy should cover all eight copied words, not just the first row");

        handler.update(buffer, 0x4380, 0x0668, 13, 1);

        assertEquals(firstLatch, state.getDeathEggDeformWrapLatch(),
                "wrap latch remains stable after the first frame instead of recomputing as frame-local floorMod");
    }

    @Test
    void deathEggBackgroundOriginLatchIsDistinctFromLaunchDisplacement() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.setLaunchActive(true);
        state.setFgAccum(0x0300_0000);
        GameServices.zoneRuntimeRegistry().install(state);
        SwScrlLbz handler = new SwScrlLbz();
        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, 0x4390, 0x668, 1, 1);
        // d0=$668-$5F0-$300=-648; ASR/SUB gives floor(-648*27/64)=-274.
        assertEquals(430, handler.getVscrollFactorBG());

        handler.init(1, 0x4390, 0x1CB);
        state.setFgAccum(0);
        handler.update(buffer, 0x4390, 0x1CB, 2, 1);
        assertEquals(0x100, handler.getVscrollFactorBG(), "the latch writes Events_fg_3 for the next dispatch");
        handler.update(buffer, 0x4390, 0x1CB, 2, 1);
        assertEquals(0x100, handler.getVscrollFactorBG(), "rebuilding the same frame must not consume the latch");
        handler.update(buffer, 0x4390, 0x1CB, 3, 1);
        assertEquals(0, handler.getVscrollFactorBG());
    }

    @Test
    void deathEggWavesReadPrecedingRomWordsAndRetainUnwrittenTail() {
        TestEnvironment.activeGameplayMode();
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);
        state.setLaunchActive(true);
        state.setBgLaunchSpeed(-1);
        GameServices.zoneRuntimeRegistry().install(state);
        byte[] waveData = new byte[320];
        for (int word = 0; word < 160; word++) {
            waveData[word * 2] = (byte) ((1000 + word) >> 8);
            waveData[word * 2 + 1] = (byte) (1000 + word);
        }
        SwScrlLbz handler = new SwScrlLbz(null, waveData);
        int[] buffer = new int[VISIBLE_LINES];
        handler.update(buffer, 0x4390, 0x668, 0, 1);
        assertEquals(1095, handler.getLbz2HScrollWordForTest(117),
                "-(a5) first reads the word BEFORE LBZ_WaterWaveArray2, even with d2=$7FFF");
        handler.update(buffer, 0x4390, 0x668, 2, 1);
        assertEquals(2190, handler.getLbz2HScrollWordForTest(117),
                "the unfilled underwater tail retains its prior value; wave phase has not advanced at tick 2");
        state.requestScreenShakeOffset(8);
        handler.update(buffer, 0x4390, 0x668, 4, 1);
        assertEquals(3286, handler.getLbz2HScrollWordForTest(117), "byte-offset phase advances at tick 4");

        Object snapshot = handler.captureRewindState();
        int[] expected = buffer.clone();
        short expectedBgY = handler.getVscrollFactorBG();
        handler.update(buffer, 0x4390, 0x668, 100, 1);
        handler.restoreRewindState(snapshot);
        handler.update(buffer, 0x4390, 0x668, 4, 1);
        assertArrayEquals(expected, buffer, "rewind rebuild retains the consumed one-shot shake and scroll words");
        state.setFinalFallActive(true);
        handler.update(buffer, 0x4390, 0x666, 101, 1);
        assertArrayEquals(expected, buffer, "LBZ2BGE_Falling retains the last deformation and cloud phase");
        assertEquals(expectedBgY, handler.getVscrollFactorBG());
        assertEquals(0x666, handler.getVscrollFactorFG(), "only the foreground camera continues to fall");
        assertEquals(3286, handler.getLbz2HScrollWordForTest(117));
    }

    private static byte[] zeroWaterlineLookup() {
        return new byte[0x1040];
    }

    private static void assertLbz2TableMatchesReference(int cameraX,
                                                        int cameraY,
                                                        int screenShakeOffset,
                                                        int frameCounter,
                                                        byte[] waterlineData) {
        SwScrlLbz handler = new SwScrlLbz(waterlineData);
        handler.setScreenShakeOffset(screenShakeOffset);
        int[] buffer = new int[VISIBLE_LINES];

        handler.update(buffer, cameraX, cameraY, frameCounter, 1);

        short[] expected = buildExpectedLbz2DeformTable(
                cameraX, cameraY, screenShakeOffset, frameCounter, waterlineData);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], handler.getLbz2HScrollWordForTest(i),
                    "HScroll_table word " + i + " must match LBZ2_Deform for cameraY $"
                            + Integer.toHexString(cameraY));
        }
    }

    private static short[] buildExpectedLbz2DeformTable(int cameraX,
                                                        int cameraY,
                                                        int screenShakeOffset,
                                                        int frameCounter,
                                                        byte[] waterlineData) {
        short[] hScroll = new short[241];
        int relativeY = (short) (cameraY - screenShakeOffset - 0x5F0);
        int d0Y = fixedFromWord(relativeY) >> 1;
        int d2 = d0Y >> 3;
        d0Y -= d2;
        d2 >>= 2;
        d0Y -= d2;
        int equilibriumDelta = (short) (wordFromFixed(d0Y) - relativeY);

        int cameraXFixed = fixedFromWord(cameraX);
        if (equilibriumDelta != 0) {
            applyExpectedWaterlineGradient(hScroll, cameraXFixed, equilibriumDelta, waterlineData);
        }
        applyExpectedUnderwaterBands(hScroll, cameraXFixed, equilibriumDelta);
        applyExpectedCloudBands(hScroll, cameraXFixed, 0);
        applyExpectedLowerBackgroundBands(hScroll, cameraXFixed, equilibriumDelta);
        applyExpectedWaterWaves(hScroll, equilibriumDelta, frameCounter);
        return hScroll;
    }

    private static void applyExpectedWaterlineGradient(short[] hScroll,
                                                       int cameraXFixed,
                                                       int equilibriumDelta,
                                                       byte[] waterlineData) {
        int d1 = cameraXFixed;
        int d3 = cameraXFixed >> 6;
        d3 -= d3 >> 3;
        if (equilibriumDelta <= -0x40) {
            int index = 0x01E / 2;
            for (int i = 0; i < 0x20; i++) {
                hScroll[index++] = wordFromFixed(d1);
                d1 -= d3;
                hScroll[index++] = wordFromFixed(d1);
                d1 -= d3;
            }
            return;
        }

        int index = 0x11E / 2;
        for (int i = 0; i < 0x20; i++) {
            hScroll[--index] = wordFromFixed(d1);
            d1 -= d3;
            hScroll[--index] = wordFromFixed(d1);
            d1 -= d3;
        }
        if (equilibriumDelta >= 0x40) {
            return;
        }

        int anchor = 0x09E / 2;
        if (equilibriumDelta > 0) {
            int dataOffset = (0x40 - equilibriumDelta) << 6;
            for (int i = 0; i < equilibriumDelta; i++) {
                hScroll[anchor + i] = hScroll[anchor + (waterlineData[dataOffset + i] & 0xFF)];
            }
            return;
        }

        int dataOffset = (equilibriumDelta + 0x40) << 6;
        for (int i = 0; i < -equilibriumDelta; i++) {
            hScroll[anchor - 1 - i] = hScroll[anchor + (waterlineData[dataOffset + i] & 0xFF)];
        }
    }

    private static void applyExpectedUnderwaterBands(short[] hScroll, int cameraXFixed, int equilibriumDelta) {
        int d1 = cameraXFixed >> 1;
        int d3 = d1 >> 3;
        int index = 0x1E2 / 2;
        hScroll[--index] = wordFromFixed(d1);
        d1 -= d3;
        hScroll[--index] = wordFromFixed(d1);
        d1 -= d3;

        for (int range : new int[] { 7, 1, 3, 1, 7 }) {
            d1 -= d3;
            short word = wordFromFixed(d1);
            for (int i = 0; i <= range; i++) {
                hScroll[--index] = word;
                hScroll[--index] = word;
                hScroll[--index] = word;
                hScroll[--index] = word;
            }
        }

        int count = 0x40 - 1;
        if (equilibriumDelta >= 0) {
            count -= equilibriumDelta;
            if (count < 0) {
                return;
            }
        }
        short word = wordFromFixed(d1);
        for (int i = 0; i <= count; i++) {
            hScroll[--index] = word;
        }
    }

    private static void applyExpectedCloudBands(short[] hScroll, int cameraXFixed, int cloudAccumulator) {
        int d1 = cameraXFixed >> 6;
        int d3 = d1;
        for (int offset : new int[] { 0x16, 0x0E, 0x0A, 0x14, 0x0C, 0x06, 0x18,
                0x10, 0x12, 0x02, 0x08, 0x04, 0x00 }) {
            d1 += cloudAccumulator;
            hScroll[offset / 2] = wordFromFixed(d1);
            d1 += d3;
        }
    }

    private static void applyExpectedLowerBackgroundBands(short[] hScroll,
                                                          int cameraXFixed,
                                                          int equilibriumDelta) {
        int d1 = cameraXFixed >> 4;
        int d3 = d1 >> 1;
        int index = 0x01A / 2;
        hScroll[index++] = wordFromFixed(d1);
        d1 += d3;
        hScroll[index++] = wordFromFixed(d1);

        int d4;
        if (equilibriumDelta < 0) {
            d4 = 0x40 - 1 + equilibriumDelta;
            if (d4 < 0) {
                return;
            }
            if (d4 >= 0x30) {
                d4 -= 0x30;
                short word = wordFromFixed(d1);
                for (int i = 0; i < 0x18; i++) {
                    hScroll[index++] = word;
                    hScroll[index++] = word;
                }
                d1 += d3;
            }
        } else {
            d4 = 0x10 - 1;
            short word = wordFromFixed(d1);
            for (int i = 0; i < 0x18; i++) {
                hScroll[index++] = word;
                hScroll[index++] = word;
            }
            d1 += d3;
        }

        short word = wordFromFixed(d1);
        for (int i = 0; i <= d4; i++) {
            hScroll[index++] = word;
        }
    }

    private static void applyExpectedWaterWaves(short[] hScroll, int equilibriumDelta, int frameCounter) {
        int count = 0x3F - equilibriumDelta;
        if (count < 0) {
            return;
        }
        count += 0x60;
        if (count >= 0xE0) {
            count = 0xE0 - 1;
        }

        int waveIndex = (frameCounter >> 1) & 0x3F;
        int tableIndex = 0x1DE / 2;
        for (int i = 0; i <= count; i++) {
            waveIndex = (waveIndex - 1) & 0x3F;
            hScroll[--tableIndex] = (short) (hScroll[tableIndex] + LBZ_WATER_WAVE_ARRAY[waveIndex]);
        }
    }

    private static int fixedFromWord(int value) {
        return ((short) value) << 16;
    }

    private static short wordFromFixed(int fixed) {
        return (short) (fixed >> 16);
    }

    private static final short[] LBZ_WATER_WAVE_ARRAY = {
            1, 1, 1, 0, 0, 0, -1, -1, -1, -1, -1, -1, 0, 0, 0, 1,
            1, 1, 1, 1, 1, 0, -1, -2, -2, -1, 0, 2, 2, 2, 2, 0,
            0, 0, -1, -1, -1, -1, -1, -1, 0, 0, 0, 1, 1, 1, 1, 1,
            1, 0, 0, 0, -1, -1, -1, -1, -1, -1, 0, 0, 0, 1, 1, 1
    };
}
