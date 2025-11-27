package uk.co.jamesj999.sonic.physics;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class TestGroundSensor {

    private LevelManager mockLevelManager;
    private AbstractPlayableSprite mockSprite;

    private ChunkDesc[][] chunkMap;
    private SolidTile[] tiles;

    @Before
    public void setUp() {
        chunkMap = new ChunkDesc[20][20];

        mockLevelManager = new LevelManager() {
            @Override
            public ChunkDesc getChunkDescAt(byte layer, int x, int y) {
                int gridX = x / 16;
                int gridY = y / 16;
                if (gridX >= 0 && gridX < 20 && gridY >= 0 && gridY < 20) {
                    return chunkMap[gridX][gridY];
                }
                return null;
            }

            @Override
            public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc) {
                if (chunkDesc == null) return null;
                return tiles[chunkDesc.getChunkIndex()];
            }
        };

        GroundSensor.setLevelManager(mockLevelManager);

        mockSprite = new AbstractPlayableSprite("sonic", (short)0, (short)0, false) {
            @Override protected void defineSpeeds() {
                this.rollHeight = 20;
                this.runHeight = 30;
                this.width = 20; // Assume constant width for this test
            }
            @Override protected void createSensorLines() { }
            @Override public void draw() { }
        };
        mockSprite.setGroundMode(GroundMode.GROUND);
        mockSprite.setLayer((byte) 0);
        mockSprite.setWidth(0);
        mockSprite.setHeight(0);

        tiles = new SolidTile[10];
        // Tile 0: Empty
        tiles[0] = null;
        // Tile 1: Full Solid (Height 16 everywhere)
        byte[] fullHeights = new byte[16];
        byte[] fullWidths = new byte[16];
        for(int i=0; i<16; i++) { fullHeights[i] = 16; fullWidths[i] = 16; }
        tiles[1] = new SolidTile(1, fullHeights, fullWidths, (byte)0);

        // Tile 2: Half Solid (Height 8 everywhere)
        byte[] halfHeights = new byte[16];
        for(int i=0; i<16; i++) { halfHeights[i] = 8; }
        tiles[2] = new SolidTile(2, halfHeights, fullWidths, (byte)0);

        // Tile 3: Empty but valid object (Height 0)
        byte[] emptyHeights = new byte[16];
        tiles[3] = new SolidTile(3, emptyHeights, fullWidths, (byte)0);
    }

    private void setTileAt(int x, int y, int tileIndex) {
        int gridX = x / 16;
        int gridY = y / 16;
        ChunkDesc desc = new ChunkDesc();
        desc.setChunkIndex(tileIndex);
        chunkMap[gridX][gridY] = desc;
    }

    @Test
    public void testDownSensorNormal() {
        setTileAt(100, 112, 1);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);
        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)0, true);
        SensorResult result = sensor.scan();
        assertNotNull(result);
        assertEquals(12, result.distance());
    }

    @Test
    public void testDownSensorTouch() {
        setTileAt(100, 112, 1);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 112);
        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)0, true);
        SensorResult result = sensor.scan();
        assertNotNull(result);
        assertEquals(0, result.distance());
    }

    @Test
    public void testDownSensorExtension() {
        setTileAt(100, 112, 0); // Empty
        setTileAt(100, 128, 1); // Full
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);
        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)0, true);
        SensorResult result = sensor.scan();
        assertNotNull("Should find extended tile", result);
        assertEquals(28, result.distance());
    }

    @Test
    public void testDownSensorExtensionFound() {
        setTileAt(100, 96, 0); // Empty (Initial)
        setTileAt(100, 112, 1); // Full (Next)
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);
        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)0, true);
        SensorResult result = sensor.scan();
        assertNotNull("Should find extended tile", result);
        assertEquals(12, result.distance());
    }

    @Test
    public void testDownSensorRegression() {
        setTileAt(100, 112, 1);
        setTileAt(100, 96, 2);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 112);
        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)0, true);
        SensorResult result = sensor.scan();
        assertNotNull("Should find regressed tile", result);
        assertEquals(-8, result.distance());
    }

    @Test
    public void testRightWallSensorRotation() {
        setTileAt(112, 100, 1);
        mockSprite.setGroundMode(GroundMode.RIGHTWALL);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);
        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)10, true);
        SensorResult result = sensor.scan();
        assertNotNull(result);
        assertEquals(2, result.distance());
    }

    @Test
    public void testUnrollIntoGround() {
        // Setup: Rolling on Ground.
        mockSprite.setGroundMode(GroundMode.GROUND);
        mockSprite.setRolling(true); // Should be 20 height
        mockSprite.setY((short) 100);

        // Bottom is Y + Height = 100 + 20 = 120.
        // Floor is presumably at 120.

        // Action: Unroll (Stand up).
        mockSprite.setRolling(false); // Should become 30 height.

        // Expectation:
        // We want Bottom to remain at 120.
        // So Y + 30 = 120. => Y should be 90.
        // If Y stays 100, Bottom becomes 130 (Embedded 10px).

        assertEquals("Height should be runHeight", 30, mockSprite.getHeight());
        assertEquals("Y should be adjusted to keep feet planted", 90, mockSprite.getY());
    }
}
