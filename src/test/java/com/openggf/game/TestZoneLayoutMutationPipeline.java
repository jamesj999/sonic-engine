package com.openggf.game;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.EngineContext;
import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameStep;
import com.openggf.LevelFrameTestStep;
import com.openggf.game.mutation.DirectLevelMutationSurface;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LayoutMutationIntent;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.mutation.MutableLevelMutationSurface;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Map;
import com.openggf.level.MutableLevel;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestZoneLayoutMutationPipeline {

    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @AfterEach
    void tearDown() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void queuedIntentsFlushInSubmissionOrder() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        StringBuilder log = new StringBuilder();

        pipeline.queue(intent(log, "A"));
        pipeline.queue(intent(log, "B"));
        pipeline.flush(context());

        assertEquals("AB", log.toString());
    }

    @Test
    void flushClearsQueuedIntentsAfterApply() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        StringBuilder log = new StringBuilder();

        pipeline.queue(intent(log, "A"));
        pipeline.flush(context());
        pipeline.flush(context());

        assertEquals("A", log.toString());
    }

    @Test
    void immediateApplyDoesNotPolluteQueuedBatch() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        StringBuilder log = new StringBuilder();

        pipeline.queue(intent(log, "A"));
        pipeline.applyImmediately(intent(log, "B"), context());
        pipeline.flush(context());
        pipeline.flush(context());

        assertEquals("BA", log.toString());
    }

    @Test
    void flushReportsReturnedEffectsThroughContextSink() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        List<MutationEffects> appliedEffects = new ArrayList<>();

        MutationEffects redraw = MutationEffects.foregroundRedraw();
        MutationEffects resync = MutationEffects.objectResync();

        pipeline.queue(context -> redraw);
        pipeline.applyImmediately(context -> resync, context(appliedEffects));
        pipeline.flush(context(appliedEffects));

        assertEquals(List.of(resync, redraw), appliedEffects);
    }

    @Test
    void flushRetainsFailedAndRemainingIntentsWithoutRepeatingAppliedWork() {
        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        StringBuilder log = new StringBuilder();
        AtomicInteger attempts = new AtomicInteger();

        pipeline.queue(intent(log, "A"));
        pipeline.queue(context -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("boom");
            }
            log.append("B");
            return MutationEffects.NONE;
        });
        pipeline.queue(intent(log, "C"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> pipeline.flush(context()));
        assertEquals("boom", thrown.getMessage());
        assertEquals("A", log.toString());

        pipeline.flush(context());
        pipeline.flush(context());

        assertEquals("ABC", log.toString());
    }

    // Removed: parkAndResumeClearsQueuedMutationState. Tested
    // the old gameplay-mode parking path, which has been deleted
    // in favor of proper teardown+rebuild (SessionManager mode teardown,
    // gameplay-mode initialization, and level restoration). Queued mutation
    // discard at gameplay-mode teardown is exercised by
    // runtimeDestroyClearsPendingMutationState below.

    @Test
    void mutableLevelSurfaceUsesDirtyTrackingAndRequestsSameFrameProcessing() {
        MutableLevel mutableLevel = MutableLevel.snapshot(new TestLevel());
        LevelMutationSurface surface = new MutableLevelMutationSurface(mutableLevel);

        Pattern replacementPattern = new Pattern();
        replacementPattern.setPixel(0, 0, (byte) 7);

        assertTrue(surface.setPattern(0, replacementPattern).dirtyRegionProcessingRequired());
        assertTrue(surface.restoreChunkState(0, changedChunkState()).dirtyRegionProcessingRequired());
        assertTrue(surface.restoreBlockState(0, changedBlockState()).dirtyRegionProcessingRequired());
        assertTrue(surface.setBlockInMap(0, 0, 0, 0).dirtyRegionProcessingRequired());

        BitSet dirtyPatterns = mutableLevel.consumeDirtyPatterns();
        BitSet dirtyChunks = mutableLevel.consumeDirtyChunks();
        BitSet dirtyBlocks = mutableLevel.consumeDirtyBlocks();
        BitSet dirtyMapCells = mutableLevel.consumeDirtyMapCells();

        assertTrue(dirtyPatterns.get(0), "mutable surface should route pattern writes through MutableLevel dirty tracking");
        assertTrue(dirtyChunks.get(0), "mutable chunk mutations should be tracked for frame-start dirty processing");
        assertTrue(dirtyBlocks.get(0), "mutable block mutations should be tracked for frame-start dirty processing");
        assertFalse(dirtyMapCells.isEmpty(), "transitive map-cell dirtiness should be preserved for MutableLevel processing");
        assertEquals(7, mutableLevel.getPattern(0).getPixel(0, 0));
    }

    @Test
    void directLevelSurfaceReturnsImmediateEffectsForGameplayLevels() {
        TestLevel level = new TestLevel();
        LevelMutationSurface surface = new DirectLevelMutationSurface(level);

        Pattern replacementPattern = new Pattern();
        replacementPattern.setPixel(0, 0, (byte) 9);

        MutationEffects patternEffects = surface.setPattern(0, replacementPattern);
        MutationEffects redrawEffects = surface.restoreChunkState(0, changedChunkState());
        MutationEffects blockEffects = surface.restoreBlockState(0, changedBlockState());
        MutationEffects mapEffects = surface.setBlockInMap(0, 0, 0, 0);
        MutationEffects backgroundMapEffects = surface.setBlockInMap(1, 0, 0, 0);
        MutationEffects objectEffects = surface.requestObjectResync();
        MutationEffects ringEffects = surface.requestRingResync();
        MutationEffects explicitRedraw = surface.requestRedraw();

        assertEquals(9, level.getPattern(0).getPixel(0, 0));
        assertTrue(patternEffects.hasDirtyPatterns(), "direct pattern writes should request immediate GPU reupload");
        assertTrue(patternEffects.dirtyPatterns().get(0));
        assertFalse(patternEffects.foregroundRedrawRequired(), "pattern reupload alone should not force tilemap invalidation");
        assertFalse(patternEffects.allTilemapsRedrawRequired(), "pattern reupload alone should not force full tilemap invalidation");

        assertTrue(redrawEffects.allTilemapsRedrawRequired(), "chunk writes should invalidate all tilemaps on direct gameplay levels");
        assertTrue(blockEffects.allTilemapsRedrawRequired(), "block writes should invalidate all tilemaps on direct gameplay levels");
        assertTrue(mapEffects.foregroundRedrawRequired(), "map writes should require foreground redraw on direct gameplay levels");
        assertTrue(backgroundMapEffects.allTilemapsRedrawRequired(), "background-layer map writes should invalidate all tilemaps on direct gameplay levels");
        assertTrue(objectEffects.objectResyncRequired());
        assertTrue(ringEffects.ringResyncRequired());
        assertTrue(explicitRedraw.foregroundRedrawRequired());
    }

    @Test
    void levelFrameStepFlushesQueuedMutationsAfterCameraScrollAndEventsBeforeBoundary() {
        GameModule module = mock(GameModule.class);
        when(module.createRuntimeArtCoordinator(
                org.mockito.ArgumentMatchers.any())).thenReturn(RuntimeArtCoordinator.NONE);
        LevelEventProvider levelEvents = mock(LevelEventProvider.class);
        when(module.rngFlavour()).thenReturn(GameRng.Flavour.S1_S2);
        when(module.getSpecialStageCycleCount()).thenReturn(7);
        when(module.getChaosEmeraldCount()).thenReturn(7);
        GameModuleRegistry.setCurrent(module);
        GameplayModeContext gameplayMode = SessionManager.openGameplaySession(module);
        TestEnvironment.activeGameplayMode();
        when(module.getLevelEventProvider()).thenReturn(levelEvents);

        com.openggf.level.LevelManager levelManager = mock(com.openggf.level.LevelManager.class);
        com.openggf.camera.Camera camera = mock(com.openggf.camera.Camera.class);
        StringBuilder log = new StringBuilder();
        AtomicInteger eventCalls = new AtomicInteger();

        when(levelManager.objectsExecuteAfterPlayerPhysics()).thenReturn(false);
        when(levelManager.getCurrentLevel()).thenReturn(new TestLevel());
        doCallRealMethod().when(levelManager).flushQueuedLayoutMutations();
        // The real applyMutationEffects delegates to the package-private
        // LevelDirtyRegionDispatcher field, which a Mockito mock never
        // initializes; mirror its public-effect routing on the mock instead.
        doAnswer(invocation -> {
            com.openggf.game.mutation.MutationEffects effects = invocation.getArgument(0);
            if (effects == null || effects.isEmpty()) {
                return null;
            }
            if (effects.dirtyRegionProcessingRequired()) {
                levelManager.processDirtyRegions();
            }
            if (effects.allTilemapsRedrawRequired()) {
                levelManager.invalidateAllTilemaps();
            } else if (effects.foregroundRedrawRequired()) {
                levelManager.invalidateForegroundTilemap();
            }
            return null;
        }).when(levelManager).applyMutationEffects(org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            log.append('D');
            return null;
        }).when(levelManager).processDirtyRegions();
        doAnswer(invocation -> {
            log.append('I');
            return null;
        }).when(levelManager).invalidateForegroundTilemap();
        doAnswer(invocation -> {
            log.append('A');
            return null;
        }).when(levelManager).invalidateAllTilemaps();
        doAnswer(invocation -> {
            log.append('B');
            return null;
        }).when(camera).updateBoundaryEasing();
        doAnswer(invocation -> {
            log.append('P');
            return null;
        }).when(camera).updatePosition();
        doAnswer(invocation -> {
            log.append('E');
            if (eventCalls.getAndIncrement() == 0) {
                GameServices.zoneLayoutMutationPipeline().queue(context -> {
                    log.append('M');
                    return context.surface().requestRedraw();
                });
            }
            return null;
        }).when(levelEvents).update();

        LevelFrameTestStep.execute(LevelFrameContext.from(gameplayMode), levelManager, camera, () -> { });
        assertEquals("DPEMIB", log.toString(),
                "queued gameplay mutations should flush after camera scroll and level events, before boundary easing");

        LevelFrameTestStep.execute(LevelFrameContext.from(gameplayMode), levelManager, camera, () -> { });
        assertEquals("DPEMIBDPEB", log.toString(),
                "queued mutation batch should be empty on the next frame while processDirtyRegions stays at frame start");
    }

    @Test
    void runtimeDestroyClearsPendingMutationState() {
        SessionManager.clear();

        GameplayModeContext runtime = TestEnvironment.activeGameplayMode();
        ZoneLayoutMutationPipeline pipeline = GameServices.zoneLayoutMutationPipeline();
        assertSame(runtime.getZoneLayoutMutationPipeline(), pipeline);
        assertSame(runtime.getZoneLayoutMutationPipeline(), GameServices.zoneLayoutMutationPipelineOrNull());

        StringBuilder log = new StringBuilder();
        pipeline.queue(intent(log, "A"));

        SessionManager.clear();
        // Post-migration: GameServices accessors throw only when the gameplay
        // mode is gone — destroyCurrent leaves cleared managers attached.
        SessionManager.clear();

        assertNull(GameServices.zoneLayoutMutationPipelineOrNull());
        assertThrows(IllegalStateException.class, GameServices::zoneLayoutMutationPipeline);

        pipeline.flush(context());
        assertEquals("", log.toString(), "destroy should clear queued mutations on the old pipeline instance");

        GameplayModeContext recreated = TestEnvironment.activeGameplayMode();
        // The recreated gameplay mode owns a fresh shared registry, so compare
        // against the originally captured pipeline instance.
        assertNotSame(pipeline, recreated.getZoneLayoutMutationPipeline());
    }

    private static LayoutMutationIntent intent(StringBuilder log, String marker) {
        return context -> {
            log.append(marker);
            return MutationEffects.NONE;
        };
    }

    private static LayoutMutationContext context() {
        return context(null, new ArrayList<>());
    }

    private static LayoutMutationContext context(List<MutationEffects> appliedEffects) {
        return context(null, appliedEffects);
    }

    private static LayoutMutationContext context(LevelMutationSurface surface, List<MutationEffects> appliedEffects) {
        return new LayoutMutationContext(surface, appliedEffects::add);
    }

    private static int[] changedChunkState() {
        int[] state = new int[Chunk.PATTERNS_PER_CHUNK + 2];
        state[0] = 3;
        state[1] = 4;
        state[Chunk.PATTERNS_PER_CHUNK] = 1;
        state[Chunk.PATTERNS_PER_CHUNK + 1] = 2;
        return state;
    }

    private static int[] changedBlockState() {
        int[] state = new int[64];
        state[0] = new ChunkDesc(0).get();
        state[1] = new ChunkDesc(0x0800).get();
        return state;
    }

    private static final class TestLevel extends AbstractLevel {

        private TestLevel() {
            super(0);
            palettes = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
            patterns = new Pattern[] { new Pattern(), new Pattern(), new Pattern(), new Pattern(), new Pattern() };
            patternCount = patterns.length;
            chunks = new Chunk[] { buildChunk() };
            chunkCount = chunks.length;
            blocks = new Block[] { buildBlock() };
            blockCount = blocks.length;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM],
                            new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };
            solidTileCount = solidTiles.length;
            map = new Map(2, 1, 1);
            map.setValue(0, 0, 0, (byte) 0);
            map.setValue(1, 0, 0, (byte) 0);
            objects = new ArrayList<ObjectSpawn>();
            rings = new ArrayList<RingSpawn>();
            ringSpriteSheet = null;
            minX = 0;
            maxX = 128;
            minY = 0;
            maxY = 128;
        }

        private static Chunk buildChunk() {
            Chunk chunk = new Chunk();
            chunk.restoreState(new int[] { 0, 1, 2, 3, 0, 0 });
            return chunk;
        }

        private static Block buildBlock() {
            Block block = new Block();
            block.setChunkDesc(0, 0, new ChunkDesc(0));
            return block;
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }
    }
}
