package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;

/**
 * Utility class providing terrain collision detection for game objects.
 * <p>
 * This mirrors the ROM's ObjCheckFloorDist subroutine (s2.asm line 43738)
 * which provides floor and ceiling detection for objects like falling pillars,
 * animals, monitors, and other physics-enabled game objects.
 * <p>
 * Unlike player collision (which uses paired sensors), object collision uses
 * a single point check, making this API simpler.
 * <p>
 * <b>Usage example:</b>
 * <pre>{@code
 * // Check floor from bottom of object (y + yRadius)
 * TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(x, y, yRadius);
 * if (result.hasCollision()) {
 *     // Snap to floor: y = y + result.distance()
 *     y = y + result.distance();
 * }
 * }</pre>
 */
public final class ObjectTerrainUtils {

    /**
     * Solidity bit index for top-solid collision (layer 0).
     * Objects check this bit to determine if a tile is solid from above.
     */
    private static final int SOLIDITY_TOP = 0x0C;

    /**
     * Solidity bit index for all-sides-solid collision (layer 0).
     * Used for ceiling checks.
     */
    private static final int SOLIDITY_ALL = 0x0D;

    private ObjectTerrainUtils() {
        // Utility class - no instantiation
    }

    /**
     * Check distance to floor from the bottom of an object.
     * <p>
     * This is the primary API for objects with gravity. It checks from
     * (x, y + yRadius) which represents the bottom center of the object.
     *
     * @param x Object center X position
     * @param y Object center Y position
     * @param yRadius Object's vertical radius (half-height)
     * @return TerrainCheckResult with distance to floor (negative = collision)
     */
    public static TerrainCheckResult checkFloorDist(int x, int y, int yRadius) {
        return checkFloorDistAtPoint(x, y + yRadius);
    }

    /**
     * Check distance to floor from an exact point.
     * <p>
     * Use this when you need to check from a specific position rather than
     * the object's bounding box bottom.
     *
     * @param x X position to check from
     * @param y Y position to check from
     * @return TerrainCheckResult with distance to floor (negative = collision)
     */
    public static TerrainCheckResult checkFloorDist(int x, int y) {
        return checkFloorDistAtPoint(x, y);
    }

    /**
     * Check distance to ceiling from the top of an object.
     * <p>
     * Checks from (x, y - yRadius) which represents the top center of the object.
     *
     * @param x Object center X position
     * @param y Object center Y position
     * @param yRadius Object's vertical radius (half-height)
     * @return TerrainCheckResult with distance to ceiling (negative = collision)
     */
    public static TerrainCheckResult checkCeilingDist(int x, int y, int yRadius) {
        return checkCeilingDistAtPoint(x, y - yRadius);
    }

    /**
     * Check distance to right wall from an object.
     * <p>
     * Mirrors ROM's ObjCheckRightWallDist (s2.asm line 43871).
     * Checks from (x + xOffset, y) for wall collision to the right.
     *
     * @param x X position to check from (typically object center + offset)
     * @param y Y position to check from (typically object center)
     * @return TerrainCheckResult with distance to wall (negative = collision)
     */
    public static TerrainCheckResult checkRightWallDist(int x, int y) {
        return checkWallDistAtPoint(x, y, false);
    }

    /**
     * Check distance to left wall from an object.
     * <p>
     * Mirrors ROM's ObjCheckLeftWallDist (s2.asm line 44063).
     * Checks from (x - xOffset, y) for wall collision to the left.
     *
     * @param x X position to check from (typically object center - offset)
     * @param y Y position to check from (typically object center)
     * @return TerrainCheckResult with distance to wall (negative = collision)
     */
    public static TerrainCheckResult checkLeftWallDist(int x, int y) {
        return checkWallDistAtPoint(x, y, true);
    }

    /**
     * Internal floor distance check at exact position.
     * Implements the same logic as ringCheckFloorDist in LostRingManager.
     */
    private static TerrainCheckResult checkFloorDistAtPoint(int x, int y) {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null) {
            return TerrainCheckResult.noCollision();
        }

        ChunkDesc chunkDesc = levelManager.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(levelManager, chunkDesc, SOLIDITY_TOP);
        byte metric = getHeightMetric(tile, chunkDesc, x, y);

        if (metric == 0) {
            // No solid surface at this tile - check 16 pixels down (extension)
            int nextY = y + 16;
            ChunkDesc nextDesc = levelManager.getChunkDescAt((byte) 0, x, nextY);
            SolidTile nextTile = getSolidTile(levelManager, nextDesc, SOLIDITY_TOP);
            byte nextMetric = getHeightMetric(nextTile, nextDesc, x, nextY);
            if (nextMetric > 0) {
                int dist = calculateDistance(nextMetric, y, nextY);
                byte angle = nextTile != null ? nextTile.getAngle() : 0;
                int tileIdx = nextDesc != null ? nextDesc.getChunkIndex() : -1;
                return new TerrainCheckResult(dist, angle, tileIdx);
            }
            return TerrainCheckResult.noCollision();
        }

        if (metric == 16) {
            // Full height tile - check previous tile up for edge detection
            int prevY = y - 16;
            ChunkDesc prevDesc = levelManager.getChunkDescAt((byte) 0, x, prevY);
            SolidTile prevTile = getSolidTile(levelManager, prevDesc, SOLIDITY_TOP);
            byte prevMetric = getHeightMetric(prevTile, prevDesc, x, prevY);
            if (prevMetric > 0 && prevMetric < 16) {
                int dist = calculateDistance(prevMetric, y, prevY);
                byte angle = prevTile != null ? prevTile.getAngle() : 0;
                int tileIdx = prevDesc != null ? prevDesc.getChunkIndex() : -1;
                return new TerrainCheckResult(dist, angle, tileIdx);
            }
        }

        // Standard case: use current tile's metric
        int dist = calculateDistance(metric, y, y);
        byte angle = tile != null ? tile.getAngle() : 0;
        int tileIdx = chunkDesc != null ? chunkDesc.getChunkIndex() : -1;
        return new TerrainCheckResult(dist, angle, tileIdx);
    }

    /**
     * Internal ceiling distance check at exact position.
     * Mirrors floor check but inverted for ceiling detection.
     */
    private static TerrainCheckResult checkCeilingDistAtPoint(int x, int y) {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null) {
            return TerrainCheckResult.noCollision();
        }

        ChunkDesc chunkDesc = levelManager.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(levelManager, chunkDesc, SOLIDITY_ALL);
        byte metric = getWidthMetric(tile, chunkDesc, x, y);

        if (metric == 0) {
            // No solid surface - check 16 pixels up
            int prevY = y - 16;
            ChunkDesc prevDesc = levelManager.getChunkDescAt((byte) 0, x, prevY);
            SolidTile prevTile = getSolidTile(levelManager, prevDesc, SOLIDITY_ALL);
            byte prevMetric = getWidthMetric(prevTile, prevDesc, x, prevY);
            if (prevMetric > 0) {
                int dist = calculateCeilingDistance(prevMetric, y, prevY);
                byte angle = prevTile != null ? prevTile.getAngle() : 0;
                int tileIdx = prevDesc != null ? prevDesc.getChunkIndex() : -1;
                return new TerrainCheckResult(dist, angle, tileIdx);
            }
            return TerrainCheckResult.noCollision();
        }

        if (metric == 16) {
            // Full width tile - check next tile down for edge
            int nextY = y + 16;
            ChunkDesc nextDesc = levelManager.getChunkDescAt((byte) 0, x, nextY);
            SolidTile nextTile = getSolidTile(levelManager, nextDesc, SOLIDITY_ALL);
            byte nextMetric = getWidthMetric(nextTile, nextDesc, x, nextY);
            if (nextMetric > 0 && nextMetric < 16) {
                int dist = calculateCeilingDistance(nextMetric, y, nextY);
                byte angle = nextTile != null ? nextTile.getAngle() : 0;
                int tileIdx = nextDesc != null ? nextDesc.getChunkIndex() : -1;
                return new TerrainCheckResult(dist, angle, tileIdx);
            }
        }

        int dist = calculateCeilingDistance(metric, y, y);
        byte angle = tile != null ? tile.getAngle() : 0;
        int tileIdx = chunkDesc != null ? chunkDesc.getChunkIndex() : -1;
        return new TerrainCheckResult(dist, angle, tileIdx);
    }

    /**
     * Internal wall distance check at exact position.
     * Implements wall collision similar to ROM's FindWall (s2.asm line 43194).
     * Uses horizontal collision array (width metrics) to detect walls.
     *
     * @param x X position to check
     * @param y Y position to check
     * @param checkingLeft true if checking left wall, false for right wall
     */
    private static TerrainCheckResult checkWallDistAtPoint(int x, int y, boolean checkingLeft) {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null) {
            return TerrainCheckResult.noCollision();
        }

        ChunkDesc chunkDesc = levelManager.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(levelManager, chunkDesc, SOLIDITY_ALL);
        byte metric = getWallMetric(tile, chunkDesc, x, y, checkingLeft);

        if (metric == 0) {
            // No solid surface at this tile - check 16 pixels in wall direction
            // ROM: loc_1E9C2 adds a3 to d3 (a3 = +/-16), then adds 16 to result (line 43207)
            int nextX = checkingLeft ? (x - 16) : (x + 16);
            ChunkDesc nextDesc = levelManager.getChunkDescAt((byte) 0, nextX, y);
            SolidTile nextTile = getSolidTile(levelManager, nextDesc, SOLIDITY_ALL);
            byte nextMetric = getWallMetric(nextTile, nextDesc, nextX, y, checkingLeft);
            if (nextMetric > 0) {
                // ROM adds 16 to distance when extending to next tile (line 43207: addi.w #$10,d1)
                int dist = calculateWallDistance(nextMetric, x, checkingLeft, 16);
                byte angle = nextTile != null ? nextTile.getAngle() : 0;
                int tileIdx = nextDesc != null ? nextDesc.getChunkIndex() : -1;
                return new TerrainCheckResult(dist, angle, tileIdx);
            }
            return TerrainCheckResult.noCollision();
        }

        if (metric == 16) {
            // Full width tile - check previous tile for edge detection
            // ROM: loc_1EA4A subtracts a3 from d3, then subtracts 16 from result (line 43264)
            int prevX = checkingLeft ? (x + 16) : (x - 16);
            ChunkDesc prevDesc = levelManager.getChunkDescAt((byte) 0, prevX, y);
            SolidTile prevTile = getSolidTile(levelManager, prevDesc, SOLIDITY_ALL);
            byte prevMetric = getWallMetric(prevTile, prevDesc, prevX, y, checkingLeft);
            if (prevMetric > 0 && prevMetric < 16) {
                // ROM subtracts 16 from distance when checking previous tile (line 43264: subi.w #$10,d1)
                int dist = calculateWallDistance(prevMetric, x, checkingLeft, -16);
                byte angle = prevTile != null ? prevTile.getAngle() : 0;
                int tileIdx = prevDesc != null ? prevDesc.getChunkIndex() : -1;
                return new TerrainCheckResult(dist, angle, tileIdx);
            }
        }

        // Standard case: use current tile's metric, no offset
        int dist = calculateWallDistance(metric, x, checkingLeft, 0);
        byte angle = tile != null ? tile.getAngle() : 0;
        int tileIdx = chunkDesc != null ? chunkDesc.getChunkIndex() : -1;
        return new TerrainCheckResult(dist, angle, tileIdx);
    }

    /**
     * Get the SolidTile for a ChunkDesc if it's solid.
     */
    private static SolidTile getSolidTile(LevelManager levelManager, ChunkDesc chunkDesc, int solidityBitIndex) {
        if (chunkDesc == null || !chunkDesc.isSolidityBitSet(solidityBitIndex)) {
            return null;
        }
        return levelManager.getSolidTileForChunkDesc(chunkDesc, solidityBitIndex);
    }

    /**
     * Get height metric from a solid tile, handling H/V flip.
     * Used for floor checks.
     */
    private static byte getHeightMetric(SolidTile tile, ChunkDesc desc, int x, int y) {
        if (tile == null) {
            return 0;
        }
        int index = x & 0x0F;
        if (desc != null && desc.getHFlip()) {
            index = 15 - index;
        }
        byte metric = tile.getHeightAt((byte) index);
        if (metric != 0 && metric != 16 && desc != null && desc.getVFlip()) {
            metric = (byte) (16 - metric);
        }
        return metric;
    }

    /**
     * Get width metric from a solid tile, handling H/V flip.
     * Used for ceiling checks (reading from width array).
     */
    private static byte getWidthMetric(SolidTile tile, ChunkDesc desc, int x, int y) {
        if (tile == null) {
            return 0;
        }
        int index = y & 0x0F;
        if (desc != null && desc.getVFlip()) {
            index = 15 - index;
        }
        byte metric = tile.getWidthAt((byte) index);
        if (metric != 0 && metric != 16 && desc != null && desc.getHFlip()) {
            metric = (byte) (16 - metric);
        }
        return metric;
    }

    /**
     * Calculate distance to floor surface.
     * Returns negative if surface is above checkY (collision).
     */
    private static int calculateDistance(byte metric, int checkY, int tileY) {
        int tileTop = tileY & ~0x0F;
        int surfaceY = tileTop + 16 - metric;
        return surfaceY - checkY;
    }

    /**
     * Calculate distance to ceiling surface.
     * Returns negative if surface is below checkY (collision).
     */
    private static int calculateCeilingDistance(byte metric, int checkY, int tileY) {
        int tileTop = tileY & ~0x0F;
        int surfaceY = tileTop + metric - 1;
        return checkY - surfaceY;
    }

    /**
     * Get wall collision metric from a solid tile, handling H/V flip.
     * Uses the horizontal collision array (width values indexed by Y position).
     *
     * @param tile The solid tile to check
     * @param desc The chunk descriptor (for flip flags)
     * @param x X position (for flip calculations)
     * @param y Y position (determines which row of collision data to use)
     * @param checkingLeft true if checking left wall, false for right wall
     */
    private static byte getWallMetric(SolidTile tile, ChunkDesc desc, int x, int y, boolean checkingLeft) {
        if (tile == null) {
            return 0;
        }
        // Index by Y position within the 16-pixel tile
        int index = y & 0x0F;
        if (desc != null && desc.getVFlip()) {
            index = 15 - index;
        }
        byte metric = tile.getWidthAt((byte) index);

        // Handle H-flip: negate metric for collision from opposite side
        boolean hFlip = desc != null && desc.getHFlip();
        // When checking left wall on h-flipped tile, or right wall on non-flipped,
        // the metric interpretation changes
        if (checkingLeft != hFlip && metric != 0 && metric != 16) {
            metric = (byte) (16 - metric);
        }

        return metric;
    }

    /**
     * Calculate distance to wall surface using ROM's FindWall logic.
     * <p>
     * ROM logic (s2.asm lines 43246-43251):
     * <pre>
     * move.w  d3,d1       ; d1 = x position
     * andi.w  #$F,d1      ; d1 = xInTile (0-15)
     * add.w   d1,d0       ; d0 = metric + xInTile
     * move.w  #$F,d1
     * sub.w   d0,d1       ; d1 = 15 - (metric + xInTile)
     * </pre>
     * Returns negative when (metric + xInTile) > 15, indicating collision.
     * <p>
     * For left wall checks, the ROM flips xInTile with eori.w #$F,d3 (line 44070),
     * effectively making xInTile = 15 - (x & 0x0F).
     *
     * @param metric The wall collision metric (0-16)
     * @param checkX The X position being checked
     * @param checkingLeft true if checking left wall, false for right wall
     * @param tileOffset 0 for same tile, +16 for extension tile, -16 for previous tile
     * @return Distance to wall (negative = collision)
     */
    private static int calculateWallDistance(byte metric, int checkX, boolean checkingLeft, int tileOffset) {
        int xInTile = checkX & 0x0F;

        if (checkingLeft) {
            // For left wall, ROM flips xInTile: eori.w #$F,d3 (line 44070)
            // This gives: distance = 15 - (metric + (15 - xInTile))
            //                      = xInTile - metric
            return (xInTile - metric) + tileOffset;
        } else {
            // For right wall: distance = 15 - (metric + xInTile)
            return (15 - (metric + xInTile)) + tileOffset;
        }
    }
}
