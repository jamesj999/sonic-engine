package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestCheckpointStateRewind {
    @Test
    void rewindStateRestoresEveryCheckpointField() {
        CheckpointState state = new CheckpointState();
        CheckpointState.RewindState snapshot = new CheckpointState.RewindState(
                3,
                5,
                0x1200,
                0x0340,
                0x1100,
                0x0200,
                true,
                true,
                0x0300,
                4,
                true,
                0x0480,
                7,
                true,
                (byte) 0x0A,
                (byte) 0x0B,
                true,
                12345L,
                true);

        state.restoreRewindState(snapshot);

        assertTrue(state.isActive());
        assertEquals(3, state.getLastCheckpointIndex());
        assertEquals(5, state.getStarPostActivationMark());
        assertEquals(0x1200, state.getSavedX());
        assertEquals(0x0340, state.getSavedY());
        assertEquals(0x1100, state.getSavedCameraX());
        assertEquals(0x0200, state.getSavedCameraY());
        assertTrue(state.hasCameraLock());
        assertTrue(state.isUsedForSpecialStage());
        assertTrue(state.hasWaterState());
        assertEquals(0x0300, state.getSavedWaterLevel());
        assertEquals(4, state.getSavedWaterRoutine());
        assertTrue(state.hasS3kRuntimeState());
        assertEquals(0x0480, state.getSavedCameraMaxY());
        assertEquals(7, state.getSavedDynamicResizeRoutine());
        assertTrue(state.hasSolidBits());
        assertEquals((byte) 0x0A, state.getSavedTopSolidBit());
        assertEquals((byte) 0x0B, state.getSavedLrbSolidBit());
        assertTrue(state.hasSavedTimer());
        assertEquals(12345L, state.getSavedTimerFrames());
    }

    @Test
    void rewindStateCanRestoreInactiveSnapshotAfterMutation() {
        CheckpointState state = new CheckpointState();
        CheckpointState.RewindState inactive = state.captureRewindState();

        state.restoreRewindState(new CheckpointState.RewindState(
                2,
                2,
                100,
                200,
                300,
                400,
                true,
                true,
                500,
                6,
                true,
                700,
                8,
                true,
                (byte) 1,
                (byte) 2,
                true,
                999L,
                true));
        state.restoreRewindState(inactive);

        assertFalse(state.isActive());
        assertEquals(-1, state.getLastCheckpointIndex());
        assertEquals(-1, state.getStarPostActivationMark());
        assertEquals(0, state.getSavedX());
        assertEquals(0, state.getSavedY());
        assertFalse(state.hasCameraLock());
        assertFalse(state.isUsedForSpecialStage());
        assertFalse(state.hasWaterState());
        assertFalse(state.hasS3kRuntimeState());
        assertFalse(state.hasSolidBits());
        assertEquals((byte) 0x0C, state.getSavedTopSolidBit());
        assertEquals((byte) 0x0D, state.getSavedLrbSolidBit());
        assertFalse(state.hasSavedTimer());
        assertEquals(0L, state.getSavedTimerFrames());
    }
}
