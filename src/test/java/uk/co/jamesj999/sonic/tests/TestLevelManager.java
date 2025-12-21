package uk.co.jamesj999.sonic.tests;

import org.junit.After;
import org.junit.Test;
import uk.co.jamesj999.sonic.level.*;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TestLevelManager {

    @After
    public void tearDown() throws Exception {
        LevelManager levelManager = LevelManager.getInstance();
        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, null);
    }

    @Test
    public void testGetBlockAtPositionWithLargeIndex() throws Exception {
        // Setup a mock level
        MockLevel level = new MockLevel();

        // Inject into LevelManager
        LevelManager levelManager = LevelManager.getInstance();
        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, level);

        // Try to access the block at (0,0) which is mapped to index 128 (0x80)
        // LevelManager.getChunkDescAt calls getBlockAtPosition
        ChunkDesc chunkDesc = levelManager.getChunkDescAt((byte) 0, 0, 0);

        assertNotNull("ChunkDesc should not be null for block index 128", chunkDesc);
    }

    private static class MockLevel implements Level {
        private final Map map;
        private final Block validBlock;

        public MockLevel() {
            // Map 1x1, 1 layer
            map = new Map(1, 1, 1);
            // Set value at (0,0) to -128 (which is index 128)
            map.setValue(0, 0, 0, (byte) -128);

            validBlock = new Block();
        }

        @Override public int getPaletteCount() { return 0; }
        @Override public Palette getPalette(int index) { return null; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { return null; }
        @Override public int getChunkCount() { return 0; }
        @Override public Chunk getChunk(int index) { return null; }
        @Override public int getBlockCount() { return 256; }

        @Override
        public Block getBlock(int index) {
            if (index == 128) {
                return validBlock;
            }
            return null;
        }

        @Override public SolidTile getSolidTile(int index) { return null; }
        @Override public Map getMap() { return map; }
        @Override public java.util.List<ObjectSpawn> getObjects() { return java.util.Collections.emptyList(); }
        @Override public java.util.List<uk.co.jamesj999.sonic.level.rings.RingSpawn> getRings() { return java.util.Collections.emptyList(); }
        @Override public RingSpriteSheet getRingSpriteSheet() { return null; }
        @Override public int getMinX() { return 0; }
        @Override public int getMaxX() { return 0; }
        @Override public int getMinY() { return 0; }
        @Override public int getMaxY() { return 0; }
        public short getStartX() { return 0; }
        public short getStartY() { return 0; }
    }
}
