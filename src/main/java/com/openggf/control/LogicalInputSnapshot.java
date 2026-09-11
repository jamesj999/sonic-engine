package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;

public record LogicalInputSnapshot(
        PlayerInputState player1,
        PlayerInputState player2,
        boolean menuUp,
        boolean menuDown,
        boolean menuLeft,
        boolean menuRight,
        boolean menuAccept,
        boolean menuBack,
        boolean menuStart,
        boolean anyActionPressed,
        boolean debugModeTogglePressed,
        boolean debugShiftDown,
        boolean debugControlDown,
        boolean debugAltDown,
        boolean debugSuperDown) {

    public static LogicalInputSnapshot neutral() {
        return ofPlayers(PlayerInputState.neutral(), PlayerInputState.neutral());
    }

    public static LogicalInputSnapshot ofPlayers(PlayerInputState player1, PlayerInputState player2) {
        PlayerInputState p1 = player1 != null ? player1 : PlayerInputState.neutral();
        PlayerInputState p2 = player2 != null ? player2 : PlayerInputState.neutral();
        int p1Pressed = p1.pressedMask();
        boolean p1ActionPressed = p1.actionPressedMask() != 0;
        boolean p2ActionPressed = p2.actionPressedMask() != 0;

        return new LogicalInputSnapshot(
                p1,
                p2,
                (p1Pressed & AbstractPlayableSprite.INPUT_UP) != 0,
                (p1Pressed & AbstractPlayableSprite.INPUT_DOWN) != 0,
                (p1Pressed & AbstractPlayableSprite.INPUT_LEFT) != 0,
                (p1Pressed & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                p1ActionPressed || p1.startPressed(),
                (p1.actionPressedMask() & InputActionMasks.ACTION_C) != 0,
                p1.startPressed(),
                p1ActionPressed || p2ActionPressed || p1.startPressed() || p2.startPressed(),
                false,
                false,
                false,
                false,
                false);
    }

    public LogicalInputSnapshot withMenuPolicy(boolean accept, boolean back) {
        return new LogicalInputSnapshot(
                player1,
                player2,
                menuUp,
                menuDown,
                menuLeft,
                menuRight,
                accept,
                back,
                menuStart,
                anyActionPressed,
                debugModeTogglePressed,
                debugShiftDown,
                debugControlDown,
                debugAltDown,
                debugSuperDown);
    }

    public LogicalInputSnapshot withDebugInput(
            boolean modeTogglePressed,
            boolean shiftDown,
            boolean controlDown,
            boolean altDown,
            boolean superDown) {
        return new LogicalInputSnapshot(
                player1,
                player2,
                menuUp,
                menuDown,
                menuLeft,
                menuRight,
                menuAccept,
                menuBack,
                menuStart,
                anyActionPressed,
                modeTogglePressed,
                shiftDown,
                controlDown,
                altDown,
                superDown);
    }
}
