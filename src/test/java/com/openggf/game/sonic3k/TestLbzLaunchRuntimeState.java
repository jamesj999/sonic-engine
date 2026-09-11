package com.openggf.game.sonic3k;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.*;

class TestLbzLaunchRuntimeState {

    @Test
    void seamlessActReloadRetainsLevelLoopOscillationTail() {
        LbzZoneRuntimeState state =
                new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE);

        assertTrue(state.advancesOscillationOnSeamlessTransition(),
                "LBZ1BGE_DoTransition returns to LevelLoop's OscillateNumDo tail");
    }

    @Test
    void legacyThreeByteRestoreStillRestoresRollingDrumAngles() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(0, PlayerCharacter.SONIC_ALONE);
        state.setActiveInteriorLayoutMod(4);
        state.setRollingDrumAngle(0, 0);
        state.setRollingDrumAngle(1, 0);
        dirtyLaunchState(state);

        state.restoreBytes(new byte[]{1, (byte) 0x83, (byte) 0xFE});

        assertTrue(state.isAlarmAnimationActive());
        assertEquals(4, state.getActiveInteriorLayoutMod(),
                "3-byte snapshots must keep the historical drum-only field layout");
        assertEquals(0x83, state.getRollingDrumAngle(0));
        assertEquals(0xFE, state.getRollingDrumAngle(1));
        assertLaunchStateCleared(state);
    }

    @Test
    void legacyTwelveByteRestoreStillRestoresExistingFields() {
        ByteBuffer bytes = ByteBuffer.allocate(12);
        bytes.put((byte) 1);
        bytes.put((byte) 2);
        bytes.put((byte) 1);
        bytes.put((byte) 0x44);
        bytes.put((byte) 0x55);
        bytes.putInt(-7);
        bytes.put((byte) 1);
        bytes.put((byte) 1);
        bytes.put((byte) 1);

        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.KNUCKLES);
        dirtyLaunchState(state);
        state.restoreBytes(bytes.array());

        assertTrue(state.isAlarmAnimationActive());
        assertEquals(2, state.getActiveInteriorLayoutMod());
        assertTrue(state.isInteriorLayoutMod3Disabled());
        assertEquals(0x44, state.getRollingDrumAngle(0));
        assertEquals(0x55, state.getRollingDrumAngle(1));
        assertEquals(-7, state.consumeScreenShakeOffset());
        assertTrue(state.isRobotnikIntroActive());
        assertTrue(state.isLbz2LayoutAdjustApplied());
        assertTrue(state.isLbz2EntryCorridorApplied());
        assertLaunchStateCleared(state);
    }

    @Test
    void launchFieldsRoundTripThroughRewindBytes() {
        LbzZoneRuntimeState original = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_AND_TAILS);
        original.setLbz1KnucklesCutsceneControlLocked(true);
        original.setLaunchActive(true);
        original.setDeathEggRumble(true);
        original.setFgLaunchSpeed(0x1E00);
        original.setBgLaunchSpeed(-0x18000);
        original.setFgAccum(0x0012_3456);
        original.setBgAccum(0x00AB_CDEF);
        original.setLaunchYDelta(-3);
        original.setPreLaunchDelay(0x3C);
        original.setDetachScroll(0x27);
        original.setDetachWindowCopyTimer(0x1B);
        original.setWaterTargetYForTest(0x0F80);
        original.setPadCollapseActive(true);
        original.setFinalFallActive(true);
        original.setDeathEggTerrainSwapQueued(true);
        original.setDeathEggTerrainSwapApplied(true);
        original.setDeathEggDeformWrapLatch(0x0200);
        original.publishLbz2DeformOutputs(0xFFFC, 0x1234, 0x1200);
        original.setLbz2RideAnimatedTileGateActive(true);
        original.requestLaunchStart();
        original.requestPadCollapseStart();
        original.requestFinalFall();
        original.registerLaunchRiderAnchor(0x1234_5678);
        original.registerFinaleCutsceneAnchor(0x3344_5566);

        LbzZoneRuntimeState restored = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_AND_TAILS);
        restored.restoreBytes(original.captureBytes());

        assertTrue(restored.isLbz1KnucklesCutsceneControlLocked(),
                "LBZ1's _unkFAA9 cutscene input gate must rewind with the zone runtime state.");
        assertTrue(restored.isLaunchActive());
        assertTrue(restored.isDeathEggRumble());
        assertEquals(0x1E00, restored.getFgLaunchSpeed());
        assertEquals(-0x18000, restored.getBgLaunchSpeed());
        assertEquals(0x0012_3456, restored.getFgAccum());
        assertEquals(0x00AB_CDEF, restored.getBgAccum());
        assertEquals(-3, restored.getLaunchYDelta());
        assertEquals(0x3C, restored.getPreLaunchDelay());
        assertEquals(0x27, restored.getDetachScroll());
        assertEquals(0x1B, restored.getDetachWindowCopyTimer());
        assertEquals(0x0F80, restored.getWaterTargetY());
        assertTrue(restored.isPadCollapseActive());
        assertTrue(restored.isFinalFallActive());
        assertTrue(restored.isDeathEggTerrainSwapQueued());
        assertTrue(restored.isDeathEggTerrainSwapApplied());
        assertEquals(0x0200, restored.getDeathEggDeformWrapLatch());
        assertEquals(0xFFFC, restored.lbz2WaterlinePhase());
        assertEquals(0x1234, restored.lbz2ScrollArtPhaseSource());
        assertEquals(0x1200, restored.publishedBgCameraX());
        assertEquals(0x04, restored.lbz2ScrollArtPhase());
        assertTrue(restored.isLbz2RideAnimatedTileGateActive());
        assertEquals(OptionalInt.of(0x1234_5678), restored.getLaunchRiderAnchorId());
        assertEquals(OptionalInt.of(0x3344_5566), restored.getFinaleCutsceneAnchorId());
        assertTrue(restored.consumeLaunchStartRequested());
        assertTrue(restored.consumePadCollapseStartRequested());
        assertTrue(restored.consumeFinalFallRequested());
    }

    @Test
    void launchSignalConsumersAreOneShot() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);

        assertFalse(state.consumeLaunchStartRequested());
        assertFalse(state.consumePadCollapseStartRequested());
        assertFalse(state.consumeFinalFallRequested());

        state.requestLaunchStart();
        state.requestPadCollapseStart();
        state.requestFinalFall();

        assertTrue(state.consumeLaunchStartRequested());
        assertFalse(state.consumeLaunchStartRequested());
        assertTrue(state.consumePadCollapseStartRequested());
        assertFalse(state.consumePadCollapseStartRequested());
        assertTrue(state.consumeFinalFallRequested());
        assertFalse(state.consumeFinalFallRequested());
    }

    @Test
    void lbz2RideAnimatedTileGatePersistsUntilExplicitlyCleared() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);

        assertFalse(state.isLbz2RideAnimatedTileGateActive());

        state.setLbz2RideAnimatedTileGateActive(true);

        assertTrue(state.isLbz2RideAnimatedTileGateActive());
        assertTrue(state.isLbz2RideAnimatedTileGateActive(),
                "ROM Anim_Counters+$F is a latched flag, not a one-frame consumer");

        state.setLbz2RideAnimatedTileGateActive(false);

        assertFalse(state.isLbz2RideAnimatedTileGateActive());
    }

    @Test
    void launchRiderAnchorCanBeClearedWithoutObjectReferences() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);

        assertTrue(state.getLaunchRiderAnchorId().isEmpty());
        state.registerLaunchRiderAnchor(42);
        assertEquals(OptionalInt.of(42), state.getLaunchRiderAnchorId());

        state.clearLaunchRiderAnchor();

        assertTrue(state.getLaunchRiderAnchorId().isEmpty());
    }

    @Test
    void finaleCutsceneAnchorCanBeClearedWithoutObjectReferences() {
        LbzZoneRuntimeState state = new LbzZoneRuntimeState(1, PlayerCharacter.SONIC_ALONE);

        assertTrue(state.getFinaleCutsceneAnchorId().isEmpty());
        state.registerFinaleCutsceneAnchor(84);
        assertEquals(OptionalInt.of(84), state.getFinaleCutsceneAnchorId());

        state.clearFinaleCutsceneAnchor();

        assertTrue(state.getFinaleCutsceneAnchorId().isEmpty());
    }

    private static void dirtyLaunchState(LbzZoneRuntimeState state) {
        state.setLaunchActive(true);
        state.setDeathEggRumble(true);
        state.setFgLaunchSpeed(0x1E00);
        state.setBgLaunchSpeed(-0x18000);
        state.setFgAccum(0x0012_3456);
        state.setBgAccum(0x00AB_CDEF);
        state.setLaunchYDelta(-3);
        state.setPreLaunchDelay(0x3C);
        state.setDetachScroll(0x27);
        state.setDetachWindowCopyTimer(0x1B);
        state.setWaterTargetYForTest(0x0F80);
        state.setPadCollapseActive(true);
        state.setFinalFallActive(true);
        state.setDeathEggTerrainSwapQueued(true);
        state.setDeathEggTerrainSwapApplied(true);
        state.setDeathEggDeformWrapLatch(0x0200);
        state.publishLbz2DeformOutputs(0xFFFC, 0x1234, 0x1200);
        state.setLbz2RideAnimatedTileGateActive(true);
        state.requestLaunchStart();
        state.requestPadCollapseStart();
        state.requestFinalFall();
        state.registerLaunchRiderAnchor(0x1234_5678);
        state.registerFinaleCutsceneAnchor(0x3344_5566);
    }

    private static void assertLaunchStateCleared(LbzZoneRuntimeState state) {
        assertFalse(state.isLaunchActive());
        assertFalse(state.isDeathEggRumble());
        assertEquals(0, state.getFgLaunchSpeed());
        assertEquals(0, state.getBgLaunchSpeed());
        assertEquals(0, state.getFgAccum());
        assertEquals(0, state.getBgAccum());
        assertEquals(0, state.getLaunchYDelta());
        assertEquals(0, state.getPreLaunchDelay());
        assertEquals(0, state.getDetachScroll());
        assertEquals(0, state.getDetachWindowCopyTimer());
        assertEquals(0, state.getWaterTargetY());
        assertFalse(state.isPadCollapseActive());
        assertFalse(state.isFinalFallActive());
        assertFalse(state.isDeathEggTerrainSwapQueued());
        assertFalse(state.isDeathEggTerrainSwapApplied());
        assertEquals(0, state.getDeathEggDeformWrapLatch());
        assertEquals(0, state.lbz2WaterlinePhase());
        assertEquals(0, state.lbz2ScrollArtPhaseSource());
        assertEquals(0, state.publishedBgCameraX());
        assertFalse(state.isLbz2RideAnimatedTileGateActive());
        assertFalse(state.consumeLaunchStartRequested());
        assertFalse(state.consumePadCollapseStartRequested());
        assertFalse(state.consumeFinalFallRequested());
        assertTrue(state.getLaunchRiderAnchorId().isEmpty());
        assertTrue(state.getFinaleCutsceneAnchorId().isEmpty());
    }
}
