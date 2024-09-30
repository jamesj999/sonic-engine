package uk.co.jamesj999.sonic.level;


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
