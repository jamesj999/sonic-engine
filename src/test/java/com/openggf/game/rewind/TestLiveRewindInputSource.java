package com.openggf.game.rewind;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_CONTROL;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestLiveRewindInputSource {

    private SonicConfigurationService config;

    @BeforeEach
    void resetConfig() {
        config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
    }

    @Test
    void startsWithFrameZeroSoRewindControllerHasAnInitialInput() {
        LiveRewindInputSource source = new LiveRewindInputSource();

        assertEquals(1, source.frameCount());
        assertEquals(0, source.read(0).frameIndex());
        assertEquals(0, source.read(0).p1InputMask());
    }

    @Test
    void appendFrameRecordsP1LogicalHeldButtonsActionMaskAndStartPressEdge() {
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(
                        AbstractPlayableSprite.INPUT_RIGHT,
                        AbstractPlayableSprite.INPUT_RIGHT,
                        InputActionMasks.ACTION_C,
                        InputActionMasks.ACTION_C,
                        true,
                        true),
                PlayerInputState.neutral()));
        source.appendFrame(input, config);

        Bk2FrameInput frame = source.read(1);
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                frame.p1InputMask());
        assertEquals(InputActionMasks.ACTION_C, frame.p1ActionMask());
        assertTrue(frame.p1StartPressed(),
                "Live rewind should capture ROM Start from logical input, not the pause key");
    }

    @Test
    void appendFrameRecordsHeldActionMaskOnEveryHeldFrame() {
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, InputActionMasks.ACTION_B, InputActionMasks.ACTION_B, false, false),
                PlayerInputState.neutral()));
        source.appendFrame(input, config);
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, InputActionMasks.ACTION_B, 0, false, false),
                PlayerInputState.neutral()));
        source.appendFrame(input, config);

        assertEquals(InputActionMasks.ACTION_B, source.read(1).p1ActionMask());
        assertEquals(InputActionMasks.ACTION_B, source.read(2).p1ActionMask());
    }

    @Test
    void appendFrameRecordsP2HeldButtonsAndStartHeldState() {
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.neutral(),
                PlayerInputState.of(AbstractPlayableSprite.INPUT_LEFT, AbstractPlayableSprite.INPUT_LEFT,
                        InputActionMasks.ACTION_A, InputActionMasks.ACTION_A, true, true)));
        source.appendFrame(input, config);

        Bk2FrameInput frame = source.read(1);
        assertEquals(AbstractPlayableSprite.INPUT_LEFT | AbstractPlayableSprite.INPUT_JUMP, frame.p2InputMask());
        assertEquals(InputActionMasks.ACTION_A, frame.p2ActionMask());
        assertTrue(frame.p2StartPressed());

        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.neutral(),
                PlayerInputState.of(AbstractPlayableSprite.INPUT_LEFT, 0,
                        InputActionMasks.ACTION_A, 0, true, false)));
        source.appendFrame(input, config);

        assertTrue(source.read(2).p2StartPressed(),
                "Live rewind stores Start held state; replay derives the press edge from adjacent rows");
    }

    @Test
    void appendFrameRecordsDebugToggleEdgeAndDebugMovementModifiers() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.handleKeyEvent(config.getInt(SonicConfiguration.DEBUG_MODE_KEY), GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_LEFT_ALT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_LEFT_SUPER, GLFW_PRESS);
        source.appendFrame(input, config);

        Bk2FrameInput first = source.read(1);
        assertTrue(first.debugModeTogglePressed());
        assertTrue(first.debugShiftDown());
        assertTrue(first.debugControlDown());
        assertTrue(first.debugAltDown());
        assertTrue(first.debugSuperDown());

        input.update();
        source.appendFrame(input, config);

        Bk2FrameInput held = source.read(2);
        assertFalse(held.debugModeTogglePressed());
        assertTrue(held.debugShiftDown());
        assertTrue(held.debugControlDown());
        assertTrue(held.debugAltDown());
        assertTrue(held.debugSuperDown());
    }

    @Test
    void discardAfterDropsFutureFramesWhenLivePlaybackBranchesFromRewind() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();

        source.appendFrame(input, config);
        input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        source.discardAfter(1);
        input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
        input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);

        assertEquals(3, source.frameCount());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, source.read(2).p1InputMask());
    }

    @Test
    void discardBeforeKeepsAbsoluteFrameNumbersForRetainedHistory() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();

        for (int i = 0; i < 5; i++) {
            source.appendFrame(input, config);
        }

        source.discardBefore(3);

        assertEquals(3, source.earliestFrame());
        assertEquals(6, source.frameCount());
        assertEquals(3, source.read(3).frameIndex());
        assertEquals(5, source.read(5).frameIndex());
    }

    @Test
    void appendAfterDiscardBeforeUsesNextAbsoluteFrame() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();

        for (int i = 0; i < 5; i++) {
            source.appendFrame(input, config);
        }
        source.discardBefore(3);
        input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);

        assertEquals(7, source.frameCount());
        assertEquals(6, source.read(6).frameIndex());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, source.read(6).p1InputMask());
    }

    @Test
    void resetToFrameZeroClearsOldRowsAndBaseFrame() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
        input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        source.discardBefore(2);

        source.resetToFrameZero();

        assertEquals(0, source.earliestFrame());
        assertEquals(1, source.frameCount());
        Bk2FrameInput frame = source.read(0);
        assertEquals(0, frame.frameIndex());
        assertEquals(0, frame.p1InputMask());
        assertEquals(0, frame.p1ActionMask());
    }

    @Test
    void retainOnlyFrameKeepsRequestedExistingFrame() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        input.handleKeyEvent(config.getInt(SonicConfiguration.LEFT), GLFW_RELEASE);
        input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);

        source.retainOnlyFrame(2);

        assertEquals(2, source.earliestFrame());
        assertEquals(3, source.frameCount());
        Bk2FrameInput frame = source.read(2);
        assertEquals(2, frame.frameIndex());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, frame.p1InputMask());
    }

    @Test
    void retainOnlyFrameCreatesNeutralRowWhenFrameIsAbsent() {
        LiveRewindInputSource source = new LiveRewindInputSource();

        source.retainOnlyFrame(9);

        assertEquals(9, source.earliestFrame());
        assertEquals(10, source.frameCount());
        assertEquals(9, source.read(9).frameIndex());
        assertEquals(0, source.read(9).p1InputMask());
        assertEquals(0, source.read(9).p1ActionMask());
    }
    @Test
    void circularHistoryPreservesRowsThroughWrapGrowthBranchAndReset() {
        InputHandler input = new InputHandler();
        LiveRewindInputSource source = new LiveRewindInputSource();
        for (int frame = 1; frame <= 5000; frame++) {
            source.appendFrame(input, config);
            if (frame < 1500) source.discardBefore(Math.max(0, frame - 90));
            else source.discardBefore(Math.max(0, frame - 700));
            assertEquals(frame, source.read(frame).frameIndex());
            assertEquals(source.earliestFrame(), source.read(source.earliestFrame()).frameIndex());
        }
        for (int frame = source.earliestFrame(); frame <= 5000; frame++) {
            assertEquals(frame, source.read(frame).frameIndex());
        }
        source.discardAfter(4800);
        input.handleKeyEvent(config.getInt(SonicConfiguration.RIGHT), GLFW_PRESS);
        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        assertEquals(4802, source.frameCount());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT, source.read(4801).p1InputMask());
        source.retainOnlyFrame(4801);
        assertEquals(4801, source.earliestFrame());
        source.appendFrame(input, config);
        assertEquals(4802, source.read(4802).frameIndex());
        source.resetToFrameZero();
        assertEquals(1, source.frameCount());
        assertEquals(0, source.read(0).frameIndex());
    }

}
