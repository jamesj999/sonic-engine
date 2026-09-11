package com.openggf.control;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlayerInputState {

    @Test
    void actionHeldCollapsesToJumpBit() {
        PlayerInputState state = PlayerInputState.of(
                AbstractPlayableSprite.INPUT_LEFT | AbstractPlayableSprite.INPUT_JUMP | 0x80,
                0,
                InputActionMasks.ACTION_A | 0x80,
                0,
                false,
                false);

        assertEquals(AbstractPlayableSprite.INPUT_LEFT | AbstractPlayableSprite.INPUT_JUMP, state.heldMask());
        assertEquals(InputActionMasks.ACTION_A, state.actionHeldMask());
    }

    @Test
    void actionPressedCollapsesToJumpPress() {
        PlayerInputState state = PlayerInputState.of(
                0,
                AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP | 0x80,
                0,
                InputActionMasks.ACTION_B | 0x80,
                false,
                false);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP, state.pressedMask());
        assertEquals(InputActionMasks.ACTION_B, state.actionPressedMask());
    }

    @Test
    void mergeCombinesDirectionsActionsAndStart() {
        PlayerInputState first = PlayerInputState.of(
                AbstractPlayableSprite.INPUT_UP,
                AbstractPlayableSprite.INPUT_LEFT,
                InputActionMasks.ACTION_A,
                0,
                true,
                false);
        PlayerInputState second = PlayerInputState.of(
                AbstractPlayableSprite.INPUT_DOWN,
                AbstractPlayableSprite.INPUT_RIGHT,
                InputActionMasks.ACTION_B,
                InputActionMasks.ACTION_C,
                false,
                true);

        PlayerInputState merged = first.merge(second);

        assertEquals(AbstractPlayableSprite.INPUT_UP
                | AbstractPlayableSprite.INPUT_DOWN
                | AbstractPlayableSprite.INPUT_JUMP, merged.heldMask());
        assertEquals(AbstractPlayableSprite.INPUT_LEFT
                | AbstractPlayableSprite.INPUT_RIGHT
                | AbstractPlayableSprite.INPUT_JUMP, merged.pressedMask());
        assertEquals(InputActionMasks.ACTION_A | InputActionMasks.ACTION_B, merged.actionHeldMask());
        assertEquals(InputActionMasks.ACTION_C, merged.actionPressedMask());
        assertTrue(merged.startHeld());
        assertTrue(merged.startPressed());
        assertSame(first, first.merge(null));
    }

    @Test
    void logicalSnapshotDerivesMenuFromPlayerOne() {
        PlayerInputState player1 = PlayerInputState.of(
                0,
                AbstractPlayableSprite.INPUT_UP | AbstractPlayableSprite.INPUT_LEFT,
                0,
                InputActionMasks.ACTION_C,
                false,
                true);
        PlayerInputState player2 = PlayerInputState.of(
                0,
                AbstractPlayableSprite.INPUT_DOWN | AbstractPlayableSprite.INPUT_RIGHT,
                0,
                0,
                false,
                false);

        LogicalInputSnapshot snapshot = LogicalInputSnapshot.ofPlayers(player1, player2);

        assertTrue(snapshot.menuUp());
        assertFalse(snapshot.menuDown());
        assertTrue(snapshot.menuLeft());
        assertFalse(snapshot.menuRight());
        assertTrue(snapshot.menuAccept());
        assertTrue(snapshot.menuBack());
        assertTrue(snapshot.menuStart());
        assertTrue(snapshot.anyActionPressed());
        assertFalse(snapshot.debugModeTogglePressed());
        assertFalse(snapshot.debugShiftDown());
        assertFalse(snapshot.debugControlDown());
        assertFalse(snapshot.debugAltDown());
        assertFalse(snapshot.debugSuperDown());
    }

    @Test
    void logicalSnapshotCanOverrideMenuPolicyAndDebugInput() {
        LogicalInputSnapshot snapshot = LogicalInputSnapshot.ofPlayers(
                        PlayerInputState.neutral(),
                        PlayerInputState.of(0, 0, 0, InputActionMasks.ACTION_A, false, false))
                .withMenuPolicy(true, true)
                .withDebugInput(true, true, true, true, true);

        assertTrue(snapshot.menuAccept());
        assertTrue(snapshot.menuBack());
        assertTrue(snapshot.anyActionPressed());
        assertTrue(snapshot.debugModeTogglePressed());
        assertTrue(snapshot.debugShiftDown());
        assertTrue(snapshot.debugControlDown());
        assertTrue(snapshot.debugAltDown());
        assertTrue(snapshot.debugSuperDown());
        assertEquals(PlayerInputState.neutral(), snapshot.player1());
        assertEquals(InputActionMasks.ACTION_A, snapshot.player2().actionPressedMask());
    }
}
