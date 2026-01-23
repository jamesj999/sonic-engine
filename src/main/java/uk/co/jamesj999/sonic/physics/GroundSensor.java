package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class GroundSensor extends Sensor {
    private static LevelManager levelManager = LevelManager.getInstance();

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

        byte mapLayer = 0;
        SensorConfiguration sensorConfiguration = SpriteManager
                .getSensorConfigurationForGroundModeAndDirection(sprite.getGroundMode(), direction);
        boolean vertical = sensorConfiguration.vertical();
        Direction globalDirection = sensorConfiguration.direction();

        short[] rotatedOffset = getRotatedOffset();
        short xOffset = rotatedOffset[0];
        short yOffset = rotatedOffset[1];

        // Cache sprite center coordinates to avoid repeated getter calls
        int centreX = sprite.getCentreX();
        int centreY = sprite.getCentreY();

        short originalX = (short) (centreX + xOffset + dx);
        short originalY = (short) (centreY + yOffset + dy);

        // ROM ACCURACY FIX: Use the sensor's LOCAL direction (this.direction) to determine
        // which solidity bit to check, NOT the global direction after ground mode rotation.
        //
        // In the original game (s2.asm):
        // - AnglePos and Sonic_CheckFloor (floor detection): always use top_solid_bit
        // - CalcRoomInFront and CalcRoomOverHead (wall/ceiling checks): use lrb_solid_bit
        //
        // The sensor's local direction indicates its ROLE:
        // - DOWN = floor sensor → top_solid_bit (check if walkable from above)
        // - LEFT/RIGHT = push sensor → lrb_solid_bit (check if solid from the side)
        // - UP = ceiling sensor → lrb_solid_bit (check if solid from below)
        //
        // Previously this used globalDirection which caused floor sensors on steep slopes
        // (RIGHTWALL/LEFTWALL modes) to incorrectly use lrb_solid_bit, leading to collision
        // issues when crossing paths with different collision layers.
        int solidityBitIndex = (direction == Direction.DOWN)
                ? sprite.getTopSolidBit()
                : sprite.getLrbSolidBit();

        if (vertical) {
            SensorResult initialResult = scanTile(originalX, originalY, originalX, originalY, mapLayer,
                    solidityBitIndex, globalDirection, true);
            if (initialResult != null) {
                return initialResult;
            }

            short nextY = calculateNextTile(globalDirection, originalY);
            SensorResult nextResult = scanTile(originalX, originalY, originalX, nextY, mapLayer,
                    solidityBitIndex, globalDirection, true);
            if (nextResult != null) {
                return nextResult;
            }

            byte distance = calculateDistance((byte) 0, originalX, originalY, originalX, nextY, globalDirection);
            return createResultWithDistance(null, null, distance, globalDirection);
        }

        return scanHorizontal(originalX, originalY, mapLayer, solidityBitIndex, globalDirection);
    }

    private SolidTile getSolidTile(ChunkDesc chunkDesc, int solidityBitIndex) {
        if (chunkDesc == null) {
            return null;
        }
        if (!chunkDesc.isSolidityBitSet(solidityBitIndex)) {
            return null;
        }

        return levelManager.getSolidTileForChunkDesc(chunkDesc, solidityBitIndex);
    }

    private SensorResult scanTile(short originalX,
            short originalY,
            short checkX,
            short checkY,
            byte mapLayer,
            int solidityBitIndex,
            Direction direction,
            boolean vertical) {
        ChunkDesc chunkDesc = levelManager.getChunkDescAt(mapLayer, checkX, checkY);
        SolidTile tile = getSolidTile(chunkDesc, solidityBitIndex);
        if (tile == null) {
            return null;
        }
        byte metric = getMetric(tile, chunkDesc, checkX, checkY, vertical, direction);
        if (metric == 0) {
            return null;
        }
        if (metric == 16) {
            short prevX = checkX;
            short prevY = checkY;
            if (vertical) {
                prevY = calculateNextTile(direction.opposite(), checkY);
            } else {
                prevX = calculateNextTile(direction.opposite(), checkX);
            }

            ChunkDesc prevDesc = levelManager.getChunkDescAt(mapLayer, prevX, prevY);
            SolidTile prevTile = getSolidTile(prevDesc, solidityBitIndex);
            byte prevMetric = getMetric(prevTile, prevDesc, prevX, prevY, vertical, direction);
            if (prevMetric > 0 && prevMetric < 16) {
                return createResult(prevTile, prevDesc, originalX, originalY, prevX, prevY, direction, vertical);
            }

            byte distance = calculateDistance(metric, originalX, originalY, checkX, checkY, direction);
            return createResultWithDistance(tile, chunkDesc, distance, direction);
        }

        return createResult(tile, chunkDesc, originalX, originalY, checkX, checkY, direction, vertical);
    }

    private SensorResult scanHorizontal(short originalX, short originalY, byte mapLayer,
            int solidityBitIndex, Direction direction) {
        WallScanResult result = findWall(originalX, originalY, mapLayer, solidityBitIndex, direction);
        if (result == null) {
            return null;
        }
        return createResultWithDistance(result.tile, result.desc, (byte) result.distance, direction);
    }

    private WallScanResult findWall(int x, int y, byte mapLayer,
            int solidityBitIndex, Direction direction) {
        int step = (direction == Direction.LEFT) ? -16 : 16;

        WallScanResult current = evaluateWallTile(x, y, mapLayer, solidityBitIndex, direction);
        if (current != null && current.state == WallScanState.FOUND) {
            return current;
        }

        if (current != null && current.state == WallScanState.REGRESS) {
            WallScanResult prev = findWall2(x - step, y, mapLayer, solidityBitIndex, direction);
            prev.distance -= 16;
            return prev;
        }

        // Extension: check the next tile in the scan direction
        WallScanResult next = findWall2(x + step, y, mapLayer, solidityBitIndex, direction);
        next.distance += 16;
        return next;
    }

    private WallScanResult evaluateWallTile(int x, int y, byte mapLayer,
            int solidityBitIndex, Direction direction) {
        ChunkDesc chunkDesc = levelManager.getChunkDescAt(mapLayer, x, y);
        SolidTile tile = getSolidTile(chunkDesc, solidityBitIndex);
        if (tile == null) {
            return new WallScanResult(WallScanState.EXTEND, 0, null, null);
        }

        int metric = getWallMetric(tile, chunkDesc, y, direction);
        if (metric == 0) {
            return new WallScanResult(WallScanState.EXTEND, 0, null, null);
        }

        int xInTile = x & 0x0F;
        int xAdjusted = (direction == Direction.LEFT) ? (15 - xInTile) : xInTile;

        if (metric < 0) {
            int sum = metric + xAdjusted;
            if (sum >= 0) {
                return new WallScanResult(WallScanState.EXTEND, 0, null, null);
            }
            return new WallScanResult(WallScanState.REGRESS, 0, null, null);
        }

        if (metric == 16) {
            return new WallScanResult(WallScanState.REGRESS, 0, null, null);
        }

        int distance = (direction == Direction.LEFT)
                ? (xInTile - metric)
                : (15 - (metric + xInTile));
        return new WallScanResult(WallScanState.FOUND, distance, tile, chunkDesc);
    }

    private WallScanResult findWall2(int x, int y, byte mapLayer,
            int solidityBitIndex, Direction direction) {
        ChunkDesc chunkDesc = levelManager.getChunkDescAt(mapLayer, x, y);
        SolidTile tile = getSolidTile(chunkDesc, solidityBitIndex);
        int xInTile = x & 0x0F;
        int xAdjusted = (direction == Direction.LEFT) ? (15 - xInTile) : xInTile;

        if (tile == null) {
            int distance = 15 - xAdjusted;
            return new WallScanResult(WallScanState.FOUND, distance, null, null);
        }

        int metric = getWallMetric(tile, chunkDesc, y, direction);
        if (metric == 0) {
            int distance = 15 - xAdjusted;
            return new WallScanResult(WallScanState.FOUND, distance, null, null);
        }

        if (metric < 0) {
            int sum = metric + xAdjusted;
            if (sum >= 0) {
                int distance = 15 - xAdjusted;
                return new WallScanResult(WallScanState.FOUND, distance, null, null);
            }
            int distance = -1 - xAdjusted;
            return new WallScanResult(WallScanState.FOUND, distance, tile, chunkDesc);
        }

        int distance = (direction == Direction.LEFT)
                ? (xInTile - metric)
                : (15 - (metric + xInTile));
        return new WallScanResult(WallScanState.FOUND, distance, tile, chunkDesc);
    }

    private int getWallMetric(SolidTile tile, ChunkDesc desc, int y, Direction direction) {
        if (tile == null) {
            return 0;
        }
        int index = y & 0x0F;
        if (desc != null && desc.getVFlip()) {
            index = 15 - index;
        }
        int metric = tile.getWidthAt((byte) index);
        boolean xMirror = desc != null && desc.getHFlip();
        if (direction == Direction.LEFT) {
            xMirror = !xMirror;
        }
        if (xMirror) {
            metric = -metric;
        }
        return metric;
    }

    private enum WallScanState {
        FOUND,
        EXTEND,
        REGRESS
    }

    private static final class WallScanResult {
        private final WallScanState state;
        private int distance;
        private final SolidTile tile;
        private final ChunkDesc desc;

        private WallScanResult(WallScanState state, int distance, SolidTile tile, ChunkDesc desc) {
            this.state = state;
            this.distance = distance;
            this.tile = tile;
            this.desc = desc;
        }
    }

    private byte getMetric(SolidTile tile, ChunkDesc desc, int x, int y, boolean vertical, Direction direction) {
        if (tile == null)
            return 0;
        int index;
        if (vertical) {
            index = x & 0x0F;
            if (desc != null && desc.getHFlip())
                index = 15 - index;
            byte metric = tile.getHeightAt((byte) index);
            if (metric != 0 && metric != 16) {
                boolean invert = (desc != null && desc.getVFlip()) ^ (direction == Direction.UP);
                if (invert) {
                    metric = (byte) (16 - metric);
                }
            }
            return metric;
        } else {
            index = y & 0x0F;
            if (desc != null && desc.getVFlip())
                index = 15 - index;
            byte metric = tile.getWidthAt((byte) index);
            if (metric != 0 && metric != 16) {
                boolean invert = (desc != null && desc.getHFlip()) ^ (direction == Direction.LEFT);
                if (invert) {
                    metric = (byte) (16 - metric);
                }
            }
            return metric;
        }
    }

    private SensorResult createResult(SolidTile tile, ChunkDesc desc, short originalX, short originalY, short checkX,
            short checkY, Direction direction, boolean vertical) {
        byte metric = getMetric(tile, desc, checkX, checkY, vertical, direction);
        byte distance = calculateDistance(metric, originalX, originalY, checkX, checkY, direction);

        return createResultWithDistance(tile, desc, distance, direction);
    }

    private SensorResult createResultWithDistance(SolidTile tile, ChunkDesc desc, byte distance, Direction direction) {
        byte angle = 0;
        int index = 0;
        if (tile != null) {
            // Get angle with flips
            boolean hFlip = (desc != null) && desc.getHFlip();
            boolean vFlip = (desc != null) && desc.getVFlip();
            angle = tile.getAngle(hFlip, vFlip);
            index = tile.getIndex();
        }

        return new SensorResult(angle, distance, index, direction);
    }

    private byte calculateDistance(byte metric, short originalX, short originalY, short checkX, short checkY,
            Direction direction) {
        // Round down to block start
        short tileX = (short) (checkX & ~0x0F);
        short tileY = (short) (checkY & ~0x0F);

        switch (direction) {
            case DOWN:
                // Looking for floor (Top of solid). Solid from Bottom.
                // Surface = TileY + 16 - Height
                // Distance = Surface - SensorY
                return (byte) ((tileY + 16 - metric) - originalY);
            case UP:
                // Looking for ceiling (Bottom of solid). Solid from Top.
                // Surface = TileY + Height
                // Distance = SensorY - Surface
                return (byte) (originalY - (tileY + metric));
            case RIGHT:
                // Looking for Wall (Left of solid). Solid from Right?
                // Logic: (TileX + 16 - Width) - SensorX
                return (byte) ((tileX + 16 - metric) - originalX);
            case LEFT:
                // Looking for Wall (Right of solid). Solid from Left?
                // Logic: SensorX - (TileX + Width)
                return (byte) (originalX - (tileX + metric));
        }
        return 0;
    }

    private short calculateNextTile(Direction direction, short xOrY) {
        switch (direction) {
            case UP, LEFT -> {
                return (short) (xOrY - 16);
            }
            case DOWN, RIGHT -> {
                return (short) (xOrY + 16);
            }
        }
        return 0;
    }
}
