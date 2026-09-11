package com.openggf.game;

import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PersistentRespawnState;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBonusStageTransitionCoordinator {
    private final BonusStageTransitionCoordinator coordinator =
            new BonusStageTransitionCoordinator();

    @Test
    void captureEntryUsesActiveStarPostStateAndRomZeroCheckpointIndex() {
        LevelManager level = mock(LevelManager.class);
        LevelState levelState = mock(LevelState.class);
        RespawnState checkpoint = mock(RespawnState.class);
        Camera camera = mock(Camera.class);
        WaterSystem water = mock(WaterSystem.class);
        AbstractPlayableSprite playable = mock(AbstractPlayableSprite.class);
        AbstractLevelEventManager events = mock(AbstractLevelEventManager.class);

        when(level.getCurrentZone()).thenReturn(3);
        when(level.getCurrentAct()).thenReturn(1);
        when(level.getApparentAct()).thenReturn(0);
        when(level.getFeatureZoneId()).thenReturn(13);
        when(level.getFeatureActId()).thenReturn(2);
        when(level.getLevelGamestate()).thenReturn(levelState);
        when(levelState.getRings()).thenReturn(47);
        when(levelState.getTimerFrames()).thenReturn(12_345L);
        when(level.getCheckpointState()).thenReturn(checkpoint);
        when(checkpoint.isActive()).thenReturn(true);
        when(checkpoint.getSavedX()).thenReturn(0x4567);
        when(checkpoint.getSavedY()).thenReturn(0x678);
        when(checkpoint.getStarPostActivationMark()).thenReturn(9);
        when(playable.getCentreX()).thenReturn((short) 0x1111);
        when(playable.getCentreY()).thenReturn((short) 0x2222);
        when(playable.getTopSolidBit()).thenReturn((byte) 0x0C);
        when(playable.getLrbSolidBit()).thenReturn((byte) 0x0D);
        when(camera.getX()).thenReturn((short) 0x3333);
        when(camera.getY()).thenReturn((short) 0x4444);
        when(camera.getMaxY()).thenReturn((short) 0x5555);
        when(events.getEventRoutineFg()).thenReturn(6);
        when(events.getEventRoutineBg()).thenReturn(10);
        when(water.hasWater(13, 2)).thenReturn(true);
        when(water.getWaterLevelY(13, 2)).thenReturn(0x06A0);

        var capture = coordinator.captureEntry(level, camera, water, playable, events, 0x20);
        BonusStageState state = capture.savedState();

        assertEquals(0x0301, state.savedZoneAndAct());
        assertEquals(0x0300, state.savedApparentZoneAndAct());
        assertEquals(47, state.savedRingCount());
        assertEquals(0, state.savedLastStarPostHit());
        assertEquals(0x20, state.savedStatusSecondary());
        assertEquals(6, state.dynamicResizeRoutineFg());
        assertEquals(10, state.dynamicResizeRoutineBg());
        assertEquals(0x4567, state.playerX());
        assertEquals(0x678, state.playerY());
        assertEquals(0x3333, state.cameraX());
        assertEquals(0x4444, state.cameraY());
        assertEquals((byte) 0x0C, state.topSolidBit());
        assertEquals((byte) 0x0D, state.lrbSolidBit());
        assertEquals(0x5555, state.cameraMaxY());
        assertEquals(12_345L, state.savedTimerFrames());
        assertEquals(0x06A0, state.meanWaterLevel());
        assertEquals(9, capture.pendingStarPostActivationMark());
    }

    @Test
    void captureEntryWithoutCheckpointUsesLivePlayerPositionAndNoActivationMark() {
        LevelManager level = mock(LevelManager.class);
        Camera camera = mock(Camera.class);
        AbstractPlayableSprite playable = mock(AbstractPlayableSprite.class);
        when(playable.getCentreX()).thenReturn((short) 123);
        when(playable.getCentreY()).thenReturn((short) 456);

        var capture = coordinator.captureEntry(level, camera, null, playable, null, 0);

        assertEquals(123, capture.savedState().playerX());
        assertEquals(456, capture.savedState().playerY());
        assertEquals(-1, capture.pendingStarPostActivationMark());
    }

    @Test
    void restoreReturnStatePreservesLiveInteriorRingsCheckpointMarkAndRuntimeState() {
        LevelManager level = mock(LevelManager.class);
        LevelState levelState = mock(LevelState.class);
        RespawnState checkpoint = mock(RespawnState.class);
        Camera camera = mock(Camera.class);
        WaterSystem water = mock(WaterSystem.class);
        AbstractPlayableSprite playable = mock(AbstractPlayableSprite.class);
        AbstractLevelEventManager events = mock(AbstractLevelEventManager.class);
        when(level.getCheckpointState()).thenReturn(checkpoint);
        when(level.getLevelGamestate()).thenReturn(levelState);
        when(level.getFeatureZoneId()).thenReturn(1);
        when(level.getFeatureActId()).thenReturn(0);
        when(water.hasWater(1, 0)).thenReturn(true);
        AtomicInteger lives = new AtomicInteger();
        BonusStageState state = state(0x3456, 0x789, 8, 12, 0x111, 0x222,
                (byte) 0x0C, (byte) 0x0D, 0x333, 9_876L, 0x6A0);
        var rewards = new BonusStageProvider.BonusStageRewards(999, 2,
                false, false, false, false);

        coordinator.restoreReturnState(level, camera, water, playable, events,
                state, 7, 69, ShieldType.LIGHTNING, rewards, lives::incrementAndGet);

        verify(checkpoint).restoreFromSaved(0x3456, 0x789, 0x111, 0x222, 0);
        verify(checkpoint).restoreStarPostActivationMark(7);
        verify(events).restoreEventRoutineState(8, 12);
        verify(playable).setCentreX((short) 0x3456);
        verify(playable).setCentreY((short) 0x789);
        verify(playable).setTopSolidBit((byte) 0x0C);
        verify(playable).setLrbSolidBit((byte) 0x0D);
        verify(playable).setXSpeed((short) 0);
        verify(playable).setYSpeed((short) 0);
        verify(playable).setGSpeed((short) 0);
        verify(playable).giveShield(ShieldType.LIGHTNING);
        verify(playable).setHighPriority(false);
        verify(playable).setPriorityBucket(2);
        verify(camera).setX((short) 0x111);
        verify(camera).setY((short) 0x222);
        verify(camera).setMaxY((short) 0x333);
        verify(camera).updatePosition(true);
        verify(water).setWaterLevelDirect(1, 0, 0x6A0);
        verify(water).setWaterLevelTarget(1, 0, 0x6A0);
        verify(levelState).setRings(69);
        verify(levelState).setTimerFrames(9_876L);
        verify(levelState).resumeTimer();
        assertEquals(2, lives.get());
    }

    @Test
    void prepareReturnLoadQueuesRespawnTableBeforeFreshManagerReset() {
        LevelManager level = mock(LevelManager.class);
        ObjectManager entryObjects = mock(ObjectManager.class);
        PersistentRespawnState respawnState = new PersistentRespawnState(
                new long[]{0x12L}, new long[]{0x04L}, new long[]{0x20L});
        when(level.getObjectManager()).thenReturn(entryObjects);
        when(entryObjects.capturePersistentRespawn()).thenReturn(respawnState);

        coordinator.captureEntry(level, mock(Camera.class), null, null, null, 0);
        coordinator.prepareReturnLoad(level);

        verify(level).restorePersistentRespawnOnNextObjectReset(respawnState);
    }

    @Test
    void captureInteriorExitRingCountFallsBackToSavedEntryRingsWithoutLiveGameState() {
        LevelManager level = mock(LevelManager.class);
        BonusStageState saved = state(0, 0, 0, 0, 0, 0,
                (byte) 0, (byte) 0, 0, 0, 0);

        assertEquals(47, coordinator.captureInteriorExitRingCount(level,
                new BonusStageState(saved.savedZoneAndAct(), saved.savedApparentZoneAndAct(),
                        47, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        (byte) 0, (byte) 0, 0, 0, 0)));
    }

    @Test
    void captureInteriorExitRingCountUsesLiveInteriorHudTotal() {
        LevelManager level = mock(LevelManager.class);
        LevelState levelState = mock(LevelState.class);
        when(level.getLevelGamestate()).thenReturn(levelState);
        when(levelState.getRings()).thenReturn(69);

        assertEquals(69, coordinator.captureInteriorExitRingCount(level,
                new BonusStageState(0, 0, 47, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, (byte) 0, (byte) 0, 0, 0, 0)));
    }

    @Test
    void captureInteriorExitRingCountReturnsZeroWithoutAnyState() {
        assertEquals(0, coordinator.captureInteriorExitRingCount(mock(LevelManager.class), null));
    }

    private static BonusStageState state(int playerX, int playerY, int fg, int bg,
                                         int cameraX, int cameraY, byte top, byte lrb,
                                         int cameraMaxY, long timer, int water) {
        return new BonusStageState(0x0301, 0x0300, 11, 0, 0, 0,
                fg, bg, playerX, playerY, cameraX, cameraY, top, lrb,
                cameraMaxY, timer, water);
    }
}
