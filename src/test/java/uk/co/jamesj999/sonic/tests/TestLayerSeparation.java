package uk.co.jamesj999.sonic.tests;

import org.junit.After;
import org.junit.Test;
import uk.co.jamesj999.sonic.level.*;
import uk.co.jamesj999.sonic.physics.*;
import uk.co.jamesj999.sonic.sprites.playable.Sonic;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.Assert.*;

public class TestLayerSeparation {

    @After
    public void tearDown() throws Exception {
        LevelManager levelManager = LevelManager.getInstance();
        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, null);
    }

    @Test
    public void testGroundSensorReadsCorrectMapLayer() throws Exception {
        // Setup a mock level with 2 map layers.
        MockLevel level = new MockLevel();

        LevelManager levelManager = LevelManager.getInstance();
        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, level);

        // Chunk 1 (Layer 0): Path 2 Solid.
        // Chunk 0 (Layer 1): Empty.

        // Sonic on Path 2 (Layer 1).
        Sonic sonic = new Sonic("Sonic", (short)50, (short)50, true);
        sonic.setLayer((byte)1); // Path 2
        sonic.setGroundMode(GroundMode.GROUND);

        // Sensor check at (0, 16) -> Block 1 (which has Chunk 1 on Map Layer 0).
        // If GroundSensor reads Map Layer 1, it gets Block 0 -> Empty -> Fall.
        // If GroundSensor reads Map Layer 0, it gets Block 1 -> Chunk 1 -> Path 2 Solid -> Stand.

        GroundSensor sensor = new GroundSensor(sonic, Direction.DOWN, (byte)0, (byte)16, true);

        SensorResult result = sensor.scan();

        // We expect collision.
        assertNotNull("Sonic should detect ground on Path 2 even if Map Layer 1 is empty", result);

        // If detection works, distance should be negative/small (solid tile).
        // 50 + 16 = 66. Tile 16 high at 64. Distance -18.
        // If falling, distance ~ +26 or null.

        if (result.distance() >= -24 && result.distance() < 0) {
            // Success: Detected solid tile.
        } else {
            fail("Sonic fell through floor! Distance: " + result.distance());
        }
    }

    private static class MockLevel implements Level {
        private final SolidTile fullSolidTile;

        public MockLevel() {
            byte[] heights = new byte[16];
            Arrays.fill(heights, (byte)16);
            fullSolidTile = new SolidTile(1, heights, heights, (byte)0);
        }

        @Override public int getPaletteCount() { return 0; }
        @Override public Palette getPalette(int index) { return null; }
        @Override public int getPatternCount() { return 0; }
        @Override public Pattern getPattern(int index) { return null; }
        @Override public int getChunkCount() { return 2; }

        @Override
        public Chunk getChunk(int index) {
             if (index == 1) {
                 Chunk chunk = new Chunk();
                 chunk.setSolidTileIndex(1); // Path 1 Solid
                 chunk.setSolidTileAltIndex(1); // Path 2 Solid
                 return chunk;
             }
             return null; // Chunk 0 is empty
        }

        @Override public int getBlockCount() { return 2; }

        @Override
        public Block getBlock(int index) {
            if (index == 1) {
                return new Block() {
                    @Override
                    public ChunkDesc getChunkDesc(int x, int y) {
                        // Chunk 1.
                        // Secondary Mode (Path 2) = ALL_SOLID (3).
                        // Primary Mode (Path 1) = ALL_SOLID (3).
                        return new ChunkDesc(0xF001);
                    }
                };
            }
            // Block 0: Empty
            return new Block() {
                 @Override
                 public ChunkDesc getChunkDesc(int x, int y) {
                     return ChunkDesc.EMPTY;
                 }
            };
        }

        @Override
        public SolidTile getSolidTile(int index) {
            if(index == 1) return fullSolidTile;
            return null;
        }

        @Override
        public Map getMap() {
             // Map with 2 layers.
             return new Map(2, 10, 10) {
                 @Override
                 public byte getValue(int layer, int x, int y) {
                     if (layer == 0) return 1; // Block 1 (Solid)
                     return 0; // Block 0 (Empty)
                 }
             };
        }
    }
}
