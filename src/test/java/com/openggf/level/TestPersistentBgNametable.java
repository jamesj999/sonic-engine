package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.level.scroll.BgTilemapUpdateMode;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPersistentBgNametable {
    private static final int BLOCK_PX = 128;
    private static final int MAP_WIDTH_BLOCKS = 8;
    private static final int MAP_HEIGHT_BLOCKS = 4;
    private static final int MAP_WIDTH_PX = MAP_WIDTH_BLOCKS * BLOCK_PX;
    private static final int MAP_HEIGHT_PX = MAP_HEIGHT_BLOCKS * BLOCK_PX;
    private static final int RING_WIDTH_TILES = 64;
    private static final int RING_HEIGHT_TILES = 32;

    private GraphicsManager graphicsManager;
    private Level level;
    private LevelGeometry geometry;
    private LevelTilemapManager.BlockLookup blockLookup;
    private ZoneFeatureProvider zoneFeatures;

    @BeforeEach
    void setUp() {
        GraphicsManager.destroyForReinit();
        TestEnvironment.resetAll();
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
        level = new FixtureLevel();
        geometry = new LevelGeometry(level,
                MAP_WIDTH_PX, MAP_HEIGHT_PX,
                MAP_WIDTH_PX, MAP_WIDTH_PX, MAP_HEIGHT_PX,
                BLOCK_PX, 8);
        blockLookup = this::lookup;
        zoneFeatures = new WrappingZoneFeatures();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GraphicsManager.destroyForReinit();
    }

    @Test
    void defaultHandlerAndMissingHandlerUseStaticWindowMode() {
        assertEquals(BgTilemapUpdateMode.STATIC_WINDOW, new TestScrollHandler(false).getBgTilemapUpdateMode());
        assertEquals(BgTilemapUpdateMode.STATIC_WINDOW, new ParallaxManager().getBgTilemapUpdateMode());
    }

    @Test
    void invalidBaselineSeedsFullRingAtFloorAlignedPosition() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        injectRenderer(renderer);
        LevelTilemapManager manager = newManager();

        ensurePersistent(manager, -1, -1);

        assertEquals(RING_WIDTH_TILES * RING_HEIGHT_TILES * 4,
                manager.getPersistentBgRingCopy().length);
        assertEquals(0, manager.getPersistentBgOriginXTiles());
        assertEquals(0, manager.getPersistentBgOriginYTiles());
        assertEquals(-16, manager.getPersistentBgAlignedX());
        assertEquals(-16, manager.getPersistentBgAlignedY());
        assertTrue(manager.isPersistentBgBaselineValid());
        assertEquals(1, manager.persistentBgFullPublicationCount);
        assertEquals(0, manager.persistentBgIncrementalPublicationCount);
        assertEquals(RING_WIDTH_TILES * RING_HEIGHT_TILES * 4,
                renderer.getPendingBackgroundUploadBytes());
        assertDescriptorAtLogical(manager, 0, 0, -16, -32);
        assertDescriptorAtLogical(manager, 63, 31, -16 + 63 * 8, -32 + 31 * 8);
    }

    @Test
    void positiveAndNegativeXCrossingsRotateOriginAndRewriteOnlyEnteringColumns() {
        LevelTilemapManager forward = newManager();
        ensurePersistent(forward, 0, 0);
        byte[] beforeForward = forward.getPersistentBgRingCopy();
        ensurePersistent(forward, 16, 0);
        assertEquals(2, forward.getPersistentBgOriginXTiles());
        assertEquals(0, forward.getPersistentBgOriginYTiles());
        assertOnlyPhysicalColumnsChanged(beforeForward, forward.getPersistentBgRingCopy(), 0, 1);
        assertDescriptorAtLogical(forward, 62, 0, 16 + 62 * 8, -16);
        assertDescriptorAtLogical(forward, 63, 31, 16 + 63 * 8, -16 + 31 * 8);

        LevelTilemapManager backward = newManager();
        ensurePersistent(backward, 16, 0);
        byte[] beforeBackward = backward.getPersistentBgRingCopy();
        ensurePersistent(backward, 0, 0);
        assertEquals(62, backward.getPersistentBgOriginXTiles());
        assertOnlyPhysicalColumnsChanged(beforeBackward, backward.getPersistentBgRingCopy(), 62, 63);
        assertDescriptorAtLogical(backward, 0, 0, 0, -16);
        assertDescriptorAtLogical(backward, 1, 31, 8, -16 + 31 * 8);
    }

    @Test
    void positiveAndNegativeYCrossingsRotateOriginAndRewriteOnlyEnteringRows() {
        LevelTilemapManager forward = newManager();
        ensurePersistent(forward, 0, 0);
        byte[] beforeForward = forward.getPersistentBgRingCopy();
        ensurePersistent(forward, 0, 16);
        assertEquals(0, forward.getPersistentBgOriginXTiles());
        assertEquals(2, forward.getPersistentBgOriginYTiles());
        assertOnlyPhysicalRowsChanged(beforeForward, forward.getPersistentBgRingCopy(), 0, 1);
        assertDescriptorAtLogical(forward, 0, 30, 0, 30 * 8);
        assertDescriptorAtLogical(forward, 63, 31, 63 * 8, 31 * 8);

        LevelTilemapManager backward = newManager();
        ensurePersistent(backward, 0, 16);
        byte[] beforeBackward = backward.getPersistentBgRingCopy();
        ensurePersistent(backward, 0, 0);
        assertEquals(30, backward.getPersistentBgOriginYTiles());
        assertOnlyPhysicalRowsChanged(beforeBackward, backward.getPersistentBgRingCopy(), 30, 31);
        assertDescriptorAtLogical(backward, 0, 0, 0, -16);
        assertDescriptorAtLogical(backward, 63, 1, 63 * 8, -8);
    }

    @Test
    void upwardCrossingRetainsTheRomLeadingRowAheadOfTheVisibleWindow() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 16);
        byte[] before = manager.getPersistentBgRingCopy();

        ensurePersistent(manager, 0, 0);

        assertEquals(30, manager.getPersistentBgOriginYTiles());
        assertOnlyPhysicalRowsChanged(before, manager.getPersistentBgRingCopy(), 30, 31);
        assertDescriptorAtLogical(manager, 0, 0, 0, -16);
        assertDescriptorAtLogical(manager, 63, 1, 63 * 8, -8);
    }

    @Test
    void upwardWrapSourcesTheBottomLayoutRowInsteadOfWrappedRowZero() {
        blockLookup = (layer, x, y) -> {
            int wrappedY = Math.floorMod(y, 2048);
            int sentinelBlock = wrappedY == 0 ? 0 : wrappedY >= 1920 ? 24 : 8;
            return level.getBlock(sentinelBlock);
        };
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, -2032);

        ensurePersistent(manager, 0, -2048);

        assertDescriptorAtLogical(manager, 0, 0, 0, -2064);
        PatternDesc rowZeroSentinel = expectedDescriptor(0, -2048);
        assertLogicalDescriptorDoesNotEqual(manager, 0, 0, rowZeroSentinel);
    }

    @Test
    void downwardCrossingKeepsTheBottomOfTheLeadingRowWindowCurrent() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        byte[] before = manager.getPersistentBgRingCopy();

        ensurePersistent(manager, 0, 16);

        assertEquals(2, manager.getPersistentBgOriginYTiles());
        assertOnlyPhysicalRowsChanged(before, manager.getPersistentBgRingCopy(), 0, 1);
        assertDescriptorAtLogical(manager, 0, 30, 0, 240);
        assertDescriptorAtLogical(manager, 63, 31, 63 * 8, 248);
    }

    @Test
    void simultaneousCrossingUpdatesBothStripsAndCorner() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        byte[] before = manager.getPersistentBgRingCopy();

        ensurePersistent(manager, 16, 16);

        assertEquals(2, manager.getPersistentBgOriginXTiles());
        assertEquals(2, manager.getPersistentBgOriginYTiles());
        assertOnlyPhysicalCrossChanged(before, manager.getPersistentBgRingCopy(), 0, 1, 0, 1);
        assertDescriptorAtLogical(manager, 62, 30, 16 + 62 * 8, 30 * 8);
        assertDescriptorAtLogical(manager, 63, 31, 16 + 63 * 8, 31 * 8);
    }

    @Test
    void originsWrapOnBothAxes() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        for (int step = 1; step <= 32; step++) {
            ensurePersistent(manager, step * 16, step * 16);
        }
        assertEquals(0, manager.getPersistentBgOriginXTiles());
        assertEquals(0, manager.getPersistentBgOriginYTiles());
        assertEquals(512, manager.getPersistentBgAlignedX());
        assertEquals(512, manager.getPersistentBgAlignedY());
        assertDescriptorAtLogical(manager, 63, 31, 512 + 63 * 8, 496 + 31 * 8);
    }

    @Test
    void largeDeltaAndExplicitInvalidationReseedDeterministically() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        ensurePersistent(manager, 32, 0);
        assertEquals(2, manager.persistentBgFullPublicationCount);
        assertEquals(0, manager.getPersistentBgOriginXTiles());
        assertEquals(0, manager.getPersistentBgOriginYTiles());

        ensurePersistent(manager, 32, 32);
        assertEquals(3, manager.persistentBgFullPublicationCount);
        assertEquals(0, manager.getPersistentBgOriginXTiles());
        assertEquals(0, manager.getPersistentBgOriginYTiles());

        byte[] firstSeed = manager.getPersistentBgRingCopy();
        manager.invalidateAllTilemaps();
        ensurePersistent(manager, 32, 32);
        assertEquals(4, manager.persistentBgFullPublicationCount);
        assertArrayEquals(firstSeed, manager.getPersistentBgRingCopy());
    }

    @Test
    void hscrollOnlyFramesDoNotChangePersistentResidencyOrPublishUploads() {
        TestScrollHandler handler = new TestScrollHandler(true);
        LevelTilemapManager manager = newManager();
        handler.update(new int[224], 0, 0, 1, 0);
        ensurePersistent(manager, handler.getBgCameraX(), handler.getVscrollFactorBG());
        byte[] baseline = manager.getPersistentBgRingCopy();
        int fulls = manager.persistentBgFullPublicationCount;
        int incrementals = manager.persistentBgIncrementalPublicationCount;

        int[] changedCloudHscroll = new int[224];
        handler.update(changedCloudHscroll, 0, 0, 37, 0);
        assertFalse(Arrays.stream(changedCloudHscroll).allMatch(value -> value == 0));
        ensurePersistent(manager, handler.getBgCameraX(), handler.getVscrollFactorBG());

        assertArrayEquals(baseline, manager.getPersistentBgRingCopy());
        assertEquals(0, manager.getPersistentBgOriginXTiles());
        assertEquals(0, manager.getPersistentBgOriginYTiles());
        assertEquals(fulls, manager.persistentBgFullPublicationCount);
        assertEquals(incrementals, manager.persistentBgIncrementalPublicationCount);
    }

    @Test
    void noRedrawMutableLayoutWriteStaysOutsideRetainedRingUntilAffectedStripCrosses() {
        level = MutableLevel.snapshot(level);
        geometry = new LevelGeometry(level,
                MAP_WIDTH_PX, MAP_HEIGHT_PX,
                MAP_WIDTH_PX, MAP_WIDTH_PX, MAP_HEIGHT_PX,
                BLOCK_PX, 8);
        blockLookup = this::lookup;
        MutableLevel mutableLevel = (MutableLevel) level;
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        byte[] retainedBeforeMutation = manager.getPersistentBgRingCopy();
        int originX = manager.getPersistentBgOriginXTiles();
        int originY = manager.getPersistentBgOriginYTiles();
        int fullPublications = manager.persistentBgFullPublicationCount;
        byte[] snapshotMapData = mutableLevel.getMap().getData();
        int mutatedMapOffset = MAP_WIDTH_BLOCKS + 5;
        byte snapshotMapValue = snapshotMapData[mutatedMapOffset];
        assertEquals(5, snapshotMapValue & 0xFF,
                "fixture layer 1/x 5/y 0 must use the row-interleaved map offset");
        mutableLevel.bumpEpoch();

        MutationEffects effects = LevelMutationSurface.forLevel(mutableLevel)
                .setBlockInMapWithoutRedraw(1, 5, 0, 31);

        assertTrue(effects.isEmpty());
        assertEquals(31, mutableLevel.getMap().getValue(1, 5, 0) & 0xFF);
        assertNotSame(snapshotMapData, mutableLevel.getMap().getData(),
                "layout-RAM-only writes must retain copy-on-write snapshot isolation");
        assertEquals(31, mutableLevel.getMap().getData()[mutatedMapOffset] & 0xFF,
                "the cloned live backing must receive the layer 1 mutation");
        assertEquals(5, snapshotMapData[mutatedMapOffset] & 0xFF,
                "the captured backing must retain the pre-mutation layer 1 value");
        assertTrue(mutableLevel.consumeDirtyMapCells().isEmpty(),
                "layout-RAM-only writes must not queue next-frame tilemap invalidation");
        assertArrayEquals(retainedBeforeMutation, manager.getPersistentBgRingCopy());
        assertEquals(originX, manager.getPersistentBgOriginXTiles());
        assertEquals(originY, manager.getPersistentBgOriginYTiles());
        assertTrue(manager.isPersistentBgBaselineValid());

        ensurePersistent(manager, 0, 0);
        assertArrayEquals(retainedBeforeMutation, manager.getPersistentBgRingCopy(),
                "a stationary next frame must keep the pre-write VDP nametable bytes");
        assertEquals(fullPublications, manager.persistentBgFullPublicationCount);
        assertEquals(originX, manager.getPersistentBgOriginXTiles());
        assertEquals(originY, manager.getPersistentBgOriginYTiles());
        assertTrue(manager.isPersistentBgBaselineValid());

        for (int bgX = 16; bgX <= 144; bgX += 16) {
            ensurePersistent(manager, bgX, 0);
        }
        assertDescriptorAtLogical(manager, 63, 2, 648, 0);
    }

    @Test
    void staticModeKeepsExistingWindowBuilderAndDoesNotCreateRing() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        assertTrue(manager.isPersistentBgBaselineValid());
        manager.setCurrentBgPeriodWidth(512);
        manager.setBgTilemapBaseX(0);
        manager.ensureBackgroundTilemapData(blockLookup, zoneFeatures, 0, null, false);
        byte[] baseline = manager.getBackgroundTilemapData().clone();

        manager.requestBgWindowBaseX(16);
        manager.ensureBackgroundTilemapData(blockLookup, zoneFeatures, 0, null, false);

        assertNull(manager.getPersistentBgRingCopy());
        assertFalse(manager.isPersistentBgBaselineValid());
        assertEquals(64, manager.getBackgroundTilemapWidthTiles());
        assertEquals(64, manager.getBackgroundTilemapHeightTiles());
        assertEquals(1, manager.bgIncrementalShiftCount);
        assertFalse(Arrays.equals(baseline, manager.getBackgroundTilemapData()));
    }

    @Test
    void physicalCpuRingMatchesGpuOriginsAndIncrementalTextureContents() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        injectRenderer(renderer);
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        byte[] physicalTexture = new byte[RING_WIDTH_TILES * RING_HEIGHT_TILES * 4];
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);

        ensurePersistent(manager, 16, 16);

        assertEquals(manager.getPersistentBgOriginXTiles(), renderer.getBackgroundRingBaseXTiles());
        assertEquals(manager.getPersistentBgOriginYTiles(), renderer.getBackgroundRingBaseYTiles());
        assertEquals((2 * RING_HEIGHT_TILES + 2 * RING_WIDTH_TILES) * 4,
                renderer.getPendingBackgroundUploadBytes());
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        assertArrayEquals(manager.getPersistentBgRingCopy(), physicalTexture);
    }

    @Test
    void nonzeroPersistentAnchorIsSubtractedFromRenderSamplingCoordinates() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 16, 16);

        LevelRenderer.BackgroundTilemapSampling sampling =
                LevelRenderer.backgroundTilemapSampling(manager, 16);

        assertEquals(16, sampling.compositorWorldAnchorX());
        assertEquals(16, sampling.tilePassWorldOffsetY());
        int logicalX = Math.floorDiv(16 - sampling.compositorWorldAnchorX(), 8);
        int logicalY = Math.floorDiv(sampling.tilePassWorldOffsetY(), 8);
        assertDescriptorAtLogical(manager, logicalX, logicalY, 16, 16);
    }

    @Test
    void persistentSourceAnchorIncludesLeadingRowWithoutShiftingVisibleWorldMapping() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 16, 32);

        LevelRenderer.BackgroundTilemapSampling sampling =
                LevelRenderer.backgroundTilemapSampling(manager, 32);

        assertEquals(32, manager.getPersistentBgAlignedY(),
                "the retained baseline remains the live aligned camera Y");
        assertEquals(16, manager.getBackgroundTilemapSourceY(),
                "the texture source starts one ROM redraw row above the baseline");
        assertEquals(16, sampling.tilePassWorldOffsetY(),
                "visible world Y must sample logical row 2, not shift upward on screen");
        assertDescriptorAtLogical(manager, 0, 0, 16, 16);
        assertDescriptorAtLogical(manager, 0, 2, 16, 32);
    }

    @Test
    void statelessSourceAnchorsRemainUnchanged() {
        LevelTilemapManager manager = newManager();
        manager.setBgTilemapBaseX(48);
        manager.ensureBackgroundTilemapData(blockLookup, zoneFeatures, 0, null, false);

        LevelRenderer.BackgroundTilemapSampling sampling =
                LevelRenderer.backgroundTilemapSampling(manager, 32);

        assertEquals(48, manager.getBackgroundTilemapSourceX());
        assertEquals(0, manager.getBackgroundTilemapSourceY());
        assertEquals(48, sampling.compositorWorldAnchorX());
        assertEquals(32, sampling.tilePassWorldOffsetY());
    }

    @Test
    void normalWrapCrossesPositiveAndNegativeContiguousWidthBoundariesIncrementally() throws Exception {
        useContiguousWidth(512);

        RecordingRenderer positiveRenderer = new RecordingRenderer();
        injectRenderer(positiveRenderer);
        LevelTilemapManager positive = newManager();
        ensurePersistent(positive, 496, 0);
        byte[] positivePhysicalTexture = new byte[RING_WIDTH_TILES * RING_HEIGHT_TILES * 4];
        positiveRenderer.applyPendingBackgroundUploadForTest(positivePhysicalTexture);
        byte[] positiveBefore = positive.getPersistentBgRingCopy();
        assertRingMatchesWrappedSourceWindow(positive, 496, -16, 512);
        ensurePersistent(positive, 512, 0);
        assertEquals(1, positive.persistentBgFullPublicationCount);
        assertEquals(1, positive.persistentBgIncrementalPublicationCount);
        assertEquals(2, positive.getPersistentBgOriginXTiles());
        assertEquals(2 * RING_HEIGHT_TILES * 4,
                positiveRenderer.getPendingBackgroundUploadBytes(),
                "only the two incoming physical columns should be published");
        assertNoPhysicalColumnsOutsideChanged(
                positiveBefore, positive.getPersistentBgRingCopy(), 0, 1);
        assertRingMatchesWrappedSourceWindow(positive, 0, -16, 512);

        RecordingRenderer negativeRenderer = new RecordingRenderer();
        injectRenderer(negativeRenderer);
        LevelTilemapManager negative = newManager();
        ensurePersistent(negative, 0, 0);
        byte[] negativePhysicalTexture = new byte[RING_WIDTH_TILES * RING_HEIGHT_TILES * 4];
        negativeRenderer.applyPendingBackgroundUploadForTest(negativePhysicalTexture);
        byte[] negativeBefore = negative.getPersistentBgRingCopy();
        ensurePersistent(negative, -16, 0);
        assertEquals(1, negative.persistentBgFullPublicationCount);
        assertEquals(1, negative.persistentBgIncrementalPublicationCount);
        assertEquals(62, negative.getPersistentBgOriginXTiles());
        assertEquals(2 * RING_HEIGHT_TILES * 4,
                negativeRenderer.getPendingBackgroundUploadBytes(),
                "only the two incoming physical columns should be published");
        assertNoPhysicalColumnsOutsideChanged(
                negativeBefore, negative.getPersistentBgRingCopy(), 62, 63);
        assertRingMatchesWrappedSourceWindow(negative, 496, -16, 512);
    }

    @Test
    void linearRowOverflowKeepsRawXAcrossNormalWrapBoundary() {
        useContiguousWidth(512);
        zoneFeatures = new WrappingZoneFeatures(true);
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 496, 0);

        ensurePersistent(manager, 512, 0);

        assertEquals(1, manager.persistentBgFullPublicationCount);
        assertEquals(1, manager.persistentBgIncrementalPublicationCount);
        assertEquals(2, manager.getPersistentBgOriginXTiles());
        assertDescriptorAtLogical(manager, 0, 0, 512, -16);
        assertDescriptorAtLogical(manager, 63, 31, 512 + 63 * 8, -16 + 31 * 8);
    }

    @Test
    void physicalDescriptorFullPublicationPreservesOriginsBaselineAndMutation() throws Exception {
        RecordingRenderer renderer = new RecordingRenderer();
        injectRenderer(renderer);
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, 0, 0);
        byte[] physicalTexture = new byte[RING_WIDTH_TILES * RING_HEIGHT_TILES * 4];
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        ensurePersistent(manager, 16, 16);
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        int originX = manager.getPersistentBgOriginXTiles();
        int originY = manager.getPersistentBgOriginYTiles();
        int fulls = manager.persistentBgFullPublicationCount;
        int generation = renderer.getBackgroundContentGeneration();
        int descriptor = 0xE5A5;

        assertTrue(manager.setBackgroundTileDescriptorAtTilemapCell(10, 11, descriptor));
        manager.uploadBackgroundTilemap();

        assertEquals(originX, renderer.getBackgroundRingBaseXTiles());
        assertEquals(originY, renderer.getBackgroundRingBaseYTiles());
        assertTrue(renderer.getBackgroundContentGeneration() > generation);
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        assertArrayEquals(manager.getPersistentBgRingCopy(), physicalTexture);
        ensurePersistent(manager, 16, 16);
        assertEquals(fulls, manager.persistentBgFullPublicationCount,
                "stationary ensure must not reseed after a physical full publication");
        assertTrue(manager.isPersistentBgBaselineValid());
        assertEquals(originX, manager.getPersistentBgOriginXTiles());
        assertEquals(originY, manager.getPersistentBgOriginYTiles());
        assertPhysicalDescriptor(manager.getPersistentBgRingCopy(), 10, 11, descriptor);
    }

    @Test
    void everyDiagonalDirectionUpdatesTheExpectedRingOrigins() {
        int[][] directions = { { 1, 1 }, { 1, -1 }, { -1, 1 }, { -1, -1 } };
        for (int[] direction : directions) {
            LevelTilemapManager manager = newManager();
            ensurePersistent(manager, 32, 32);
            int targetX = 32 + direction[0] * 16;
            int targetY = 32 + direction[1] * 16;

            ensurePersistent(manager, targetX, targetY);

            assertEquals(direction[0] > 0 ? 2 : 62, manager.getPersistentBgOriginXTiles());
            assertEquals(direction[1] > 0 ? 2 : 30, manager.getPersistentBgOriginYTiles());
            assertRingMatchesSourceWindow(manager, targetX, targetY - LevelConstants.CHUNK_HEIGHT);
        }
    }

    @Test
    void negativePersistentAnchorIsSubtractedFromRenderSamplingCoordinates() {
        LevelTilemapManager manager = newManager();
        ensurePersistent(manager, -16, -16);

        LevelRenderer.BackgroundTilemapSampling sampling =
                LevelRenderer.backgroundTilemapSampling(manager, -16);

        assertEquals(-16, sampling.compositorWorldAnchorX());
        assertEquals(16, sampling.tilePassWorldOffsetY());
        assertDescriptorAtLogical(manager, 0, 0, -16, -32);
        assertDescriptorAtLogical(manager, 0, 2, -16, -16);
    }

    private LevelTilemapManager newManager() {
        return new LevelTilemapManager(geometry, graphicsManager, null);
    }

    private void useContiguousWidth(int contiguousWidthPx) {
        geometry = new LevelGeometry(level,
                MAP_WIDTH_PX, MAP_HEIGHT_PX,
                MAP_WIDTH_PX, contiguousWidthPx, MAP_HEIGHT_PX,
                BLOCK_PX, 8);
    }

    private void ensurePersistent(LevelTilemapManager manager, int bgX, int bgY) {
        manager.ensureBackgroundTilemapData(blockLookup, zoneFeatures, 0, null,
                BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32, bgX, bgY, false);
    }

    private Block lookup(byte layer, int x, int y) {
        int wrappedX = Math.floorMod(x, MAP_WIDTH_PX);
        int wrappedY = Math.floorMod(y, MAP_HEIGHT_PX);
        int blockIndex = level.getMap().getValue(layer,
                wrappedX / BLOCK_PX, wrappedY / BLOCK_PX) & 0xFF;
        return level.getBlock(blockIndex);
    }

    private void assertDescriptorAtLogical(LevelTilemapManager manager, int logicalX, int logicalY,
                                           int worldX, int worldY) {
        byte[] ring = manager.getPersistentBgRingCopy();
        assertNotNull(ring);
        int physicalX = Math.floorMod(manager.getPersistentBgOriginXTiles() + logicalX, RING_WIDTH_TILES);
        int physicalY = Math.floorMod(manager.getPersistentBgOriginYTiles() + logicalY, RING_HEIGHT_TILES);
        int offset = (physicalY * RING_WIDTH_TILES + physicalX) * 4;
        PatternDesc expected = expectedDescriptor(worldX, worldY);
        assertEquals(expected.getPatternIndex() & 0xFF, ring[offset] & 0xFF);
        int expectedG = ((expected.getPatternIndex() >>> 8) & 7)
                | (expected.getPaletteIndex() << 3)
                | (expected.getHFlip() ? 0x20 : 0)
                | (expected.getVFlip() ? 0x40 : 0)
                | (expected.getPriority() ? 0x80 : 0);
        assertEquals(expectedG, ring[offset + 1] & 0xFF);
        assertEquals(255, ring[offset + 3] & 0xFF);
    }

    private PatternDesc expectedDescriptor(int worldX, int worldY) {
        Block block = blockLookup.lookup((byte) 1, worldX, worldY);
        int chunkX = Math.floorMod(worldX, BLOCK_PX) / 16;
        int chunkY = Math.floorMod(worldY, BLOCK_PX) / 16;
        Chunk chunk = level.getChunk(block.getChunkDesc(chunkX, chunkY).getChunkIndex());
        int patternX = Math.floorMod(worldX, 16) / 8;
        int patternY = Math.floorMod(worldY, 16) / 8;
        return chunk.getPatternDesc(patternX, patternY);
    }

    private void assertLogicalDescriptorDoesNotEqual(LevelTilemapManager manager,
                                                     int logicalX,
                                                     int logicalY,
                                                     PatternDesc unexpected) {
        byte[] ring = manager.getPersistentBgRingCopy();
        assertNotNull(ring);
        int physicalX = Math.floorMod(manager.getPersistentBgOriginXTiles() + logicalX,
                RING_WIDTH_TILES);
        int physicalY = Math.floorMod(manager.getPersistentBgOriginYTiles() + logicalY,
                RING_HEIGHT_TILES);
        int offset = (physicalY * RING_WIDTH_TILES + physicalX) * 4;
        int actualPattern = (ring[offset] & 0xFF) | ((ring[offset + 1] & 7) << 8);
        assertFalse(actualPattern == unexpected.getPatternIndex(),
                "the entering row must not use the wrapped row-zero sentinel");
    }

    private void assertRingMatchesSourceWindow(LevelTilemapManager manager,
                                               int sourceX, int sourceY) {
        for (int logicalY = 0; logicalY < RING_HEIGHT_TILES; logicalY++) {
            for (int logicalX = 0; logicalX < RING_WIDTH_TILES; logicalX++) {
                assertDescriptorAtLogical(manager, logicalX, logicalY,
                        sourceX + logicalX * 8, sourceY + logicalY * 8);
            }
        }
    }

    private void assertRingMatchesWrappedSourceWindow(LevelTilemapManager manager,
                                                      int sourceX, int sourceY,
                                                      int wrapWidthPx) {
        for (int logicalY = 0; logicalY < RING_HEIGHT_TILES; logicalY++) {
            for (int logicalX = 0; logicalX < RING_WIDTH_TILES; logicalX++) {
                assertDescriptorAtLogical(manager, logicalX, logicalY,
                        Math.floorMod(sourceX + logicalX * 8, wrapWidthPx),
                        sourceY + logicalY * 8);
            }
        }
    }

    private static void assertPhysicalDescriptor(byte[] ring, int physicalX, int physicalY,
                                                 int descriptor) {
        int offset = (physicalY * RING_WIDTH_TILES + physicalX) * 4;
        int patternIndex = descriptor & 0x7FF;
        int expectedG = ((patternIndex >>> 8) & 7)
                | (((descriptor >>> 13) & 3) << 3)
                | ((descriptor & 0x800) != 0 ? 0x20 : 0)
                | ((descriptor & 0x1000) != 0 ? 0x40 : 0)
                | ((descriptor & 0x8000) != 0 ? 0x80 : 0);
        assertEquals(patternIndex & 0xFF, ring[offset] & 0xFF);
        assertEquals(expectedG, ring[offset + 1] & 0xFF);
        assertEquals(255, ring[offset + 3] & 0xFF);
    }

    private static void assertOnlyPhysicalColumnsChanged(byte[] before, byte[] after, int... columns) {
        boolean[] allowed = new boolean[RING_WIDTH_TILES];
        for (int column : columns) allowed[column] = true;
        int changed = 0;
        for (int y = 0; y < RING_HEIGHT_TILES; y++) {
            for (int x = 0; x < RING_WIDTH_TILES; x++) {
                boolean differs = descriptorDiffers(before, after, x, y);
                if (differs) changed++;
                assertEquals(allowed[x], differs, "unexpected changed slot (" + x + "," + y + ")");
            }
        }
        assertTrue(changed > 0);
    }

    private static void assertNoPhysicalColumnsOutsideChanged(byte[] before, byte[] after,
                                                               int... columns) {
        boolean[] allowed = new boolean[RING_WIDTH_TILES];
        for (int column : columns) allowed[column] = true;
        for (int y = 0; y < RING_HEIGHT_TILES; y++) {
            for (int x = 0; x < RING_WIDTH_TILES; x++) {
                if (!allowed[x]) {
                    assertFalse(descriptorDiffers(before, after, x, y),
                            "unexpected changed slot (" + x + "," + y + ")");
                }
            }
        }
    }

    private static void assertOnlyPhysicalRowsChanged(byte[] before, byte[] after, int... rows) {
        boolean[] allowed = new boolean[RING_HEIGHT_TILES];
        for (int row : rows) allowed[row] = true;
        int changed = 0;
        for (int y = 0; y < RING_HEIGHT_TILES; y++) {
            for (int x = 0; x < RING_WIDTH_TILES; x++) {
                boolean differs = descriptorDiffers(before, after, x, y);
                if (differs) changed++;
                assertEquals(allowed[y], differs, "unexpected changed slot (" + x + "," + y + ")");
            }
        }
        assertTrue(changed > 0);
    }

    private static void assertOnlyPhysicalCrossChanged(byte[] before, byte[] after,
                                                       int columnA, int columnB, int rowA, int rowB) {
        int changed = 0;
        for (int y = 0; y < RING_HEIGHT_TILES; y++) {
            for (int x = 0; x < RING_WIDTH_TILES; x++) {
                boolean differs = descriptorDiffers(before, after, x, y);
                if (differs) changed++;
                assertEquals(x == columnA || x == columnB || y == rowA || y == rowB, differs,
                        "unexpected changed slot (" + x + "," + y + ")");
            }
        }
        assertTrue(changed > 0);
    }

    private static boolean descriptorDiffers(byte[] before, byte[] after, int x, int y) {
        int offset = (y * RING_WIDTH_TILES + x) * 4;
        for (int i = 0; i < 4; i++) {
            if (before[offset + i] != after[offset + i]) return true;
        }
        return false;
    }

    private void injectRenderer(TilemapGpuRenderer renderer) throws Exception {
        Field field = GraphicsManager.class.getDeclaredField("tilemapGpuRenderer");
        field.setAccessible(true);
        field.set(graphicsManager, renderer);
    }

    private static final class RecordingRenderer extends TilemapGpuRenderer {
        @Override public int getPendingBackgroundUploadBytes() {
            return super.getPendingBackgroundUploadBytes();
        }
        @Override public void applyPendingBackgroundUploadForTest(byte[] physicalTexture) {
            super.applyPendingBackgroundUploadForTest(physicalTexture);
        }
    }

    private static final class TestScrollHandler implements ZoneScrollHandler {
        private final boolean persistent;
        private int frame;

        private TestScrollHandler(boolean persistent) {
            this.persistent = persistent;
        }

        @Override public void update(int[] horizScrollBuf, int cameraX, int cameraY,
                                     int frameCounter, int actId) {
            frame = frameCounter;
            Arrays.fill(horizScrollBuf, frameCounter);
        }
        @Override public short getVscrollFactorBG() { return 0; }
        @Override public int getMinScrollOffset() { return frame; }
        @Override public int getMaxScrollOffset() { return frame; }
        @Override public int getBgCameraX() { return 0; }
        @Override public BgTilemapUpdateMode getBgTilemapUpdateMode() {
            return persistent ? BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32
                    : ZoneScrollHandler.super.getBgTilemapUpdateMode();
        }
    }

    private static final class FixtureLevel extends AbstractLevel {
        FixtureLevel() {
            super(0);
            palettes = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
            patternCount = 2048;
            patterns = new Pattern[patternCount];
            Arrays.setAll(patterns, ignored -> new Pattern());
            chunkCount = 1024;
            chunks = new Chunk[chunkCount];
            for (int index = 0; index < chunkCount; index++) {
                Chunk chunk = new Chunk();
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        int pattern = 2 + Math.floorMod(index * 4 + y * 2 + x, 1800);
                        int descriptor = pattern | ((index & 3) << 13)
                                | ((index & 4) != 0 ? 0x800 : 0)
                                | ((index & 8) != 0 ? 0x1000 : 0)
                                | ((index & 16) != 0 ? 0x8000 : 0);
                        chunk.setPatternDesc(x, y, new PatternDesc(descriptor));
                    }
                }
                chunks[index] = chunk;
            }
            blockCount = MAP_WIDTH_BLOCKS * MAP_HEIGHT_BLOCKS;
            blocks = new Block[blockCount];
            for (int blockIndex = 0; blockIndex < blockCount; blockIndex++) {
                Block block = new Block(8);
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int chunkIndex = Math.floorMod(blockIndex * 61 + y * 8 + x, chunkCount);
                        block.setChunkDesc(x, y, new ChunkDesc(chunkIndex));
                    }
                }
                blocks[blockIndex] = block;
            }
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(2, MAP_WIDTH_BLOCKS, MAP_HEIGHT_BLOCKS);
            for (int y = 0; y < MAP_HEIGHT_BLOCKS; y++) {
                for (int x = 0; x < MAP_WIDTH_BLOCKS; x++) {
                    map.setValue(1, x, y, (byte) (y * MAP_WIDTH_BLOCKS + x));
                }
            }
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = MAP_WIDTH_PX;
            minY = 0;
            maxY = MAP_HEIGHT_PX;
        }

        @Override public SolidTile getSolidTile(int index) { return null; }
        @Override public List<ObjectSpawn> getObjects() { return List.of(); }
        @Override public List<RingSpawn> getRings() { return List.of(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
    }

    private static final class WrappingZoneFeatures implements ZoneFeatureProvider {
        private final boolean linearOverflow;

        private WrappingZoneFeatures() {
            this(false);
        }

        private WrappingZoneFeatures(boolean linearOverflow) {
            this.linearOverflow = linearOverflow;
        }

        @Override public boolean bgWrapsHorizontally() { return true; }
        @Override public boolean useLinearBackgroundLayoutOverflow(int zoneIndex) {
            return linearOverflow;
        }
        @Override public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) { }
        @Override public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) { }
        @Override public void reset() { }
        @Override public boolean hasCollisionFeatures(int zoneIndex) { return false; }
        @Override public boolean hasWater(int zoneIndex) { return false; }
        @Override public int getWaterLevel(int zoneIndex, int actIndex) { return 0; }
        @Override public void render(Camera camera, int frameCounter) { }
        @Override public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }
    }
}
