package uk.co.jamesj999.sonic.physics;

import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

public class GroundSensor extends Sensor {
    public GroundSensor(AbstractPlayableSprite sprite, Direction direction, byte x, byte y) {
        super(sprite, direction, x, y, (byte) 32);
    }

    @Override
    public SensorResult scan() {
        return null;
    }
}
