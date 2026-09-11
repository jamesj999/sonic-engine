package com.openggf.tests;

import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(SingletonResetExtension.class)
class TestSingletonResetExtension {
    @Test
    void perTestResetClearsGameState() {
        GameStateManager gs = GameServices.gameState();
        assertEquals(0, gs.getScore(), "Score should be 0 after per-test reset");
    }

    @FullReset
    @Test
    void fullResetClearsEverything() {
        GameStateManager gs = GameServices.gameState();
        assertEquals(0, gs.getScore(), "Score should be 0 after full reset");
    }
}


