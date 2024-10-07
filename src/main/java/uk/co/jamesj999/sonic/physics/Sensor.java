package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public abstract class Sensor {
    protected AbstractPlayableSprite sprite;
    protected Direction direction;
    protected byte x;
    protected byte y;
    protected byte range;

    public abstract SensorResult scan();

    public Sensor(AbstractPlayableSprite sprite, Direction direction, byte x, byte y, byte range) {
        this.direction = direction;
        this.x = x;
        this.y = y;
        this.range = range;
    }
}
