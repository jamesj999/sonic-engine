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
            // We only do this for horizontal sensors. Vertical regression (checking the tile above/below)
            // causes issues where a ceiling sensor checks the floor (or vice versa) and ejects the sprite
            // through the opposite surface.
            if (!vertical) {
                short prevX = calculateNextTile(globalDirection.opposite(), currentX);

                ChunkDesc prevChunkDesc = levelManager.getChunkDescAt(layer, prevX, currentY);
                SolidTile prevTile = getSolidTile(prevChunkDesc, layer, globalDirection);
                byte prevHeight = getMetric(prevTile, prevChunkDesc, prevX, currentY, vertical);

                if (prevHeight > 0) {
                    // Found a valid previous tile, use it
                    return createResult(prevTile, prevChunkDesc, originalX, originalY, prevX, currentY, globalDirection, vertical);
                }
            }
            // Previous tile empty or invalid (or vertical sensor), revert to initial
            return createResult(initialTile, initialChunkDesc, originalX, originalY, originalX, originalY, globalDirection, vertical);
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
                    // Distance = Surface - SensorY. (Wait. SensorY is above surface).
                    // Wait. Floor means Sprite is ABOVE. SensorY < Surface.
                    // Distance = Surface - SensorY.
                    // Surface = TileY + metric.
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
