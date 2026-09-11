package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.ObjectArtProvider;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotLayoutRenderer {
    private static final int BOOTSTRAP_CAMERA_X = S3kSlotRomData.SLOT_BONUS_PLAYER_START_X - 0xA0;
    private static final int BOOTSTRAP_CAMERA_Y = S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y - 0x70;

    @Test
    void visibleCellsRetainExactRowMajorScanOrderAndValues() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = stagedBootstrapBuffers(renderer);

        S3kSlotRenderBuffers.VisibleCells cells = renderer.buildVisibleCells(buffers);

        assertArrayEquals(new int[] {
                1, 1084, 748, 1, 1108, 748, 1, 1132, 748, 1, 1156, 748, 7, 1276, 748,
                5, 1036, 844, 5, 1204, 844, 7, 964, 868, 7, 1060, 868, 7, 1180, 868,
                7, 1276, 868, 8, 1012, 916, 8, 1228, 916, 8, 988, 940, 8, 1012, 940,
                8, 1228, 940, 8, 1252, 940, 8, 964, 964, 8, 988, 964, 8, 1012, 964,
                7, 1060, 964, 7, 1180, 964, 8, 1228, 964, 8, 1252, 964, 8, 1276, 964,
                5, 1060, 988, 5, 1180, 988
        }, snapshot(cells));
    }

    @Test
    void renderBuildsVisiblePiecesFromStagedExpandedBuffers() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();

        buffers.stageViewport(BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y);
        buffers.stagePointGrid(renderer.buildPointGrid(0, BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y));

        S3kSlotRenderBuffers.VisibleCells cells = renderer.buildVisibleCells(buffers);

        assertFalse(cells.isEmpty());
        assertTrue(containsCellId(cells, 5));
        assertTrue(containsCellId(cells, 7));
        assertTrue(allCellsWithinViewport(cells));
    }

    private static S3kSlotRenderBuffers stagedBootstrapBuffers(S3kSlotLayoutRenderer renderer) {
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        buffers.stageViewport(BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y);
        buffers.stagePointGrid(renderer.buildPointGrid(0, BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y));
        return buffers;
    }

    private static int[] snapshot(S3kSlotRenderBuffers.VisibleCells cells) {
        int[] snapshot = new int[cells.size() * 3];
        for (int i = 0; i < cells.size(); i++) {
            snapshot[i * 3] = cells.cellIdAt(i);
            snapshot[i * 3 + 1] = cells.worldXAt(i);
            snapshot[i * 3 + 2] = cells.worldYAt(i);
        }
        return snapshot;
    }

    @Test
    void transientRingAnimationUsesRuntimeAnimationSlots() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();

        int expandedIndex = buffers.compactToExpandedIndex(0x21);
        int expandedRow = expandedIndex / buffers.layoutStrideBytes();
        int expandedCol = expandedIndex % buffers.layoutStrideBytes();
        buffers.stageViewport(BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y);
        buffers.stagePointGrid(renderer.buildPointGrid(0, BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y));
        buffers.startRingAnimationAt(0x21);

        renderer.tickTransientAnimations(buffers);

        assertTrue(buffers.hasActiveTransientAnimationAt(0x21));
        assertEquals(0x10, buffers.renderCellIdAt(expandedRow, expandedCol));
    }

    @Test
    void transientRingAnimationAdvancesAndFallsBackToExpandedLayoutTile() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        int expandedIndex = buffers.compactToExpandedIndex(0x21);
        int expandedRow = expandedIndex / buffers.layoutStrideBytes();
        int expandedCol = expandedIndex % buffers.layoutStrideBytes();
        buffers.expandedLayout()[expandedIndex] = 8;

        buffers.startRingAnimationAt(0x21);

        // ROM loc_4BF30 and its siblings claim the slot without touching the layout
        // byte (sonic3k.asm:99283-99300); loc_4B5C2's first sub_4B592 pass publishes
        // frames[0] because a cleared slot's countdown is 0 and `subq.b #1 / bpl`
        // falls straight through (sonic3k.asm:98420-98428). Each later step then
        // costs RING_SPARKLE_DELAY waiting passes plus the publishing pass, since
        // the reload of #5 is tested for negative, not zero.
        renderer.tickTransientAnimations(buffers);
        assertEquals(0x10, buffers.renderCellIdAt(expandedRow, expandedCol));

        int period = S3kSlotRomData.RING_SPARKLE_DELAY + 1;
        for (int i = 0; i < period; i++) {
            renderer.tickTransientAnimations(buffers);
        }
        assertEquals(0x11, buffers.renderCellIdAt(expandedRow, expandedCol));

        for (int i = 0; i < period; i++) {
            renderer.tickTransientAnimations(buffers);
        }
        assertEquals(0x12, buffers.renderCellIdAt(expandedRow, expandedCol));

        for (int i = 0; i < period; i++) {
            renderer.tickTransientAnimations(buffers);
        }
        assertEquals(0x13, buffers.renderCellIdAt(expandedRow, expandedCol));

        for (int i = 0; i < period; i++) {
            renderer.tickTransientAnimations(buffers);
        }
        assertFalse(buffers.hasActiveTransientAnimationAt(0x21));
        assertEquals(0, buffers.renderCellIdAt(expandedRow, expandedCol));
    }

    @Test
    void transientBumperAnimationUsesExactCompactLayoutIndex() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        int compactIndex = 0x22;
        int expandedIndex = buffers.compactToExpandedIndex(compactIndex);
        int expandedRow = expandedIndex / buffers.layoutStrideBytes();
        int expandedCol = expandedIndex % buffers.layoutStrideBytes();

        buffers.startBumperAnimationAt(compactIndex);

        // loc_4B5F2 (sonic3k.asm:98446-98460): the claiming branch leaves the layout
        // byte alone, the first sub_4B592 pass publishes byte_4B622[0] = $A, and the
        // reload of #1 costs one waiting pass before $B.
        renderer.tickTransientAnimations(buffers);
        assertEquals(0x0A, buffers.renderCellIdAt(expandedRow, expandedCol));

        renderer.tickTransientAnimations(buffers);
        renderer.tickTransientAnimations(buffers);
        assertEquals(0x0B, buffers.renderCellIdAt(expandedRow, expandedCol));
    }

    @Test
    void zeroAngleBuildsStable16x16PointGrid() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();

        short[] points = renderer.buildPointGrid(0, 0, 0);

        assertEquals(16 * 16 * 2, points.length);
        assertArrayEquals(new short[] {(short) -0xB4, (short) -0xB4}, new short[] {points[0], points[1]});
        assertArrayEquals(new short[] {(short) -0x9C, (short) -0xB4}, new short[] {points[2], points[3]});
        int secondRowIndex = 16 * 2;
        assertArrayEquals(new short[] {(short) -0xB4, (short) -0x9C},
                new short[] {points[secondRowIndex], points[secondRowIndex + 1]});
    }

    @Test
    void quarterTurnRotatesGridBasisClockwise() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();

        short[] points = renderer.buildPointGrid(0x40, 0, 0);

        assertArrayEquals(new short[] {(short) 0xB4, (short) -0xB4}, new short[] {points[0], points[1]});
        assertArrayEquals(new short[] {(short) 0xB4, (short) -0x9C}, new short[] {points[2], points[3]});
        int secondRowIndex = 16 * 2;
        assertArrayEquals(new short[] {(short) 0x9C, (short) -0xB4},
                new short[] {points[secondRowIndex], points[secondRowIndex + 1]});
    }

    @Test
    void transformStagePointUsesSameLayoutTransformAsVisibleGrid() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();

        S3kSlotLayoutRenderer.TransformedStagePoint point = renderer.transformStagePoint(
                0,
                BOOTSTRAP_CAMERA_X,
                BOOTSTRAP_CAMERA_Y,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X,
                S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y);

        assertEquals(0x44C, point.worldX());
        assertEquals(0x3EC, point.worldY());
        assertEquals(0x10C, point.screenX());
        assertEquals(0x17C, point.screenY());
    }

    @Test
    void visibleCellsIncludeSemanticSlotStagePieces() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();

        buffers.stageViewport(BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y);
        buffers.stagePointGrid(renderer.buildPointGrid(0, BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y));

        S3kSlotRenderBuffers.VisibleCells cells = renderer.buildVisibleCells(buffers);

        assertFalse(cells.isEmpty());
        assertTrue(containsCellId(cells, 5));
        assertTrue(containsCellId(cells, 7));
        assertTrue(containsCellId(cells, 8));
        assertTrue(allCellsWithinViewport(cells));
    }

    @Test
    void renderVisibleCellsUsesWorldCoordinates() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        RecordingRenderer recordingRenderer = new RecordingRenderer();
        ObjectRenderManager renderManager = new ObjectRenderManager(
                new StubObjectArtProvider(recordingRenderer, com.openggf.game.sonic3k.Sonic3kObjectArtKeys.SLOT_COLORED_WALL));

        renderer.renderVisibleCells(
                singleVisibleCell(0x01, 0x450, 0x390),
                new StubCamera(0x460, 0x430),
                renderManager);

        assertEquals(1, recordingRenderer.drawCount);
        assertEquals(0x450, recordingRenderer.lastX);
        assertEquals(0x390, recordingRenderer.lastY);
    }

    @Test
    void coloredWallsUseAngleDrivenFrameOverride() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        RecordingRenderer recordingRenderer = new RecordingRenderer();
        ObjectRenderManager renderManager = new ObjectRenderManager(
                new StubObjectArtProvider(recordingRenderer, com.openggf.game.sonic3k.Sonic3kObjectArtKeys.SLOT_COLORED_WALL));

        renderer.updateAnimations(0x1C);
        renderer.renderVisibleCells(
                singleVisibleCell(0x01, 0x450, 0x390),
                new StubCamera(0x460, 0x430),
                renderManager);

        assertEquals(1, recordingRenderer.drawCount);
        assertEquals(7, recordingRenderer.lastFrameIndex);
    }

    @Test
    void coloredWallsDoNotForceSinglePaletteOverride() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        RecordingRenderer recordingRenderer = new RecordingRenderer();
        ObjectRenderManager renderManager = new ObjectRenderManager(
                new StubObjectArtProvider(recordingRenderer, com.openggf.game.sonic3k.Sonic3kObjectArtKeys.SLOT_COLORED_WALL));

        renderer.renderVisibleCells(
                singleVisibleCell(0x01, 0x450, 0x390),
                new StubCamera(0x460, 0x430),
                renderManager);

        assertEquals(1, recordingRenderer.drawCount);
        assertEquals(Integer.MIN_VALUE, recordingRenderer.lastPaletteOverride);
        assertEquals(3, recordingRenderer.lastPaletteBase);
    }

    @Test
    void slotStageRingsUseLiveRingRotationFrame() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        RecordingRenderer recordingRenderer = new RecordingRenderer();
        ObjectRenderManager renderManager = new ObjectRenderManager(
                new StubObjectArtProvider(recordingRenderer, com.openggf.game.sonic3k.Sonic3kObjectArtKeys.SLOT_RING_STAGE));

        for (int i = 0; i < 8; i++) {
            renderer.updateAnimations(0);
        }
        renderer.renderVisibleCells(
                singleVisibleCell(0x08, 0x450, 0x390),
                new StubCamera(0x460, 0x430),
                renderManager);

        assertEquals(1, recordingRenderer.drawCount);
        assertEquals(1, recordingRenderer.lastFrameIndex);
    }

    private static final class StubCamera extends com.openggf.camera.Camera {
        private StubCamera(int x, int y) {
            setX((short) x);
            setY((short) y);
        }
    }

    private static S3kSlotRenderBuffers.VisibleCells singleVisibleCell(int cellId, int worldX, int worldY) {
        S3kSlotRenderBuffers.VisibleCells cells = new S3kSlotRenderBuffers.VisibleCells(1);
        cells.add(cellId, worldX, worldY);
        return cells;
    }

    private static boolean containsCellId(S3kSlotRenderBuffers.VisibleCells cells, int cellId) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.cellIdAt(i) == cellId) {
                return true;
            }
        }
        return false;
    }

    private static boolean allCellsWithinViewport(S3kSlotRenderBuffers.VisibleCells cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (cells.worldXAt(i) < BOOTSTRAP_CAMERA_X - 0x10
                    || cells.worldXAt(i) >= BOOTSTRAP_CAMERA_X + 0x150
                    || cells.worldYAt(i) < BOOTSTRAP_CAMERA_Y - 0x10
                    || cells.worldYAt(i) >= BOOTSTRAP_CAMERA_Y + 0xF0) {
                return false;
            }
        }
        return true;
    }

    private static final class StubObjectArtProvider implements ObjectArtProvider {
        private final PatternSpriteRenderer renderer;
        private final String artKey;

        private StubObjectArtProvider(PatternSpriteRenderer renderer, String artKey) {
            this.renderer = renderer;
            this.artKey = artKey;
        }

        @Override
        public void loadArtForZone(int zoneIndex) {
        }

        @Override
        public PatternSpriteRenderer getRenderer(String key) {
            return artKey.equals(key) ? renderer : null;
        }

        @Override
        public ObjectSpriteSheet getSheet(String key) {
            return null;
        }

        @Override
        public com.openggf.sprites.animation.SpriteAnimationSet getAnimations(String key) {
            return null;
        }

        @Override
        public int getZoneData(String key, int zoneIndex) {
            return -1;
        }

        @Override
        public Pattern[] getHudDigitPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudTextPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesPatterns() {
            return new Pattern[0];
        }

        @Override
        public Pattern[] getHudLivesNumbers() {
            return new Pattern[0];
        }

        @Override
        public List<String> getRendererKeys() {
            return List.of(artKey);
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean isReady() {
            return true;
        }
    }

    private static final class RecordingRenderer extends PatternSpriteRenderer {
        private int drawCount;
        private int lastFrameIndex;
        private int lastX;
        private int lastY;
        private int lastPaletteOverride = Integer.MIN_VALUE;
        private int lastPaletteBase = Integer.MIN_VALUE;

        private RecordingRenderer() {
            super(dummySheet());
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void drawFrameIndex(int frameIndex, int originX, int originY, boolean hFlip, boolean vFlip, int paletteOverride) {
            drawCount++;
            lastFrameIndex = frameIndex;
            lastX = originX;
            lastY = originY;
            lastPaletteOverride = paletteOverride;
        }

        @Override
        public void drawFrameIndexWithPaletteBase(int frameIndex, int originX, int originY,
                boolean hFlip, boolean vFlip, int paletteBase) {
            drawCount++;
            lastFrameIndex = frameIndex;
            lastX = originX;
            lastY = originY;
            lastPaletteBase = paletteBase;
        }

        private static ObjectSpriteSheet dummySheet() {
            Pattern[] patterns = {new Pattern()};
            SpriteMappingPiece piece = new SpriteMappingPiece(0, 0, 1, 1, 0, false, false, 0, false);
            return new ObjectSpriteSheet(patterns, List.of(new SpriteMappingFrame(List.of(piece))), 0, 1);
        }
    }
}
