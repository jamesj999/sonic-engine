package com.openggf.sprites.playable;

/** Reads the ROM-visible raw controller word before CPU logical input is composed. */
public final class RawControllerInput {
    private RawControllerInput() {
    }

    public static boolean isHeld(AbstractPlayableSprite player, int inputMask) {
        if (player.isCpuControlled() && player.getCpuController() != null) {
            return player.getCpuController().isRawController2InputHeld(inputMask);
        }
        int held = 0;
        if (player.isUpPressed()) held |= AbstractPlayableSprite.INPUT_UP;
        if (player.isDownPressed()) held |= AbstractPlayableSprite.INPUT_DOWN;
        if (player.isLeftPressed()) held |= AbstractPlayableSprite.INPUT_LEFT;
        if (player.isRightPressed()) held |= AbstractPlayableSprite.INPUT_RIGHT;
        if (player.isJumpPressed()) held |= AbstractPlayableSprite.INPUT_JUMP;
        return (held & inputMask) != 0;
    }
}
