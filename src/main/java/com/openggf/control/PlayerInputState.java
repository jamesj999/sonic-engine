package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;

public record PlayerInputState(
        int heldMask,
        int pressedMask,
        int actionHeldMask,
        int actionPressedMask,
        boolean startHeld,
        boolean startPressed) {

    private static final int DIRECTION_MASK = AbstractPlayableSprite.INPUT_UP
            | AbstractPlayableSprite.INPUT_DOWN
            | AbstractPlayableSprite.INPUT_LEFT
            | AbstractPlayableSprite.INPUT_RIGHT;

    public PlayerInputState {
        actionHeldMask = InputActionMasks.sanitizeActionMask(actionHeldMask);
        actionPressedMask = InputActionMasks.sanitizeActionMask(actionPressedMask);
        heldMask = sanitizeDirectionMask(heldMask)
                | (actionHeldMask != 0 ? AbstractPlayableSprite.INPUT_JUMP : 0);
        pressedMask = sanitizeDirectionMask(pressedMask)
                | (actionPressedMask != 0 ? AbstractPlayableSprite.INPUT_JUMP : 0);
    }

    public static PlayerInputState neutral() {
        return of(0, 0, 0, 0, false, false);
    }

    public static PlayerInputState of(
            int heldMask,
            int pressedMask,
            int actionHeldMask,
            int actionPressedMask,
            boolean startHeld,
            boolean startPressed) {
        return new PlayerInputState(
                heldMask,
                pressedMask,
                actionHeldMask,
                actionPressedMask,
                startHeld,
                startPressed);
    }

    public PlayerInputState merge(PlayerInputState other) {
        if (other == null) {
            return this;
        }
        return of(
                heldMask | other.heldMask,
                pressedMask | other.pressedMask,
                actionHeldMask | other.actionHeldMask,
                actionPressedMask | other.actionPressedMask,
                startHeld || other.startHeld,
                startPressed || other.startPressed);
    }

    private static int sanitizeDirectionMask(int mask) {
        return mask & DIRECTION_MASK;
    }
}
