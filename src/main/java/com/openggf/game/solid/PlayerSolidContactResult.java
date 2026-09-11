package com.openggf.game.solid;

public record PlayerSolidContactResult(
        ContactKind kind,
        boolean standingNow,
        boolean standingLastFrame,
        boolean pushingNow,
        boolean pushingLastFrame,
        PreContactState preContact,
        PostContactState postContact,
        int sideDistX) {

    public static PlayerSolidContactResult noContact(
            PlayerStandingState previous,
            PreContactState preContact,
            PostContactState postContact) {
        return new PlayerSolidContactResult(
                ContactKind.NONE,
                false,
                previous.standing(),
                false,
                previous.pushing(),
                preContact,
                postContact,
                0);
    }
}
