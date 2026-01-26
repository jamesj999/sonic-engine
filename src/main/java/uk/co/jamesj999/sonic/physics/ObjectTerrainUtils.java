package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;

/**
 * Terrain collision detection for game objects.
 * Mirrors ROM's ObjCheckFloorDist (s2.asm:43738) for floor/ceiling/wall detection
 * used by falling objects, animals, monitors, etc.
 *
 * Unlike player collision (paired sensors), object collision uses single-point checks.
 */
public final class ObjectTerrainUtils {

    /** Solidity bit for top-solid collision (walkable from above) */
    private static final int SOLIDITY_TOP = 0x0C;

    /** Solidity bit for all-sides-solid collision */
    private static final int SOLIDITY_ALL = 0x0D;

    private static final int FULL_TILE = 16;

    private ObjectTerrainUtils() {}

    // ========================================
    // PUBLIC API
    // ========================================

    /** Check distance to floor from object bottom (x, y + yRadius) */
    public static TerrainCheckResult checkFloorDist(int x, int y, int yRadius) {
        return checkFloorDistAtPoint(x, y + yRadius);
    }

    /** Check distance to floor from exact point */
    public static TerrainCheckResult checkFloorDist(int x, int y) {
        return checkFloorDistAtPoint(x, y);
    }

    /** Check distance to ceiling from object top (x, y - yRadius) */
    public static TerrainCheckResult checkCeilingDist(int x, int y, int yRadius) {
        return checkCeilingDistAtPoint(x, y - yRadius);
    }

    /** Check distance to right wall (ROM: ObjCheckRightWallDist s2.asm:43871) */
    public static TerrainCheckResult checkRightWallDist(int x, int y) {
        return checkWallDistAtPoint(x, y, false);
    }

    /** Check distance to left wall (ROM: ObjCheckLeftWallDist s2.asm:44063) */
    public static TerrainCheckResult checkLeftWallDist(int x, int y) {
        return checkWallDistAtPoint(x, y, true);
    }

    // ========================================
    // FLOOR COLLISION
    // ========================================

    private static TerrainCheckResult checkFloorDistAtPoint(int x, int y) {
        LevelManager lm = LevelManager.getInstance();
        if (lm == null) return TerrainCheckResult.noCollision();

        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);

        if (metric == 0) {
            // No surface - extend 16 pixels down
            return checkFloorExtension(lm, x, y);
        }

        if (metric == FULL_TILE) {
            // Full tile - check previous tile up for edge detection
            TerrainCheckResult edgeResult = checkFloorEdge(lm, x, y);
            if (edgeResult != null) return edgeResult;
        }

        return createFloorResult(tile, desc, metric, y, y);
    }

    private static TerrainCheckResult checkFloorExtension(LevelManager lm, int x, int y) {
        int nextY = y + 16;
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, nextY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);

        if (metric > 0) {
            return createFloorResult(tile, desc, metric, y, nextY);
        }
        return TerrainCheckResult.noCollision();
    }

    private static TerrainCheckResult checkFloorEdge(LevelManager lm, int x, int y) {
        int prevY = y - 16;
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, prevY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);

        if (metric > 0 && metric < FULL_TILE) {
            return createFloorResult(tile, desc, metric, y, prevY);
        }
        return null;
    }

    private static TerrainCheckResult createFloorResult(SolidTile tile, ChunkDesc desc,
                                                        byte metric, int checkY, int tileY) {
        // ROM formula (FindFloor s2.asm:42994-42999):
        // dist = 15 - (metric + (tileY & 0xF)) + (tileY - checkY)
        int yInTile = tileY & 0x0F;
        int dist = 15 - (metric + yInTile) + (tileY - checkY);
        return new TerrainCheckResult(dist, getAngle(tile), getTileIndex(desc));
    }

    // ========================================
    // CEILING COLLISION
    // ========================================

    private static TerrainCheckResult checkCeilingDistAtPoint(int x, int y) {
        LevelManager lm = LevelManager.getInstance();
        if (lm == null) return TerrainCheckResult.noCollision();

        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getCeilingMetric(tile, desc, y);

        if (metric == 0) {
            return checkCeilingExtension(lm, x, y);
        }

        if (metric == FULL_TILE) {
            TerrainCheckResult edgeResult = checkCeilingEdge(lm, x, y);
            if (edgeResult != null) return edgeResult;
        }

        return createCeilingResult(tile, desc, metric, y, y);
    }

    private static TerrainCheckResult checkCeilingExtension(LevelManager lm, int x, int y) {
        int prevY = y - 16;
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, prevY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getCeilingMetric(tile, desc, prevY);

        if (metric > 0) {
            return createCeilingResult(tile, desc, metric, y, prevY);
        }
        return TerrainCheckResult.noCollision();
    }

    private static TerrainCheckResult checkCeilingEdge(LevelManager lm, int x, int y) {
        int nextY = y + 16;
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, nextY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getCeilingMetric(tile, desc, nextY);

        if (metric > 0 && metric < FULL_TILE) {
            return createCeilingResult(tile, desc, metric, y, nextY);
        }
        return null;
    }

    private static TerrainCheckResult createCeilingResult(SolidTile tile, ChunkDesc desc,
                                                          byte metric, int checkY, int tileY) {
        int tileTop = tileY & ~0x0F;
        int surfaceY = tileTop + metric - 1;
        int dist = checkY - surfaceY;
        return new TerrainCheckResult(dist, getAngle(tile), getTileIndex(desc));
    }

    // ========================================
    // WALL COLLISION
    // ========================================

    private static TerrainCheckResult checkWallDistAtPoint(int x, int y, boolean checkingLeft) {
        LevelManager lm = LevelManager.getInstance();
        if (lm == null) return TerrainCheckResult.noCollision();

        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, desc, y, checkingLeft);

        if (metric == 0) {
            return checkWallExtension(lm, x, y, checkingLeft);
        }

        if (metric == FULL_TILE) {
            TerrainCheckResult edgeResult = checkWallEdge(lm, x, y, checkingLeft);
            if (edgeResult != null) return edgeResult;
        }

        return createWallResult(tile, desc, metric, x, checkingLeft, 0);
    }

    private static TerrainCheckResult checkWallExtension(LevelManager lm, int x, int y, boolean checkingLeft) {
        // ROM adds 16 to distance when extending (s2.asm:43207)
        int nextX = checkingLeft ? (x - 16) : (x + 16);
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, nextX, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, desc, y, checkingLeft);

        if (metric > 0) {
            return createWallResult(tile, desc, metric, x, checkingLeft, 16);
        }
        return TerrainCheckResult.noCollision();
    }

    private static TerrainCheckResult checkWallEdge(LevelManager lm, int x, int y, boolean checkingLeft) {
        // ROM subtracts 16 from distance when checking previous (s2.asm:43264)
        int prevX = checkingLeft ? (x + 16) : (x - 16);
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, prevX, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, desc, y, checkingLeft);

        if (metric > 0 && metric < FULL_TILE) {
            return createWallResult(tile, desc, metric, x, checkingLeft, -16);
        }
        return null;
    }

    private static TerrainCheckResult createWallResult(SolidTile tile, ChunkDesc desc,
                                                       byte metric, int checkX, boolean checkingLeft, int tileOffset) {
        // ROM formula (FindWall s2.asm:43246-43251)
        int xInTile = checkX & 0x0F;
        int dist = checkingLeft
                ? (xInTile - metric) + tileOffset
                : (15 - (metric + xInTile)) + tileOffset;
        return new TerrainCheckResult(dist, getAngle(tile), getTileIndex(desc));
    }

    // ========================================
    // METRIC HELPERS
    // ========================================

    private static SolidTile getSolidTile(LevelManager lm, ChunkDesc desc, int solidityBit) {
        if (desc == null || !desc.isSolidityBitSet(solidityBit)) {
            return null;
        }
        return lm.getSolidTileForChunkDesc(desc, solidityBit);
    }

    private static byte getHeightMetric(SolidTile tile, ChunkDesc desc, int x) {
        if (tile == null) return 0;

        int index = x & 0x0F;
        if (desc != null && desc.getHFlip()) {
            index = 15 - index;
        }

        byte metric = tile.getHeightAt((byte) index);
        if (metric != 0 && metric != FULL_TILE && desc != null && desc.getVFlip()) {
            metric = (byte) (16 - metric);
        }
        return metric;
    }

    private static byte getCeilingMetric(SolidTile tile, ChunkDesc desc, int y) {
        if (tile == null) return 0;

        int index = y & 0x0F;
        if (desc != null && desc.getVFlip()) {
            index = 15 - index;
        }

        byte metric = tile.getWidthAt((byte) index);
        if (metric != 0 && metric != FULL_TILE && desc != null && desc.getHFlip()) {
            metric = (byte) (16 - metric);
        }
        return metric;
    }

    private static byte getWallMetric(SolidTile tile, ChunkDesc desc, int y, boolean checkingLeft) {
        if (tile == null) return 0;

        int index = y & 0x0F;
        if (desc != null && desc.getVFlip()) {
            index = 15 - index;
        }

        byte metric = tile.getWidthAt((byte) index);
        boolean hFlip = desc != null && desc.getHFlip();

        // Handle H-flip for opposite side collision
        if (checkingLeft != hFlip && metric != 0 && metric != FULL_TILE) {
            metric = (byte) (16 - metric);
        }
        return metric;
    }

    private static byte getAngle(SolidTile tile) {
        return tile != null ? tile.getAngle() : 0;
    }

    private static int getTileIndex(ChunkDesc desc) {
        return desc != null ? desc.getChunkIndex() : -1;
    }
}
