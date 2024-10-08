package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.level.Level;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.Tile;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

public class GroundSensor extends Sensor {
    public GroundSensor(AbstractPlayableSprite sprite, Direction direction, byte x, byte y, boolean active) {
        super(sprite, direction, x, y, active);
    }

    @Override
    protected SensorResult doScan() {
        if (!active) {
            return null;
        }

        SensorConfiguration sensorConfiguration = SpriteManager.getSensorConfigurationForGroundModeAndDirection(sprite.getGroundMode(), direction);
        byte xIncrement = sensorConfiguration.xIncrement();
        byte yIncrement = sensorConfiguration.yIncrement();
        boolean vertical = sensorConfiguration.vertical();
        Direction globalDirection = sensorConfiguration.direction();

        Level level = LevelManager.getInstance().getLevel();

        // First, find if there is a tile underneath the sensor
        short originalX = (short) (sprite.getX() + x);
        short originalY = (short) (sprite.getY() + y);

        short currentX = originalX;
        short currentY = originalY;

        // Check for a tile under the sensor.
        Tile initialTile = level.getTileAt(currentX, currentY);
        byte initialHeight;
        if (initialTile != null) {
            // There is a tile under the sensor, let's remember its height (or width, depending on direction the sensor is facing)
            initialHeight = (vertical) ? initialTile.getHeightAt((byte) (currentX % 16)) : initialTile.calculateWidthAt((byte) (currentY % 16));
        } else {
            // No tile so a height of 0.
            initialHeight = 0;
        }

        if (initialHeight == 16) {
            // Full tile under sensor so we must go 'backwards' and check the 'previous' tile.
            // Work out new currentX and currentY values to move 16 pixels backwards.
            if (vertical) {
                currentY = calculateNextTile(globalDirection.opposite(), currentY);
            } else {
                currentX = calculateNextTile(globalDirection.opposite(), currentX);
            }
            // Look for a 'previous' tile using the new coordinates
            Tile prevTile = level.getTileAt(currentX, currentY);
            if (prevTile != null) {
                // Extract height or width value as appropriate from the 'previous' tile.
                byte prevTileHeight = (vertical) ? prevTile.getHeightAt((byte) (currentX % 16)) : prevTile.calculateWidthAt((byte) (currentY % 16));
                if (prevTileHeight > 0) {
                    // 'Previous' tile has a height value > 0 so this is our tile to calculate distance for.
                    return new SensorResult(prevTile.getAngle(), calculateDistance(prevTile, originalX, originalY, currentX, currentY, direction), 0);
                }
            }
            // 'Previous' tile not found or has a height of 0, so return distance to initial tile.
            return new SensorResult(initialTile.getAngle(), calculateDistance(initialTile, originalX, originalY, originalX, originalY, direction), 0);

        } else if (initialHeight > 0) {
            // First tile has a height value > 0 and < 16 so return the distance to the edge of this tile.
            return new SensorResult(initialTile.getAngle(), calculateDistance(initialTile, originalX, originalY, originalX, originalY, direction), 0);
        } else {
            // No tiles found so far (after initial spot and 'previous' if applicable)
            // Need to expand our search to the 'next' block.
            // Update our currentY or currentX to move 'forwards' to the next tile.
            if (vertical) {
                currentY = calculateNextTile(globalDirection, currentY);
            } else {
                currentX = calculateNextTile(globalDirection, currentX);
            }
            // Retrieve 'next' tile based on new currentX and currentY.
            Tile nextTile = level.getTileAt(currentX, currentY);
            byte lastDistance;
            if (nextTile == null) {
                // No tile here either so send the maximum possible distance it could be.
                // needs to be 16 + distance of previous tile... work it out mathematically:
                // Or just be lazy and use the calculateDistance method for the first tile (or lack thereof) then add (or subtract?) 16...
                byte distance = calculateDistance(initialTile, originalX, originalY, originalX, originalY, direction);
                distance = (byte) ((Direction.LEFT.equals(globalDirection) || Direction.UP.equals(globalDirection)) ? distance - 32 : distance + 32);
                return new SensorResult((byte ) 0, distance,0);
            } else {
                return new SensorResult((byte) 0, calculateDistance(nextTile, originalX, originalY, currentX, currentY, direction), 0);
            }
        }
    }

    private byte calculateDistance(Tile tile, short originalX, short originalY, short checkX, short checkY, Direction direction) {
        short tileX = (short) (checkX - (checkX % 16));
        short tileY = (short) (checkY - (checkY % 16));
        switch (direction) {
            case DOWN, UP -> {
                // needs splitting - direction is important to work out whether we subtract or add the height
                // or it might be fine, I dunno
                byte height = (tile == null) ? 0 : tile.getHeightAt((byte) (checkX % 16));
                return (byte) (tileY - height - originalY);
            }
            case LEFT, RIGHT -> {
                byte width = (tile == null) ? 0 : tile.calculateWidthAt((byte) (checkY % 16));
                return (byte) (tileX + width - originalX);
            }
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