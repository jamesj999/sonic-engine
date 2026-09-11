package com.openggf.physics;

import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelManager;
import com.openggf.level.SolidTile;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestObjectTerrainUtils {

    @Test
    void getAngle_ignoresChunkFlipFlagsToPreserveObjectTerrainConvention() throws Exception {
        SolidTile tile = createMirroredTile();
        ChunkDesc mirroredDesc = new ChunkDesc(7 | 0x0400 | 0x0800);

        Method method = ObjectTerrainUtils.class.getDeclaredMethod("getAngle", SolidTile.class, ChunkDesc.class);
        method.setAccessible(true);
        byte angle = (byte) method.invoke(null, tile, mirroredDesc);

        assertEquals(tile.getAngle(), angle);
        assertNotEquals(tile.getAngle(true, true), angle);
    }

    @Test
    void getAngle_flipAwareModeAppliesChunkFlipFlagsForMgzPlatformPath() throws Exception {
        SolidTile tile = createMirroredTile();
        ChunkDesc mirroredDesc = new ChunkDesc(7 | 0x0400 | 0x0800);

        Method method = ObjectTerrainUtils.class.getDeclaredMethod(
                "getAngle", SolidTile.class, ChunkDesc.class, boolean.class);
        method.setAccessible(true);
        byte angle = (byte) method.invoke(null, tile, mirroredDesc, true);

        assertEquals(tile.getAngle(true, true), angle);
        assertNotEquals(tile.getAngle(), angle);
    }

    @Test
    void floorRegressToPreviousSlopeUsesSingleRomTileOffset() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc desc = new ChunkDesc(7 | 0x1000);
        SolidTile previousTile = createFlatHeightTile(7, (byte) 8, (byte) 0x20);
        SolidTile originalTile = createFlatHeightTile(3, (byte) -8, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x1000);
        int x = 0x2345;
        int y = 0x1234;

        when(level.getChunkDescAt((byte) 0, x, y - 16)).thenReturn(desc);
        when(level.getSolidTileForChunkDesc(eq(desc), eq(0x0C), anyBoolean())).thenReturn(previousTile);

        TerrainCheckResult result = invokeFloorRegress(level, originalTile, originalDesc, x, y, false);

        // sub_F30C returns 15 - (8 + 4), then loc_F2FE subtracts $10.
        assertEquals(-13, result.distance());
    }

    @Test
    void floorRegressToEmptyPreviousTileMatchesSubF30CEmptyResult() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc emptyDesc = new ChunkDesc(0);
        SolidTile originalTile = createFlatHeightTile(3, (byte) -8, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x1000);
        int x = 0x2345;
        int y = 0x1234;

        when(level.getChunkDescAt((byte) 0, x, y - 16)).thenReturn(emptyDesc);

        TerrainCheckResult result = invokeFloorRegress(level, originalTile, originalDesc, x, y, false);

        // sub_F30C empty path returns 15 - 4, then loc_F2FE subtracts $10.
        assertEquals(-5, result.distance());
        assertEquals((byte) 0x10, result.angle());
        assertEquals(3, result.tileIndex());
    }

    @Test
    void floorFullTileEdgeKeepsPreviousCollisionAngleWhenItsSampleIsEmpty() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc previousDesc = new ChunkDesc(7 | 0x1000);
        SolidTile previousTile = createFlatHeightTile(7, (byte) 0, (byte) 0x20);
        SolidTile originalTile = createFlatHeightTile(3, (byte) 16, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x1000);
        int x = 0x2345;
        int y = 0x1234;

        when(level.getChunkDescAt((byte) 0, x, y - 16)).thenReturn(previousDesc);
        when(level.getSolidTileForChunkDesc(eq(previousDesc), eq(0x0C), anyBoolean())).thenReturn(previousTile);

        TerrainCheckResult result = invokeFloorEdge(level, originalTile, originalDesc, x, y, false);

        // sub_F30C stores the previous collision shape's angle before its zero-height branch.
        assertEquals(-5, result.distance());
        assertEquals((byte) 0x20, result.angle());
        assertEquals(7, result.tileIndex());
    }

    @Test
    void floorFullTileEdgeChecksPreviousFullTileLikeSubF30C() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc previousDesc = new ChunkDesc(7 | 0x1000);
        SolidTile previousTile = createFlatHeightTile(7, (byte) 16, (byte) 0x20);
        SolidTile originalTile = createFlatHeightTile(3, (byte) 16, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x1000);
        int x = 0x2345;
        int y = 0x1234;

        when(level.getChunkDescAt((byte) 0, x, y - 16)).thenReturn(previousDesc);
        when(level.getSolidTileForChunkDesc(eq(previousDesc), eq(0x0C), anyBoolean())).thenReturn(previousTile);

        TerrainCheckResult result = invokeFloorEdge(level, originalTile, originalDesc, x, y, false);

        // Current full tile takes loc_F2FE: sub_F30C(previous full) then subtract $10.
        assertEquals(-21, result.distance());
    }

    @Test
    void wallFullTileEdgeChecksPreviousFullTileLikeSubF584() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc previousDesc = new ChunkDesc(7 | 0x2000);
        SolidTile previousTile = createFlatWidthTile(7, (byte) 16, (byte) 0x20);
        SolidTile originalTile = createFlatWidthTile(3, (byte) 16, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x2000);
        int x = 0x2344;
        int y = 0x1235;

        when(level.getChunkDescAt((byte) 0, x - 16, y)).thenReturn(previousDesc);
        when(level.getSolidTileForChunkDesc(eq(previousDesc), eq(0x0D), anyBoolean())).thenReturn(previousTile);

        TerrainCheckResult result = invokeWallEdge(level, originalTile, originalDesc, x, y, false, false);

        // Current full wall takes loc_F576: sub_F584(previous full) then subtract $10.
        assertEquals(-21, result.distance());
    }

    @Test
    void leftWallFullTileEdgeUsesMirroredDefaultDistanceForEmptyPreviousTile() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc emptyDesc = new ChunkDesc(0);
        SolidTile originalTile = createFlatWidthTile(3, (byte) 16, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x2000);
        int x = 0x2344;
        int y = 0x1235;

        when(level.getChunkDescAt((byte) 0, x + 16, y)).thenReturn(emptyDesc);

        TerrainCheckResult result = invokeWallEdge(level, originalTile, originalDesc, x, y, true, false);

        // Left-wall probes mirror x before FindWall, so sub_F584 empty default is xInTile, then loc_F576 subtracts $10.
        assertEquals(-12, result.distance());
    }

    @Test
    void leftWallFullTileEdgeUsesMirroredNegativeDistanceForPreviousTile() throws Exception {
        LevelManager level = mock(LevelManager.class);
        ChunkDesc previousDesc = new ChunkDesc(7 | 0x2000 | 0x0400);
        SolidTile previousTile = createFlatWidthTile(7, (byte) -12, (byte) 0x20);
        SolidTile originalTile = createFlatWidthTile(3, (byte) 16, (byte) 0x10);
        ChunkDesc originalDesc = new ChunkDesc(3 | 0x2000);
        int x = 0x2344;
        int y = 0x1235;

        when(level.getChunkDescAt((byte) 0, x + 16, y)).thenReturn(previousDesc);
        when(level.getSolidTileForChunkDesc(eq(previousDesc), eq(0x0D), anyBoolean())).thenReturn(previousTile);

        TerrainCheckResult result = invokeWallEdge(level, originalTile, originalDesc, x, y, true, false);

        // sub_F584 negative path uses ~(mirrored xInTile), then loc_F576 subtracts $10.
        assertEquals(-28, result.distance());
    }

    private static SolidTile createMirroredTile() {
        SolidTile tile = new SolidTile(
                7,
                new byte[SolidTile.TILE_SIZE_IN_ROM],
                new byte[SolidTile.TILE_SIZE_IN_ROM],
                (byte) 0x20);
        return tile;
    }

    private static SolidTile createFlatHeightTile(int index, byte height, byte angle) {
        byte[] heights = new byte[SolidTile.TILE_SIZE_IN_ROM];
        for (int i = 0; i < heights.length; i++) {
            heights[i] = height;
        }
        return new SolidTile(index, heights, new byte[SolidTile.TILE_SIZE_IN_ROM], angle);
    }

    private static SolidTile createFlatWidthTile(int index, byte width, byte angle) {
        byte[] widths = new byte[SolidTile.TILE_SIZE_IN_ROM];
        for (int i = 0; i < widths.length; i++) {
            widths[i] = width;
        }
        return new SolidTile(index, new byte[SolidTile.TILE_SIZE_IN_ROM], widths, angle);
    }

    private static TerrainCheckResult invokeFloorRegress(LevelManager level,
                                                         SolidTile originalTile,
                                                         ChunkDesc originalDesc,
                                                         int x,
                                                         int y,
                                                         boolean flipAwareAngle) throws Exception {
        Method method = ObjectTerrainUtils.class.getDeclaredMethod(
                "checkFloorRegress",
                LevelManager.class,
                SolidTile.class,
                ChunkDesc.class,
                int.class,
                int.class,
                boolean.class,
                byte.class);
        method.setAccessible(true);
        return (TerrainCheckResult) method.invoke(
                null, level, originalTile, originalDesc, x, y, flipAwareAngle, (byte) 0);
    }

    private static TerrainCheckResult invokeFloorEdge(LevelManager level,
                                                      SolidTile originalTile,
                                                      ChunkDesc originalDesc,
                                                      int x,
                                                      int y,
                                                      boolean flipAwareAngle) throws Exception {
        Method method = ObjectTerrainUtils.class.getDeclaredMethod(
                "checkFloorEdge",
                LevelManager.class,
                SolidTile.class,
                ChunkDesc.class,
                int.class,
                int.class,
                boolean.class,
                byte.class);
        method.setAccessible(true);
        return (TerrainCheckResult) method.invoke(
                null, level, originalTile, originalDesc, x, y, flipAwareAngle, (byte) 0);
    }

    private static TerrainCheckResult invokeWallEdge(LevelManager level,
                                                     SolidTile originalTile,
                                                     ChunkDesc originalDesc,
                                                     int x,
                                                     int y,
                                                     boolean checkingLeft,
                                                     boolean flipAwareAngle) throws Exception {
        Method method = ObjectTerrainUtils.class.getDeclaredMethod(
                "checkWallEdge",
                LevelManager.class,
                SolidTile.class,
                ChunkDesc.class,
                int.class,
                int.class,
                boolean.class,
                boolean.class);
        method.setAccessible(true);
        return (TerrainCheckResult) method.invoke(
                null, level, originalTile, originalDesc, x, y, checkingLeft, flipAwareAngle);
    }
}
