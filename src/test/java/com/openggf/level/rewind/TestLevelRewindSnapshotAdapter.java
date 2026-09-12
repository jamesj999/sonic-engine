package com.openggf.level.rewind;

import com.openggf.game.LevelGamestate;
import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.mutation.DirectLevelMutationSurface;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.LevelManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLevelRewindSnapshotAdapter {
    @Test
    void captureBuildsLevelSnapshotFromLevelManagerState() {
        StubLevel level = new StubLevel();
        LevelGamestate levelState = new LevelGamestate();
        levelState.setRings(31);
        levelState.setRingExtraLifeFlags(0x02);
        levelState.setTimerFrames(456);
        levelState.pauseTimer();
        LevelManager manager = mock(LevelManager.class);
        when(manager.getCurrentLevel()).thenReturn(level);
        when(manager.getLevelGamestate()).thenReturn(levelState);
        when(manager.getFrameCounter()).thenReturn(77);
        when(manager.isRespawnRequestedForRewind()).thenReturn(true);
        when(manager.isTransitionRingInitializationPendingForRewind()).thenReturn(true);
        when(manager.capturePendingInitialProcessSpritesLifecycleForRewind())
                .thenReturn(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        LevelSnapshot snapshot = LevelRewindSnapshotAdapter.create(manager).capture();

        assertEquals("level", LevelRewindSnapshotAdapter.create(manager).key());
        assertSame(level.blocksReference(), snapshot.blocks());
        assertSame(level.chunksReference(), snapshot.chunks());
        assertSame(level.getMap().getData(), snapshot.mapData());
        assertEquals(77, snapshot.frameCounter());
        assertEquals(31, snapshot.levelRings());
        assertEquals(0x02, snapshot.levelRingExtraLifeFlags());
        assertEquals(456, snapshot.levelTimerFrames());
        assertTrue(snapshot.levelTimerPaused());
        assertTrue(snapshot.respawnRequested());
        assertTrue(snapshot.transitionRingInitializationPending());
        assertEquals(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE,
                snapshot.pendingInitialProcessSpritesLifecycle());
    }

    @Test
    void pausedBeforeCaptureRoundTripsPendingInitialSetupLifecycle() {
        StubLevel level = new StubLevel();
        LevelGamestate levelState = new LevelGamestate();
        levelState.pauseTimer();
        LevelManager manager = mock(LevelManager.class);
        when(manager.getCurrentLevel()).thenReturn(level);
        when(manager.getLevelGamestate()).thenReturn(levelState);
        when(manager.capturePendingInitialProcessSpritesLifecycleForRewind())
                .thenReturn(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        RewindSnapshottable<LevelSnapshot> adapter = LevelRewindSnapshotAdapter.create(manager);

        LevelSnapshot snapshot = adapter.capture();
        levelState.resumeTimer();
        adapter.restore(snapshot);

        assertTrue(levelState.isTimerPaused());
        verify(manager).restorePendingInitialProcessSpritesLifecycleForRewind(
                InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
    }

    @Test
    void restoreAppliesSnapshotThroughLevelManagerStateHooks() {
        StubLevel level = new StubLevel();
        LevelGamestate levelState = new LevelGamestate();
        LevelManager manager = mock(LevelManager.class);
        when(manager.getCurrentLevel()).thenReturn(level);
        when(manager.getLevelGamestate()).thenReturn(levelState);
        Block[] snapshotBlocks = new Block[] {new Block()};
        Chunk[] snapshotChunks = new Chunk[] {new Chunk()};
        byte[] snapshotMap = new byte[] {0x11, 0x22};
        LevelSnapshot snapshot = new LevelSnapshot(
                3,
                snapshotBlocks,
                snapshotChunks,
                snapshotMap,
                88,
                true,
                9,
                0x06,
                123,
                false,
                true,
                null,
                true,
                InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        RewindSnapshottable<LevelSnapshot> adapter = LevelRewindSnapshotAdapter.create(manager);
        adapter.restore(snapshot);

        assertSame(snapshotBlocks, level.blocksReference());
        assertSame(snapshotChunks, level.chunksReference());
        assertSame(snapshotMap, level.getMap().getData());
        assertEquals(9, levelState.getRings());
        assertEquals(0x06, levelState.getRingExtraLifeFlags());
        assertEquals(123, levelState.getTimerFrames());
        assertTrue(!levelState.isTimerPaused());
        verify(manager).discardPreparedLevelLoad();
        verify(manager).setFrameCounter(88);
        verify(manager).invalidateAllTilemaps();
        verify(manager).restoreRespawnRequestedForRewind(true);
        verify(manager).restoreCheckpointStateForRewind(null);
        verify(manager).restoreTransitionRingInitializationPendingForRewind(true);
        verify(manager).restorePendingInitialProcessSpritesLifecycleForRewind(
                InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
    }

    @Test
    void restoreSkipsTilemapInvalidationWhenGeometryReferencesAreAlreadyCurrent() {
        StubLevel level = new StubLevel();
        LevelGamestate levelState = new LevelGamestate();
        LevelManager manager = mock(LevelManager.class);
        when(manager.getCurrentLevel()).thenReturn(level);
        when(manager.getLevelGamestate()).thenReturn(levelState);
        LevelSnapshot snapshot = new LevelSnapshot(
                level.currentEpoch(),
                level.blocksReference(),
                level.chunksReference(),
                level.getMap().getData(),
                44,
                true,
                5,
                0,
                67,
                true,
                false,
                null,
                InitialProcessSpritesLifecycle.NONE);

        LevelRewindSnapshotAdapter.create(manager).restore(snapshot);

        assertSame(snapshot.blocks(), level.blocksReference());
        assertSame(snapshot.chunks(), level.chunksReference());
        assertSame(snapshot.mapData(), level.getMap().getData());
        assertEquals(5, levelState.getRings());
        assertEquals(67, levelState.getTimerFrames());
        assertTrue(levelState.isTimerPaused());
        verify(manager, never()).invalidateAllTilemaps();
        verify(manager).setFrameCounter(44);
        verify(manager).restoreRespawnRequestedForRewind(false);
        verify(manager).restoreCheckpointStateForRewind(null);
        verify(manager).restoreTransitionRingInitializationPendingForRewind(false);
        verify(manager).restorePendingInitialProcessSpritesLifecycleForRewind(
                InitialProcessSpritesLifecycle.NONE);
    }

    @Test
    void captureIsolatesMapDataFromLaterProductionMutations() {
        StubLevel level = new StubLevel();
        level.getMap().setValue(0, 0, 0, (byte) 0x11);
        LevelManager manager = mock(LevelManager.class);
        when(manager.getCurrentLevel()).thenReturn(level);

        LevelSnapshot snapshot = LevelRewindSnapshotAdapter.create(manager).capture();
        byte[] snapshotMap = snapshot.mapData();

        new DirectLevelMutationSurface(level).setBlockInMapWithoutRedraw(0, 0, 0, 0x99);

        assertNotSame(snapshotMap, level.getMap().getData());
        assertEquals((byte) 0x11, snapshotMap[0]);
        assertEquals((byte) 0x99, level.getMap().getValue(0, 0, 0));
    }

    static class StubLevel extends AbstractLevel {
        StubLevel() {
            super(0);
            this.palettes = new Palette[PALETTE_COUNT];
            this.patterns = new Pattern[16];
            this.chunks = new Chunk[16];
            this.blocks = new Block[16];
            for (int i = 0; i < 16; i++) {
                this.chunks[i] = new Chunk();
                this.blocks[i] = new Block();
            }
            this.solidTiles = new SolidTile[16];
            this.map = new Map(2, 256, 256);
            this.objects = new ArrayList<>();
            this.rings = new ArrayList<>();
            this.patternCount = 16;
            this.chunkCount = 16;
            this.blockCount = 16;
            this.solidTileCount = 16;
            this.minX = 0;
            this.maxX = 1024;
            this.minY = 0;
            this.maxY = 1024;
        }
    }
}
