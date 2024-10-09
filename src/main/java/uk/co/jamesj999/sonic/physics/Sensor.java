package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public abstract class Sensor {
    protected AbstractPlayableSprite sprite;
    protected Direction direction;
    protected byte x;
    protected byte y;
    protected boolean active;

    protected SensorResult currentResult;

    protected abstract SensorResult doScan();

    public SensorResult scan() {
        currentResult = doScan();
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

    public SensorResult getCurrentResult() {
        return currentResult;
    }

    public byte getX() {
        return x;
    }

    public byte getY() {
        return y;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public boolean isActive() {
        return active;
    }

}
