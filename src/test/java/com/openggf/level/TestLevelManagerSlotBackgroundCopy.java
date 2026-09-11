package com.openggf.level;

import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestLevelManagerSlotBackgroundCopy {
    private GameplayModeContext gameplayMode;

    @BeforeEach
    public void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        gameplayMode = TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void slotBackgroundRowCopyUpdatesBothEightPixelRowsOfSixteenPixelBlockRow() throws Exception {
        TestLevelManager levelManager = new TestLevelManager(gameplayMode);
        RecordingTilemapManager tilemapManager = new RecordingTilemapManager();
        setTilemapManager(levelManager, tilemapManager);

        levelManager.copyBackgroundTileRowFromWorldToVdpPlane(0x40, 0x20, 0xE000, 1);

        assertEquals(levelManager.descriptorFor(0x40, 0x20), tilemapManager.descriptorAt(0, 0));
        assertEquals(levelManager.descriptorFor(0x48, 0x20), tilemapManager.descriptorAt(1, 0));
        assertEquals(levelManager.descriptorFor(0x40, 0x28), tilemapManager.descriptorAt(0, 1));
        assertEquals(levelManager.descriptorFor(0x48, 0x28), tilemapManager.descriptorAt(1, 1));
    }

    @Test
    public void slotBackgroundRowCopySnapsSourceXToSixteenPixelBlockBoundary() throws Exception {
        TestLevelManager levelManager = new TestLevelManager(gameplayMode);
        RecordingTilemapManager tilemapManager = new RecordingTilemapManager();
        setTilemapManager(levelManager, tilemapManager);

        levelManager.copyBackgroundTileRowFromWorldToVdpPlane(0x48, 0x20, 0xE000, 1);

        assertEquals(levelManager.descriptorFor(0x40, 0x20), tilemapManager.descriptorAt(0, 0));
        assertEquals(levelManager.descriptorFor(0x48, 0x20), tilemapManager.descriptorAt(1, 0));
        assertEquals(levelManager.descriptorFor(0x40, 0x28), tilemapManager.descriptorAt(0, 1));
        assertEquals(levelManager.descriptorFor(0x48, 0x28), tilemapManager.descriptorAt(1, 1));
    }

    @Test
    public void setLevelRebindsTilemapGeometryToReplacementLevel() throws Exception {
        TestLevelManager levelManager = new TestLevelManager(gameplayMode);
        RecordingTilemapManager tilemapManager = new RecordingTilemapManager();
        setTilemapManager(levelManager, tilemapManager);
        SyntheticLevel replacement = new SyntheticLevel(3, 2);

        levelManager.setLevel(replacement);

        assertSame(replacement, tilemapManager.updatedGeometry.level(),
                "setLevel must refresh the tilemap manager's level geometry");
        assertTrue(tilemapManager.invalidatedAll,
                "setLevel must invalidate all tilemap caches after a geometry swap");
    }

    private static void setTilemapManager(LevelManager levelManager, LevelTilemapManager tilemapManager)
            throws Exception {
        Field field = LevelManager.class.getDeclaredField("tilemapManager");
        field.setAccessible(true);
        field.set(levelManager, tilemapManager);
    }

    private static final class TestLevelManager extends LevelManager {
        private TestLevelManager(GameplayModeContext gameplayMode) {
            super(GameServices.camera(), GameServices.sprites(), GameServices.parallax(),
                    GameServices.collision(), GameServices.water(), GameServices.gameState(),
                    EngineServices.current(), gameplayMode.getWorldSession());
        }

        @Override
        public int getBackgroundTileDescriptorAtWorld(int worldX, int worldY) {
            return descriptorFor(worldX, worldY);
        }

        int descriptorFor(int worldX, int worldY) {
            return ((worldY & 0xFF) << 8) | (worldX & 0xFF);
        }
    }

    private static final class RecordingTilemapManager extends LevelTilemapManager {
        private final Map<Integer, Integer> writes = new HashMap<>();

        RecordingTilemapManager() {
            super(null, null, null);
        }

        @Override
        public void ensureBackgroundTilemapData(BlockLookup blockLookup, com.openggf.game.ZoneFeatureProvider zoneFeatureProvider,
                                                int currentZone, ParallaxManager parallaxManager,
                                                boolean verticalWrapEnabled) {
        }

        @Override
        public int getBackgroundTilemapWidthTiles() {
            return 64;
        }

        @Override
        public int getBackgroundTilemapHeightTiles() {
            return 32;
        }

        @Override
        public boolean setBackgroundTileDescriptorAtTilemapCell(int tileX, int tileY, int descriptor) {
            writes.put(key(tileX, tileY), descriptor);
            return true;
        }

        @Override
        public void updateGeometry(LevelGeometry geometry) {
            this.updatedGeometry = geometry;
        }

        @Override
        public void invalidateAllTilemaps() {
            this.invalidatedAll = true;
        }

        int descriptorAt(int tileX, int tileY) {
            return writes.getOrDefault(key(tileX, tileY), -1);
        }

        private static int key(int tileX, int tileY) {
            return (tileY << 8) | tileX;
        }

        private LevelGeometry updatedGeometry;
        private boolean invalidatedAll;
    }

    private static final class SyntheticLevel implements Level {
        private final com.openggf.level.Map map;

        private SyntheticLevel(int width, int height) {
            this.map = new com.openggf.level.Map(2, width, height);
        }

        @Override public int getPaletteCount() { return 0; }
        @Override public Palette getPalette(int index) { return null; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { return null; }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { return null; }
        @Override public int getBlockCount() { return 0; }
        @Override public Block getBlock(int index) { return null; }
        @Override public SolidTile getSolidTile(int index) { return null; }
        @Override public com.openggf.level.Map getMap() { return map; }
        @Override public List<ObjectSpawn> getObjects() { return Collections.emptyList(); }
        @Override public List<RingSpawn> getRings() { return Collections.emptyList(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return map.getWidth() * getBlockPixelSize(); }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return map.getHeight() * getBlockPixelSize(); }
        @Override public int getZoneIndex() { return 0; }
    }
}


