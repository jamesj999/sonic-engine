package uk.co.jamesj999.sonic.physics;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.CollisionMode;
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
            public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, int solidityBitIndex) {
                if (chunkDesc == null)
                    return null;
                return tiles[chunkDesc.getChunkIndex()];
            }

            @Override
            public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, byte layer) {
                if (chunkDesc == null)
                    return null;
                return tiles[chunkDesc.getChunkIndex()];
            }

            @Override
            public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc) {
                return getSolidTileForChunkDesc(chunkDesc, (byte) 0);
            }
        };

        GroundSensor.setLevelManager(mockLevelManager);

        mockSprite = new AbstractPlayableSprite("sonic", (short) 0, (short) 0) {
            @Override
            protected void defineSpeeds() {
            }

            @Override
            protected void createSensorLines() {
            }

            @Override
            public void draw() {
            }
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
        for (int i = 0; i < 16; i++) {
            fullHeights[i] = 16;
            fullWidths[i] = 16;
        }
        tiles[1] = new SolidTile(1, fullHeights, fullWidths, (byte) 0);

        // Tile 2: Half Solid (Height 8 everywhere)
        byte[] halfHeights = new byte[16];
        for (int i = 0; i < 16; i++) {
            halfHeights[i] = 8;
        }
        tiles[2] = new SolidTile(2, halfHeights, fullWidths, (byte) 0);

        // Tile 3: Empty but valid object (Height 0)
        byte[] emptyHeights = new byte[16];
        tiles[3] = new SolidTile(3, emptyHeights, fullWidths, (byte) 0);
    }

    private void setTileAt(int x, int y, int tileIndex) {
        setTileAt(x, y, tileIndex, CollisionMode.ALL_SOLID);
    }

    private void setTileAt(int x, int y, int tileIndex, CollisionMode mode) {
        int gridX = x / 16;
        int gridY = y / 16;
        ChunkDesc desc = new ChunkDesc(tileIndex);
        // We need to set collision mode bits in the index
        // Mode is bits 12-13 (Primary) and 14-15 (Secondary)
        // Shift mode value to 12
        int modeBits = mode.getValue() << 12;
        desc.set(tileIndex | modeBits);

        chunkMap[gridX][gridY] = desc;
    }

    @Test
    public void testDownSensorNormal() {
        // Sensor at 100, 100.
        // Tile at 100, 112 (Grid 6, 7).
        // Tile is Full Solid (1).
        // ROM formula: distance = 15 - metric - (sensorY & 0xF)
        // Surface at tileBase + 15 - metric = 112 + 15 - 16 = 111
        // Distance = 111 - 100 = 11

        setTileAt(100, 112, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(11, result.distance());
    }

    @Test
    public void testDownSensorTouch() {
        // Sensor at 100, 112.
        // Tile at 100, 112 (Full Solid).
        // ROM formula: distance = 15 - metric - (sensorY & 0xF)
        // At sensorY = 112 (tileBase): distance = 15 - 16 - 0 = -1
        // In ROM, distance = -1 means sensor is at the top solid pixel.

        setTileAt(100, 112, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 112);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(-1, result.distance());
    }

    @Test
    public void testDownSensorExtension() {
        // Sensor at 100, 100.
        // Tile at 100, 112 is Empty (0).
        // Tile at 100, 128 is Full Solid (1).
        // Extension should find tile at 128.
        // ROM formula: surface = tileBase + 15 - metric = 128 + 15 - 16 = 127
        // Distance = 127 - 100 = 27

        setTileAt(100, 112, 0, CollisionMode.NO_COLLISION); // Empty
        setTileAt(100, 128, 1); // Full

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scan();

        assertNotNull("Should find extended tile", result);
        assertEquals(27, result.distance());
    }

    @Test
    public void testDownSensorExtensionFound() {
        // Sensor at 100, 100.
        // Tile at 100, 112 is Full Solid (1). (Grid 7)
        // Tile at 100, 96 (Grid 6) is Empty.
        // Extension should find tile at 112.
        // Surface at 112.
        // Distance = 112 - 100 = 12.

        // Initial check at 100, 100. Tile is at 100, 96?
        // Wait. Sensor checks at (100, 100).
        // Initial tile: Grid(6, 6) -> (96, 96).
        // If (100, 96) is Empty.
        // It extends to Next Tile.
        // Next Tile for DOWN is (100, 112).
        // Tile at (100, 112) is Full.
        // Distance calculated.

        // Wait, current logic:
        // currentX = 100, currentY = 100.
        // Initial Tile at (100, 100).
        // The test setup sets tile at (100, 96).
        // (100, 96) is Grid (6, 6).
        // (100, 100) is Grid (6, 6).
        // So yes, Initial Tile is 0 (Empty).
        setTileAt(100, 96, 0, CollisionMode.NO_COLLISION); // Empty (Initial)
        setTileAt(100, 112, 1); // Full (Next)

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scan();

        assertNotNull("Should find extended tile", result);
        assertEquals(11, result.distance());
    }

    @Test
    public void testDownSensorRegression() {
        // Sensor at 100, 112.
        // Tile at 100, 112 is Full Solid (1).
        // Tile at 100, 96 is Half Solid (2) (Height 8).
        // ROM formula: surface = tileBase + 15 - metric = 96 + 15 - 8 = 103
        // Distance = 103 - 112 = -9

        setTileAt(100, 112, 1);
        setTileAt(100, 96, 2);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 112);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scan();

        assertNotNull("Should find regressed tile", result);
        assertEquals(-9, result.distance());
    }

    @Test
    public void testRightWallSensorRotation() {
        // Mode: RIGHTWALL.
        // Sensor: (x=0, y=10) [Relative to Sprite in GROUND mode].
        // Rotated for RIGHTWALL: (x, y) -> (y, x) -> (10, 0).
        // ROM: s2.asm Sonic_WalkVertR (42684-42712)
        // Sprite Center: (100, 100).
        // Sensor Scan Start: (110, 100).
        // Direction: RIGHTWALL + DOWN -> RIGHT (from SpriteManager).
        // Looking for wall to Right.
        // Wall at (112, 100). Tile 1 (Full).
        // Distance calculation accounts for tile edge detection.

        setTileAt(112, 100, 1);

        mockSprite.setGroundMode(GroundMode.RIGHTWALL);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);
        // Note: mockSprite width/height are 0 in setup. getCentreX/Y = X/Y.

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 10, true);
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(1, result.distance());
    }

    @Test
    public void testRightWallSensorRotationWithNonZeroX() {
        // Mode: RIGHTWALL.
        // Sensor: (x=5, y=10) [Relative to Sprite in GROUND mode].
        // Rotated for RIGHTWALL: (x, y) -> (y, x) -> (10, 5).
        // ROM: s2.asm Sonic_WalkVertR (42684-42712)
        // Just swaps axes, no negation needed.

        mockSprite.setGroundMode(GroundMode.RIGHTWALL);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 5, (byte) 10, true);
        short[] rotated = sensor.getRotatedOffset();

        assertEquals("X should be y = 10", 10, rotated[0]);
        assertEquals("Y should be x = 5", 5, rotated[1]);
    }

    @Test
    public void testCeilingSensorRotation() {
        // Mode: CEILING.
        // Sensor: (x=-9, y=19) [Left ground sensor offset].
        // Rotated for CEILING: (x, y) -> (x, -y) -> (-9, -19).
        // ROM: s2.asm Sonic_WalkCeiling (42750-42779)
        // X stays the same (left sensor stays on left side).
        // Only Y is negated (sensor probes upward toward ceiling).
        // This was the bug: old code did (-x, -y) which swapped left/right sensors!

        mockSprite.setGroundMode(GroundMode.CEILING);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) -9, (byte) 19, true);
        short[] rotated = sensor.getRotatedOffset();

        assertEquals("X should remain -9 (left side)", -9, rotated[0]);
        assertEquals("Y should be negated to -19", -19, rotated[1]);
    }

    @Test
    public void testCeilingSensorRotationRightSide() {
        // Mode: CEILING.
        // Sensor: (x=9, y=19) [Right ground sensor offset].
        // Rotated for CEILING: (x, y) -> (x, -y) -> (9, -19).
        // X stays positive (right sensor stays on right side).

        mockSprite.setGroundMode(GroundMode.CEILING);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 9, (byte) 19, true);
        short[] rotated = sensor.getRotatedOffset();

        assertEquals("X should remain 9 (right side)", 9, rotated[0]);
        assertEquals("Y should be negated to -19", -19, rotated[1]);
    }

    @Test
    public void testLeftWallSensorRotation() {
        // Mode: LEFTWALL.
        // Sensor: (x=5, y=10) [Relative to Sprite in GROUND mode].
        // Rotated for LEFTWALL: (x, y) -> (-y, x) -> (-10, 5).
        // ROM: s2.asm Sonic_WalkVertL (42817-42846)

        mockSprite.setGroundMode(GroundMode.LEFTWALL);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 5, (byte) 10, true);
        short[] rotated = sensor.getRotatedOffset();

        assertEquals("X should be -y = -10", -10, rotated[0]);
        assertEquals("Y should be x = 5", 5, rotated[1]);
    }

    @Test
    public void testGroundSensorRotation() {
        // Mode: GROUND (default).
        // Sensor: (x=-9, y=19) [Left ground sensor offset].
        // No rotation: (x, y) -> (x, y) -> (-9, 19).

        mockSprite.setGroundMode(GroundMode.GROUND);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) -9, (byte) 19, true);
        short[] rotated = sensor.getRotatedOffset();

        assertEquals("X should remain -9", -9, rotated[0]);
        assertEquals("Y should remain 19", 19, rotated[1]);
    }

    @Test
    public void testTopSolidBehavior() {
        // TOP_SOLID: Solid only for DOWN sensor.
        // Place a tile at 100, 112 with TOP_SOLID.

        setTileAt(100, 112, 1, CollisionMode.TOP_SOLID);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        // 1. Check with DOWN sensor (Ground). Should detect collision.
        // ROM formula: surface = 112 + 15 - 16 = 111, distance = 111 - 100 = 11
        GroundSensor downSensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult downResult = downSensor.scan();
        assertNotNull("DOWN sensor should detect TOP_SOLID", downResult);
        assertEquals(11, downResult.distance());

        // 2. Check with UP sensor (Ceiling). Should NOT detect collision (treat as
        // empty/extend).
        // Tile is below sprite, so UP sensor normally wouldn't see it anyway.
        // Let's place tile ABOVE sprite at 100, 80.
        // Note: setTileAt helper sets collisions mode for the given tile index globally
        // in mock setup.
        // We need to be careful. setTileAt sets chunkMap and creates a desc.
        setTileAt(100, 80, 1, CollisionMode.TOP_SOLID);

        // UP Sensor looking up.
        GroundSensor upSensor = new GroundSensor(mockSprite, Direction.UP, (byte) 0, (byte) 0, true);
        // Scan UP.
        // Tile at 100, 80.
        // If solid: Distance = SensorY - (TileY + Height).
        // 100 - (80 + 16) = 4.
        // If not solid: Extends to next tile (100, 64). Empty.
        // Returns larger distance.
        // Or if handled as empty, returns extension distance (e.g. 100 - (64+16) ? No.
        // UP: Next tile is 100, 64.
        // If empty, calculates distance to 64.
        // Distance = 100 - (64 + 0) = 36.
        // If solid, distance = 4.

        SensorResult upResult = upSensor.scan();
        // Should be 20 (distance to empty tile at 80), not 4 (distance to solid tile at
        // 80).
        // 100 - (80 + 0) = 20.
        assertNotNull(upResult);
        assertEquals("UP sensor should ignore TOP_SOLID", 20, upResult.distance());
    }

    @Test
    public void testLeftRightBottomSolidBehavior() {
        // L_R_B_SOLID: Solid for UP/LEFT/RIGHT, but NOT DOWN.

        // 1. Check DOWN sensor. Should ignore.
        setTileAt(100, 112, 1, CollisionMode.LEFT_RIGHT_BOTTOM_SOLID);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor downSensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        // If solid: distance 11.
        // If ignored: Sees tile at 112 as empty, extends to 128.
        // ROM formula: surface = 128 + 15 - 16 = 127, distance = 127 - 100 = 27
        SensorResult downResult = downSensor.scan();
        assertEquals("DOWN sensor should ignore L_R_B_SOLID", 27, downResult.distance());

        // 2. Check UP sensor. Should detect.
        setTileAt(100, 80, 1, CollisionMode.LEFT_RIGHT_BOTTOM_SOLID);
        GroundSensor upSensor = new GroundSensor(mockSprite, Direction.UP, (byte) 0, (byte) 0, true);
        // If solid: 100 - (80 + 16) = 4.
        SensorResult upResult = upSensor.scan();
        assertEquals("UP sensor should detect L_R_B_SOLID", 4, upResult.distance());
    }
}
