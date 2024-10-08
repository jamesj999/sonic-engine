package uk.co.jamesj999.sonic.physics;

public enum Direction {
    UP,
    DOWN,
    LEFT,
    RIGHT;

    Direction opposite() {
        switch(this) {
            case UP -> {
                return DOWN;
            }
            case DOWN -> {
                return UP;
            }
            case LEFT -> {
                return RIGHT;
            }
            case RIGHT -> {
                return LEFT;
            }
        }
        // Please, Java
        throw new IllegalStateException("Direction is none of the expected ones. If you added more, please update the opposite() method accordingly!");
    }
}
