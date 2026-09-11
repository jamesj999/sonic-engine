package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies rewind-friendly tilemap behavior on {@link LevelTilemapManager}.
 * <p>
 * After a rewind restore the BG incremental-shift baseline is stale (the retained
 * bytes reflect the discarded forward window), so the restore hook must invalidate
 * it for a full rebuild. The foreground needs no rewind invalidation: a flat FG
 * tilemap is a pure function of the static layout, and the AIZ2 FG ship-loop
 * "ring" self-heals via the bidirectional window reconcile — every chunk column
 * entering the visible window (on either edge) is refilled from the flat layout on
 * entry, and camera jumps of at least the ring width degenerate to a full re-seed.
 * Held rewind fires the restore hook on every backward step, so anything it
 * invalidates is rebuilt every rewind frame.
 * <p>
 * Headless: uses {@code GraphicsManager.initHeadless()} (no OpenGL, no GPU upload)
 * and a synthetic {@link StubLevel} (no ROM). Asserts private latch fields via
 * reflection because they are the contract the integrator relies on.
 */
public class TestLevelTilemapManagerRewindReset {

    private static final int BLOCK_PX = 128;
    private static final int MAP_WIDTH_BLOCKS = 16;
    private static final int MAP_HEIGHT_BLOCKS = 2;
    private static final int BG_WIDTH_PX = MAP_WIDTH_BLOCKS * BLOCK_PX;
    private static final int BG_HEIGHT_PX = MAP_HEIGHT_BLOCKS * BLOCK_PX;
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

        level = new StubLevel(MAP_HEIGHT_BLOCKS);
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

    // ── BG incremental-shift baseline ─────────────────────────────────────────

    @Test
    public void resetBgIncrementalShiftBaselineInvalidatesBuildSnapshotAndForcesFullRebuild()
            throws Exception {
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false, false);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setCurrentBgPeriodWidth(PERIOD_PX);
        manager.setBgTilemapBaseX(496);
        manager.ensureBackgroundTilemapData(blockLookup, zfp, 0, null, false);
        assertNotNull(manager.getBackgroundTilemapData());

        // A clean window step would normally take the cheap incremental shift path.
        manager.requestBgWindowBaseX(512);
        assertTrue(getBoolean(manager, "bgWindowShiftCandidate"),
                "fixture sanity: a clean window step is a shift candidate");
        assertTrue(getBoolean(manager, "bgLastBuildValid"),
                "fixture sanity: full build recorded a valid snapshot");

        // Rewind restore: the retained bytes reflect the discarded forward window.
        manager.resetBgIncrementalShiftBaseline();

        assertFalse(getBoolean(manager, "bgLastBuildValid"),
                "shift snapshot must be invalidated");
        assertFalse(getBoolean(manager, "bgWindowShiftCandidate"),
                "shift candidacy must be cleared so the next build is a full rebuild");
        assertTrue(manager.isBackgroundTilemapDirty(),
                "background tilemap must be marked dirty");

        // Next ensure must take the FULL rebuild path, not the incremental shift.
        int fullBefore = manager.bgFullRebuildCount;
        int shiftBefore = manager.bgIncrementalShiftCount;
        manager.ensureBackgroundTilemapData(blockLookup, zfp, 0, null, false);
        assertEquals(fullBefore + 1, manager.bgFullRebuildCount,
                "post-reset ensure must full-rebuild");
        assertEquals(shiftBefore, manager.bgIncrementalShiftCount,
                "post-reset ensure must NOT take the incremental shift path");
    }

    // ── FG ring window reconcile (bidirectional fill) ─────────────────────────

    @Test
    public void rewindRestoreHookLeavesFlatForegroundTilemapClean() throws Exception {
        // Normal zone: no FG horizontal wrap, so the flat FG tilemap is a pure
        // function of the static layout (camera-independent). Held rewind fires
        // the restore hook on EVERY backward step; it must not dirty the
        // tilemap, or every rewind frame pays a full-level FG rebuild + GPU
        // upload (the render.fg rewind hotspot).
        ZoneFeatureProvider zfp = new StubZoneFeatures(false, false, false);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        assertNotNull(manager.getForegroundTilemapData());
        assertFalse(manager.isForegroundTilemapDirty(),
                "fixture sanity: the flat build clears the dirty flag");

        manager.resetTilemapsForRewindRestore();

        assertFalse(manager.isForegroundTilemapDirty(),
                "a rewind restore must not force a full FG rebuild");
        assertEquals(Boolean.FALSE, getField(manager, "lastForegroundWrap"),
                "wrap-state latch must be retained so wrap-transition detection still works");
    }

    @Test
    public void backwardCameraScrollRefillsEnteringLeftColumns() throws Exception {
        // Held rewind retreats the camera a few px per frame. Columns entering the
        // visible window at the LEFT edge hold content from one ring-lap ahead
        // (written when the leading edge passed worldX+$200 on the way forward)
        // and must be refilled from the flat layout on entry — with no rewind
        // reset / full rebuild involved.
        ZoneFeatureProvider zfp = new StubZoneFeatures(false, false, true);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setForegroundRingCamera(800, 320);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);
        assertTrue(getBoolean(manager, "foregroundRingSeeded"));

        // Forward play far enough that the ring laps: cells for the 800-window
        // get overwritten with next-lap forest columns.
        for (int x = 816; x <= 1200; x += 16) {
            manager.setForegroundRingCamera(x, 320);
            manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);
        }
        // Held rewind: retreat in sub-chunk steps back to 800.
        for (int x = 1192; x >= 800; x -= 8) {
            manager.setForegroundRingCamera(x, 320);
            manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);
        }

        assertVisibleRingColumnsMatchFreshSeed(manager, zfp, 800, 320);
    }

    @Test
    public void largeBackwardCameraJumpReseedsVisibleWindow() throws Exception {
        // A rewind seek can teleport the camera arbitrarily far back in one frame.
        // A jump at/beyond the ring width cannot be filled incrementally (every
        // cell is stale) and must degenerate to a full re-seed.
        ZoneFeatureProvider zfp = new StubZoneFeatures(false, false, true);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setForegroundRingCamera(1200, 320);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        manager.setForegroundRingCamera(640, 320);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        assertVisibleRingColumnsMatchFreshSeed(manager, zfp, 640, 320);
    }

    @Test
    public void nativeWorldWrapRetainsPlaneRingInsteadOfReseedingFlatEntrance() throws Exception {
        ZoneFeatureProvider zfp = new StubZoneFeatures(false, false, true);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setForegroundRingCamera(800, 320);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        // Advance far enough that the leading edge has replaced the ring cell at
        // local X=$100 with the next lap's world-$500 forest column.
        for (int x = 804; x <= 1276; x += 4) {
            manager.setForegroundRingCamera(x, 320);
            manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);
        }
        byte[] beforeWrapColumn = copyChunkColumn(manager, 0x100);

        // Native Camera_X_pos step: $4FC + 4 - $200 = $300. The explicit
        // Level_repeat_offset must preserve Plane A rather than seed world $300.
        manager.setForegroundRingCamera(768, 320, 0x200);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        assertEquals(768, getInt(manager, "foregroundRingLastLeftCol"));
        assertArrayEquals(beforeWrapColumn, copyChunkColumn(manager, 0x100),
                "a native one-plane world wrap must retain non-entering ring cells");
    }

    @Test
    public void forwardCameraScrollKeepsVisibleColumnsCorrect() throws Exception {
        // Baseline: the existing forward leading-edge behavior must be preserved.
        ZoneFeatureProvider zfp = new StubZoneFeatures(false, false, true);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setForegroundRingCamera(800, 320);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        for (int x = 804; x <= 1200; x += 4) {
            manager.setForegroundRingCamera(x, 320);
            manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);
        }

        assertVisibleRingColumnsMatchFreshSeed(manager, zfp, 1200, 320);
    }

    /**
     * Asserts every ring cell displayed in the visible window {@code [cameraX,
     * cameraX + screenWidthPx)} is byte-identical to a fresh manager seeded
     * directly at that camera position (the ring is a pure function of visible
     * camera window + static layout; cells outside the window may differ).
     */
    private void assertVisibleRingColumnsMatchFreshSeed(LevelTilemapManager actual,
                                                        ZoneFeatureProvider zfp,
                                                        int cameraX, int screenWidthPx) {
        LevelTilemapManager reference = new LevelTilemapManager(geometry, graphicsManager, null);
        reference.setForegroundRingCamera(cameraX, screenWidthPx);
        reference.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        byte[] act = actual.getForegroundTilemapData();
        byte[] ref = reference.getForegroundTilemapData();
        assertNotNull(act);
        assertNotNull(ref);
        int widthTiles = reference.getForegroundTilemapWidthTiles();
        int heightTiles = reference.getForegroundTilemapHeightTiles();
        assertEquals(widthTiles, actual.getForegroundTilemapWidthTiles());
        assertEquals(heightTiles, actual.getForegroundTilemapHeightTiles());

        int firstTileWorldX = Math.floorDiv(cameraX, 8);
        int lastTileWorldX = Math.floorDiv(cameraX + screenWidthPx - 1, 8);
        for (int tileWorldX = firstTileWorldX; tileWorldX <= lastTileWorldX; tileWorldX++) {
            int cellX = Math.floorMod(tileWorldX, widthTiles);
            for (int row = 0; row < heightTiles; row++) {
                int offset = (row * widthTiles + cellX) * 4;
                for (int b = 0; b < 4; b++) {
                    assertEquals(ref[offset + b], act[offset + b],
                            "ring cell mismatch at worldTileX=" + tileWorldX
                                    + " (cell " + cellX + "), row=" + row + ", byte " + b);
                }
            }
        }
    }

    // ── Combined convenience hook ─────────────────────────────────────────────

    @Test
    public void resetTilemapsForRewindRestoreResetsBgBaselineAndLeavesRingIntact() throws Exception {
        // The FG ring self-heals via the bidirectional window reconcile (every
        // column entering view is refilled on entry, jumps >= ring width re-seed),
        // so a rewind restore must NOT force a ring rebuild — held rewind fires
        // this hook on every backward step.
        ZoneFeatureProvider zfp = new StubZoneFeatures(true, false, true);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        manager.setCurrentBgPeriodWidth(PERIOD_PX);
        manager.setBgTilemapBaseX(496);
        manager.setForegroundRingCamera(800, 320);
        manager.ensureBackgroundTilemapData(blockLookup, zfp, 0, null, false);
        manager.ensureForegroundTilemapData(blockLookup, zfp, 0, null, false);

        assertTrue(getBoolean(manager, "bgLastBuildValid"));
        assertTrue(getBoolean(manager, "foregroundRingSeeded"));

        manager.resetTilemapsForRewindRestore();

        // BG baseline reset
        assertFalse(getBoolean(manager, "bgLastBuildValid"));
        assertFalse(getBoolean(manager, "bgWindowShiftCandidate"));
        assertTrue(manager.isBackgroundTilemapDirty());
        // FG ring untouched
        assertTrue(getBoolean(manager, "foregroundRingSeeded"),
                "ring seed must survive a rewind restore");
        assertFalse(manager.isForegroundTilemapDirty(),
                "rewind restore must not force a full FG rebuild");
        assertEquals(Boolean.TRUE, getField(manager, "lastForegroundWrap"),
                "wrap-state latch must be retained");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static boolean getBoolean(Object target, String name) throws Exception {
        return (Boolean) getField(target, name);
    }

    private static int getInt(Object target, String name) throws Exception {
        return (Integer) getField(target, name);
    }

    private static byte[] copyChunkColumn(LevelTilemapManager manager, int localPixelX) {
        byte[] data = manager.getForegroundTilemapData();
        int widthTiles = manager.getForegroundTilemapWidthTiles();
        int heightTiles = manager.getForegroundTilemapHeightTiles();
        int firstTileX = localPixelX / 8;
        int tileCount = LevelConstants.CHUNK_WIDTH / 8;
        byte[] result = new byte[heightTiles * tileCount * 4];
        int out = 0;
        for (int row = 0; row < heightTiles; row++) {
            for (int tileX = firstTileX; tileX < firstTileX + tileCount; tileX++) {
                int offset = (row * widthTiles + tileX) * 4;
                System.arraycopy(data, offset, result, out, 4);
                out += 4;
            }
        }
        return result;
    }

    // ── Stubs ───────────────────────────────────────────────────────────────

    /**
     * Synthetic level with deterministic, varied content for both FG and BG layers,
     * so a wrong column or stale ring cell would change bytes. Mirrors the fixture in
     * {@code TestIncrementalBgTilemapWindow} (single map height, no tall column).
     */
    private static final class StubLevel extends AbstractLevel {

        StubLevel(int mapHeightBlocks) {
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
                                | ((c % 4) << 13)
                                | ((c & 1) != 0 ? 0x800 : 0)
                                | ((c & 2) != 0 ? 0x1000 : 0)
                                | ((c % 5 == 0) ? 0x8000 : 0);
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
                        int chunkIndex = (b * 7 + cy * 8 + cx) % 52;
                        int descBits = chunkIndex
                                | (((b + cx) % 3 == 0) ? 0x400 : 0)
                                | (((b + cy) % 4 == 0) ? 0x800 : 0);
                        block.setChunkDesc(cx, cy, new ChunkDesc(descBits));
                    }
                }
                blocks[b] = block;
            }

            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            // layer 0 (FG) and layer 1 (BG) both populated from the same map values.
            map = new Map(2, MAP_WIDTH_BLOCKS, mapHeightBlocks);
            for (int my = 0; my < mapHeightBlocks; my++) {
                for (int mx = 0; mx < MAP_WIDTH_BLOCKS; mx++) {
                    int blockIndex = (mx * 5 + my * 11) % 26;
                    if (blockIndex >= blockCount) {
                        blockIndex = 0xFF;
                    }
                    map.setValue(0, mx, my, (byte) blockIndex);
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

    /** ZoneFeatureProvider with configurable BG and FG wrap behavior. */
    private static final class StubZoneFeatures implements ZoneFeatureProvider {
        private final boolean bgWraps;
        private final boolean linearOverflow;
        private final boolean fgWraps;

        StubZoneFeatures(boolean bgWraps, boolean linearOverflow, boolean fgWraps) {
            this.bgWraps = bgWraps;
            this.linearOverflow = linearOverflow;
            this.fgWraps = fgWraps;
        }

        @Override
        public boolean bgWrapsHorizontally() {
            return bgWraps;
        }

        @Override
        public boolean foregroundWrapsHorizontally() {
            return fgWraps;
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
}
