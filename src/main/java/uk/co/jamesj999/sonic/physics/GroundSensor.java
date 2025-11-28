package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.ChunkDesc;
import uk.co.jamesj999.sonic.level.CollisionMode;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.SolidTile;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

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

        byte layer = sprite.getLayer();
        SensorConfiguration sensorConfiguration = SpriteManager.getSensorConfigurationForGroundModeAndDirection(sprite.getGroundMode(), direction);
        boolean vertical = sensorConfiguration.vertical();
        Direction globalDirection = sensorConfiguration.direction();

        short xOffset = x;
        short yOffset = y;

        switch (sprite.getGroundMode()) {
            case RIGHTWALL -> {
                short temp = xOffset;
                xOffset = yOffset;
                yOffset = (short) -temp;
            }
            case CEILING -> {
                xOffset = (short) -xOffset;
                yOffset = (short) -yOffset;
            }
            case LEFTWALL -> {
                short temp = xOffset;
                xOffset = (short) -yOffset;
                yOffset = temp;
            }
            default -> { }
        }

        short originalX = (short) (sprite.getCentreX() + xOffset + dx);
        short originalY = (short) (sprite.getCentreY() + yOffset + dy);

        short currentX = originalX;
        short currentY = originalY;

        // 1. Check Initial Tile
        ChunkDesc initialChunkDesc = levelManager.getChunkDescAt(layer, currentX, currentY);
        SolidTile initialTile = getSolidTile(initialChunkDesc, layer, globalDirection);
        byte initialHeight = getMetric(initialTile, initialChunkDesc, currentX, currentY, vertical);

        if (initialHeight == 16) {
            // Regression: Check 'previous' tile (against sensor direction)
            short prevX = currentX;
            short prevY = currentY;
            if (vertical) {
                prevY = calculateNextTile(globalDirection.opposite(), currentY);
            } else {
                prevX = calculateNextTile(globalDirection.opposite(), currentX);
            }

            ChunkDesc prevChunkDesc = levelManager.getChunkDescAt(layer, prevX, prevY);
            SolidTile prevTile = getSolidTile(prevChunkDesc, layer, globalDirection);
            byte prevHeight = getMetric(prevTile, prevChunkDesc, prevX, prevY, vertical);

            // Compare distances to determine the nearest surface.
            // If Regression (Prev) finds a solid tile, it might be the nearest surface (e.g. stepping up a slope).
            // But if Initial (Current) is also solid (because we are embedded), it might be the nearest (e.g. hitting a ceiling).
            // We should pick the one with the smallest absolute distance to the sensor.
            // See Sonic Physics Guide: "a Sensor can always locate the nearest open surface".
            if (prevHeight > 0) {
                SensorResult initialResult = createResult(initialTile, initialChunkDesc, originalX, originalY, currentX, currentY, globalDirection, vertical);
                SensorResult prevResult = createResult(prevTile, prevChunkDesc, originalX, originalY, prevX, prevY, globalDirection, vertical);

                // If Prev is "nearer" (smaller absolute distance), use it.
                // Note: Distances can be negative (inside).
                // Example: Initial (Ceiling) -6. Prev (Floor) -22. |-6| < |-22|. Use Initial.
                // Example: Initial (Slope) -10. Prev (Slope) -5. |-5| < |-10|. Use Prev.
                if (Math.abs(prevResult.distance()) < Math.abs(initialResult.distance())) {
                    return prevResult;
                } else {
                    return initialResult;
                }
            } else {
                // Previous tile empty, revert to initial
                return createResult(initialTile, initialChunkDesc, originalX, originalY, originalX, originalY, globalDirection, vertical);
            }

        } else if (initialHeight == 0) {
            // Extension: Check 'next' tile (in sensor direction)
            short nextX = currentX;
            short nextY = currentY;
            if (vertical) {
                nextY = calculateNextTile(globalDirection, currentY);
            } else {
                nextX = calculateNextTile(globalDirection, currentX);
            }

            ChunkDesc nextChunkDesc = levelManager.getChunkDescAt(layer, nextX, nextY);
            SolidTile nextTile = getSolidTile(nextChunkDesc, layer, globalDirection);
            byte nextHeight = getMetric(nextTile, nextChunkDesc, nextX, nextY, vertical);

            if (nextHeight > 0) {
                // Found valid extension tile
                return createResult(nextTile, nextChunkDesc, originalX, originalY, nextX, nextY, globalDirection, vertical);
            } else {
                // Extension failed (no tile found). Return distance to end of second block.
                byte distance = calculateDistance((byte) 0, originalX, originalY, nextX, nextY, globalDirection);
                return new SensorResult((byte) 0, distance, 0, globalDirection);
            }
        } else {
            // Normal case (0 < height < 16)
            return createResult(initialTile, initialChunkDesc, originalX, originalY, originalX, originalY, globalDirection, vertical);
        }
    }

    private SolidTile getSolidTile(ChunkDesc chunkDesc, byte layer, Direction direction) {
        if (chunkDesc == null) {
            return null;
        }
        CollisionMode mode;
        if (layer == 0) {
            mode = chunkDesc.getPrimaryCollisionMode();
        } else {
            mode = chunkDesc.getSecondaryCollisionMode();
        }

        if (mode == null || !mode.isSolid(direction)) {
            return null;
        }

        return levelManager.getSolidTileForChunkDesc(chunkDesc, layer);
    }

    private byte getMetric(SolidTile tile, ChunkDesc desc, int x, int y, boolean vertical) {
        if (tile == null) return 0;
        int index;
        if (vertical) {
            index = x & 0x0F;
            if (desc != null && desc.getHFlip()) index = 15 - index;
            return tile.getHeightAt((byte) index);
        } else {
            index = y & 0x0F;
            if (desc != null && desc.getVFlip()) index = 15 - index;
            return tile.getWidthAt((byte) index);
        }
    }

    private SensorResult createResult(SolidTile tile, ChunkDesc desc, short originalX, short originalY, short checkX, short checkY, Direction direction, boolean vertical) {
        byte metric = getMetric(tile, desc, checkX, checkY, vertical);
        boolean hFlip = (desc != null) && desc.getHFlip();
        boolean vFlip = (desc != null) && desc.getVFlip();
        byte distance = calculateDistance(metric, originalX, originalY, checkX, checkY, direction, hFlip, vFlip);

        byte angle = 0;
        int index = 0;
        if (tile != null) {
            // Get angle with flips
            angle = tile.getAngle(hFlip, vFlip);
            index = tile.getIndex();
        }

        return new SensorResult(angle, distance, index, direction);
    }

    private byte calculateDistance(byte metric, short originalX, short originalY, short checkX, short checkY, Direction direction) {
        return calculateDistance(metric, originalX, originalY, checkX, checkY, direction, false, false);
    }

    private byte calculateDistance(byte metric, short originalX, short originalY, short checkX, short checkY, Direction direction, boolean hFlip, boolean vFlip) {
        // Round down to block start
        short tileX = (short) (checkX & ~0x0F);
        short tileY = (short) (checkY & ~0x0F);

        switch (direction) {
            case DOWN:
                // Looking for floor (Top of solid). Solid from Bottom.
                if (vFlip) {
                    // VFlip: Solid from Top.
                    // Surface = TileY + Metric.
                    return (byte) ((tileY + metric) - originalY);
                }
                // Normal: Solid from Bottom.
                // Surface = TileY + 16 - Height
                // Distance = Surface - SensorY
                return (byte) ((tileY + 16 - metric) - originalY);
            case UP:
                // Looking for ceiling (Bottom of solid). Solid from Top.
                if (vFlip) {
                    // VFlip: Solid from Bottom.
                    // Surface = TileY + 16 - Metric.
                    // Distance = SensorY - Surface.
                    return (byte) (originalY - (tileY + 16 - metric));
                }
                // Normal: Solid from Top.
                // Surface = TileY + Height
                // Distance = SensorY - Surface
                return (byte) (originalY - (tileY + metric));
            case RIGHT:
                // Looking for Wall (Left of solid). Solid from Right?
                if (hFlip) {
                    // HFlip: Solid from Left.
                    // Surface = TileX + Metric.
                    // Distance = Surface - SensorX.
                    return (byte) ((tileX + metric) - originalX);
                }
                // Normal: Solid from Right.
                // Logic: (TileX + 16 - Width) - SensorX
                return (byte) ((tileX + 16 - metric) - originalX);
            case LEFT:
                // Looking for Wall (Right of solid). Solid from Left?
                if (hFlip) {
                    // HFlip: Solid from Right.
                    // Surface = TileX + 16 - Metric.
                    // Distance = SensorX - Surface.
                    return (byte) (originalX - (tileX + 16 - metric));
                }
                // Normal: Solid from Left.
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
