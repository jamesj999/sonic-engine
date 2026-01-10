package uk.co.jamesj999.sonic.physics;

public enum Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    // Pre-computed opposites indexed by ordinal: UP(0)->DOWN, DOWN(1)->UP,
    // LEFT(2)->RIGHT, RIGHT(3)->LEFT
    private static final Direction[] OPPOSITES = { DOWN, UP, RIGHT, LEFT };

    Direction opposite() {
        return OPPOSITES[ordinal()];
    }
}
