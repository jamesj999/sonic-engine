package com.openggf.game;

import com.openggf.control.InputActionMasks;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Objects;

public final class SpecialStageInputMapper {

    private SpecialStageInputMapper() {
    }

    public record MappedInput(int p1Held, int p1Pressed, int p2Held, int p2Logical) {
    }

    public static MappedInput map(LogicalInputSnapshot logical) {
        Objects.requireNonNull(logical, "logical");
        PlayerInputState p1 = logical.player1();
        PlayerInputState p2 = logical.player2();

        int p1Held = directionBits(p1.heldMask())
                | InputActionMasks.toMegaDriveButtonBits(p1.actionHeldMask())
                | (p1.startHeld() ? 0x80 : 0);
        int p1Pressed = directionBits(p1.pressedMask())
                | InputActionMasks.toMegaDriveButtonBits(p1.actionPressedMask())
                | (p1.startPressed() ? 0x80 : 0);
        int p2Held = directionBits(p2.heldMask())
                | InputActionMasks.toMegaDriveButtonBits(p2.actionHeldMask())
                | (p2.startHeld() ? 0x80 : 0);

        return new MappedInput(p1Held, p1Pressed, p2Held, p2Held);
    }

    private static int directionBits(int logicalMask) {
        int bits = 0;
        if ((logicalMask & AbstractPlayableSprite.INPUT_UP) != 0) {
            bits |= 0x01;
        }
        if ((logicalMask & AbstractPlayableSprite.INPUT_DOWN) != 0) {
            bits |= 0x02;
        }
        if ((logicalMask & AbstractPlayableSprite.INPUT_LEFT) != 0) {
            bits |= 0x04;
        }
        if ((logicalMask & AbstractPlayableSprite.INPUT_RIGHT) != 0) {
            bits |= 0x08;
        }
        return bits;
    }
}
