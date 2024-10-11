package uk.co.jamesj999.sonic.physics;

public class WallSensor extends Sensor {
    public WallSensor(Direction direction, byte x, byte y) {
        super(direction, x, y);
    }

    @Override
    public SensorResult scan() {
        return null;
    }
}
