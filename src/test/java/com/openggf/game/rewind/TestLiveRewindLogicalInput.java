package com.openggf.game.rewind;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestLiveRewindLogicalInput {

    @Test
    void appendFrameCapturesHeldActionMaskAndRomStart() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_RIGHT,
                        AbstractPlayableSprite.INPUT_RIGHT,
                        InputActionMasks.ACTION_B,
                        InputActionMasks.ACTION_B,
                        true,
                        true),
                PlayerInputState.neutral()));
        LiveRewindInputSource source = new LiveRewindInputSource();

        source.appendFrame(input, config);

        Bk2FrameInput frame = source.read(1);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                frame.p1InputMask());
        assertEquals(InputActionMasks.ACTION_B, frame.p1ActionMask());
        assertTrue(frame.p1StartPressed());
    }

    @Test
    void recordedSnapshotCarriesDebugModifiersIntoInputOverride() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        Bk2FrameInput previous = new Bk2FrameInput(0, 0, 0, false, 0, 0, false, "previous");
        Bk2FrameInput current = new Bk2FrameInput(
                1, 0, 0, false, 0, 0, false,
                true, true, true, true, true, "current");

        input.setLogicalOverride(RecordedInputSnapshots.fromBk2(current, previous));

        assertTrue(input.isShiftDown());
        assertTrue(input.isControlDown());
        assertTrue(input.isAltDown());
        assertTrue(input.isSuperDown());
        assertTrue(input.isKeyPressed(config.getInt(SonicConfiguration.DEBUG_MODE_KEY)));
    }

    @Test
    void recordedSnapshotDerivesPressedEdgesFromHeldMasks() {
        Bk2FrameInput previous = new Bk2FrameInput(
                0,
                AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                InputActionMasks.ACTION_A,
                true,
                0,
                0,
                false,
                "previous");
        Bk2FrameInput current = new Bk2FrameInput(
                1,
                AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                InputActionMasks.ACTION_A | InputActionMasks.ACTION_B,
                true,
                0,
                0,
                false,
                "current");

        LogicalInputSnapshot snapshot = RecordedInputSnapshots.fromBk2(current, previous);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                snapshot.player1().heldMask());
        assertEquals(0, snapshot.player1().pressedMask() & AbstractPlayableSprite.INPUT_RIGHT);
        assertEquals(AbstractPlayableSprite.INPUT_JUMP,
                snapshot.player1().pressedMask() & AbstractPlayableSprite.INPUT_JUMP);
        assertEquals(InputActionMasks.ACTION_A | InputActionMasks.ACTION_B,
                snapshot.player1().actionHeldMask());
        assertEquals(InputActionMasks.ACTION_B, snapshot.player1().actionPressedMask());
        assertTrue(snapshot.player1().startHeld());
        assertFalse(snapshot.player1().startPressed());
    }

    @Test
    void heldDirectionNeverReplaysAsZeroedAcrossOneHundredTwentyAppendedLiveRewindFrames() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        int rightKey = config.getInt(SonicConfiguration.RIGHT);
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        input.handleKeyEvent(rightKey, GLFW_PRESS);
        LiveRewindInputSource source = new LiveRewindInputSource();

        for (int i = 0; i < 120; i++) {
            input.refreshLogicalSnapshot();
            source.appendFrame(input, config);
            input.update();
        }

        for (int frame = source.earliestFrame() + 1; frame < source.frameCount(); frame++) {
            Bk2FrameInput current = source.read(frame);
            Bk2FrameInput previous = source.read(frame - 1);
            LogicalInputSnapshot snapshot = RecordedInputSnapshots.fromBk2(current, previous);
            assertTrue((snapshot.player1().heldMask() & AbstractPlayableSprite.INPUT_RIGHT) != 0,
                    "Replayed held-right on recorded frame " + frame + " should not read as released");
        }
    }
}
