package com.openggf.game.solid;

public record PreContactState(
        short xSpeed,
        short ySpeed,
        boolean rolling,
        boolean air,
        int animationId,
        boolean pushing) {

    public PreContactState(short xSpeed, short ySpeed, boolean rolling, boolean air, int animationId) {
        this(xSpeed, ySpeed, rolling, air, animationId, false);
    }

    public PreContactState(short xSpeed, short ySpeed, boolean rolling, int animationId) {
        this(xSpeed, ySpeed, rolling, false, animationId, false);
    }

    public static final PreContactState ZERO =
            new PreContactState((short) 0, (short) 0, false, false, 0, false);
}
