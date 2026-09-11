package com.openggf.physics;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.ScrollHandlerProvider;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.level.scroll.ZoneScrollHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.level.ChunkDesc;
import com.openggf.level.CollisionMode;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.SolidTile;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GroundMode;
import java.lang.reflect.Field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TestGroundSensor {

    private LevelManager mockLevelManager;
    private AbstractPlayableSprite mockSprite;

    private ChunkDesc[][] fgChunkMap;
    private ChunkDesc[][] bgChunkMap;
    private SolidTile[] tiles;

    @BeforeEach
    public void setUp() {
        fgChunkMap = new ChunkDesc[20][20];
        bgChunkMap = new ChunkDesc[20][20];

        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SessionManager.clear();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();

        mockLevelManager = mock(LevelManager.class);
        when(mockLevelManager.getChunkDescAt(anyByte(), anyInt(), anyInt()))
                .thenAnswer(invocation -> {
                    byte layer = invocation.getArgument(0);
                    int x = invocation.getArgument(1);
                    int y = invocation.getArgument(2);
                    int gridX = x / 16;
                    int gridY = y / 16;
                    if (gridX >= 0 && gridX < 20 && gridY >= 0 && gridY < 20) {
                        return chunkMapForLayer(layer)[gridX][gridY];
                    }
                    return null;
                });
        when(mockLevelManager.getChunkDescAt(anyByte(), anyInt(), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    byte layer = invocation.getArgument(0);
                    int x = invocation.getArgument(1);
                    int y = invocation.getArgument(2);
                    int gridX = x / 16;
                    int gridY = y / 16;
                    if (gridX >= 0 && gridX < 20 && gridY >= 0 && gridY < 20) {
                        return chunkMapForLayer(layer)[gridX][gridY];
                    }
                    return null;
                });
        when(mockLevelManager.getSolidTileForChunkDesc(any(ChunkDesc.class), anyInt()))
                .thenAnswer(invocation -> {
                    ChunkDesc chunkDesc = invocation.getArgument(0);
                    return chunkDesc == null ? null : tiles[chunkDesc.getChunkIndex()];
                });
        when(mockLevelManager.getSolidTileForChunkDesc(any(ChunkDesc.class), anyByte()))
                .thenAnswer(invocation -> {
                    ChunkDesc chunkDesc = invocation.getArgument(0);
                    return chunkDesc == null ? null : tiles[chunkDesc.getChunkIndex()];
                });
        when(mockLevelManager.getSolidTileForChunkDesc(any(ChunkDesc.class)))
                .thenAnswer(invocation -> {
                    ChunkDesc chunkDesc = invocation.getArgument(0);
                    return chunkDesc == null ? null : tiles[chunkDesc.getChunkIndex()];
                });

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

    @AfterEach
    void tearDown() {
        GroundSensor.setLevelManager(null);
        SessionManager.clear();
    }

    private void setTileAt(int x, int y, int tileIndex) {
        setTileAt((byte) 0, x, y, tileIndex, CollisionMode.ALL_SOLID);
    }

    private void setTileAt(int x, int y, int tileIndex, CollisionMode mode) {
        setTileAt((byte) 0, x, y, tileIndex, mode);
    }

    private void setTileAt(byte layer, int x, int y, int tileIndex) {
        setTileAt(layer, x, y, tileIndex, CollisionMode.ALL_SOLID);
    }

    private void setTileAt(byte layer, int x, int y, int tileIndex, CollisionMode mode) {
        int gridX = x / 16;
        int gridY = y / 16;
        ChunkDesc desc = new ChunkDesc(tileIndex);
        // We need to set collision mode bits in the index
        // Mode is bits 12-13 (Primary) and 14-15 (Secondary)
        // Shift mode value to 12
        int modeBits = mode.getValue() << 12;
        desc.set(tileIndex | modeBits);

        chunkMapForLayer(layer)[gridX][gridY] = desc;
    }

    private ChunkDesc[][] chunkMapForLayer(byte layer) {
        return layer == 1 ? bgChunkMap : fgChunkMap;
    }

    @Test
    public void resetAllClearsStaticLevelManagerOverride() throws Exception {
        assertNotNull(groundSensorOverrideLevelManager(),
                "setUp should install a GroundSensor level-manager override");

        TestEnvironment.resetAll();

        assertNull(groundSensorOverrideLevelManager(),
                "Test reset must clear GroundSensor's static level-manager override");
    }

    private static Object groundSensorOverrideLevelManager() throws Exception {
        Field field = GroundSensor.class.getDeclaredField("overrideLevelManager");
        field.setAccessible(true);
        return field.get(null);
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

        assertNotNull(result, "Should find extended tile");
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

        assertNotNull(result, "Should find extended tile");
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

        assertNotNull(result, "Should find regressed tile");
        assertEquals(-9, result.distance());
    }

    @Test
    public void backgroundVerticalScanUsesNativeFullTileRegression() throws Exception {
        // FindFloor selects Find_Tile_BG but otherwise keeps the same full-height
        // regression state machine as the foreground path. A full BG tile at the
        // probe regresses into the half-height tile above, returning -9 rather
        // than the simplified current-tile result -1.
        setTileAt((byte) 1, 100, 112, 1);
        setTileAt((byte) 1, 100, 96, 2);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 112);
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        setParallaxField("cachedBgCameraX", Integer.MIN_VALUE);
        setParallaxField("vscrollFactorBG", (short) 0);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = invokeBackgroundScan(sensor, (short) 100, (short) 112,
                mockSprite.getTopSolidBit(), Direction.DOWN, true);

        assertNotNull(result, "background scan should find the regressed tile");
        assertEquals(-9, result.distance());
        assertEquals(2, result.tileId());
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

        assertEquals(10, rotated[0], "X should be y = 10");
        assertEquals(5, rotated[1], "Y should be x = 5");
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

        assertEquals(-9, rotated[0], "X should remain -9 (left side)");
        assertEquals(-19, rotated[1], "Y should be negated to -19");
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

        assertEquals(9, rotated[0], "X should remain 9 (right side)");
        assertEquals(-19, rotated[1], "Y should be negated to -19");
    }

    @Test
    public void ceilingEmptyExtensionDistanceUsesMirroredLowNibble() {
        // No ceiling tile is present. ROM WalkCeiling mirrors the probe low Y
        // nibble before FindFloor returns its empty extension default.
        // Center 100 with y_radius 19 gives probe Y 81 (low nibble 1);
        // mirrored low nibble is 14, so distance is 31 - 14 = 17.
        mockSprite.setGroundMode(GroundMode.CEILING);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 19, true);
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(17, result.distance());
    }

    @Test
    public void upwardCeilingProbeAboveLevelTopUsesBlankChunkDistance() {
        // S1 Sonic_FindCeiling transforms obY-obHeight and calls FindFloor;
        // FindNearestTile masks above-top lookups into the layout window. If
        // that wrapped row is blank, the result is non-penetrating rather than
        // a hard absolute-top ceiling collision.
        mockSprite.setGroundMode(GroundMode.GROUND);
        mockSprite.setX((short) 100);
        mockSprite.setY((short) 15);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.UP, (byte) 0, (byte) -19, true);
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(Direction.UP, result.direction());
        assertTrue(result.distance() > 0);
    }

    @Test
    public void upwardCeilingProbeAboveLevelTopUsesRomWrappedLookupWhenSolid() {
        // The same above-top probe can collide when the ROM's masked layout
        // row contains solid terrain.
        ChunkDesc wrappedSolid = new ChunkDesc(1 | (CollisionMode.ALL_SOLID.getValue() << 12));
        when(mockLevelManager.getChunkDescAt(eq((byte) 0), anyInt(), eq(0x07F3), anyBoolean()))
                .thenReturn(wrappedSolid);

        mockSprite.setGroundMode(GroundMode.GROUND);
        mockSprite.setX((short) 0x0E74);
        mockSprite.setY((short) 15);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.UP, (byte) 0, (byte) -19, true);
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(Direction.UP, result.direction());
        assertEquals(-4, result.distance());
    }

    @Test
    public void negativeCeilingLookupUsesPerGameLayoutRowWindow() {
        short rawProbeY = (short) -5;

        assertEquals(0x07F4,
                GroundSensor.verticalTileLookupY(rawProbeY, Direction.UP, 0x07FF));
        assertEquals(0x0FF4,
                GroundSensor.verticalTileLookupY(rawProbeY, Direction.UP, 0x0FFF));
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

        assertEquals(-10, rotated[0], "X should be -y = -10");
        assertEquals(5, rotated[1], "Y should be x = 5");
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

        assertEquals(-9, rotated[0], "X should remain -9");
        assertEquals(19, rotated[1], "Y should remain 19");
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
        assertNotNull(downResult, "DOWN sensor should detect TOP_SOLID");
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
        // Should use the empty-tile extension path, not 4 (distance to the ignored
        // top-solid tile at 80).
        assertNotNull(upResult);
        assertEquals(27, upResult.distance(), "UP sensor should ignore TOP_SOLID");
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
        assertEquals(27, downResult.distance(), "DOWN sensor should ignore L_R_B_SOLID");

        // 2. Check UP sensor. Should detect.
        setTileAt(100, 80, 1, CollisionMode.LEFT_RIGHT_BOTTOM_SOLID);
        GroundSensor upSensor = new GroundSensor(mockSprite, Direction.UP, (byte) 0, (byte) 0, true);
        // If solid: 100 - (80 + 16) = 4.
        SensorResult upResult = upSensor.scan();
        assertEquals(4, upResult.distance(), "UP sensor should detect L_R_B_SOLID");
    }

    @Test
    public void backgroundCollisionPrefersCloserBgFloorOverEmptyFgFallback() throws Exception {
        setTileAt((byte) 0, 100, 112, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 0, 100, 128, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 1, 100, 112, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GameServices.gameState().setBackgroundCollisionFlag(true);
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        setParallaxField("cachedBgCameraX", Integer.MIN_VALUE);
        setParallaxField("vscrollFactorBG", (short) 0);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult bgOnly = invokeBackgroundScan(sensor, (short) 100, (short) 100, mockSprite.getTopSolidBit(),
                Direction.DOWN, true);
        assertNotNull(bgOnly, "background scan should find the BG floor tile");
        assertEquals(11, bgOnly.distance(),
                "background scan alone should report the closer BG floor tile from the extension pass");
        SensorResult result = sensor.scan();

        assertNotNull(result);
        assertEquals(11, result.distance(),
                "BG floor should beat the empty FG fallback when it is closer to the probe");
        assertEquals(1, result.tileId(),
                "Result should come from the BG floor tile, not the empty FG path");
    }

    @Test
    public void backgroundCollisionUsesLiveHandlerStateWhenParallaxCacheIsStale() throws Exception {
        setTileAt((byte) 0, 100, 112, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 0, 100, 128, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 1, 4, 112, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GameServices.gameState().setBackgroundCollisionFlag(true);
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        when(mockLevelManager.getFeatureZoneId()).thenReturn(7);
        setParallaxField("cachedBgCameraX", Integer.MIN_VALUE);
        setParallaxField("vscrollFactorBG", (short) 0);
        installParallaxHandler(7, new TestZoneScrollHandler(-96, (short) 0));

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.DOWN, (byte) 0, (byte) 0, true);
        SensorResult result = invokeBackgroundScan(sensor, (short) 100, (short) 100, mockSprite.getTopSolidBit(),
                Direction.DOWN, true);

        assertNotNull(result, "background scan should consult live handler state before stale parallax cache");
        assertEquals(11, result.distance(),
                "live handler bgCameraX should translate the probe onto the populated BG tile");
    }

    @Test
    public void backgroundCollisionExtendsHorizontalWallScanOnBgLayer() throws Exception {
        setTileAt((byte) 1, 100, 100, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 1, 116, 100, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GameServices.gameState().setBackgroundCollisionFlag(true);
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        setParallaxField("cachedBgCameraX", Integer.MIN_VALUE);
        setParallaxField("vscrollFactorBG", (short) 0);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.RIGHT, (byte) 0, (byte) 0, true);
        SensorResult result = invokeBackgroundScan(sensor, (short) 100, (short) 100, mockSprite.getLrbSolidBit(),
                Direction.RIGHT, false);

        assertNotNull(result, "background wall scan should extend into the next BG tile");
        assertEquals(11, result.distance(),
                "BG wall extension should mirror the foreground wall-scan distance");
    }

    @Test
    public void explicitWorldScanHonorsBackgroundCollisionForCalcRoomInFront() throws Exception {
        setTileAt((byte) 0, 100, 100, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 0, 116, 100, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 1, 100, 100, 0, CollisionMode.NO_COLLISION);
        setTileAt((byte) 1, 116, 100, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GameServices.gameState().setBackgroundCollisionFlag(true);
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);
        setParallaxField("cachedBgCameraX", Integer.MIN_VALUE);
        setParallaxField("vscrollFactorBG", (short) 0);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.RIGHT, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scanWorld(
                Direction.RIGHT, (short) 0, (short) 0, (short) 0, (short) 0,
                mockSprite.getLrbSolidBit());

        assertNotNull(result, "CalcRoomInFront's world-space scan should consult BG collision");
        assertEquals(11, result.distance(),
                "world-space scan should return the extended BG wall result");
        assertEquals(1, result.tileId(),
                "world-space scan result should come from the background collision layer");
    }

    @Test
    public void backgroundCollisionDoesNotOverwriteCloserForegroundLeftWallHit() {
        setTileAt((byte) 0, 100, 100, 1);

        mockSprite.setX((short) 100);
        mockSprite.setY((short) 100);

        GameServices.gameState().setBackgroundCollisionFlag(true);
        GameServices.camera().setX((short) 0);
        GameServices.camera().setY((short) 0);

        GroundSensor sensor = new GroundSensor(mockSprite, Direction.LEFT, (byte) 0, (byte) 0, true);
        SensorResult result = sensor.scan();

        assertNotNull(result, "foreground left-wall hit should still resolve while BG collision is enabled");
        assertEquals(-12, result.distance(),
                "BG wall fallback must not overwrite a closer penetrating FG wall hit");
        assertEquals(1, result.tileId(),
                "selected wall result should come from the foreground solid tile");
    }

    private void setParallaxField(String fieldName, Object value) throws Exception {
        ParallaxManager parallaxManager = GameServices.parallax();
        Field field = ParallaxManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(parallaxManager, value);
    }

    private void installParallaxHandler(int zoneId, ZoneScrollHandler handler) throws Exception {
        ScrollHandlerProvider provider = new ScrollHandlerProvider() {
            @Override
            public void load(com.openggf.data.Rom rom) {
            }

            @Override
            public ZoneScrollHandler getHandler(int zoneIndex) {
                return zoneIndex == zoneId ? handler : null;
            }

            @Override
            public ZoneConstants getZoneConstants() {
                return mock(ZoneConstants.class);
            }
        };
        ParallaxManager parallaxManager = GameServices.parallax();
        Field field = ParallaxManager.class.getDeclaredField("scrollProvider");
        field.setAccessible(true);
        field.set(parallaxManager, provider);
    }

    private SensorResult invokeBackgroundScan(GroundSensor sensor,
                                              short fgX,
                                              short fgY,
                                              int solidityBit,
                                              Direction direction,
                                              boolean vertical) throws Exception {
        var method = GroundSensor.class.getDeclaredMethod(
                "scanBackgroundCollision",
                LevelManager.class, short.class, short.class, int.class, Direction.class, boolean.class);
        method.setAccessible(true);
        return (SensorResult) method.invoke(sensor, mockLevelManager, fgX, fgY, solidityBit, direction, vertical);
    }

    private record TestZoneScrollHandler(int bgCameraX, short bgVscroll) implements ZoneScrollHandler {
        @Override
        public void update(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter, int actId) {
        }

        @Override
        public short getVscrollFactorBG() {
            return bgVscroll;
        }

        @Override
        public int getMinScrollOffset() {
            return 0;
        }

        @Override
        public int getMaxScrollOffset() {
            return 0;
        }

        @Override
        public int getBgCameraX() {
            return bgCameraX;
        }
    }
}
