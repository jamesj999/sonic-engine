package com.openggf.tools.audio.completerun;

import static com.openggf.control.InputActionMasks.ACTION_A;
import static com.openggf.control.InputActionMasks.ACTION_B;
import static com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_DOWN;
import static com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_LEFT;
import static com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_RIGHT;
import static com.openggf.sprites.playable.AbstractPlayableSprite.INPUT_UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.control.InputBindings;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TestBk2InputCursor {
    @Test
    void frameZeroUsesNeutralPredecessorAndListOrdinal() {
        Bk2InputCursor cursor = cursor(frame(700,
                INPUT_LEFT, ACTION_A, true,
                INPUT_RIGHT, ACTION_B, true));
        InputHandler input = realInputHandler();

        cursor.publish(input);

        assertEquals(0, cursor.absoluteFrame(),
                "the list ordinal, not the embedded frameIndex, owns the cursor");
        assertFalse(cursor.exhausted());
        assertEquals(INPUT_LEFT, input.logical().player1().heldMask() & INPUT_LEFT);
        assertEquals(INPUT_LEFT, input.logical().player1().pressedMask() & INPUT_LEFT);
        assertEquals(ACTION_A, input.logical().player1().actionHeldMask());
        assertEquals(ACTION_A, input.logical().player1().actionPressedMask());
        assertTrue(input.logical().player1().startHeld());
        assertTrue(input.logical().player1().startPressed());
        assertEquals(INPUT_RIGHT, input.logical().player2().heldMask() & INPUT_RIGHT);
        assertEquals(INPUT_RIGHT, input.logical().player2().pressedMask() & INPUT_RIGHT);
        assertEquals(ACTION_B, input.logical().player2().actionHeldMask());
        assertEquals(ACTION_B, input.logical().player2().actionPressedMask());
        assertTrue(input.logical().player2().startHeld());
        assertTrue(input.logical().player2().startPressed());
    }

    @Test
    void nextRowUsesImmediatePreviousRowForBothPlayers() {
        Bk2InputCursor cursor = cursor(
                frame(90, INPUT_LEFT, ACTION_A, true,
                        INPUT_RIGHT, ACTION_B, true),
                frame(11, INPUT_LEFT | INPUT_UP, ACTION_A, true,
                        INPUT_RIGHT | INPUT_DOWN, ACTION_A | ACTION_B, true));
        InputHandler input = realInputHandler();
        cursor.publish(input);
        cursor.advance();

        cursor.publish(input);

        assertEquals(1, cursor.absoluteFrame());
        assertEquals(INPUT_LEFT | INPUT_UP,
                input.logical().player1().heldMask() & (INPUT_LEFT | INPUT_UP));
        assertEquals(INPUT_UP,
                input.logical().player1().pressedMask() & (INPUT_LEFT | INPUT_UP));
        assertEquals(0, input.logical().player1().actionPressedMask());
        assertFalse(input.logical().player1().startPressed());
        assertEquals(INPUT_RIGHT | INPUT_DOWN,
                input.logical().player2().heldMask() & (INPUT_RIGHT | INPUT_DOWN));
        assertEquals(INPUT_DOWN,
                input.logical().player2().pressedMask() & (INPUT_RIGHT | INPUT_DOWN));
        assertEquals(ACTION_A, input.logical().player2().actionPressedMask());
        assertFalse(input.logical().player2().startPressed());
    }

    @Test
    void advanceBeforePublishIsRejected() {
        Bk2InputCursor cursor = cursor(frame(0, 0, 0, false, 0, 0, false));

        assertThrows(IllegalStateException.class, cursor::advance);
        assertEquals(0, cursor.absoluteFrame());
    }

    @Test
    void duplicatePublishIsRejectedWithoutAdvancing() {
        Bk2InputCursor cursor = cursor(frame(0, 0, 0, false, 0, 0, false));
        InputHandler input = realInputHandler();
        cursor.publish(input);

        assertThrows(IllegalStateException.class, () -> cursor.publish(input));
        assertEquals(0, cursor.absoluteFrame());
    }

    @Test
    void duplicateAdvanceIsRejected() {
        Bk2InputCursor cursor = cursor(
                frame(0, 0, 0, false, 0, 0, false),
                frame(1, 0, 0, false, 0, 0, false));
        cursor.publish(realInputHandler());
        cursor.advance();

        assertThrows(IllegalStateException.class, cursor::advance);
        assertEquals(1, cursor.absoluteFrame());
    }

    @Test
    void exhaustionFailsFastInsteadOfClampingToTheLastRow() {
        Bk2InputCursor cursor = cursor(frame(991, INPUT_LEFT, 0, false, 0, 0, false));
        InputHandler input = realInputHandler();
        cursor.publish(input);
        cursor.advance();

        assertTrue(cursor.exhausted());
        assertEquals(1, cursor.absoluteFrame());
        assertThrows(IllegalStateException.class, () -> cursor.publish(input));
        assertThrows(IllegalStateException.class, cursor::advance);
    }

    @Test
    void emptyMovieStartsExhausted() {
        Bk2InputCursor cursor = cursor();

        assertTrue(cursor.exhausted());
        assertEquals(0, cursor.absoluteFrame());
        assertThrows(IllegalStateException.class,
                () -> cursor.publish(realInputHandler()));
    }

    private static Bk2InputCursor cursor(Bk2FrameInput... frames) {
        return new Bk2InputCursor(new Bk2Movie(
                Path.of("movie.bk2"), "LogKey:#1", Map.of(),
                List.of(frames), 1));
    }

    private static Bk2FrameInput frame(int embeddedIndex,
            int p1Input, int p1Actions, boolean p1Start,
            int p2Input, int p2Actions, boolean p2Start) {
        return new Bk2FrameInput(embeddedIndex,
                p1Input, p1Actions, p1Start,
                p2Input, p2Actions, p2Start,
                "|row|");
    }

    private static InputHandler realInputHandler() {
        return new InputHandler(TestBk2InputCursor::bindings);
    }

    private static InputBindings bindings() {
        return new InputBindings(
                1, 2, 3, 4, 5, 6, 7, 8,
                9, 10, 11, 12, 13, 14, 15, 16,
                false, 0.35, "auto", "auto", 17, 18, 19);
    }
}
