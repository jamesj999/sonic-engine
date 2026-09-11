package com.openggf.level;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.LevelSnapshot;
import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.LevelGamestate;
import com.openggf.game.mutation.LevelMutationSurface;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for LevelManager's rewind snapshot adapter.
 *
 * Note: These tests use a minimal stub level without full ROM loading.
 * Full integration tests would require a HeadlessTestRunner or similar fixture.
 */
class TestLevelManagerRewindSnapshot {

    @Test
    void pendingInitialSetupLifecycleRoundTripsAndDispatchesExactlyOnce() {
        InitialProcessSpritesLifecycleCoordinator coordinator = new InitialProcessSpritesLifecycleCoordinator();
        coordinator.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        InitialProcessSpritesLifecycle captured = coordinator.captureForRewind();
        coordinator.discard();
        coordinator.restoreForRewind(captured);

        int[] dispatches = {0};
        assertTrue(coordinator.consume(() -> dispatches[0]++));
        assertFalse(coordinator.consume(() -> dispatches[0]++));
        assertEquals(1, dispatches[0]);
    }

    @Test
    void consumedInitialSetupLifecycleRoundTripsWithoutDispatchAuthority() {
        InitialProcessSpritesLifecycleCoordinator coordinator = new InitialProcessSpritesLifecycleCoordinator();
        coordinator.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        coordinator.consume(() -> { });

        InitialProcessSpritesLifecycle captured = coordinator.captureForRewind();
        coordinator.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        coordinator.restoreForRewind(captured);

        assertFalse(coordinator.consume(() -> fail("consumed lifecycle must not dispatch")));
    }

    @Test
    void exceptionAfterConsumeRestoresConsumedStateWithoutReplay() {
        InitialProcessSpritesLifecycleCoordinator coordinator = new InitialProcessSpritesLifecycleCoordinator();
        coordinator.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        assertThrows(IllegalStateException.class,
                () -> coordinator.consume(() -> {
                    throw new IllegalStateException("setup boom");
                }));

        InitialProcessSpritesLifecycle captured = coordinator.captureForRewind();
        coordinator.publish(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        coordinator.restoreForRewind(captured);

        assertEquals(InitialProcessSpritesLifecycle.NONE, captured);
        assertFalse(coordinator.consume(() -> fail("exception-consumed lifecycle must not replay")));
    }

    @Test
    void levelManagerFacadeRoundTripsTypedInitialSetupLifecycle() {
        com.openggf.game.session.EngineContext services =
                mock(com.openggf.game.session.EngineContext.class);
        org.mockito.Mockito.when(services.graphics())
                .thenReturn(mock(com.openggf.graphics.GraphicsManager.class));
        org.mockito.Mockito.when(services.audio())
                .thenReturn(mock(com.openggf.audio.AudioManager.class));
        org.mockito.Mockito.when(services.configuration())
                .thenReturn(mock(com.openggf.configuration.SonicConfigurationService.class));
        org.mockito.Mockito.when(services.debugOverlay())
                .thenReturn(mock(com.openggf.debug.DebugOverlayManager.class));
        org.mockito.Mockito.when(services.profiler())
                .thenReturn(mock(com.openggf.debug.PerformanceProfiler.class));
        org.mockito.Mockito.when(services.crossGameFeatures())
                .thenReturn(mock(com.openggf.game.CrossGameFeatureProvider.class));
        LevelManager manager = new LevelManager(
                mock(com.openggf.camera.Camera.class),
                mock(com.openggf.sprites.managers.SpriteManager.class),
                mock(com.openggf.level.ParallaxManager.class),
                mock(com.openggf.physics.CollisionSystem.class),
                mock(com.openggf.level.WaterSystem.class),
                mock(com.openggf.game.GameStateManager.class),
                services,
                mock(com.openggf.game.session.WorldSession.class));

        manager.restorePendingInitialProcessSpritesLifecycleForRewind(
                InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);

        assertEquals(InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE,
                manager.capturePendingInitialProcessSpritesLifecycleForRewind());
        manager.restorePendingInitialProcessSpritesLifecycleForRewind(InitialProcessSpritesLifecycle.NONE);
        assertEquals(InitialProcessSpritesLifecycle.NONE,
                manager.capturePendingInitialProcessSpritesLifecycleForRewind());
    }

    /**
     * Minimal concrete stub for testing level snapshots without full load.
     */
    static class StubLevel extends AbstractLevel {
        StubLevel() {
            super(0);
            // Initialize required fields to non-null values
            this.palettes = new Palette[PALETTE_COUNT];
            this.patterns = new Pattern[256];
            this.chunks = new Chunk[256];
            this.blocks = new Block[256];
            for (int i = 0; i < 256; i++) {
                this.chunks[i] = new Chunk();
                this.blocks[i] = new Block();
            }
            this.solidTiles = new SolidTile[256];
            this.map = new Map(2, 256, 256);
            this.objects = new ArrayList<>();
            this.rings = new ArrayList<>();
            this.patternCount = 256;
            this.chunkCount = 256;
            this.blockCount = 256;
            this.solidTileCount = 256;
            this.minX = 0;
            this.maxX = 1024;
            this.minY = 0;
            this.maxY = 1024;
        }
    }

    /**
     * Minimal stub LevelManager that wraps a level for testing.
     */
    static class StubLevelManager {
        private final Level level;
        private final LevelGamestate levelGamestate = new LevelGamestate();

        StubLevelManager(Level level) {
            this.level = level;
        }

        public Level getCurrentLevel() {
            return level;
        }

        /**
         * Returns a rewind snapshottable adapter (copied from LevelManager).
         */
        public RewindSnapshottable<LevelSnapshot> levelRewindSnapshottable() {
            return new RewindSnapshottable<LevelSnapshot>() {
                @Override
                public String key() {
                    return "level";
                }

                @Override
                public LevelSnapshot capture() {
                    Level currentLevel = getCurrentLevel();
                    if (!(currentLevel instanceof AbstractLevel)) {
                        throw new IllegalStateException("Current level is not an AbstractLevel: " + currentLevel.getClass().getName());
                    }
                    AbstractLevel level = (AbstractLevel) currentLevel;

                    return new LevelSnapshot(
                            level.currentEpoch(),
                            level.blocksReference(),
                            level.chunksReference(),
                            level.getMap().getData(),
                            0,
                            true,
                            levelGamestate.getRings(),
                            levelGamestate.getTimerFrames(),
                            levelGamestate.isTimerPaused(),
                            false,
                            null,
                            InitialProcessSpritesLifecycle.NONE
                    );
                }

                @Override
                public void restore(LevelSnapshot s) {
                    Level currentLevel = getCurrentLevel();
                    if (!(currentLevel instanceof AbstractLevel)) {
                        throw new IllegalStateException("Current level is not an AbstractLevel: " + currentLevel.getClass().getName());
                    }
                    AbstractLevel level = (AbstractLevel) currentLevel;

                    level.replaceBlocks(s.blocks());
                    level.replaceChunks(s.chunks());
                    level.getMap().restoreData(s.mapData());
                    level.bumpEpoch();
                    if (s.hasLevelHudState()) {
                        levelGamestate.setRings(s.levelRings());
                        levelGamestate.setTimerFrames(s.levelTimerFrames());
                        if (s.levelTimerPaused()) {
                            levelGamestate.pauseTimer();
                        } else {
                            levelGamestate.resumeTimer();
                        }
                    }
                }
            };
        }
    }

    @Test
    void adapterKeyIsStable() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        assertEquals("level", adapter.key());
    }

    @Test
    void captureRestoreRoundTripBlockArrayReference() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        // Capture snapshot
        LevelSnapshot snap1 = adapter.capture();

        // Mutate block array by replacing the reference
        Block[] newBlocks = new Block[512];
        for (int i = 0; i < 512; i++) {
            newBlocks[i] = new Block();
        }
        level.replaceBlocks(newBlocks);

        // Snapshot should still have the original
        assertEquals(256, snap1.blocks().length);

        // Restore
        adapter.restore(snap1);

        // Level should be back to 256 blocks
        assertEquals(256, level.getBlockCount());
        assertEquals(snap1.blocks(), level.blocksReference());
    }

    @Test
    void captureRestoreRoundTripChunkArrayReference() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        // Capture snapshot
        LevelSnapshot snap1 = adapter.capture();

        // Mutate chunk array by replacing the reference
        Chunk[] newChunks = new Chunk[512];
        for (int i = 0; i < 512; i++) {
            newChunks[i] = new Chunk();
        }
        level.replaceChunks(newChunks);

        // Snapshot should still have the original
        assertEquals(256, snap1.chunks().length);

        // Restore
        adapter.restore(snap1);

        // Level should be back to 256 chunks
        assertEquals(256, level.getChunkCount());
        assertEquals(snap1.chunks(), level.chunksReference());
    }

    @Test
    void mutationAfterCaptureCowClonesUnderlyingMapData() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        // Initialize map with some data
        level.getMap().setValue(0, 0, 0, (byte) 0x11);

        // Capture snapshot
        LevelSnapshot snap1 = adapter.capture();
        byte[] snapshotMapData = snap1.mapData();

        // Verify initial value
        assert snapshotMapData[0] == (byte) 0x11;

        // Mutate the map with CoW
        level.bumpEpoch();
        level.getMap().cowEnsureWritable(level.currentEpoch());
        level.getMap().setValue(0, 0, 0, (byte) 0x99);

        // The snapshot's data should still have the original value
        assert snapshotMapData[0] == (byte) 0x11 : "Snapshot data should be unchanged after CoW mutation";

        // The live map should have the new value
        assert level.getMap().getValue(0, 0, 0) == (byte) 0x99;
    }

    @Test
    void restoreIncrementsEpoch() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        long epoch1 = level.currentEpoch();

        // Capture snapshot
        LevelSnapshot snap1 = adapter.capture();

        // Restore
        adapter.restore(snap1);

        long epoch2 = level.currentEpoch();

        // Epoch should have been bumped
        assertEquals(epoch1 + 1, epoch2);
    }

    @Test
    void captureRecordsCurrentEpoch() {
        StubLevel level = new StubLevel();
        level.bumpEpoch();
        level.bumpEpoch();

        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        LevelSnapshot snap = adapter.capture();

        // Snapshot should record the epoch at capture time
        assertEquals(2L, snap.epochAtCapture());
    }

    @Test
    void snapshotCarriesLevelHudRingsAndTimer() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();
        manager.levelGamestate.setRings(37);
        manager.levelGamestate.setTimerFrames(1234);
        manager.levelGamestate.pauseTimer();

        LevelSnapshot snap = adapter.capture();
        manager.levelGamestate.setRings(99);
        manager.levelGamestate.setTimerFrames(5678);
        manager.levelGamestate.resumeTimer();

        adapter.restore(snap);

        assertEquals(37, manager.levelGamestate.getRings());
        assertEquals(1234, manager.levelGamestate.getTimerFrames());
        assertTrue(manager.levelGamestate.isTimerPaused());
    }

    @Test
    void snapshotCarriesNoLevelHudStateWhenUnavailable() {
        LevelSnapshot snap = new LevelSnapshot(
                0,
                new Block[0],
                new Chunk[0],
                new byte[0],
                0);

        assertFalse(snap.hasLevelHudState());
    }

    @Test
    void unchangedCapturesShareLevelArrayReferences() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        LevelSnapshot snap1 = adapter.capture();
        LevelSnapshot snap2 = adapter.capture();

        assertSame(snap1.blocks(), snap2.blocks());
        assertSame(snap1.chunks(), snap2.chunks());
    }

    @Test
    void directBlockMutationAfterCaptureReplacesLiveArray() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();
        int[] originalState = level.getBlock(0).saveState();
        int[] changedState = originalState.clone();
        changedState[0] = 0x1234;

        LevelSnapshot snap = adapter.capture();

        LevelMutationSurface.forLevel(level).restoreBlockState(0, changedState);

        assertNotSame(snap.blocks(), level.blocksReference());
        assertArrayEquals(originalState, snap.blocks()[0].saveState());
        assertArrayEquals(changedState, level.getBlock(0).saveState());
    }

    @Test
    void directChunkMutationAfterCaptureReplacesLiveArray() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();
        int[] originalState = level.getChunk(0).saveState();
        int[] changedState = originalState.clone();
        changedState[0] = 0x1234;

        LevelSnapshot snap = adapter.capture();

        LevelMutationSurface.forLevel(level).restoreChunkState(0, changedState);

        assertNotSame(snap.chunks(), level.chunksReference());
        assertArrayEquals(originalState, snap.chunks()[0].saveState());
        assertArrayEquals(changedState, level.getChunk(0).saveState());
    }

    @Test
    void multipleCaptureCyclesIndependent() {
        StubLevel level = new StubLevel();
        StubLevelManager manager = new StubLevelManager(level);
        RewindSnapshottable<LevelSnapshot> adapter = manager.levelRewindSnapshottable();

        // First capture
        LevelSnapshot snap1 = adapter.capture();
        Block[] blocks1 = snap1.blocks();

        // Mutate
        level.bumpEpoch();
        Block[] newBlocks = new Block[512];
        for (int i = 0; i < 512; i++) {
            newBlocks[i] = new Block();
        }
        level.replaceBlocks(newBlocks);

        // Second capture (should have new blocks)
        LevelSnapshot snap2 = adapter.capture();
        Block[] blocks2 = snap2.blocks();

        // Snapshots should be independent
        assertEquals(256, blocks1.length);
        assertEquals(512, blocks2.length);
        assertNotSame(blocks1, blocks2);
    }
}
