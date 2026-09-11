package com.openggf;

import com.openggf.game.*;
import com.openggf.level.LevelManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestContinueExitLifecycle {
    @Test void timeoutLeavesAllProgressUntouched() {
        var screen = mock(ContinueScreenProvider.class);
        var state = new GameStateManager();
        state.addContinue();
        var level = mock(LevelManager.class);
        var save = mock(Runnable.class);
        assertFalse(GameLoopGameOverExit.acceptContinue(screen, state, level, save));
        assertEquals(1, state.getContinues());
        verifyNoInteractions(level, save);
    }

    @Test void sonic1And2ClearCheckpointBeforeReload() {
        var screen = mock(ContinueScreenProvider.class);
        when(screen.isAccepted()).thenReturn(true);
        when(screen.clearsCheckpointOnContinue()).thenReturn(true);
        var state = new GameStateManager();
        state.addContinue();
        var level = mock(LevelManager.class);
        var checkpoint = new CheckpointState();
        checkpoint.saveCheckpoint(3, 900, 400, true);
        var transientState = new LevelGamestate();
        transientState.setRings(99);
        transientState.setTimerFrames(12345);
        when(level.getCheckpointState()).thenReturn(checkpoint);
        when(level.getLevelGamestate()).thenReturn(transientState);
        var save = mock(Runnable.class);
        assertTrue(GameLoopGameOverExit.acceptContinue(screen, state, level, save));
        assertFalse(checkpoint.isActive());
        assertEquals(0, transientState.getRings());
        assertEquals(0, transientState.getTimerFrames());
        assertEquals(3, state.getLives());
        assertEquals(0, state.getContinues());
        verifyNoInteractions(save);
    }

    @Test void sonic3kRetainsCheckpointAndSavesSpentContinueBeforeLoad() {
        var screen = mock(ContinueScreenProvider.class);
        when(screen.isAccepted()).thenReturn(true);
        when(screen.savesOnContinue()).thenReturn(true);
        var state = new GameStateManager();
        state.addContinue();
        state.addContinue();
        var level = mock(LevelManager.class);
        var checkpoint = new CheckpointState();
        checkpoint.saveCheckpoint(3, 900, 400, true);
        when(level.getCheckpointState()).thenReturn(checkpoint);
        when(level.getLevelGamestate()).thenReturn(new LevelGamestate());
        assertTrue(GameLoopGameOverExit.acceptContinue(screen, state, level, () -> {
            assertEquals(3, state.getLives());
            assertEquals(1, state.getContinues());
            assertTrue(checkpoint.isActive());
        }));
        assertTrue(checkpoint.isActive());
        assertEquals(900, checkpoint.getSavedX());
    }
}
