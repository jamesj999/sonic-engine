package uk.co.jamesj999.sonic.sprites;

import uk.co.jamesj999.sonic.physics.Direction;

public record SensorConfiguration(byte xIncrement, byte yIncrement, boolean vertical, Direction direction) {
}
