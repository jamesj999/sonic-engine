package com.openggf.game.continuescreen;

import com.openggf.game.GameStateManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestContinueProgress {
    @Test void acceptanceSpendsOneContinueAndResetsLivesScoreButPreservesEmeralds() {
        GameStateManager state = new GameStateManager();
        state.restoreSaveProgress(0, 2, java.util.List.of(1, 3), java.util.List.of());
        state.addScore(12300);
        while (state.getLives() > 0) state.loseLife();
        var before = state.capture();
        assertTrue(state.consumeContinue());
        assertEquals(3, state.getLives());
        assertEquals(1, state.getContinues());
        assertEquals(0, state.getScore());
        assertEquals(2, state.getEmeraldCount());
        assertArrayEquals(before.gotEmeralds(), state.capture().gotEmeralds());
    }

    @Test void noContinueCannotResetProgress() {
        GameStateManager state = new GameStateManager();
        state.addScore(90);
        while (state.getLives() > 0) state.loseLife();
        assertFalse(state.consumeContinue());
        assertEquals(0, state.getLives());
        assertEquals(90, state.getScore());
    }
}
