package com.openggf.debug.playback;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlaybackDebugManagerPreparedInput {
    private final PlaybackDebugManager playback =
            PlaybackDebugManager.getInstance();

    @AfterEach
    void tearDown() {
        playback.endSession();
    }

    @Test
    void preparedOffsetAppliesPreviousRowWithoutMovingValidationCursor() {
        RecordingObserver observer = new RecordingObserver(-1, false, 0);
        playback.startSession(movie(), 1);
        playback.setFrameObserver(observer);

        playback.prepareCurrentFrame();

        assertEquals(1, playback.getCursorFrame());
        assertEquals(AbstractPlayableSprite.INPUT_RIGHT,
                playback.getCurrentForcedInputMask());
        assertFalse(playback.isCurrentForcedJumpPress());
        assertFalse(playback.isCurrentForcedStartPress());
        assertEquals(1, observer.prepareCalls);
        assertEquals(1, observer.offsetCalls);

        playback.prepareCurrentFrame();
        playback.getCurrentForcedInputMask();
        assertEquals(1, observer.prepareCalls,
                "preparing and applying one cursor must be idempotent");
        assertEquals(1, observer.offsetCalls);
        assertEquals(1, playback.getCursorFrame());
    }

    @Test
    void selectedRowAndItsPredecessorDriveActionAndStartEdges() {
        RecordingObserver observer = new RecordingObserver(-1, false, 0);
        playback.startSession(movie(), 2);
        playback.setFrameObserver(observer);

        playback.prepareCurrentFrame();
        assertTrue(playback.isCurrentForcedJumpPress());
        assertTrue(playback.isCurrentForcedStartPress(),
                "Start admission must be available before held input is applied");
        assertEquals(AbstractPlayableSprite.INPUT_LEFT,
                playback.getCurrentForcedInputMask());

        assertTrue(playback.isCurrentForcedJumpPress());
        assertTrue(playback.isCurrentForcedStartPress());
        assertEquals(2, playback.getCursorFrame(),
                "edge derivation must not consume the validation row");
    }

    @Test
    void preparedAppliedRowPublishesACompleteLogicalSnapshot() {
        RecordingObserver observer = new RecordingObserver(-1, false, 0);
        playback.startSession(movie(), 2);
        playback.setFrameObserver(observer);

        var snapshot = playback.getCurrentLogicalInputSnapshot();

        assertEquals(AbstractPlayableSprite.INPUT_LEFT
                        | AbstractPlayableSprite.INPUT_JUMP,
                snapshot.player1().heldMask());
        assertEquals(AbstractPlayableSprite.INPUT_LEFT
                        | AbstractPlayableSprite.INPUT_JUMP,
                snapshot.player1().pressedMask());
        assertTrue(snapshot.player1().startHeld());
        assertTrue(snapshot.player1().startPressed());
        assertEquals(2, playback.getCursorFrame());
    }

    @Test
    void actionPressRemainsInSnapshotAfterInputOnlyCursorAdvance() {
        playback.startSession(actionCarryMovie(), 0);

        var pressed = playback.getCurrentLogicalInputSnapshot();
        assertEquals(0x02, pressed.player1().actionPressedMask());

        playback.onLevelFrameAdvanced();

        var held = playback.getCurrentLogicalInputSnapshot();
        assertEquals(0x02, held.player1().actionPressedMask(),
                "a skipped gameplay row must carry the exact pending action edge");
        assertTrue((held.player1().pressedMask()
                        & AbstractPlayableSprite.INPUT_JUMP) != 0,
                "the carried action edge must remain visible as abstract jump press");

        playback.onCurrentGameplayTickExecuted();
        assertEquals(0, playback.getCurrentLogicalInputSnapshot()
                .player1().actionPressedMask(),
                "the first real gameplay dispatch consumes the pending edge");
    }

    @Test
    void invalidAppliedOffsetFailsBeforeInputFallsBackOrCursorMoves() {
        playback.startSession(movie(), 0);
        playback.setFrameObserver(new RecordingObserver(-1, false, 0));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, playback::prepareCurrentFrame);

        assertTrue(error.getMessage().contains("outside movie"), error.getMessage());
        assertEquals(0, playback.getCursorFrame());
    }

    @Test
    void peekUsesValidationMaskConversionAndNeverAdvances() {
        playback.startSession(movie(), 1);

        assertEquals(AbstractPlayableSprite.INPUT_RIGHT,
                playback.peekInputMaskAt(-1));
        assertEquals(AbstractPlayableSprite.INPUT_LEFT
                        | AbstractPlayableSprite.INPUT_JUMP,
                playback.peekInputMaskAt(0));
        assertEquals(-1, playback.peekInputMaskAt(-2));
        assertEquals(1, playback.getCursorFrame());
    }

    @Test
    void skipAndVblankAnswersAreCachedForPreparedCursor() {
        RecordingObserver observer = new RecordingObserver(0, true, 1);
        playback.startSession(movie(), 1);
        playback.setFrameObserver(observer);
        playback.prepareCurrentFrame();

        assertTrue(playback.shouldSkipCurrentGameplayTick());
        assertTrue(playback.shouldSkipCurrentGameplayTick());
        assertTrue(playback.isCurrentGameplayTickSuppressed());
        assertEquals(1, playback.currentSkippedTickVblankAdvanceCount());
        assertEquals(1, playback.currentSkippedTickVblankAdvanceCount());
        assertEquals(1, observer.skipCalls);
        assertEquals(1, observer.vblankCalls);
    }

    @Test
    void observerDefaultsPreserveCurrentRowPlayback() {
        playback.startSession(movie(), 1);

        assertEquals(AbstractPlayableSprite.INPUT_LEFT,
                playback.getCurrentForcedInputMask());
        assertTrue(playback.isCurrentForcedJumpPress());
        assertTrue(playback.isCurrentForcedStartPress());
        assertEquals(1, playback.getCursorFrame());
        assertFalse(playback.shouldSkipCurrentGameplayTick());
    }

    private static Bk2Movie movie() {
        return new Bk2Movie(
                Path.of("prepared-input.bk2"), "logkey", Map.of(),
                List.of(
                        new Bk2FrameInput(0,
                                AbstractPlayableSprite.INPUT_RIGHT,
                                0, false, "right"),
                        new Bk2FrameInput(1,
                                AbstractPlayableSprite.INPUT_LEFT,
                                1, true, "left+jump+start"),
                        new Bk2FrameInput(2,
                                AbstractPlayableSprite.INPUT_DOWN,
                                1, true, "down+hold")),
                1);
    }

    private static Bk2Movie actionCarryMovie() {
        return new Bk2Movie(
                Path.of("pending-action-input.bk2"), "logkey", Map.of(),
                List.of(
                        new Bk2FrameInput(0,
                                AbstractPlayableSprite.INPUT_JUMP,
                                0x02, false, "press B"),
                        new Bk2FrameInput(1,
                                AbstractPlayableSprite.INPUT_JUMP,
                                0x02, false, "hold B")),
                1);
    }

    private static final class RecordingObserver
            implements PlaybackDebugManager.PlaybackFrameObserver {
        private final int appliedOffset;
        private final boolean skip;
        private final int vblankCount;
        private int prepareCalls;
        private int offsetCalls;
        private int skipCalls;
        private int vblankCalls;

        private RecordingObserver(int appliedOffset, boolean skip, int vblankCount) {
            this.appliedOffset = appliedOffset;
            this.skip = skip;
            this.vblankCount = vblankCount;
        }

        @Override
        public void prepareFrame(Bk2FrameInput frame) {
            prepareCalls++;
        }

        @Override
        public int appliedInputOffset(Bk2FrameInput frame) {
            offsetCalls++;
            return appliedOffset;
        }

        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            skipCalls++;
            return skip;
        }

        @Override
        public int vblankAdvanceCountOnSkippedTick(Bk2FrameInput frame) {
            vblankCalls++;
            return vblankCount;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
        }
    }
}
