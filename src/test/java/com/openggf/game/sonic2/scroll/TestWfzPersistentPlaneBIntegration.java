package com.openggf.game.sonic2.scroll;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.rewind.snapshot.LevelTilemapSnapshot;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.runtime.WfzRuntimeState;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelGeometry;
import com.openggf.level.LevelTilemapManager;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.level.scroll.M68KMath;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestWfzPersistentPlaneBIntegration {
    private static final int BLOCK_PX = 128;
    private static final int MAP_WIDTH_BLOCKS = 8;
    private static final int MAP_HEIGHT_BLOCKS = 4;
    private static final int MAP_WIDTH_PX = MAP_WIDTH_BLOCKS * BLOCK_PX;
    private static final int MAP_HEIGHT_PX = MAP_HEIGHT_BLOCKS * BLOCK_PX;

    private GraphicsManager graphicsManager;

    @BeforeEach
    void setUp() {
        GraphicsManager.destroyForReinit();
        TestEnvironment.resetAll();
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GraphicsManager.destroyForReinit();
    }

    @Test
    void cloudAccumulatorChangesDoNotMoveOrCorruptPhysicalRingResidency() throws IOException {
        BackgroundCamera fallback = new BackgroundCamera();
        SwScrlWfz handler = new SwScrlWfz(
                new ParallaxTables(TestEnvironment.currentRom()), fallback);
        GameServices.zoneRuntimeRegistry().install(new FixedWfzState(0x200, 0x2AE3));
        FixtureLevel level = new FixtureLevel();
        LevelGeometry geometry = new LevelGeometry(level,
                MAP_WIDTH_PX, MAP_HEIGHT_PX,
                MAP_WIDTH_PX, MAP_WIDTH_PX, MAP_HEIGHT_PX,
                BLOCK_PX, 8);
        LevelTilemapManager manager = new LevelTilemapManager(geometry, graphicsManager, null);
        ZoneFeatureProvider zoneFeatures = new WrappingZoneFeatures();
        LevelTilemapManager.BlockLookup blockLookup = (layer, x, y) -> {
            int wrappedX = Math.floorMod(x, MAP_WIDTH_PX);
            int wrappedY = Math.floorMod(y, MAP_HEIGHT_PX);
            int blockIndex = level.getMap().getValue(layer,
                    wrappedX / BLOCK_PX, wrappedY / BLOCK_PX) & 0xFF;
            return level.getBlock(blockIndex);
        };

        int[] initialHscroll = new int[M68KMath.VISIBLE_LINES];
        handler.update(initialHscroll, 0x2C00, 0, 0, 0);
        ensureWfzPlaneB(manager, blockLookup, zoneFeatures, handler);
        LevelTilemapSnapshot initialSnapshot = manager.capturePersistentBgNametableSnapshot();

        int[] advancedCloudHscroll = new int[M68KMath.VISIBLE_LINES];
        handler.update(advancedCloudHscroll, 0x2C00, 0, 0x35FF, 0);
        ensureWfzPlaneB(manager, blockLookup, zoneFeatures, handler);

        assertFalse(Arrays.equals(initialHscroll, advancedCloudHscroll),
                "the frame-derived WFZ cloud scroll words must actually advance");
        LevelTilemapSnapshot advancedSnapshot = manager.capturePersistentBgNametableSnapshot();
        assertTrue(initialSnapshot.baselineValid(),
                "WFZ must seed the generic retained Plane-B ring");
        assertArrayEquals(initialSnapshot.descriptors(), advancedSnapshot.descriptors(),
                "cloud HScroll changes must not alter retained Plane-B cells");
        assertEquals(initialSnapshot.originXTiles(), advancedSnapshot.originXTiles());
        assertEquals(initialSnapshot.originYTiles(), advancedSnapshot.originYTiles());
        assertEquals(initialSnapshot.alignedBgX(), advancedSnapshot.alignedBgX());
        assertEquals(initialSnapshot.alignedBgY(), advancedSnapshot.alignedBgY());
    }

    private static void ensureWfzPlaneB(LevelTilemapManager manager,
                                        LevelTilemapManager.BlockLookup blockLookup,
                                        ZoneFeatureProvider zoneFeatures,
                                        SwScrlWfz handler) {
        manager.ensureBackgroundTilemapData(blockLookup, zoneFeatures, 0, null,
                handler.getBgTilemapUpdateMode(), handler.getBgCameraX(),
                handler.getVscrollFactorBG(), false);
    }

    private record FixedWfzState(int bgVscrollFactor, int bgXPos) implements WfzRuntimeState {
        @Override public int zoneIndex() { return 9; }
        @Override public int actIndex() { return 0; }
    }

    private static final class FixtureLevel extends AbstractLevel {
        private FixtureLevel() {
            super(0);
            palettes = new Palette[] { new Palette(), new Palette(), new Palette(), new Palette() };
            patternCount = 2048;
            patterns = new Pattern[0];
            chunkCount = 1024;
            chunks = new Chunk[chunkCount];
            for (int index = 0; index < chunkCount; index++) {
                Chunk chunk = new Chunk();
                for (int y = 0; y < 2; y++) {
                    for (int x = 0; x < 2; x++) {
                        int pattern = 2 + Math.floorMod(index * 4 + y * 2 + x, 1800);
                        chunk.setPatternDesc(x, y, new PatternDesc(pattern));
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
                        block.setChunkDesc(x, y,
                                new ChunkDesc(Math.floorMod(blockIndex * 61 + y * 8 + x, chunkCount)));
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
        @Override public boolean bgWrapsHorizontally() { return true; }
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
