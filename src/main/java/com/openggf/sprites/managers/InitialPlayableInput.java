package com.openggf.sprites.managers;

/**
 * Controller words visible to the playable slots during the initial native
 * {@code Process_Sprites} pass.
 */
public record InitialPlayableInput(
        int p1Held,
        int p1Pressed,
        int p1ActionPressedMask,
        int p2Held,
        int p2Pressed,
        boolean consumeQueuedObjectControlState) {

    public static InitialPlayableInput nativeNeutral() {
        return new InitialPlayableInput(0, 0, 0, 0, 0, true);
    }
}
