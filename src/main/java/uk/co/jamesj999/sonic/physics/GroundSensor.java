package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.ChunkDesc;
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
        SolidTile initialTile = levelManager.getSolidTileForChunkDesc(initialChunkDesc);
        byte initialHeight = getMetric(initialTile, initialChunkDesc, currentX, currentY, vertical);

        // When in Air, sensors are treated as points/lines and do not use Extension/Regression logic which is for surface tracking.
        if (sprite.getAir()) {
            if (initialHeight > 0) {
                return createResult(initialTile, initialChunkDesc, originalX, originalY, originalX, originalY, globalDirection, vertical);
            } else {
                return null;
            }
        }

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
            SolidTile prevTile = levelManager.getSolidTileForChunkDesc(prevChunkDesc);
            byte prevHeight = getMetric(prevTile, prevChunkDesc, prevX, prevY, vertical);

            if (prevHeight > 0) {
                // Found a valid previous tile, use it
                return createResult(prevTile, prevChunkDesc, originalX, originalY, prevX, prevY, globalDirection, vertical);
            } else {
                // Previous tile empty or invalid, revert to initial
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
            SolidTile nextTile = levelManager.getSolidTileForChunkDesc(nextChunkDesc);
            byte nextHeight = getMetric(nextTile, nextChunkDesc, nextX, nextY, vertical);

            if (nextHeight > 0) {
                // Found valid extension tile
                return createResult(nextTile, nextChunkDesc, originalX, originalY, nextX, nextY, globalDirection, vertical);
            } else {
                // Extension failed (no tile found). Return distance to end of second block.
                // We use the 'next' coordinates to calculate the distance to its far edge.
                // If nextTile is null, we can't get angle/index. Use defaults (0).
                // But we need the distance.
                // Distance = Distance to "End of Next Block".
                // If DOWN: End is Top + 32?
                // Logic: 16 (first block empty) + 16 (second block empty) = 32?
                // Actually, let's use calculateDistance but pretend we found a full block at next position?
                // Or just use the formula:
                // DOWN: (nextY + 16 - 0) - originalY. (nextY is +16 from start).
                // If originalY=100. nextY=112. (112+16-0) - 100 = 28.
                // Matches my test expectation (28).
                // So we can use calculateDistance with height=0 at nextX/nextY.
                byte distance = calculateDistance((byte) 0, originalX, originalY, nextX, nextY, globalDirection);
                return new SensorResult((byte) 0, distance, 0, globalDirection);
            }
        } else {
            // Normal case (0 < height < 16)
            return createResult(initialTile, initialChunkDesc, originalX, originalY, originalX, originalY, globalDirection, vertical);
        }
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
        byte distance = calculateDistance(metric, originalX, originalY, checkX, checkY, direction);

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

    private byte calculateDistance(byte metric, short originalX, short originalY, short checkX, short checkY, Direction direction) {
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
