package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;

public class KeyboardInputMapper {

    public PlayerInputState mapPlayer1(InputHandler input, InputBindings bindings) {
        return mapPlayer(
                input,
                bindings.p1Up(),
                bindings.p1Down(),
                bindings.p1Left(),
                bindings.p1Right(),
                bindings.p1A(),
                bindings.p1B(),
                bindings.p1C(),
                bindings.p1Start());
    }

    public PlayerInputState mapPlayer2(InputHandler input, InputBindings bindings) {
        return mapPlayer(
                input,
                bindings.p2Up(),
                bindings.p2Down(),
                bindings.p2Left(),
                bindings.p2Right(),
                bindings.p2A(),
                bindings.p2B(),
                bindings.p2C(),
                bindings.p2Start());
    }

    private PlayerInputState mapPlayer(
            InputHandler input,
            int up,
            int down,
            int left,
            int right,
            int actionA,
            int actionB,
            int actionC,
            int start) {
        int heldMask = directionMask(input, false, up, down, left, right);
        int pressedMask = directionMask(input, true, up, down, left, right);
        int actionHeldMask = actionMask(input, false, actionA, actionB, actionC);
        int actionPressedMask = actionMask(input, true, actionA, actionB, actionC);
        return PlayerInputState.of(
                heldMask,
                pressedMask,
                actionHeldMask,
                actionPressedMask,
                input.isKeyDown(start),
                input.isKeyPressed(start));
    }

    private int directionMask(InputHandler input, boolean pressed, int up, int down, int left, int right) {
        int mask = 0;
        if (key(input, pressed, up)) {
            mask |= AbstractPlayableSprite.INPUT_UP;
        }
        if (key(input, pressed, down)) {
            mask |= AbstractPlayableSprite.INPUT_DOWN;
        }
        if (key(input, pressed, left)) {
            mask |= AbstractPlayableSprite.INPUT_LEFT;
        }
        if (key(input, pressed, right)) {
            mask |= AbstractPlayableSprite.INPUT_RIGHT;
        }
        return mask;
    }

    private int actionMask(InputHandler input, boolean pressed, int actionA, int actionB, int actionC) {
        int mask = 0;
        if (key(input, pressed, actionA)) {
            mask |= InputActionMasks.ACTION_A;
        }
        if (key(input, pressed, actionB)) {
            mask |= InputActionMasks.ACTION_B;
        }
        if (key(input, pressed, actionC)) {
            mask |= InputActionMasks.ACTION_C;
        }
        return mask;
    }

    private boolean key(InputHandler input, boolean pressed, int keyCode) {
        return pressed ? input.isKeyPressed(keyCode) : input.isKeyDown(keyCode);
    }
}
