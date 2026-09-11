package com.openggf.game.sonic2.scroll;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.runtime.WfzRuntimeState;
import com.openggf.level.scroll.BgTilemapUpdateMode;
import com.openggf.level.scroll.M68KMath;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSwScrlWfz {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void bgCacheCameraUsesLiveWfzRuntimeX() throws IOException {
        BackgroundCamera fallback = new BackgroundCamera();
        fallback.setBgXPos(0x111);
        SwScrlWfz handler = new SwScrlWfz(
                new ParallaxTables(TestEnvironment.currentRom()), fallback);
        GameServices.zoneRuntimeRegistry().install(new TestWfzState(0x222, 0x3456));

        assertEquals(0x3456, handler.getBgCameraX());
    }

    @Test
    void bgCacheCameraFallsBackOutsideGameplayRuntime() throws IOException {
        BackgroundCamera fallback = new BackgroundCamera();
        fallback.setBgXPos(0x456);
        SwScrlWfz handler = new SwScrlWfz(
                new ParallaxTables(TestEnvironment.currentRom()), fallback);
        SessionManager.clear();

        assertEquals(0x456, handler.getBgCameraX());
    }

    @Test
    void wfzUsesPersistentHardwareSizedPlaneBResidency() throws IOException {
        SwScrlWfz handler = new SwScrlWfz(
                new ParallaxTables(TestEnvironment.currentRom()), new BackgroundCamera());
        GameServices.zoneRuntimeRegistry().install(new TestWfzState(0x58C, 0x2AE3));

        handler.update(new int[M68KMath.VISIBLE_LINES], 0x2C00, 0, 0x35FF, 0);

        assertEquals(BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32,
                handler.getBgTilemapUpdateMode());
        assertEquals(512, handler.getBgPeriodWidth());
    }

    @Test
    void wfzScrollTablesAreLoadedFromRomOffsets() throws IOException {
        Rom rom = TestEnvironment.currentRom();
        ParallaxTables tables = new ParallaxTables(rom);

        assertArrayEquals(
                rom.readBytes(ParallaxTables.SWSCRL_WFZ_NORMAL_ADDR, ParallaxTables.SWSCRL_WFZ_NORMAL_SIZE),
                tables.getWfzNormalArray(),
                "WFZ normal scroll array should be the shipped ROM bytes");
        assertArrayEquals(
                rom.readBytes(ParallaxTables.SWSCRL_WFZ_TRANS_ADDR, ParallaxTables.SWSCRL_WFZ_TRANS_SIZE),
                tables.getWfzTransArray(),
                "WFZ transition scroll array should be the shipped ROM bytes");
    }

    @Test
    void wfzNormalTableCoversEveryReachableVisibleSpan() throws IOException {
        ParallaxTables tables = new ParallaxTables(TestEnvironment.currentRom());
        byte[] normal = tables.getWfzNormalArray();

        assertEquals(0x980, sumLineCounts(normal),
                "The ROM WFZ normal table covers more than one $800 BG-Y wrap");
        for (int bgY = 0; bgY <= 0x7FF; bgY++) {
            assertFalse(exhaustsVisibleSpan(normal, bgY),
                    "WFZ normal table should not exhaust for BG Y $" + Integer.toHexString(bgY));
        }
    }

    @Test
    void wfzTransitionTableCoversEveryReachableVisibleSpan() throws IOException {
        ParallaxTables tables = new ParallaxTables(TestEnvironment.currentRom());
        byte[] transition = tables.getWfzTransArray();

        assertEquals(0xA00, sumLineCounts(transition),
                "The ROM WFZ transition table includes the extra ship-layer span");
        for (int bgY = 0; bgY <= 0x7FF; bgY++) {
            assertFalse(exhaustsVisibleSpan(transition, bgY),
                    "WFZ transition table should not exhaust for BG Y $" + Integer.toHexString(bgY));
        }
    }

    @Test
    void wfzSwitchesToTransitionArrayAtShipThreshold() throws IOException {
        ParallaxTables tables = new ParallaxTables(TestEnvironment.currentRom());
        BackgroundCamera bgCamera = new BackgroundCamera();
        SwScrlWfz handler = new SwScrlWfz(tables, bgCamera);
        int[] beforeThreshold = new int[M68KMath.VISIBLE_LINES];
        int[] atThreshold = new int[M68KMath.VISIBLE_LINES];

        bgCamera.setBgXPos(0x120);
        bgCamera.setBgYPos(0x5A0);
        handler.update(beforeThreshold, 0x26FF, 0, 0, 0);

        handler = new SwScrlWfz(tables, bgCamera);
        bgCamera.setBgXPos(0x120);
        bgCamera.setBgYPos(0x5A0);
        handler.update(atThreshold, 0x2700, 0, 0, 0);

        assertTrue(hasDifferentBgScroll(beforeThreshold, atThreshold),
                "Camera X $2700 should select the transition table, changing BG layer layout");
    }

    private static int sumLineCounts(byte[] segments) {
        int total = 0;
        for (int i = 0; i < segments.length; i += 2) {
            total += segments[i] & 0xFF;
        }
        return total;
    }

    private static boolean exhaustsVisibleSpan(byte[] segments, int bgY) {
        int arrayPos = 0;
        int remainingBgY = bgY & 0x7FF;
        while (arrayPos + 1 < segments.length) {
            remainingBgY -= segments[arrayPos] & 0xFF;
            if (remainingBgY < 0) {
                break;
            }
            arrayPos += 2;
        }

        if (arrayPos + 1 >= segments.length) {
            return true;
        }

        int linesInCurrentSegment = -remainingBgY;
        arrayPos += 2;
        for (int line = 0; line < M68KMath.VISIBLE_LINES; line++) {
            linesInCurrentSegment--;
            if (linesInCurrentSegment == 0) {
                if (arrayPos + 1 >= segments.length) {
                    return line < M68KMath.VISIBLE_LINES - 1;
                }
                linesInCurrentSegment = segments[arrayPos] & 0xFF;
                arrayPos += 2;
            }
        }
        return false;
    }

    private static boolean hasDifferentBgScroll(int[] left, int[] right) {
        for (int i = 0; i < left.length; i++) {
            if ((short) left[i] != (short) right[i]) {
                return true;
            }
        }
        return false;
    }

    private record TestWfzState(
            int bgVscrollFactor,
            int bgXPos) implements WfzRuntimeState {

        @Override
        public int zoneIndex() {
            return 9;
        }

        @Override
        public int actIndex() {
            return 0;
        }
    }
}
