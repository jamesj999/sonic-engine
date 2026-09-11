package com.openggf.level.rewind;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.LevelTilemapSnapshot;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.TilemapGpuRenderer;
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
import com.openggf.level.scroll.BgTilemapUpdateMode;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPersistentBgNametableRewind {
    private static final int BLOCK_PX = 128;
    private static final int MAP_WIDTH_BLOCKS = 8;
    private static final int MAP_HEIGHT_BLOCKS = 4;
    private static final int MAP_WIDTH_PX = MAP_WIDTH_BLOCKS * BLOCK_PX;
    private static final int MAP_HEIGHT_PX = MAP_HEIGHT_BLOCKS * BLOCK_PX;
    private static final int RING_BYTES = 64 * 32 * 4;

    private GraphicsManager graphicsManager;
    private FixtureLevel level;
    private LevelTilemapManager manager;
    private RecordingRenderer renderer;
    private LevelTilemapManager.BlockLookup blockLookup;
    private final ZoneFeatureProvider zoneFeatures = new WrappingZoneFeatures();

    @BeforeEach
    void setUp() throws Exception {
        GraphicsManager.destroyForReinit();
        TestEnvironment.resetAll();
        graphicsManager = GraphicsManager.getInstance();
        graphicsManager.initHeadless();
        renderer = new RecordingRenderer();
        Field rendererField = GraphicsManager.class.getDeclaredField("tilemapGpuRenderer");
        rendererField.setAccessible(true);
        rendererField.set(graphicsManager, renderer);

        level = new FixtureLevel();
        LevelGeometry geometry = new LevelGeometry(level,
                MAP_WIDTH_PX, MAP_HEIGHT_PX,
                MAP_WIDTH_PX, MAP_WIDTH_PX, MAP_HEIGHT_PX,
                BLOCK_PX, 8);
        manager = new LevelTilemapManager(geometry, graphicsManager, null);
        blockLookup = this::lookup;
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GraphicsManager.destroyForReinit();
    }

    @Test
    void persistentRingRoundTripsAndPublishesExactlyOneCoherentFullUploadAfterRestore() {
        RewindSnapshottable<LevelTilemapSnapshot> adapter =
                new LevelTilemapRewindAdapter(manager);
        assertEquals("level-tilemap", adapter.key());
        RewindRegistry registry = new RewindRegistry();
        registry.register(adapter);
        registry.registerPostRestoreCallback("level-tilemap-event-reconcile",
                manager::resetTilemapsForRewindRestore);

        ensurePersistent(0x2800, 0x0300);
        renderer.consumePendingUpload();
        ensurePersistent(0x2810, 0x02F0);
        renderer.consumePendingUpload();
        LevelTilemapSnapshot expected = adapter.capture();
        CompositeSnapshot expectedComposite = registry.capture();

        ensurePersistent(0x2820, 0x02E0);
        renderer.consumePendingUpload();
        int generationBeforeRestoreCallback = renderer.getBackgroundContentGeneration();

        registry.restore(expectedComposite);

        LevelTilemapSnapshot restored = adapter.capture();
        assertArrayEquals(expected.descriptors(), restored.descriptors());
        assertEquals(expected.originXTiles(), restored.originXTiles());
        assertEquals(expected.originYTiles(), restored.originYTiles());
        assertEquals(expected.alignedBgX(), restored.alignedBgX());
        assertEquals(expected.alignedBgY(), restored.alignedBgY());
        assertEquals(expected.baselineValid(), restored.baselineValid());
        assertFalse(manager.isBackgroundTilemapDirty(),
                "persistent restore must not request a camera-derived full rebuild");
        assertEquals(expected.originXTiles(), renderer.getBackgroundRingBaseXTiles());
        assertEquals(expected.originYTiles(), renderer.getBackgroundRingBaseYTiles());
        assertEquals(RING_BYTES, renderer.pendingBackgroundUploadBytes(),
                "restored physical ring must be queued as one full GPU publication");
        assertEquals(generationBeforeRestoreCallback + 1,
                renderer.getBackgroundContentGeneration());

        manager.finishPersistentRestoreUpload();
        assertEquals(generationBeforeRestoreCallback + 1,
                renderer.getBackgroundContentGeneration(),
                "post-restore full publication must be idempotent");

        renderer.consumePendingUpload();
        ensurePersistent(0x2810, 0x02F0);
        assertArrayEquals(expected.descriptors(), adapter.capture().descriptors(),
                "stationary ensure must retain the restored history-dependent ring");
        assertEquals(0, renderer.pendingBackgroundUploadBytes());
    }

    @Test
    void snapshotDefensivelyCopiesDescriptorBytes() {
        byte[] source = {1, 2, 3, 4};
        LevelTilemapSnapshot snapshot = new LevelTilemapSnapshot(source, 2, 3, 16, -16, true);
        source[0] = 99;

        byte[] firstRead = snapshot.descriptors();
        assertArrayEquals(new byte[] {1, 2, 3, 4}, firstRead);
        firstRead[1] = 88;

        byte[] secondRead = snapshot.descriptors();
        assertArrayEquals(new byte[] {1, 2, 3, 4}, secondRead);
        assertNotSame(firstRead, secondRead);
    }

    @Test
    void statelessModeCapturesInvalidSnapshotAndKeepsExistingRewindResetBehavior() {
        RewindSnapshottable<LevelTilemapSnapshot> adapter =
                new LevelTilemapRewindAdapter(manager);
        LevelTilemapSnapshot empty = adapter.capture();

        assertEquals(0, empty.descriptors().length);
        assertFalse(empty.baselineValid());

        manager.ensureBackgroundTilemapData(blockLookup, zoneFeatures, 0, null, false);
        assertFalse(manager.isBackgroundTilemapDirty());
        renderer.consumePendingUpload();
        adapter.restore(empty);
        manager.resetTilemapsForRewindRestore();

        assertTrue(manager.isBackgroundTilemapDirty());
        assertEquals(0, renderer.pendingBackgroundUploadBytes());
    }

    @Test
    void invalidSnapshotRestoresStatelessLifecycleAfterFuturePersistentState() {
        RewindSnapshottable<LevelTilemapSnapshot> adapter =
                new LevelTilemapRewindAdapter(manager);
        LevelTilemapSnapshot stateless = adapter.capture();
        ensurePersistent(0x2800, 0x0300);
        renderer.consumePendingUpload();

        adapter.restore(stateless);
        manager.resetTilemapsForRewindRestore();

        assertFalse(adapter.capture().baselineValid(),
                "restoring a pre-persistent keyframe must not retain the future ring");
        assertTrue(manager.isBackgroundTilemapDirty());
        assertEquals(0, renderer.pendingBackgroundUploadBytes());
    }

    private void ensurePersistent(int bgX, int bgY) {
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

    private static final class RecordingRenderer extends TilemapGpuRenderer {
        int pendingBackgroundUploadBytes() {
            return getPendingBackgroundUploadBytes();
        }

        void consumePendingUpload() {
            consumePendingBackgroundUploadForTest();
        }
    }

    private static final class WrappingZoneFeatures implements ZoneFeatureProvider {
        @Override
        public boolean bgWrapsHorizontally() {
            return true;
        }

        @Override public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) { }
        @Override public void update(com.openggf.sprites.playable.AbstractPlayableSprite player,
                                     int cameraX, int zoneIndex) { }
        @Override public void reset() { }
        @Override public boolean hasCollisionFeatures(int zoneIndex) { return false; }
        @Override public boolean hasWater(int zoneIndex) { return false; }
        @Override public int getWaterLevel(int zoneIndex, int actIndex) { return 0; }
        @Override public void render(Camera camera, int frameCounter) { }
        @Override public int ensurePatternsCached(GraphicsManager manager, int baseIndex) {
            return baseIndex;
        }
    }

    private static final class FixtureLevel extends AbstractLevel {
        FixtureLevel() {
            super(0);
            palettes = new Palette[] {new Palette(), new Palette(), new Palette(), new Palette()};
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
        }

        @Override
        public List<ObjectSpawn> getObjects() {
            return objects;
        }

        @Override
        public List<RingSpawn> getRings() {
            return List.of();
        }

        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
    }
}
