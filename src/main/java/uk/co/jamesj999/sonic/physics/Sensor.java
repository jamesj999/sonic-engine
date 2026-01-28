package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

public abstract class Sensor {
    protected AbstractPlayableSprite sprite;
    protected Direction direction;
    protected byte x;
    protected byte y;
    protected boolean active;

    protected SensorResult currentResult;

    protected abstract SensorResult doScan(short dx, short dy);

    public SensorResult scan() {
        return scan((short) 0, (short) 0);
    }

    public SensorResult scan(short dx, short dy) {
        currentResult = doScan(dx, dy);
        return currentResult;
    }

    public Sensor(AbstractPlayableSprite sprite, Direction direction, byte x, byte y, boolean active) {
        this.sprite = sprite;
        this.direction = direction;
        this.x = x;
        this.y = y;
        this.active = active;
    }

    public Direction getDirection() {
        return direction;
    }

    public short[] getRotatedOffset() {
        short xOffset = x;
        short yOffset = y;

        GroundMode mode = sprite.getGroundMode();
        switch (mode) {
            case RIGHTWALL -> {
                // ROM: (x, y) -> (y, x) - swap axes only, no negation
                // s2.asm Sonic_WalkVertR (42684-42712): sensors at (x+y_rad, y-x_rad) / (x+y_rad, y+x_rad)
                // Left sensor (-9, 19) → (19, -9), Right sensor (9, 19) → (19, 9)
                short temp = xOffset;
                xOffset = yOffset;
                yOffset = temp;
            }
            case CEILING -> {
                // ROM: (x, y) -> (x, -y) - only negate Y, X stays the same
                // s2.asm Sonic_WalkCeiling (42750-42779): sensors at (x+x_rad, y-y_rad) / (x-x_rad, y-y_rad)
                yOffset = (short) -yOffset;
            }
            case LEFTWALL -> {
                // ROM: (x, y) -> (-y, x) - negate Y, then swap
                // s2.asm Sonic_WalkVertL (42817-42846): sensors at (x-y_rad, y-x_rad) / (x-y_rad, y+x_rad)
                short temp = xOffset;
                xOffset = (short) -yOffset;
                yOffset = temp;
            }
            default -> { }
        }

        return new short[] { xOffset, yOffset };
    }

    public SensorResult getCurrentResult() {
        return currentResult;
    }

    public byte getX() {
        return x;
    }

    public byte getY() {
        return y;
    }

    public void setOffset(byte x, byte y) {
        this.x = x;
        this.y = y;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

}
