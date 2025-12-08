package uk.co.jamesj999.sonic.physics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.MockLevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestGroundSensorFlipping {

    private MockLevelManager mockLevelManager;
    private MockSprite sprite;

    @Before
    public void setUp() {
        mockLevelManager = new MockLevelManager();
        GroundSensor.setLevelManager(mockLevelManager);
        sprite = new MockSprite("MOCK", (short) 100, (short) 100);
    }

    @After
    public void tearDown() {
        GroundSensor.setLevelManager(null); // Reset
    }

    @Test
    public void testVerticalSensorHFlip() {
        // Setup a slope tile: /
        // Height increases from 1 to 16.
        byte[] heights = new byte[16];
        for (int i = 0; i < 16; i++) {
            heights[i] = (byte) (i + 1);
        }
        byte[] widths = new byte[16];
        SolidTile slopeTile = new SolidTile(1, heights, widths, (byte) 0x10);

        // HFlip | TopSolid
        ChunkDesc flippedChunk = new ChunkDesc(0x0400 | 0x1000);

        mockLevelManager.registerChunkDesc((byte) 0, 100, 80, flippedChunk);
        mockLevelManager.registerSolidTile(flippedChunk, slopeTile);

        sprite.setX((short) 100);
        sprite.setY((short) 80);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setLayer((byte) 0);

        GroundSensor sensor = new GroundSensor(sprite, Direction.DOWN, (byte) 0, (byte) 0, true);

        SensorResult result = sensor.scan();

        assertNotNull("Sensor should detect collision", result);
        assertEquals("Distance should reflect H-Flipped height", 4, result.distance());
    }

    @Test
    public void testHorizontalSensorHFlip() {
        // Setup a wall tile. Width = 8.
        byte[] heights = new byte[16];
        byte[] widths = new byte[16];
        Arrays.fill(widths, (byte) 8);
        SolidTile wallTile = new SolidTile(2, heights, widths, (byte) 0x00);

        // HFlip | AllSolid
        ChunkDesc flippedChunk = new ChunkDesc(0x0400 | 0x3000);

        mockLevelManager.registerSolidTile(flippedChunk, wallTile);

        sprite.setX((short) 100);
        sprite.setY((short) 100);
        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setLayer((byte) 0);

        GroundSensor sensor = new GroundSensor(sprite, Direction.RIGHT, (byte) 0, (byte) 0, true);

        mockLevelManager.registerChunkDesc((byte) 0, 100, 100, flippedChunk);

        SensorResult result = sensor.scan();
        assertNotNull("Sensor should detect collision", result);

        assertEquals("Distance should reflect H-Flipped wall position", -4, result.distance());
    }

    @Test
    public void testHorizontalSensorVFlip() {
        // Wall tile. Width 1..16.
        byte[] heights = new byte[16];
        byte[] widths = new byte[16];
        for(int i=0; i<16; i++) {
            widths[i] = (byte) (i+1);
        }
        SolidTile slopeWall = new SolidTile(3, heights, widths, (byte) 0x00);

        // VFlip | AllSolid
        ChunkDesc flippedChunk = new ChunkDesc(0x0800 | 0x3000);

        mockLevelManager.registerSolidTile(flippedChunk, slopeWall);
        mockLevelManager.registerChunkDesc((byte) 0, 100, 100, flippedChunk);

        sprite.setX((short) 100);
        sprite.setY((short) 100);
        sprite.setGroundMode(GroundMode.GROUND);

        GroundSensor sensor = new GroundSensor(sprite, Direction.RIGHT, (byte) 0, (byte) 0, true);

        SensorResult result = sensor.scan();
        assertNotNull(result);

        assertEquals("Distance should reflect V-Flipped wall width index", 0, result.distance());
    }

    // Static inner class for MockSprite
    static class MockSprite extends AbstractPlayableSprite {
        public MockSprite(String code, short x, short y) {
            super(code, x, y, false);
        }

        @Override
        public void defineSpeeds() {}

        @Override
        public void draw() {}

        @Override
        protected void createSensorLines() {
            // Minimal sensors to avoid NPEs if logic checks them
            groundSensors = new uk.co.jamesj999.sonic.physics.Sensor[0];
            ceilingSensors = new uk.co.jamesj999.sonic.physics.Sensor[0];
            pushSensors = new uk.co.jamesj999.sonic.physics.Sensor[0];
        }
    }
}
