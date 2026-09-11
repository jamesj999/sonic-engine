package com.openggf.physics;

import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelManager;
import com.openggf.level.SolidTile;

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
        return checkFloorDistAtPoint(x, y + yRadius, false, (byte) 0);
    }

    /**
     * Check distance to floor from object bottom (x, y + yRadius), returning the
     * ROM-transformed terrain angle after chunk H/V flip handling.
     */
    public static TerrainCheckResult checkFloorDistWithFlipAwareAngle(int x, int y, int yRadius) {
        return checkFloorDistAtPoint(x, y + yRadius, true, (byte) 0);
    }

    /** Check distance to floor from exact point */
    public static TerrainCheckResult checkFloorDist(int x, int y) {
        return checkFloorDistAtPoint(x, y, false, (byte) 0);
    }

    /**
     * Check floor distance against an explicit level layout layer. ROM helpers
     * such as S3K {@code Ring_FindFloor} run the same height-map state machine
     * against both foreground and background collision layouts.
     */
    public static TerrainCheckResult checkFloorDistOnLayer(int x, int y, byte layer) {
        return checkFloorDistAtPoint(x, y, false, layer);
    }


    /**
     * Check distance to floor from exact point, returning the ROM-transformed terrain
     * angle after chunk H/V flip handling.
     */
    public static TerrainCheckResult checkFloorDistWithFlipAwareAngle(int x, int y) {
        return checkFloorDistAtPoint(x, y, true, (byte) 0);
    }

    /** Check distance to ceiling from object top (x, y - yRadius) */
    public static TerrainCheckResult checkCeilingDist(int x, int y, int yRadius) {
        return checkCeilingDistAtPoint(x, y - yRadius, false);
    }

    /**
     * Check distance to ceiling from object top (x, y - yRadius), returning the
     * ROM-transformed terrain angle after chunk H/V flip handling.
     */
    public static TerrainCheckResult checkCeilingDistWithFlipAwareAngle(int x, int y, int yRadius) {
        return checkNativeUpwardCeilingDistAtPoint(x, y - yRadius, true);
    }

    /**
     * Check distance to the ceiling through the S3K upward FindFloor entry.
     * This keeps the height-map/X-indexed probe explicit instead of changing
     * the legacy object-ceiling contract used by S1/S2 object families.
     */
    public static TerrainCheckResult checkNativeUpwardCeilingDist(int x, int y, int yRadius) {
        return checkNativeUpwardCeilingDistAtPoint(x, y - yRadius, false);
    }

    /** Check distance to right wall (ROM: ObjCheckRightWallDist s2.asm:43871) */
    public static TerrainCheckResult checkRightWallDist(int x, int y) {
        return checkWallDistAtPoint(x, y, false, false);
    }

    /**
     * Check distance to right wall (ROM: ObjCheckRightWallDist), returning the
     * ROM-transformed terrain angle after chunk H/V flip handling.
     */
    public static TerrainCheckResult checkRightWallDistWithFlipAwareAngle(int x, int y) {
        return checkWallDistAtPoint(x, y, false, true);
    }

    /** Check distance to left wall (ROM: ObjCheckLeftWallDist s2.asm:44063) */
    public static TerrainCheckResult checkLeftWallDist(int x, int y) {
        return checkWallDistAtPoint(x, y, true, false);
    }

    /**
     * Check distance to left wall (ROM: ObjCheckLeftWallDist), returning the
     * ROM-transformed terrain angle after chunk H/V flip handling.
     */
    public static TerrainCheckResult checkLeftWallDistWithFlipAwareAngle(int x, int y) {
        return checkWallDistAtPoint(x, y, true, true);
    }

    // ========================================
    // FLOOR COLLISION
    // ========================================

    private static TerrainCheckResult checkFloorDistAtPoint(
            int x, int y, boolean flipAwareAngle, byte layer) {
        LevelManager lm = com.openggf.game.GameServices.levelOrNull();
        if (lm == null) return TerrainCheckResult.noCollision();

        ChunkDesc desc = lm.getChunkDescAt(layer, x, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);
        if (metric == 0) {
            // No surface - extend 16 pixels down
            return checkFloorExtension(lm, x, y, flipAwareAngle, layer);
        }

        // ROM: neg.w produces negative metric for V-flipped tiles.
        // FindFloor handles this via the negative metric path (s2.asm:42984-43000).
        if (metric < 0) {
            int yInTile = y & 0x0F;
            int adjusted = metric + yInTile;
            if (adjusted >= 0) {
                // No collision in this tile - extend to next tile
                return checkFloorExtension(lm, x, y, flipAwareAngle, layer);
            }
            // Collision found - regress to previous tile
            return checkFloorRegress(lm, tile, desc, x, y, flipAwareAngle, layer);
        }

        if (metric == FULL_TILE) {
            // Full tile - check previous tile up for edge detection
            TerrainCheckResult edgeResult = checkFloorEdge(
                    lm, tile, desc, x, y, flipAwareAngle, layer);
            if (edgeResult != null) return edgeResult;
        }

        return createFloorResult(tile, desc, metric, y, y, flipAwareAngle);
    }

    private static TerrainCheckResult checkFloorExtension(
            LevelManager lm, int x, int y, boolean flipAwareAngle, byte layer) {
        int nextY = y + 16;
        ChunkDesc desc = lm.getChunkDescAt(layer, x, nextY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);

        if (metric > 0) {
            return createFloorResult(tile, desc, metric, y, nextY, flipAwareAngle);
        }
        // Handle negative metric in extension (FindFloor2 path)
        if (metric < 0) {
            int yInTile = y & 0x0F;
            int adjusted = metric + yInTile;
            if (adjusted < 0) {
                // ROM FindFloor2: not.w d1 where d1 = yInTile
                int dist = ~yInTile + 16; // +16 for extension tile offset
                return new TerrainCheckResult(dist, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
            }
        }
        return TerrainCheckResult.noCollision();
    }

    /** Regress to previous tile when negative metric indicates collision from below */
    private static TerrainCheckResult checkFloorRegress(LevelManager lm, SolidTile origTile,
                                                         ChunkDesc origDesc, int x, int y,
                                                         boolean flipAwareAngle, byte layer) {
        int prevY = y - 16;
        ChunkDesc desc = lm.getChunkDescAt(layer, x, prevY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);

        return createPreviousFloorResult(tile, desc, origTile, origDesc, metric, x, y, prevY, flipAwareAngle);
    }

    private static TerrainCheckResult checkFloorEdge(LevelManager lm, SolidTile origTile, ChunkDesc origDesc,
                                                     int x, int y, boolean flipAwareAngle, byte layer) {
        int prevY = y - 16;
        ChunkDesc desc = lm.getChunkDescAt(layer, x, prevY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, desc, x);

        return createPreviousFloorResult(tile, desc, origTile, origDesc, metric, x, y, prevY, flipAwareAngle);
    }

    private static TerrainCheckResult createPreviousFloorResult(SolidTile tile, ChunkDesc desc,
                                                                SolidTile origTile, ChunkDesc origDesc,
                                                                byte metric, int x, int y, int prevY,
                                                                boolean flipAwareAngle) {
        int yInTile = y & 0x0F;
        if (metric == 0) {
            int dist = 15 - yInTile - 16;
            return createFloorRegressDefaultResult(
                    tile, desc, origTile, origDesc, dist, flipAwareAngle);
        }
        if (metric < 0) {
            int adjusted = metric + yInTile;
            if (adjusted >= 0) {
                int dist = 15 - yInTile - 16;
                return createFloorRegressDefaultResult(
                        tile, desc, origTile, origDesc, dist, flipAwareAngle);
            }
            int dist = ~yInTile - 16;
            return new TerrainCheckResult(dist, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
        }
        return createFloorResult(tile, desc, metric, y, prevY, flipAwareAngle);
    }

    private static TerrainCheckResult createFloorRegressDefaultResult(
            SolidTile tile, ChunkDesc desc, SolidTile origTile, ChunkDesc origDesc,
            int distance, boolean flipAwareAngle) {
        // ROM sub_F30C writes the prior tile's angle before sampling its height.
        // Only a missing collision shape leaves the original tile's angle intact.
        if (tile != null) {
            return new TerrainCheckResult(
                    distance, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
        }
        return new TerrainCheckResult(
                distance, getAngle(origTile, origDesc, flipAwareAngle), getTileIndex(origDesc));
    }

    private static TerrainCheckResult createFloorResult(SolidTile tile, ChunkDesc desc,
                                                        byte metric, int checkY, int tileY,
                                                        boolean flipAwareAngle) {
        // ROM formula (FindFloor s2.asm:42994-42999):
        // dist = 15 - (metric + (tileY & 0xF)) + (tileY - checkY)
        int yInTile = tileY & 0x0F;
        int dist = 15 - (metric + yInTile) + (tileY - checkY);
        return new TerrainCheckResult(dist, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
    }

    // ========================================
    // CEILING COLLISION
    // ========================================

    private static TerrainCheckResult checkCeilingDistAtPoint(int x, int y, boolean flipAwareAngle) {
        LevelManager lm = com.openggf.game.GameServices.levelOrNull();
        if (lm == null) return TerrainCheckResult.noCollision();

        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getCeilingMetric(tile, desc, y);

        if (metric == 0) {
            return checkCeilingExtension(lm, x, y, flipAwareAngle);
        }

        if (metric == FULL_TILE) {
            TerrainCheckResult edgeResult = checkCeilingEdge(lm, x, y, flipAwareAngle);
            if (edgeResult != null) return edgeResult;
        }

        return createLegacyCeilingResult(tile, desc, metric, y, y, flipAwareAngle);
    }

    private static TerrainCheckResult checkCeilingExtension(LevelManager lm, int x, int y,
                                                            boolean flipAwareAngle) {
        int prevY = y - 16;
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, prevY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getCeilingMetric(tile, desc, prevY);

        if (metric > 0) {
            return createLegacyCeilingResult(tile, desc, metric, y, prevY, flipAwareAngle);
        }
        return TerrainCheckResult.noCollision();
    }

    private static TerrainCheckResult checkCeilingEdge(LevelManager lm, int x, int y,
                                                       boolean flipAwareAngle) {
        int nextY = y + 16;
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, nextY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getCeilingMetric(tile, desc, nextY);

        if (metric > 0 && metric < FULL_TILE) {
            return createLegacyCeilingResult(tile, desc, metric, y, nextY, flipAwareAngle);
        }
        return null;
    }

    private static TerrainCheckResult createLegacyCeilingResult(SolidTile tile, ChunkDesc desc,
                                                                 byte metric, int checkY, int tileY,
                                                                 boolean flipAwareAngle) {
        int tileTop = tileY & ~0x0F;
        int surfaceY = tileTop + metric - 1;
        int dist = checkY - surfaceY;
        return new TerrainCheckResult(dist, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
    }

    private static TerrainCheckResult checkNativeUpwardCeilingDistAtPoint(
            int x, int y, boolean flipAwareAngle) {
        LevelManager lm = com.openggf.game.GameServices.levelOrNull();
        if (lm == null) return TerrainCheckResult.noCollision();

        TerrainCheckResult current = scanCeilingTile(lm, x, y, y, false, flipAwareAngle);
        if (current != null) {
            return current;
        }
        TerrainCheckResult extension = scanCeilingTile(
                lm, x, y, y - FULL_TILE, true, flipAwareAngle);
        return extension != null ? extension : TerrainCheckResult.noCollision();
    }

    /**
     * Mirrors FindFloor/FindFloor2 as entered by the native upward probe. The
     * caller's EOR #$F changes the low-nibble arithmetic, while collision data
     * still comes from the height map indexed by X (not the rotated width map).
     */
    private static TerrainCheckResult scanCeilingTile(
            LevelManager lm, int x, int originalY, int checkY,
            boolean extension, boolean flipAwareAngle) {
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, checkY);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        if (tile == null) {
            return null;
        }

        int metric = getCeilingHeightMetric(tile, desc, x);
        if (metric == 0) {
            return extension
                    ? createCeilingResult(tile, desc, 0, originalY, checkY, flipAwareAngle)
                    : null;
        }

        if (metric < 0) {
            int mirroredYInTile = (originalY ^ 0x0F) & 0x0F;
            if (metric + mirroredYInTile >= 0) {
                return extension
                        ? createCeilingResult(tile, desc, 0, originalY, checkY, flipAwareAngle)
                        : null;
            }
            if (extension) {
                return new TerrainCheckResult(
                        ~mirroredYInTile + FULL_TILE,
                        getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
            }

            int regressY = checkY + FULL_TILE;
            TerrainCheckResult regress = scanCeilingTile(
                    lm, x, originalY, regressY, true, flipAwareAngle);
            if (regress != null) {
                return regress;
            }
            return createCeilingResult(tile, desc, 0, originalY, regressY, flipAwareAngle);
        }

        if (metric == FULL_TILE && !extension) {
            int regressY = checkY + FULL_TILE;
            TerrainCheckResult regress = scanCeilingTile(
                    lm, x, originalY, regressY, true, flipAwareAngle);
            if (regress != null) {
                return regress;
            }
            return createCeilingResult(tile, desc, 0, originalY, regressY, flipAwareAngle);
        }

        return createCeilingResult(tile, desc, metric, originalY, checkY, flipAwareAngle);
    }

    private static TerrainCheckResult createCeilingResult(
            SolidTile tile, ChunkDesc desc, int metric,
            int originalY, int tileY, boolean flipAwareAngle) {
        int surfaceY = (tileY & ~0x0F) + metric;
        return new TerrainCheckResult(
                originalY - surfaceY,
                getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
    }

    // ========================================
    // WALL COLLISION
    // ========================================

    private static TerrainCheckResult checkWallDistAtPoint(int x, int y, boolean checkingLeft,
                                                           boolean flipAwareAngle) {
        LevelManager lm = com.openggf.game.GameServices.levelOrNull();
        if (lm == null) return TerrainCheckResult.noCollision();

        ChunkDesc desc = lm.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, desc, y, checkingLeft);

        if (metric == 0) {
            return checkWallExtension(lm, x, y, checkingLeft, flipAwareAngle);
        }

        if (metric == FULL_TILE) {
            TerrainCheckResult edgeResult = checkWallEdge(lm, tile, desc, x, y, checkingLeft, flipAwareAngle);
            if (edgeResult != null) return edgeResult;
        }

        return createWallResult(tile, desc, metric, x, checkingLeft, 0, flipAwareAngle);
    }

    private static TerrainCheckResult checkWallExtension(LevelManager lm, int x, int y, boolean checkingLeft,
                                                         boolean flipAwareAngle) {
        // ROM adds 16 to distance when extending (s2.asm:43207)
        int nextX = checkingLeft ? (x - 16) : (x + 16);
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, nextX, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, desc, y, checkingLeft);

        if (metric > 0) {
            return createWallResult(tile, desc, metric, x, checkingLeft, 16, flipAwareAngle);
        }
        return TerrainCheckResult.noCollision();
    }

    private static TerrainCheckResult checkWallEdge(LevelManager lm, SolidTile origTile, ChunkDesc origDesc,
                                                    int x, int y, boolean checkingLeft, boolean flipAwareAngle) {
        // ROM subtracts 16 from distance when checking previous (s2.asm:43264)
        int prevX = checkingLeft ? (x + 16) : (x - 16);
        ChunkDesc desc = lm.getChunkDescAt((byte) 0, prevX, y);
        SolidTile tile = getSolidTile(lm, desc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, desc, y, checkingLeft);

        return createPreviousWallResult(tile, desc, origTile, origDesc, metric, x, checkingLeft, flipAwareAngle);
    }

    private static TerrainCheckResult createPreviousWallResult(SolidTile tile, ChunkDesc desc,
                                                               SolidTile origTile, ChunkDesc origDesc,
                                                               byte metric, int x, boolean checkingLeft,
                                                               boolean flipAwareAngle) {
        int xInTile = x & 0x0F;
        int xAdjusted = checkingLeft ? (15 - xInTile) : xInTile;
        if (metric == 0) {
            int dist = 15 - xAdjusted - 16;
            return createWallRegressDefaultResult(
                    tile, desc, origTile, origDesc, dist, flipAwareAngle);
        }
        if (metric < 0) {
            int adjusted = metric + xAdjusted;
            if (adjusted >= 0) {
                int dist = 15 - xAdjusted - 16;
                return createWallRegressDefaultResult(
                        tile, desc, origTile, origDesc, dist, flipAwareAngle);
            }
            int dist = ~xAdjusted - 16;
            return new TerrainCheckResult(dist, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
        }
        return createWallResult(tile, desc, metric, x, checkingLeft, -16, flipAwareAngle);
    }

    private static TerrainCheckResult createWallRegressDefaultResult(
            SolidTile tile, ChunkDesc desc, SolidTile origTile, ChunkDesc origDesc,
            int distance, boolean flipAwareAngle) {
        // ROM sub_F584 writes the prior tile's angle before sampling its width.
        // Only a missing collision shape leaves the original tile's angle intact.
        if (tile != null) {
            return new TerrainCheckResult(
                    distance, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
        }
        return new TerrainCheckResult(
                distance, getAngle(origTile, origDesc, flipAwareAngle), getTileIndex(origDesc));
    }

    private static TerrainCheckResult createWallResult(SolidTile tile, ChunkDesc desc,
                                                       byte metric, int checkX, boolean checkingLeft, int tileOffset,
                                                       boolean flipAwareAngle) {
        // ROM formula (FindWall s2.asm:43246-43251)
        int xInTile = checkX & 0x0F;
        int dist = checkingLeft
                ? (xInTile - metric) + tileOffset
                : (15 - (metric + xInTile)) + tileOffset;
        return new TerrainCheckResult(dist, getAngle(tile, desc, flipAwareAngle), getTileIndex(desc));
    }

    // ========================================
    // METRIC HELPERS
    // ========================================

    private static SolidTile getSolidTile(LevelManager lm, ChunkDesc desc, int solidityBit) {
        if (desc == null || !desc.isSolidityBitSet(solidityBit)) {
            return null;
        }
        return lm.getSolidTileForChunkDesc(desc, solidityBit, useSecondaryCollisionPath());
    }

    private static boolean useSecondaryCollisionPath() {
        var camera = com.openggf.game.GameServices.cameraOrNull();
        var focused = camera != null ? camera.getFocusedSprite() : null;
        return focused != null && (focused.getTopSolidBit() & 0xFF) != SOLIDITY_TOP;
    }

    private static byte getHeightMetric(SolidTile tile, ChunkDesc desc, int x) {
        if (tile == null) return 0;

        int index = x & 0x0F;
        if (desc != null && desc.getHFlip()) {
            index = 15 - index;
        }

        byte metric = tile.getHeightAt((byte) index);
        if (metric != 0 && metric != FULL_TILE && desc != null && desc.getVFlip()) {
            // ROM: neg.w d0 (s2.asm:42984-42987) - simple negation
            metric = (byte) -metric;
        }
        return metric;
    }

    private static int getCeilingHeightMetric(SolidTile tile, ChunkDesc desc, int x) {
        if (tile == null) return 0;

        int index = x & 0x0F;
        if (desc != null && desc.getHFlip()) {
            index = 15 - index;
        }

        int metric = tile.getHeightAt((byte) index);
        if (metric != 0 && metric != FULL_TILE
                && (desc == null || !desc.getVFlip())) {
            metric = -metric;
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

    private static byte getAngle(SolidTile tile, ChunkDesc desc) {
        return getAngle(tile, desc, false);
    }

    private static byte getAngle(SolidTile tile, ChunkDesc desc, boolean flipAwareAngle) {
        if (tile == null) {
            return 0;
        }
        if (!flipAwareAngle) {
            return tile.getAngle();
        }
        boolean hFlip = desc != null && desc.getHFlip();
        boolean vFlip = desc != null && desc.getVFlip();
        return tile.getAngle(hFlip, vFlip);
    }

    private static int getTileIndex(ChunkDesc desc) {
        return desc != null ? desc.getChunkIndex() : -1;
    }
}
