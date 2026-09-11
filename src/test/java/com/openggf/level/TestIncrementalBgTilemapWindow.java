package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.TilemapGpuRenderer;
import com.openggf.graphics.TilemapTexture;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the incremental BG tilemap window shift is byte-identical to a full
 * rebuild for single 16px base-X steps in both directions, and that every
 * unproven precondition (wrap-boundary crossing, multi-column jumps, generic
 * invalidations, non-wrapping zones, runtime tilemap overlay writes) falls
 * back to the full rebuild path.
 */
public class TestIncrementalBgTilemapWindow {

    private static final int BLOCK_PX = 128;
    // BG layout: 16 blocks wide (2048px) x 2 blocks tall (256px).
    private static final int MAP_WIDTH_BLOCKS = 16;
    private static final int MAP_HEIGHT_BLOCKS = 2;
    private static final int BG_WIDTH_PX = MAP_WIDTH_BLOCKS * BLOCK_PX;
    private static final int BG_HEIGHT_PX = MAP_HEIGHT_BLOCKS * BLOCK_PX;
    // Contiguous BG data narrower than the layout so base-X wrapping is exercised.
    private static final int BG_CONTIGUOUS_PX = 1024;
    private static final int PERIOD_PX = 512;

    private GraphicsManager graphicsManager;
    private StubLevel level;
    private LevelGeometry geometry;
    private LevelTilemapManager.BlockLookup blockLookup;

    @BeforeEach
    public void setUp() {
        GraphicsManager.destroyForReinit();
        TestEnvironment.resetAll();
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();

        level = new StubLevel(MAP_HEIGHT_BLOCKS, false);
        geometry = new LevelGeometry(level,
                BG_WIDTH_PX, BG_HEIGHT_PX,
                BG_WIDTH_PX, BG_CONTIGUOUS_PX, BG_HEIGHT_PX,
                BLOCK_PX, 8);
        blockLookup = lookupFor(level, BG_WIDTH_PX, BG_HEIGHT_PX);
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
        GraphicsManager.destroyForReinit();
    }

    // ── Equivalence: single-column steps in both directions ────────────────

    @Test
    public void advanceAndRetreatByOneColumnAreByteIdenticalToFullRebuild() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);
        assertEquals(1, manager.bgFullRebuildCount);
        assertEquals(0, manager.bgIncrementalShiftCount);

        // Advance one column (rightward scroll)
        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(1, manager.bgFullRebuildCount, "advance should take the incremental path");
        assertEquals(1, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(512, zfp), manager.getBackgroundTilemapData(),
                "advance shift must be byte-identical to a full rebuild");

        // Retreat one column (leftward scroll)
        manager.requestBgWindowBaseX(496);
        ensure(manager, zfp);
        assertEquals(1, manager.bgFullRebuildCount, "retreat should take the incremental path");
        assertEquals(2, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(496, zfp), manager.getBackgroundTilemapData(),
                "retreat shift must be byte-identical to a full rebuild");
    }

    @Test
    public void consecutiveAdvancesAcrossManyColumnsStayByteIdentical() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(0, zfp);
        for (int base = 16; base <= 320; base += 16) {
            manager.requestBgWindowBaseX(base);
            ensure(manager, zfp);
            assertArrayEquals(fullRebuildAt(base, zfp), manager.getBackgroundTilemapData(),
                    "diverged at base " + base);
        }
        assertEquals(1, manager.bgFullRebuildCount);
        assertEquals(20, manager.bgIncrementalShiftCount);
    }

    @Test
    public void linearRowOverflowModeShiftsAreByteIdenticalToFullRebuild() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, true);
        LevelTilemapManager manager = newManager(496, zfp);

        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(1, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(512, zfp), manager.getBackgroundTilemapData());

        manager.requestBgWindowBaseX(496);
        ensure(manager, zfp);
        assertEquals(2, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(496, zfp), manager.getBackgroundTilemapData());
    }

    @Test
    public void linearRowOverflowWindowLeftOfLayoutBuildsBlankLeadingColumns() {
        // HCZ2's wall BG camera starts left of the layout at act entry: the
        // window base is negative, so top-row cells left of the layout have no
        // ROM row data behind them and must build blank (not wrap to the
        // layout's tail cells), and the build must not throw.
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, true);
        LevelTilemapManager manager = newManager(-64, zfp);

        byte[] data = manager.getBackgroundTilemapData();
        int widthTiles = manager.getBackgroundTilemapWidthTiles();
        // Leading 64px (8 tile columns) of the top block row query negative
        // layout X on row 0 -> blank tiles (alpha byte 0).
        for (int tileY = 0; tileY < BLOCK_PX / 8; tileY++) {
            for (int tileX = 0; tileX < 8; tileX++) {
                assertEquals(0, data[(tileY * widthTiles + tileX) * 4 + 3],
                        "tile (" + tileX + "," + tileY + ") left of the layout must be blank");
            }
        }
    }

    // ── Fallbacks ───────────────────────────────────────────────────────────

    @Test
    public void wrapBoundaryCrossingFallsBackToFullRebuild() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        // Base 1008 → wrapped offset 1008; base 1024 → wrapped offset 0.
        // The effective offset jump is not a single column, so the shift must decline.
        LevelTilemapManager manager = newManager(1008, zfp);
        manager.requestBgWindowBaseX(1024);
        ensure(manager, zfp);
        assertEquals(2, manager.bgFullRebuildCount, "wrap-boundary step must full-rebuild");
        assertEquals(0, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(1024, zfp), manager.getBackgroundTilemapData());

        // Stepping back across the boundary must also full-rebuild.
        manager.requestBgWindowBaseX(1008);
        ensure(manager, zfp);
        assertEquals(3, manager.bgFullRebuildCount);
        assertEquals(0, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(1008, zfp), manager.getBackgroundTilemapData());
    }

    @Test
    public void multiColumnJumpFallsBackToFullRebuild() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);
        manager.requestBgWindowBaseX(560); // +64px jump
        ensure(manager, zfp);
        assertEquals(2, manager.bgFullRebuildCount);
        assertEquals(0, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(560, zfp), manager.getBackgroundTilemapData());
    }

    @Test
    public void accumulatedStepsWithoutRebuildFallBackToFullRebuild() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);
        // Two window steps land before the next ensure. The rejection criterion is
        // not the step count: the shift compares the effective x-query offset against
        // the last BUILT snapshot, and the accumulated net movement (496 -> 528 =
        // +32px) is not exactly one 16px column, so the incremental path must decline.
        manager.requestBgWindowBaseX(512);
        manager.requestBgWindowBaseX(528);
        ensure(manager, zfp);
        assertEquals(2, manager.bgFullRebuildCount);
        assertEquals(0, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(528, zfp), manager.getBackgroundTilemapData());
    }

    @Test
    public void genericInvalidationForcesFullRebuildOnNextWindowStep() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);

        manager.invalidateAllTilemaps();
        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(2, manager.bgFullRebuildCount, "invalidateAllTilemaps must force full rebuild");
        assertEquals(0, manager.bgIncrementalShiftCount);

        manager.setBackgroundTilemapDirty(true);
        manager.requestBgWindowBaseX(528);
        ensure(manager, zfp);
        assertEquals(3, manager.bgFullRebuildCount, "generic dirty must force full rebuild");
        assertEquals(0, manager.bgIncrementalShiftCount);
        assertArrayEquals(fullRebuildAt(528, zfp), manager.getBackgroundTilemapData());
    }

    @Test
    public void nonWrappingZoneNeverTakesIncrementalPath() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(false, false);
        LevelTilemapManager manager = newManager(0, zfp);
        manager.requestBgWindowBaseX(16);
        ensure(manager, zfp);
        assertEquals(2, manager.bgFullRebuildCount);
        assertEquals(0, manager.bgIncrementalShiftCount);
    }

    @Test
    public void runtimeWrapActivationRebuildsFullLayoutAsVdpPlaneWindow() {
        MutableWrapZoneFeatures zfp = new MutableWrapZoneFeatures();
        LevelTilemapManager manager = newManager(0, zfp);
        assertEquals(MAP_WIDTH_BLOCKS * 16, manager.getBackgroundTilemapWidthTiles(),
                "non-wrapped S3K background initially exposes the full layout");

        zfp.wraps = true;
        ensure(manager, zfp);

        assertEquals(PERIOD_PX / Pattern.PATTERN_WIDTH,
                manager.getBackgroundTilemapWidthTiles(),
                "dynamic Plane B refresh must rebuild as a 64-cell VDP window");
        assertEquals(0, manager.getBgTilemapBaseX(),
                "post-chase Plane B refresh must retain the X=$000 source strip");
        assertArrayEquals(fullRebuildAt(0, new StubZoneFeatures(true, false)),
                manager.getBackgroundTilemapData());
        assertEquals(2, manager.bgFullRebuildCount);
        assertEquals(0, manager.bgIncrementalShiftCount);
    }

    @Test
    public void runtimeTilemapOverlayWriteForcesFullRebuildOnNextWindowStep() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);

        // Runtime BG overlay write (e.g. AIZ-style direct tile rewrites).
        assertTrue(manager.setBackgroundTileDescriptorAtTilemapCell(3, 3, 0x1234));

        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(2, manager.bgFullRebuildCount,
                "live-array overlay write must invalidate the shift snapshot");
        assertEquals(0, manager.bgIncrementalShiftCount);
        // Full rebuild discards the overlay write, exactly as before this change.
        assertArrayEquals(fullRebuildAt(512, zfp), manager.getBackgroundTilemapData());
    }

    @Test
    public void loopBandWindowShiftsAreByteIdenticalToFullRebuild() {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setCurrentBgPeriodWidth(PERIOD_PX);
        manager.setBgLoopBandBaseY(0);
        manager.setBgTilemapBaseX(496);
        ensure(manager, zfp);
        assertEquals(LevelTilemapManager.BG_LOOP_BAND_HEIGHT_PX / Pattern.PATTERN_HEIGHT,
                manager.getBackgroundTilemapHeightTiles());

        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(1, manager.bgIncrementalShiftCount);

        LevelTilemapManager reference = new LevelTilemapManager(geometry, graphicsManager, null);
        reference.setCurrentBgPeriodWidth(PERIOD_PX);
        reference.setBgLoopBandBaseY(0);
        reference.setBgTilemapBaseX(512);
        ensure(reference, zfp);
        assertArrayEquals(reference.getBackgroundTilemapData(), manager.getBackgroundTilemapData());
    }

    // ── Texture upload contract ─────────────────────────────────────────────

    @Test
    public void incrementalShiftRegistersOnlyEnteringColumnsForUpload() throws Exception {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        injectRenderer(renderer);
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);

        LevelTilemapManager manager = newManager(496, zfp);
        int fullBytes = manager.getBackgroundTilemapData().length;
        byte[] physicalTexture = new byte[fullBytes];
        assertEquals(fullBytes, renderer.getPendingBackgroundUploadBytes());
        assertEquals(1, renderer.getPendingBackgroundUploadCallCount());
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        assertPhysicalTextureMatchesLogical(renderer, manager, physicalTexture);

        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(1, manager.bgIncrementalShiftCount, "shift path must engage");
        int columnBytes = 2 * manager.getBackgroundTilemapHeightTiles() * 4;
        assertEquals(columnBytes, renderer.getPendingBackgroundUploadBytes());
        assertEquals(1, renderer.getPendingBackgroundUploadCallCount());
        assertEquals(2, renderer.getBackgroundRingBaseTiles());
        assertLogicalToPhysicalMapping(renderer, manager.getBackgroundTilemapWidthTiles());
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        assertPhysicalTextureMatchesLogical(renderer, manager, physicalTexture);

        manager.requestBgWindowBaseX(496);
        ensure(manager, zfp);
        assertEquals(2, manager.bgIncrementalShiftCount);
        assertEquals(columnBytes, renderer.getPendingBackgroundUploadBytes());
        assertEquals(1, renderer.getPendingBackgroundUploadCallCount());
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
        assertLogicalToPhysicalMapping(renderer, manager.getBackgroundTilemapWidthTiles());
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);
        assertPhysicalTextureMatchesLogical(renderer, manager, physicalTexture);
    }

    @Test
    public void physicalRingWrapsWithoutChangingLogicalPixelOrder() throws Exception {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        injectRenderer(renderer);
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(0, zfp);
        byte[] physicalTexture = new byte[manager.getBackgroundTilemapData().length];
        renderer.applyPendingBackgroundUploadForTest(physicalTexture);

        int width = manager.getBackgroundTilemapWidthTiles();
        for (int base = 16; base <= width * 8; base += 16) {
            manager.requestBgWindowBaseX(base);
            ensure(manager, zfp);
            assertEquals(2 * manager.getBackgroundTilemapHeightTiles() * 4,
                    renderer.getPendingBackgroundUploadBytes());
            assertLogicalToPhysicalMapping(renderer, width);
            renderer.applyPendingBackgroundUploadForTest(physicalTexture);
            assertPhysicalTextureMatchesLogical(renderer, manager, physicalTexture);
        }
        assertEquals(0, renderer.getBackgroundRingBaseTiles(), "physical ring must wrap at texture width");
    }

    @Test
    public void zeroShiftDoesNothingAndMultiColumnJumpForcesFullUpload() throws Exception {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        injectRenderer(renderer);
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);
        int fullBytes = manager.getBackgroundTilemapData().length;
        renderer.consumePendingBackgroundUploadForTest();

        manager.requestBgWindowBaseX(496);
        ensure(manager, zfp);
        assertEquals(0, renderer.getPendingBackgroundUploadBytes());
        assertEquals(0, renderer.getPendingBackgroundUploadCallCount());

        manager.requestBgWindowBaseX(560);
        ensure(manager, zfp);
        assertEquals(fullBytes, renderer.getPendingBackgroundUploadBytes());
        assertEquals(1, renderer.getPendingBackgroundUploadCallCount());
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
    }

    @Test
    public void invalidationAfterIncrementalShiftForcesFullUploadAndResetsRing() throws Exception {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        injectRenderer(renderer);
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);
        renderer.consumePendingBackgroundUploadForTest();
        manager.requestBgWindowBaseX(512);
        ensure(manager, zfp);
        assertEquals(2, renderer.getBackgroundRingBaseTiles());
        renderer.consumePendingBackgroundUploadForTest();

        manager.invalidateAllTilemaps();
        manager.requestBgWindowBaseX(528);
        ensure(manager, zfp);
        assertEquals(manager.getBackgroundTilemapData().length, renderer.getPendingBackgroundUploadBytes());
        assertEquals(1, renderer.getPendingBackgroundUploadCallCount());
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
    }

    @Test
    public void multiplePendingShiftsAndExplicitInvalidationCoalesceToFullUpload() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] first = syntheticRow(8, 0);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, first, 8, 1);
        byte[] physical = new byte[first.length];
        renderer.applyPendingBackgroundUploadForTest(physical);

        byte[] second = syntheticRow(8, 10);
        renderer.setBackgroundTilemapDataIncremental(second, 8, 1, 2);
        assertEquals(8, renderer.getPendingBackgroundUploadBytes());
        byte[] third = syntheticRow(8, 20);
        renderer.setBackgroundTilemapDataIncremental(third, 8, 1, 2);
        assertEquals(third.length, renderer.getPendingBackgroundUploadBytes(),
                "a second mutation before the GL consumer runs must conservatively escalate to full");
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
        renderer.applyPendingBackgroundUploadForTest(physical);
        assertArrayEquals(third, physical);

        renderer.setBackgroundTilemapDataIncremental(syntheticRow(8, 30), 8, 1, -2);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, first, 8, 1);
        assertEquals(first.length, renderer.getPendingBackgroundUploadBytes());
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
    }

    @Test
    public void wrappedPhysicalSubrectIsSplitIntoTwoRowSafeUploads() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] physical = new byte[5 * 2 * 4];
        byte[] logical = syntheticRows(5, 2, 0);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, logical, 5, 2);
        renderer.applyPendingBackgroundUploadForTest(physical);

        logical = shiftSyntheticRows(logical, 5, 2, 4, 40);
        renderer.setBackgroundTilemapDataIncremental(logical, 5, 2, 4);
        renderer.applyPendingBackgroundUploadForTest(physical);
        assertEquals(4, renderer.getBackgroundRingBaseTiles());

        logical = shiftSyntheticRows(logical, 5, 2, 2, 80);
        renderer.setBackgroundTilemapDataIncremental(logical, 5, 2, 2);
        assertEquals(2, renderer.getPendingBackgroundUploadCallCount(),
                "destination columns 4 and 0 require two GL subrect uploads");
        renderer.applyPendingBackgroundUploadForTest(physical);
        assertSyntheticPhysicalMatchesLogical(renderer, logical, physical, 5, 2);
    }

    @Test
    public void simultaneousTwoAxisShiftsPreserveEveryPhysicalDescriptorAcrossWraps() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        RecordingPhysicalTilemapTexture texture = new RecordingPhysicalTilemapTexture(64, 32);
        byte[] logical = descriptorGrid(64, 32, 0x1000);
        try {
            renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, logical, 64, 32);
            assertTrue(renderer.applyPendingBackgroundUploadForTest(texture));
            assertTwoAxisPhysicalTextureMatchesLogical(renderer, logical, texture.physical, 64, 32);

            logical = shiftDescriptorGrid(logical, 64, 32, 2, -2, 0x2000);
            texture.clearOperationOrder();
            renderer.setBackgroundTilemapDataIncremental(logical, 64, 32, 2, -2);
            assertEquals(2, renderer.getBackgroundRingBaseXTiles());
            assertEquals(30, renderer.getBackgroundRingBaseYTiles());
            assertEquals(2, renderer.getPendingBackgroundColumnCount());
            assertEquals(2, renderer.getPendingBackgroundRowCount());
            assertTrue(renderer.applyPendingBackgroundUploadForTest(texture));
            assertEquals(List.of("columns", "rows"), texture.operationOrder);
            assertTwoAxisPhysicalTextureMatchesLogical(renderer, logical, texture.physical, 64, 32);

            logical = shiftDescriptorGrid(logical, 64, 32, -3, 3, 0x3000);
            texture.clearOperationOrder();
            renderer.setBackgroundTilemapDataIncremental(logical, 64, 32, -3, 3);
            assertEquals(63, renderer.getBackgroundRingBaseXTiles());
            assertEquals(1, renderer.getBackgroundRingBaseYTiles());
            assertTrue(renderer.applyPendingBackgroundUploadForTest(texture));
            assertEquals(List.of("columns", "columns", "rows", "rows"), texture.operationOrder,
                    "both wrapped destination spans must split, with rows applied after columns");
            assertTwoAxisPhysicalTextureMatchesLogical(renderer, logical, texture.physical, 64, 32);
            assertDescriptorEquals(logical, 64, 0, 29, texture.physical, 64, 63, 30,
                    "the wrapped entering-row/column intersection must remain coherent");
        } finally {
            texture.cleanup();
        }
    }

    @Test
    public void retainedDrawResolvesBothRingOriginsWithOneContentGeneration() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] initial = syntheticRows(8, 4, 0);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, initial, 8, 4);
        renderer.applyPendingBackgroundUploadForTest(new byte[initial.length]);
        renderer.setBackgroundTilemapDataIncremental(syntheticRows(8, 4, 30), 8, 4, 2, -1);

        TilemapGpuRenderer.BackgroundRenderState captured =
                renderer.captureBackgroundRenderState();
        assertEquals(2, captured.ringBaseXTiles());
        assertEquals(3, captured.ringBaseYTiles());

        byte[] newest = syntheticRows(8, 4, 60);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, newest, 8, 4);
        renderer.setBackgroundRenderRingBaseOverride(
                captured.ringBaseXTiles(), captured.ringBaseYTiles(), captured.contentGeneration());
        renderer.render(
                TilemapGpuRenderer.Layer.BACKGROUND,
                8, 8, 0, 0, 8, 8,
                0, 0, 1, 1, 0, 0, 0,
                -1, false, false, false, 0);

        assertEquals(newest.length, renderer.getPendingBackgroundUploadBytes(),
                "a stale retained draw must not consume the newer texture generation");
        assertTrue(!renderer.hasPendingOneShotRenderState(),
                "the X/Y/generation override must be consumed as one draw token");
    }

    @Test
    public void retainedFrameCommandCannotPairOldRingBaseWithNewTextureGeneration() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] first = syntheticRow(8, 0);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, first, 8, 1);
        int capturedBase = renderer.getBackgroundRingBaseTiles();
        int capturedGeneration = renderer.getBackgroundContentGeneration();

        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, syntheticRow(8, 30), 8, 1);
        renderer.setBackgroundRenderRingBaseOverride(capturedBase, capturedGeneration);
        renderer.render(
                TilemapGpuRenderer.Layer.BACKGROUND,
                8, 8, 0, 0, 8, 8,
                0, 0, 1, 1, 0, 0, 0,
                -1, false, false, false, 0);
        assertEquals(first.length, renderer.getPendingBackgroundUploadBytes(),
                "stale command must leave the newest upload pending");

        byte[] physical = new byte[first.length];
        renderer.applyPendingBackgroundUploadForTest(physical);
        assertArrayEquals(syntheticRow(8, 30), physical,
                "the matching newest consumer must still receive the coherent full payload");
    }

    @Test
    public void lostTextureStorageEscalatesPendingPartialToCanonicalFullUpload() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] initial = syntheticRow(8, 0);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, initial, 8, 1);
        renderer.applyPendingBackgroundUploadForTest(new byte[initial.length]);

        byte[] shifted = shiftSyntheticRows(initial, 8, 1, 2, 50);
        renderer.setBackgroundTilemapDataIncremental(shifted, 8, 1, 2);
        assertEquals(8, renderer.getPendingBackgroundUploadBytes());

        // No GL storage exists in this headless renderer. The attempted draw
        // must retain work as a canonical full upload instead of dropping it.
        renderer.render(TilemapGpuRenderer.Layer.BACKGROUND,
                8, 8, 0, 0, 8, 8,
                0, 0, 1, 1, 0, 0, 0,
                -1, false, false, false, 0);
        assertEquals(shifted.length, renderer.getPendingBackgroundUploadBytes());
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
        byte[] physical = new byte[shifted.length];
        renderer.applyPendingBackgroundUploadForTest(physical);
        assertArrayEquals(shifted, physical);
    }

    @Test
    public void staleDrawClearsOneShotScrollAndBandState() {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] initial = syntheticRow(8, 0);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, initial, 8, 1);
        int oldGeneration = renderer.getBackgroundContentGeneration();
        renderer.enablePerLineScroll(7, 224, 64, 3, 5);
        renderer.enablePerColumnVScroll(new short[] {1});
        renderer.setUpperBandWrap(64, 16);
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, syntheticRow(8, 20), 8, 1);
        renderer.setBackgroundRenderRingBaseOverride(0, oldGeneration);

        renderer.render(TilemapGpuRenderer.Layer.BACKGROUND,
                8, 8, 0, 0, 8, 8,
                0, 0, 1, 1, 0, 0, 0,
                -1, false, false, false, 0);
        assertTrue(!renderer.hasPendingOneShotRenderState(),
                "stale early return must not leak per-line/band uniforms into the next draw");
    }

    @Test
    public void rendererCleanupUnderCleanManagerRepublishesCanonicalFullBaseline() throws Exception {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        injectRenderer(renderer);
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(496, zfp);
        byte[] canonical = manager.getBackgroundTilemapData();
        renderer.applyPendingBackgroundUploadForTest(new byte[canonical.length]);

        renderer.cleanup();
        ensure(manager, zfp);
        assertEquals(canonical.length, renderer.getPendingBackgroundUploadBytes());
        assertEquals(1, renderer.getPendingBackgroundUploadCallCount());
        assertEquals(0, renderer.getBackgroundRingBaseTiles());
        assertTrue(renderer.hasBackgroundBaseline(canonical,
                manager.getBackgroundTilemapWidthTiles(), manager.getBackgroundTilemapHeightTiles()));
    }

    @Test
    public void longForwardReverseAndAlternatingSequencePreservesPhysicalTexture() throws Exception {
        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        injectRenderer(renderer);
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);
        LevelTilemapManager manager = newManager(0, zfp);
        byte[] physical = new byte[manager.getBackgroundTilemapData().length];
        renderer.applyPendingBackgroundUploadForTest(physical);

        for (int base = 16; base <= 320; base += 16) {
            manager.requestBgWindowBaseX(base);
            ensure(manager, zfp);
            renderer.applyPendingBackgroundUploadForTest(physical);
            assertPhysicalTextureMatchesLogical(renderer, manager, physical);
        }
        for (int base = 304; base >= 0; base -= 16) {
            manager.requestBgWindowBaseX(base);
            ensure(manager, zfp);
            renderer.applyPendingBackgroundUploadForTest(physical);
            assertPhysicalTextureMatchesLogical(renderer, manager, physical);
        }
        for (int i = 0; i < 40; i++) {
            int base = (i & 1) == 0 ? 16 : 0;
            manager.requestBgWindowBaseX(base);
            ensure(manager, zfp);
            renderer.applyPendingBackgroundUploadForTest(physical);
            assertPhysicalTextureMatchesLogical(renderer, manager, physical);
        }
    }

    @Test
    public void tilemapTexturePacksRowsAndReusesNativeStagingForSubrects() {
        RecordingTilemapTexture texture = new RecordingTilemapTexture(4, 2);
        byte[] source = syntheticRows(4, 2, 0);
        try {
            assertTrue(texture.uploadColumns(source, 4, 2, 1, 2, 2));
            assertEquals(2, texture.lastDestination);
            assertEquals(2, texture.lastColumnCount);
            assertEquals(2, texture.lastHeight);
            byte[] expected = new byte[16];
            System.arraycopy(source, 4, expected, 0, 8);
            System.arraycopy(source, 20, expected, 8, 8);
            assertArrayEquals(expected, texture.lastPayload);
            ByteBuffer firstBuffer = texture.lastBuffer;

            assertTrue(texture.uploadColumns(source, 4, 2, 0, 0, 1));
            assertSame(firstBuffer, texture.lastBuffer,
                    "grow-only native staging must be reused for smaller uploads");
            int calls = texture.calls;
            assertTrue(!texture.uploadColumns(new byte[4], 4, 2, 0, 0, 1));
            assertEquals(calls, texture.calls, "short source must not issue a subrect upload");
        } finally {
            texture.cleanup();
        }
    }

    @Test
    public void tilemapTextureUploadsContiguousLogicalRowsAtRequestedPhysicalRow() {
        RecordingTilemapTexture texture = new RecordingTilemapTexture(4, 4);
        byte[] source = syntheticRows(4, 4, 0);
        try {
            assertTrue(texture.uploadRows(source, 4, 4, 1, 2, 2));
            assertEquals(2, texture.lastRowDestination);
            assertEquals(4, texture.lastRowWidth);
            assertEquals(2, texture.lastRowCount);
            byte[] expected = new byte[4 * 2 * 4];
            System.arraycopy(source, 4 * 4, expected, 0, expected.length);
            assertArrayEquals(expected, texture.lastRowPayload);
        } finally {
            texture.cleanup();
        }
    }

    @Test
    public void extremeDimensionsAreRejectedWithoutUploadsOrNegativeDiagnostics() {
        RecordingTilemapTexture texture = new RecordingTilemapTexture(
                Integer.MAX_VALUE, Integer.MAX_VALUE);
        try {
            assertTrue(!texture.uploadColumns(new byte[4],
                    Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 1));
            assertTrue(!texture.uploadRows(new byte[4],
                    Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 1));
            assertEquals(0, texture.calls);
        } finally {
            texture.cleanup();
        }

        RecordingTilemapGpuRenderer renderer = new RecordingTilemapGpuRenderer();
        byte[] tiny = new byte[4];
        renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND,
                tiny, Integer.MAX_VALUE, Integer.MAX_VALUE);
        renderer.consumePendingBackgroundUploadForTest();
        renderer.setBackgroundTilemapDataIncremental(
                tiny, Integer.MAX_VALUE, Integer.MAX_VALUE, 1, 1);
        assertEquals(tiny.length, renderer.getPendingBackgroundUploadBytes(),
                "invalid extreme dimensions must conservatively publish a non-negative full upload");
        assertEquals(0, renderer.getBackgroundRingBaseXTiles());
        assertEquals(0, renderer.getBackgroundRingBaseYTiles());
    }

    private static byte[] syntheticRow(int width, int seed) {
        return syntheticRows(width, 1, seed);
    }

    private static byte[] syntheticRows(int width, int height, int seed) {
        byte[] data = new byte[width * height * 4];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (seed + i);
        return data;
    }

    private static byte[] descriptorGrid(int width, int height, int seed) {
        byte[] data = new byte[width * height * 4];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                writeDescriptor(data, width, column, row, seed + row * width + column);
            }
        }
        return data;
    }

    private static byte[] shiftDescriptorGrid(byte[] previous, int width, int height,
            int shiftX, int shiftY, int enteringSeed) {
        byte[] shifted = new byte[previous.length];
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int previousColumn = column + shiftX;
                int previousRow = row + shiftY;
                if (previousColumn >= 0 && previousColumn < width
                        && previousRow >= 0 && previousRow < height) {
                    System.arraycopy(previous, (previousRow * width + previousColumn) * 4,
                            shifted, (row * width + column) * 4, 4);
                } else {
                    writeDescriptor(shifted, width, column, row,
                            enteringSeed + row * width + column);
                }
            }
        }
        return shifted;
    }

    private static void writeDescriptor(byte[] data, int width, int column, int row, int value) {
        int offset = (row * width + column) * 4;
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static void assertTwoAxisPhysicalTextureMatchesLogical(
            RecordingTilemapGpuRenderer renderer, byte[] logical, byte[] physical,
            int width, int height) {
        for (int logicalRow = 0; logicalRow < height; logicalRow++) {
            int physicalRow = Math.floorMod(renderer.getBackgroundRingBaseYTiles() + logicalRow,
                    height);
            for (int logicalColumn = 0; logicalColumn < width; logicalColumn++) {
                int physicalColumn = Math.floorMod(
                        renderer.getBackgroundRingBaseXTiles() + logicalColumn, width);
                assertDescriptorEquals(logical, width, logicalColumn, logicalRow,
                        physical, width, physicalColumn, physicalRow,
                        "descriptor mismatch logical=(" + logicalColumn + "," + logicalRow
                                + ") physical=(" + physicalColumn + "," + physicalRow + ")");
            }
        }
    }

    private static void assertDescriptorEquals(byte[] expected, int expectedWidth,
            int expectedColumn, int expectedRow, byte[] actual, int actualWidth,
            int actualColumn, int actualRow, String message) {
        int expectedOffset = (expectedRow * expectedWidth + expectedColumn) * 4;
        int actualOffset = (actualRow * actualWidth + actualColumn) * 4;
        for (int component = 0; component < 4; component++) {
            assertEquals(expected[expectedOffset + component], actual[actualOffset + component],
                    message + " component=" + component);
        }
    }

    private static byte[] shiftSyntheticRows(byte[] previous, int width, int height,
            int shiftColumns, int enteringSeed) {
        byte[] shifted = new byte[previous.length];
        int retained = width - shiftColumns;
        for (int row = 0; row < height; row++) {
            System.arraycopy(previous, (row * width + shiftColumns) * 4,
                    shifted, row * width * 4, retained * 4);
            for (int column = retained; column < width; column++) {
                int offset = (row * width + column) * 4;
                for (int component = 0; component < 4; component++) {
                    shifted[offset + component] = (byte) (enteringSeed + row * 16 + column * 4 + component);
                }
            }
        }
        return shifted;
    }

    private static void assertSyntheticPhysicalMatchesLogical(RecordingTilemapGpuRenderer renderer,
            byte[] logical, byte[] physical, int width, int height) {
        for (int row = 0; row < height; row++) {
            for (int column = 0; column < width; column++) {
                int physicalColumn = renderer.mapBackgroundLogicalColumnForTest(column);
                for (int component = 0; component < 4; component++) {
                    assertEquals(logical[(row * width + column) * 4 + component],
                            physical[(row * width + physicalColumn) * 4 + component]);
                }
            }
        }
    }

    private static void assertLogicalToPhysicalMapping(RecordingTilemapGpuRenderer renderer, int width) {
        for (int logical = 0; logical < width; logical++) {
            assertEquals((renderer.getBackgroundRingBaseTiles() + logical) % width,
                    renderer.mapBackgroundLogicalColumnForTest(logical));
        }
    }

    private static void assertPhysicalTextureMatchesLogical(RecordingTilemapGpuRenderer renderer,
            LevelTilemapManager manager, byte[] physicalTexture) {
        byte[] logical = manager.getBackgroundTilemapData();
        int width = manager.getBackgroundTilemapWidthTiles();
        int height = manager.getBackgroundTilemapHeightTiles();
        for (int row = 0; row < height; row++) {
            for (int logicalColumn = 0; logicalColumn < width; logicalColumn++) {
                int physicalColumn = renderer.mapBackgroundLogicalColumnForTest(logicalColumn);
                int logicalOffset = (row * width + logicalColumn) * 4;
                int physicalOffset = (row * width + physicalColumn) * 4;
                for (int component = 0; component < 4; component++) {
                    assertEquals(logical[logicalOffset + component], physicalTexture[physicalOffset + component],
                            "descriptor mismatch row=" + row + " logicalColumn=" + logicalColumn);
                }
            }
        }
    }

    // ── VDP wrap height across shifts ───────────────────────────────────────

    /**
     * The detected BG data height can change with the window position (a column
     * with art below tile row 32 scrolling in/out). The incremental path must
     * detect the same height/VDP-wrap state as a full rebuild.
     */
    @Test
    public void enteringColumnHeightChangeMatchesFullRebuildDetectedHeight() {
        useTallLevel();
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false);

        // Base 256: window covers world 256..767 — the tall column (768..895,
        // art below tile row 32) is just outside, so the 32-row VDP wrap applies.
        LevelTilemapManager manager = newManager(256, zfp);
        assertEquals(32, manager.getBackgroundVdpWrapHeightTiles(),
                "fixture sanity: no tall column in the initial window");

        // Advance one column: world 768..783 (tall) enters at the right edge.
        manager.requestBgWindowBaseX(272);
        ensure(manager, zfp);
        assertEquals(1, manager.bgIncrementalShiftCount, "shift path must engage");
        LevelTilemapManager referenceIn = referenceManagerAt(272, zfp);
        assertEquals(referenceIn.getBackgroundVdpWrapHeightTiles(),
                manager.getBackgroundVdpWrapHeightTiles());
        assertEquals(0, manager.getBackgroundVdpWrapHeightTiles(),
                "tall entering column must disable the 32-row VDP wrap, as a full rebuild would");
        assertArrayEquals(referenceIn.getBackgroundTilemapData(), manager.getBackgroundTilemapData());

        // Retreat: the tall column leaves again; detected height must shrink back.
        manager.requestBgWindowBaseX(256);
        ensure(manager, zfp);
        assertEquals(2, manager.bgIncrementalShiftCount);
        LevelTilemapManager referenceOut = referenceManagerAt(256, zfp);
        assertEquals(referenceOut.getBackgroundVdpWrapHeightTiles(),
                manager.getBackgroundVdpWrapHeightTiles());
        assertEquals(32, manager.getBackgroundVdpWrapHeightTiles());
        assertArrayEquals(referenceOut.getBackgroundTilemapData(), manager.getBackgroundTilemapData());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private LevelTilemapManager newManager(int baseX, ZoneFeatureProvider zfp) {
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setCurrentBgPeriodWidth(PERIOD_PX);
        manager.setBgTilemapBaseX(baseX);
        ensure(manager, zfp);
        assertNotNull(manager.getBackgroundTilemapData());
        return manager;
    }

    private void ensure(LevelTilemapManager manager, ZoneFeatureProvider zfp) {
        manager.ensureBackgroundTilemapData(blockLookup, zfp, 0, null, false);
    }

    private LevelTilemapManager referenceManagerAt(int baseX, ZoneFeatureProvider zfp) {
        LevelTilemapManager reference = new LevelTilemapManager(geometry, graphicsManager, null);
        reference.setCurrentBgPeriodWidth(PERIOD_PX);
        reference.setBgTilemapBaseX(baseX);
        ensure(reference, zfp);
        assertNotNull(reference.getBackgroundTilemapData());
        return reference;
    }

    private byte[] fullRebuildAt(int baseX, ZoneFeatureProvider zfp) {
        return referenceManagerAt(baseX, zfp).getBackgroundTilemapData().clone();
    }

    /** Swaps in the taller fixture (512px BG, art below tile row 32 only at block column 6). */
    private void useTallLevel() {
        level = new StubLevel(4, true);
        geometry = new LevelGeometry(level,
                BG_WIDTH_PX, 4 * BLOCK_PX,
                BG_WIDTH_PX, BG_WIDTH_PX, 4 * BLOCK_PX,
                BLOCK_PX, 8);
        blockLookup = lookupFor(level, BG_WIDTH_PX, 4 * BLOCK_PX);
    }

    private static LevelTilemapManager.BlockLookup lookupFor(StubLevel level, int widthPx, int heightPx) {
        return (layer, x, y) -> {
            int wrappedX = ((x % widthPx) + widthPx) % widthPx;
            int wrappedY = ((y % heightPx) + heightPx) % heightPx;
            int blockIndex = level.getMap().getValue(layer, wrappedX / BLOCK_PX, wrappedY / BLOCK_PX) & 0xFF;
            if (blockIndex >= level.getBlockCount()) {
                return null;
            }
            return level.getBlock(blockIndex);
        };
    }

    private void injectRenderer(TilemapGpuRenderer renderer) throws Exception {
        Field field = GraphicsManager.class.getDeclaredField("tilemapGpuRenderer");
        field.setAccessible(true);
        field.set(graphicsManager, renderer);
    }

    private static final class RecordingTilemapGpuRenderer extends TilemapGpuRenderer {
        @Override public int getPendingBackgroundUploadBytes() {
            return super.getPendingBackgroundUploadBytes();
        }
        @Override public int getPendingBackgroundUploadCallCount() {
            return super.getPendingBackgroundUploadCallCount();
        }
        @Override public int mapBackgroundLogicalColumnForTest(int logicalColumn) {
            return super.mapBackgroundLogicalColumnForTest(logicalColumn);
        }
        @Override public void consumePendingBackgroundUploadForTest() {
            super.consumePendingBackgroundUploadForTest();
        }
        @Override public void applyPendingBackgroundUploadForTest(byte[] physicalTexture) {
            super.applyPendingBackgroundUploadForTest(physicalTexture);
        }
        @Override public boolean hasPendingOneShotRenderState() {
            return super.hasPendingOneShotRenderState();
        }
        @Override protected int getPendingBackgroundColumnCount() {
            return super.getPendingBackgroundColumnCount();
        }
        @Override protected int getPendingBackgroundRowCount() {
            return super.getPendingBackgroundRowCount();
        }
        @Override protected boolean applyPendingBackgroundUploadForTest(TilemapTexture texture) {
            return super.applyPendingBackgroundUploadForTest(texture);
        }
    }

    private static final class RecordingPhysicalTilemapTexture extends TilemapTexture {
        private final int width;
        private final int height;
        private final byte[] physical;
        private final List<String> operationOrder = new java.util.ArrayList<>();

        private RecordingPhysicalTilemapTexture(int width, int height) {
            this.width = width;
            this.height = height;
            this.physical = new byte[width * height * 4];
        }

        @Override public boolean hasStorage(int widthTiles, int heightTiles) {
            return widthTiles == width && heightTiles == height;
        }

        @Override public void upload(byte[] data, int widthTiles, int heightTiles) {
            if (hasStorage(widthTiles, heightTiles)) {
                System.arraycopy(data, 0, physical, 0, physical.length);
            }
        }

        @Override protected void uploadSubImage(int destinationColumn, int columnCount,
                int heightTiles, ByteBuffer packedRows) {
            operationOrder.add("columns");
            int packedRowBytes = columnCount * 4;
            for (int row = 0; row < heightTiles; row++) {
                packedRows.get(physical, (row * width + destinationColumn) * 4, packedRowBytes);
            }
            packedRows.rewind();
        }

        @Override protected void uploadRowsSubImage(int destinationRow, int widthTiles,
                int rowCount, ByteBuffer contiguousRows) {
            operationOrder.add("rows");
            contiguousRows.get(physical, destinationRow * width * 4, widthTiles * rowCount * 4);
            contiguousRows.rewind();
        }

        private void clearOperationOrder() {
            operationOrder.clear();
        }
    }

    private static final class RecordingTilemapTexture extends TilemapTexture {
        private final int width;
        private final int height;
        private int calls;
        private int lastDestination;
        private int lastColumnCount;
        private int lastHeight;
        private byte[] lastPayload;
        private ByteBuffer lastBuffer;
        private int lastRowDestination;
        private int lastRowWidth;
        private int lastRowCount;
        private byte[] lastRowPayload;

        private RecordingTilemapTexture(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override public boolean hasStorage(int widthTiles, int heightTiles) {
            return widthTiles == width && heightTiles == height;
        }

        @Override protected void uploadSubImage(int destinationColumn, int columnCount,
                int heightTiles, ByteBuffer packedRows) {
            calls++;
            lastDestination = destinationColumn;
            lastColumnCount = columnCount;
            lastHeight = heightTiles;
            lastBuffer = packedRows;
            lastPayload = new byte[packedRows.remaining()];
            packedRows.get(lastPayload);
            packedRows.rewind();
        }

        @Override protected void uploadRowsSubImage(int destinationRow, int widthTiles,
                int rowCount, ByteBuffer contiguousRows) {
            lastRowDestination = destinationRow;
            lastRowWidth = widthTiles;
            lastRowCount = rowCount;
            lastRowPayload = new byte[contiguousRows.remaining()];
            contiguousRows.get(lastRowPayload);
            contiguousRows.rewind();
        }
    }

    // ── Stubs ───────────────────────────────────────────────────────────────

    /**
     * Synthetic level with deterministic, varied BG content: blocks/chunks/flips/
     * palettes/priority all vary with position so a wrong column is guaranteed to
     * change bytes. Includes empty cells (block 0xFF), out-of-range chunk indices,
     * and flipped chunk descriptors. In the tall variant, block rows below 256px
     * are empty except at block column 6, so the detected BG data height changes
     * with the window position.
     */
    private static final class StubLevel extends AbstractLevel {

        StubLevel(int mapHeightBlocks, boolean tallColumnVariant) {
            super(0);
            palettes = new Palette[4];
            for (int i = 0; i < 4; i++) {
                palettes[i] = new Palette();
            }
            patternCount = 64;
            patterns = new Pattern[0];

            chunkCount = 48;
            chunks = new Chunk[chunkCount];
            for (int c = 0; c < chunkCount; c++) {
                Chunk chunk = new Chunk();
                for (int py = 0; py < 2; py++) {
                    for (int px = 0; px < 2; px++) {
                        int patternIndex = (c * 4 + py * 2 + px) % 60 + 2;
                        int desc = patternIndex
                                | ((c % 4) << 13)            // palette line
                                | ((c & 1) != 0 ? 0x800 : 0)  // h flip
                                | ((c & 2) != 0 ? 0x1000 : 0) // v flip
                                | ((c % 5 == 0) ? 0x8000 : 0); // priority
                        chunk.setPatternDesc(px, py, new PatternDesc(desc));
                    }
                }
                chunks[c] = chunk;
            }

            blockCount = 24;
            blocks = new Block[blockCount];
            for (int b = 0; b < blockCount; b++) {
                Block block = new Block(8);
                for (int cy = 0; cy < 8; cy++) {
                    for (int cx = 0; cx < 8; cx++) {
                        int chunkIndex = (b * 7 + cy * 8 + cx) % 52; // some out of range (>= 48)
                        int descBits = chunkIndex
                                | (((b + cx) % 3 == 0) ? 0x400 : 0)  // x flip
                                | (((b + cy) % 4 == 0) ? 0x800 : 0); // y flip
                        block.setChunkDesc(cx, cy, new ChunkDesc(descBits));
                    }
                }
                blocks[b] = block;
            }

            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(2, MAP_WIDTH_BLOCKS, mapHeightBlocks);
            for (int my = 0; my < mapHeightBlocks; my++) {
                for (int mx = 0; mx < MAP_WIDTH_BLOCKS; mx++) {
                    int blockIndex;
                    if (tallColumnVariant && my >= 2) {
                        // Rows below 256px: empty everywhere except block column 6,
                        // so detected BG art height depends on the window position.
                        blockIndex = mx == 6 ? 1 : 0xFF;
                    } else {
                        blockIndex = (mx * 5 + my * 11) % 26; // varies per cell; some empty (>= 24)
                        if (blockIndex >= blockCount) {
                            blockIndex = 0xFF; // null block → empty chunk path
                        }
                    }
                    map.setValue(1, mx, my, (byte) blockIndex);
                }
            }
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = BG_WIDTH_PX;
            minY = 0;
            maxY = mapHeightBlocks * BLOCK_PX;
        }

        @Override
        public SolidTile getSolidTile(int index) {
            return null;
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return List.of();
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }

        @Override
        public RingSpriteSheet getRingSpriteSheet() {
            return null;
        }
    }

    /** Minimal ZoneFeatureProvider with configurable BG wrap behavior. */
    private static final class StubZoneFeatures implements ZoneFeatureProvider {
        private final boolean wraps;
        private final boolean linearOverflow;

        StubZoneFeatures(boolean wraps, boolean linearOverflow) {
            this.wraps = wraps;
            this.linearOverflow = linearOverflow;
        }

        @Override
        public boolean bgWrapsHorizontally() {
            return wraps;
        }

        @Override
        public boolean useLinearBackgroundLayoutOverflow(int zoneIndex) {
            return linearOverflow;
        }

        @Override
        public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) {
        }

        @Override
        public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        }

        @Override
        public void reset() {
        }

        @Override
        public boolean hasCollisionFeatures(int zoneIndex) {
            return false;
        }

        @Override
        public boolean hasWater(int zoneIndex) {
            return false;
        }

        @Override
        public int getWaterLevel(int zoneIndex, int actIndex) {
            return 0;
        }

        @Override
        public void render(Camera camera, int frameCounter) {
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }
    }

    private static final class MutableWrapZoneFeatures implements ZoneFeatureProvider {
        private boolean wraps;

        @Override public boolean bgWrapsHorizontally() { return wraps; }
        @Override public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) { }
        @Override public void update(AbstractPlayableSprite player, int cameraX, int zoneIndex) { }
        @Override public void reset() { wraps = false; }
        @Override public boolean hasCollisionFeatures(int zoneIndex) { return false; }
        @Override public boolean hasWater(int zoneIndex) { return false; }
        @Override public int getWaterLevel(int zoneIndex, int actIndex) { return 0; }
        @Override public void render(Camera camera, int frameCounter) { }
        @Override public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }
    }
}
