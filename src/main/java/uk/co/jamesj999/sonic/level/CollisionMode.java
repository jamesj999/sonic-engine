package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.physics.Direction;

/**
 *
 */
public enum CollisionMode {
    NO_COLLISION(0),
    TOP_SOLID(1),
    LEFT_RIGHT_BOTTOM_SOLID(2),
    ALL_SOLID(3);

    private final int value;

    CollisionMode(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public boolean isSolid(Direction direction) {
        return switch (this) {
            case NO_COLLISION -> false;
            case TOP_SOLID -> direction == Direction.DOWN;
            case LEFT_RIGHT_BOTTOM_SOLID -> direction != Direction.DOWN;
            case ALL_SOLID -> true;
        };
    }

    public static CollisionMode fromVal(int value) {
        return switch (value) {
            case 0 -> NO_COLLISION;
            case 1 -> TOP_SOLID;
            case 2 -> LEFT_RIGHT_BOTTOM_SOLID;
            case 3 -> ALL_SOLID;
            default -> throw new IllegalArgumentException("Invalid collision mode: " + value);
        };
    }
}
