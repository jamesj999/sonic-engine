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
            @Override protected void defineSpeeds() { }
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
        // Sensor at 100, 100.
        // Tile at 100, 112 (Grid 6, 7).
        // Tile is Full Solid (1).

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
        // Sensor at 100, 112.
        // Tile at 100, 112 (Full Solid).
        // Distance = 0.

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
        // Sensor at 100, 100.
        // Tile at 100, 112 is Empty (0).
        // Tile at 100, 128 is Full Solid (1).
        // Extension should find tile at 128.
        // Surface at 128.
        // Distance = 128 - 100 = 28.

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
        // Sensor at 100, 100.
        // Tile at 100, 112 is Full Solid (1). (Grid 7)
        // Tile at 100, 96 (Grid 6) is Empty.
        // Extension should find tile at 112.
        // Surface at 112.
        // Distance = 112 - 100 = 12.

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
        // Sensor at 100, 112.
        // Tile at 100, 112 is Full Solid (1).
        // Tile at 100, 96 is Half Solid (2) (Height 8).
        // Surface at 96 + 16 - 8 = 104.
        // Distance = 104 - 112 = -8.

        setTileAt(100, 112, 1);
        setTileAt(100, 96, 2);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 112);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte)0, (byte)0, true);
        SensorResult result = sensor.scan();

        assertNotNull("Should find regressed tile", result);
        assertEquals(-8, result.distance());
    }
}
