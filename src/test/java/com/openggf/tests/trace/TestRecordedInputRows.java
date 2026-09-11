package com.openggf.tests.trace;

import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRecordedInputRows {

    @Test
    void snapshotAtAppliesBaseOffsetToSelectBothPlayersPhysicalRow() {
        LogicalInputSnapshot snapshot = new RecordedInputRows(movie(), 1).snapshotAt(1);

        assertEquals(0x14, snapshot.player1().heldMask());
        assertEquals(0x02, snapshot.player1().actionHeldMask());
        assertTrue(snapshot.player1().startHeld());
        assertEquals(0x11, snapshot.player2().heldMask());
        assertEquals(0x04, snapshot.player2().actionHeldMask());
        assertFalse(snapshot.player2().startHeld());
    }

    @Test
    void snapshotAtUsesPhysicalPredecessorWhenCallerSkippedALocalRow() {
        LogicalInputSnapshot snapshot = new RecordedInputRows(movie(), 1).snapshotAt(2);

        assertEquals(0x12, snapshot.player1().pressedMask());
        assertEquals(0x01, snapshot.player1().actionPressedMask());
        assertFalse(snapshot.player1().startPressed());
        assertEquals(0x18, snapshot.player2().pressedMask());
        assertEquals(0x02, snapshot.player2().actionPressedMask());
        assertTrue(snapshot.player2().startPressed());
    }

    @Test
    void snapshotAtPhysicalRowZeroUsesNeutralPredecessorForJustPressedMasks() {
        LogicalInputSnapshot snapshot = new RecordedInputRows(movie(), 0).snapshotAt(0);

        assertEquals(0x11, snapshot.player1().pressedMask());
        assertEquals(0x01, snapshot.player1().actionPressedMask());
        assertTrue(snapshot.player1().startPressed());
        assertEquals(0x12, snapshot.player2().pressedMask());
        assertEquals(0x02, snapshot.player2().actionPressedMask());
        assertTrue(snapshot.player2().startPressed());
    }

    @Test
    void snapshotAtRejectsNegativeAndExhaustedAbsoluteRows() {
        assertThrows(IndexOutOfBoundsException.class,
                () -> new RecordedInputRows(movie(), -1).snapshotAt(0));
        assertThrows(IndexOutOfBoundsException.class,
                () -> new RecordedInputRows(movie(), 4).snapshotAt(0));
    }

    @Test
    void withLogicalOverrideMakesTheRecordedSnapshotVisibleOnlyInsideCallback() {
        RecordedInputRows rows = new RecordedInputRows(movie(), 1);
        InputHandler input = new InputHandler();

        rows.withLogicalOverride(1, input, () -> {
            assertEquals(0x14, input.logical().player1().heldMask());
            assertEquals(0x02, input.logical().player1().actionHeldMask());
            assertTrue(input.logical().player1().startHeld());
            assertEquals(0x11, input.logical().player2().heldMask());
            assertEquals(0x04, input.logical().player2().actionHeldMask());
        });

        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void withLogicalOverrideClearsOverrideWhenCallbackThrows() {
        RecordedInputRows rows = new RecordedInputRows(movie(), 0);
        InputHandler input = new InputHandler();

        assertThrows(IllegalStateException.class,
                () -> rows.withLogicalOverride(0, input, () -> {
                    throw new IllegalStateException("callback failure");
                }));

        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void withLogicalOverrideRejectsNestedOverrideWithoutClearingIt() {
        RecordedInputRows rows = new RecordedInputRows(movie(), 0);
        InputHandler input = new InputHandler();
        input.setLogicalOverride(LogicalInputSnapshot.neutral());

        assertThrows(IllegalStateException.class, () -> rows.withLogicalOverride(0, input, () -> { }));

        assertTrue(input.hasLogicalOverride());
        input.clearLogicalOverride();
    }

    @Test
    void withLogicalOverrideRejectsInvalidRowBeforeInstallingOverride() {
        InputHandler input = new InputHandler();

        assertThrows(IndexOutOfBoundsException.class,
                () -> new RecordedInputRows(movie(), -1).withLogicalOverride(0, input, () -> { }));
        assertThrows(IndexOutOfBoundsException.class,
                () -> new RecordedInputRows(movie(), 4).withLogicalOverride(0, input, () -> { }));

        assertFalse(input.hasLogicalOverride());
    }

    private static Bk2Movie movie() {
        return new Bk2Movie(Path.of("recorded-input-rows.bk2"), "", Map.of(), List.of(
                new Bk2FrameInput(0, 0x01, 0x01, true, 0x02, 0x02, true, "row 0"),
                new Bk2FrameInput(1, 0x08, 0x04, false, 0x04, 0x01, false, "row 1"),
                new Bk2FrameInput(2, 0x04, 0x02, true, 0x01, 0x04, false, "row 2"),
                new Bk2FrameInput(3, 0x02, 0x01, true, 0x08, 0x02, true, "row 3")), 0);
    }
}
