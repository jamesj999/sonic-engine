package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Sensor implementation for ground/floor collision detection.
 * Handles both vertical (floor/ceiling) and horizontal (wall) scanning
 * with ground mode rotation for slopes.
 */
public class GroundSensor extends Sensor {
    private static LevelManager levelManager = LevelManager.getInstance();

    // Full-height tile constant
    private static final byte FULL_TILE = 16;

    // Default flagged angle for missing tiles (ROM: odd angles trigger cardinal snap)
    private static final byte FLAGGED_ANGLE = 0x03;

    // Debug flag for tile-level collision debugging (set from PlayableSpriteMovement)
    public static boolean tileDebugEnabled = false;

    public static void setLevelManager(LevelManager levelManager) {
        GroundSensor.levelManager = levelManager;
    }

    public GroundSensor(AbstractPlayableSprite sprite, Direction direction, byte x, byte y, boolean active) {
        super(sprite, direction, x, y, active);
    }

    @Override
    protected SensorResult doScan(short dx, short dy) {
        if (!active) {
            return null;
        }

        SensorConfiguration config = SpriteManager.getSensorConfigurationForGroundModeAndDirection(
                sprite.getGroundMode(), direction);
        Direction globalDirection = config.direction();

        short[] rotatedOffset = getRotatedOffset();
        short originalX = (short) (sprite.getCentreX() + rotatedOffset[0] + dx);
        short originalY = (short) (sprite.getCentreY() + rotatedOffset[1] + dy);

        // Solidity bit: floor sensors (DOWN) use top_solid_bit, others use lrb_solid_bit
        // ROM: AnglePos uses top_solid_bit; CalcRoomInFront/OverHead use lrb_solid_bit
        int solidityBit = (direction == Direction.DOWN)
                ? sprite.getTopSolidBit()
                : sprite.getLrbSolidBit();

        if (config.vertical()) {
            return scanVertical(originalX, originalY, solidityBit, globalDirection);
        } else {
            return scanHorizontal(originalX, originalY, solidityBit, globalDirection);
        }
    }

    // ========================================
    // VERTICAL SCANNING (Floor/Ceiling)
    // ========================================

    private SensorResult scanVertical(short x, short y, int solidityBit, Direction direction) {
        // Check current tile
        SensorResult result = scanTileVertical(x, y, x, y, solidityBit, direction);
        if (result != null) {
            return result;
        }

        // Extend to next tile in scan direction
        short nextY = (short) (y + (direction == Direction.DOWN ? 16 : -16));
        result = scanTileVertical(x, y, x, nextY, solidityBit, direction);
        if (result != null) {
            return result;
        }

        // No collision found - return empty result with max distance
        byte distance = calculateVerticalDistance((byte) 0, y, nextY, direction);
        return new SensorResult(FLAGGED_ANGLE, distance, 0, direction);
    }

    private SensorResult scanTileVertical(short origX, short origY, short checkX, short checkY,
                                          int solidityBit, Direction direction) {
        ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, checkX, checkY);
        SolidTile tile = getSolidTile(desc, solidityBit);
        if (tile == null) {
            return null;
        }

        byte metric = getHeightMetric(tile, desc, checkX, direction);
        if (metric == 0) {
            return null;
        }

        // Full-height tile: check previous tile for edge detection
        if (metric == FULL_TILE) {
            short prevY = (short) (checkY + (direction == Direction.DOWN ? -16 : 16));
            ChunkDesc prevDesc = levelManager.getChunkDescAt((byte) 0, checkX, prevY);
            SolidTile prevTile = getSolidTile(prevDesc, solidityBit);
            byte prevMetric = getHeightMetric(prevTile, prevDesc, checkX, direction);

            if (prevMetric > 0 && prevMetric < FULL_TILE) {
                return createVerticalResult(prevTile, prevDesc, checkX, origY, prevY, direction);
            }

            // Use current full tile
            byte distance = calculateVerticalDistance(metric, origY, checkY, direction);
            return createResultWithDistance(tile, desc, distance, direction);
        }

        return createVerticalResult(tile, desc, checkX, origY, checkY, direction);
    }

    private SensorResult createVerticalResult(SolidTile tile, ChunkDesc desc,
                                              short checkX, short origY, short tileY, Direction direction) {
        byte metric = getHeightMetric(tile, desc, checkX, direction);
        byte distance = calculateVerticalDistance(metric, origY, tileY, direction);
        return createResultWithDistance(tile, desc, distance, direction);
    }

    private byte calculateVerticalDistance(byte metric, short origY, short tileY, Direction direction) {
        short tileBase = (short) (tileY & ~0x0F);
        if (direction == Direction.DOWN) {
            // Floor: ROM formula (FindFloor s2.asm:42994-42999)
            // distance = 15 - metric - (origY & 0xF), adjusted for tile offset
            return (byte) ((tileBase + 15 - metric) - origY);
        } else {
            // Ceiling: surface = tileBase + metric
            return (byte) (origY - (tileBase + metric));
        }
    }

    // ========================================
    // HORIZONTAL SCANNING (Walls)
    // ========================================

    private SensorResult scanHorizontal(short x, short y, int solidityBit, Direction direction) {
        if (tileDebugEnabled) {
            System.out.printf("  [HORIZ-SCAN] start=(%d,%d) dir=%s%n", x, y, direction);
        }

        WallScanResult result = evaluateWallTile(x, y, solidityBit, direction);

        switch (result.state) {
            case FOUND:
                return createResultWithDistance(result.tile, result.desc, (byte) result.distance, direction);

            case REGRESS:
                // Check previous tile (opposite direction)
                int prevX = x + (direction == Direction.LEFT ? 16 : -16);
                if (tileDebugEnabled) System.out.printf("    [REGRESS] checking prev tile at x=%d%n", prevX);
                WallScanResult prev = scanWallTileSimple(prevX, y, solidityBit, direction);
                return createResultWithDistance(prev.tile, prev.desc, (byte) (prev.distance - 16), direction);

            case EXTEND:
            default:
                // Check next tile (same direction)
                int nextX = x + (direction == Direction.LEFT ? -16 : 16);
                if (tileDebugEnabled) System.out.printf("    [EXTEND] checking next tile at x=%d%n", nextX);
                WallScanResult next = scanWallTileSimple(nextX, y, solidityBit, direction);
                return createResultWithDistance(next.tile, next.desc, (byte) (next.distance + 16), direction);
        }
    }

    private WallScanResult evaluateWallTile(int x, int y, int solidityBit, Direction direction) {
        ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(desc, solidityBit);

        if (tileDebugEnabled) {
            // Calculate block position for debugging
            int blockX = x / 128;
            int blockY = y / 128;
            int chunkXInBlock = (x % 128) / 16;
            int chunkYInBlock = (y % 128) / 16;
            System.out.printf("    [WALL-EVAL] pos=(%d,%d) dir=%s solidityBit=%d block=(%d,%d) chunkInBlock=(%d,%d)%n",
                    x, y, direction, solidityBit, blockX, blockY, chunkXInBlock, chunkYInBlock);

            // Show chunk lookup chain for debugging
            String chunkInfo = "N/A";
            if (desc != null) {
                try {
                    var level = levelManager.getCurrentLevel();
                    if (level != null) {
                        var chunk = level.getChunk(desc.getChunkIndex());
                        if (chunk != null) {
                            int primaryColIdx = chunk.getSolidTileIndex();
                            int altColIdx = chunk.getSolidTileAltIndex();
                            int usedIdx = (solidityBit < 0x0E) ? primaryColIdx : altColIdx;
                            chunkInfo = String.format("chunk[%d].solidIdx=%d altIdx=%d (using %s=%d)",
                                    desc.getChunkIndex(), primaryColIdx, altColIdx,
                                    solidityBit < 0x0E ? "primary" : "alt", usedIdx);
                        }
                    }
                } catch (Exception e) {
                    chunkInfo = "error: " + e.getMessage();
                }
            }

            System.out.printf("      desc=%s rawVal=0x%04X solidBitSet=%s%n",
                    desc != null ? String.format("idx=%d hFlip=%s vFlip=%s", desc.getChunkIndex(), desc.getHFlip(), desc.getVFlip()) : "null",
                    desc != null ? desc.get() : 0,
                    desc != null ? desc.isSolidityBitSet(solidityBit) : "N/A");
            System.out.printf("      %s%n", chunkInfo);
            System.out.printf("      tile=%s%n",
                    tile != null ? String.format("idx=%d angle=0x%02X", tile.getIndex(), tile.getAngle(false, false) & 0xFF) : "null");
        }

        if (tile == null) {
            if (tileDebugEnabled) System.out.println("      -> EXTEND (no tile)");
            return WallScanResult.extend();
        }

        int metric = getWallMetric(tile, desc, y, direction);
        if (tileDebugEnabled) {
            System.out.printf("      metric=%d yInTile=%d%n", metric, y & 0x0F);
            // Dump tile's width array to see the shape (rows 0-15 correspond to y positions)
            StringBuilder widthDump = new StringBuilder("      widths[0-15]=[");
            for (int i = 0; i < 16; i++) {
                if (i > 0) widthDump.append(",");
                int w = tile.getWidthAt((byte) i);
                widthDump.append(w);
            }
            widthDump.append("] (row ").append(y & 0x0F).append(" highlighted)");
            System.out.println(widthDump);
        }

        if (metric == 0) {
            if (tileDebugEnabled) System.out.println("      -> EXTEND (metric=0)");
            return WallScanResult.extend();
        }

        int xInTile = x & 0x0F;
        int xAdjusted = (direction == Direction.LEFT) ? (15 - xInTile) : xInTile;

        // Negative metric: partial collision from opposite side
        if (metric < 0) {
            boolean extend = (metric + xAdjusted >= 0);
            if (tileDebugEnabled) System.out.printf("      -> %s (negative metric, xInTile=%d xAdj=%d)%n",
                    extend ? "EXTEND" : "REGRESS", xInTile, xAdjusted);
            return extend ? WallScanResult.extend() : WallScanResult.regress();
        }

        // Full-width tile: need to check previous tile
        if (metric == FULL_TILE) {
            if (tileDebugEnabled) System.out.println("      -> REGRESS (full tile)");
            return WallScanResult.regress();
        }

        // Standard case: calculate distance to wall surface
        int distance = (direction == Direction.LEFT)
                ? (xInTile - metric + 1)
                : (16 - metric - xInTile);
        if (tileDebugEnabled) System.out.printf("      -> FOUND dist=%d (xInTile=%d)%n", distance, xInTile);
        return WallScanResult.found(distance, tile, desc);
    }

    private WallScanResult scanWallTileSimple(int x, int y, int solidityBit, Direction direction) {
        ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, x, y);
        SolidTile tile = getSolidTile(desc, solidityBit);
        int xInTile = x & 0x0F;
        int xAdjusted = (direction == Direction.LEFT) ? (15 - xInTile) : xInTile;

        if (tileDebugEnabled) {
            System.out.printf("    [WALL-SIMPLE] pos=(%d,%d) dir=%s%n", x, y, direction);
            System.out.printf("      desc=%s solidBitSet=%s tile=%s%n",
                    desc != null ? String.format("idx=%d hFlip=%s vFlip=%s", desc.getChunkIndex(), desc.getHFlip(), desc.getVFlip()) : "null",
                    desc != null ? desc.isSolidityBitSet(solidityBit) : "N/A",
                    tile != null ? String.format("idx=%d", tile.getIndex()) : "null");
        }

        if (tile == null) {
            int dist = 15 - xAdjusted;
            if (tileDebugEnabled) System.out.printf("      -> dist=%d (no tile, xAdj=%d)%n", dist, xAdjusted);
            return WallScanResult.found(dist, null, null);
        }

        int metric = getWallMetric(tile, desc, y, direction);
        if (tileDebugEnabled) System.out.printf("      metric=%d%n", metric);

        if (metric == 0) {
            int dist = 15 - xAdjusted;
            if (tileDebugEnabled) System.out.printf("      -> dist=%d (metric=0)%n", dist);
            return WallScanResult.found(dist, null, null);
        }

        if (metric < 0) {
            if (metric + xAdjusted >= 0) {
                int dist = 15 - xAdjusted;
                if (tileDebugEnabled) System.out.printf("      -> dist=%d (neg metric, no collision)%n", dist);
                return WallScanResult.found(dist, null, null);
            }
            int dist = -1 - xAdjusted;
            if (tileDebugEnabled) System.out.printf("      -> dist=%d (neg metric collision)%n", dist);
            return WallScanResult.found(dist, tile, desc);
        }

        int distance = (direction == Direction.LEFT)
                ? (xInTile - metric + 1)
                : (16 - metric - xInTile);
        if (tileDebugEnabled) System.out.printf("      -> dist=%d%n", distance);
        return WallScanResult.found(distance, tile, desc);
    }

    // ========================================
    // METRIC CALCULATIONS
    // ========================================

    private byte getHeightMetric(SolidTile tile, ChunkDesc desc, int x, Direction direction) {
        if (tile == null) return 0;

        int index = x & 0x0F;
        if (desc != null && desc.getHFlip()) {
            index = 15 - index;
        }

        byte metric = tile.getHeightAt((byte) index);
        if (metric != 0 && metric != FULL_TILE) {
            boolean invert = (desc != null && desc.getVFlip()) ^ (direction == Direction.UP);
            if (invert) {
                metric = (byte) (16 - metric);
            }
        }
        return metric;
    }

    private int getWallMetric(SolidTile tile, ChunkDesc desc, int y, Direction direction) {
        if (tile == null) return 0;

        int rawIndex = y & 0x0F;
        int index = rawIndex;
        boolean vFlip = desc != null && desc.getVFlip();
        if (vFlip) {
            index = 15 - index;
        }

        int metric = tile.getWidthAt((byte) index);
        boolean hFlip = desc != null && desc.getHFlip();
        boolean xMirror = hFlip ^ (direction == Direction.LEFT);
        if (xMirror) {
            metric = -metric;
        }

        // Detailed debug when tile debug is enabled
        if (tileDebugEnabled) {
            int rawWidthAtRawIndex = tile.getWidthAt((byte) rawIndex);
            int rawWidthAtIndex = tile.getWidthAt((byte) index);
            System.out.printf("        [WALL-METRIC] y=%d rawIndex=%d vFlip=%s finalIndex=%d rawWidth@rawIndex=%d rawWidth@finalIndex=%d hFlip=%s dir=%s xMirror=%s metric=%d%n",
                    y, rawIndex, vFlip, index, rawWidthAtRawIndex, rawWidthAtIndex, hFlip, direction, xMirror, metric);
        }
        return metric;
    }

    // ========================================
    // HELPER METHODS
    // ========================================

    private SolidTile getSolidTile(ChunkDesc desc, int solidityBit) {
        if (desc == null || !desc.isSolidityBitSet(solidityBit)) {
            return null;
        }
        return levelManager.getSolidTileForChunkDesc(desc, solidityBit);
    }

    private SensorResult createResultWithDistance(SolidTile tile, ChunkDesc desc, byte distance, Direction direction) {
        byte angle = FLAGGED_ANGLE;
        int tileIndex = 0;

        if (tile != null) {
            boolean hFlip = desc != null && desc.getHFlip();
            boolean vFlip = desc != null && desc.getVFlip();
            angle = tile.getAngle(hFlip, vFlip);
            tileIndex = tile.getIndex();
        }

        return new SensorResult(angle, distance, tileIndex, direction);
    }

    // ========================================
    // WALL SCAN RESULT
    // ========================================

    private enum WallScanState { FOUND, EXTEND, REGRESS }

    private static final class WallScanResult {
        final WallScanState state;
        final int distance;
        final SolidTile tile;
        final ChunkDesc desc;

        private WallScanResult(WallScanState state, int distance, SolidTile tile, ChunkDesc desc) {
            this.state = state;
            this.distance = distance;
            this.tile = tile;
            this.desc = desc;
        }

        static WallScanResult found(int distance, SolidTile tile, ChunkDesc desc) {
            return new WallScanResult(WallScanState.FOUND, distance, tile, desc);
        }

        static WallScanResult extend() {
            return new WallScanResult(WallScanState.EXTEND, 0, null, null);
        }

        static WallScanResult regress() {
            return new WallScanResult(WallScanState.REGRESS, 0, null, null);
        }
    }
}
