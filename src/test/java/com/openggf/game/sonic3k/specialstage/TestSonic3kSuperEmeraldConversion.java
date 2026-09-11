package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.GameStateManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kSuperEmeraldConversion {
    private GameStateManager gameState;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        gameState = new GameStateManager();
        for (int i = 0; i < 7; i++) {
            gameState.markEmeraldCollected(i);
        }
    }

    @Test
    void enteringFirstSuperEmeraldStageMarksConversionBeforeCollection() {
        assertTrue(Sonic3kSpecialStageManager.resolveSuperEmeraldMode(gameState));
        assertTrue(gameState.isEmeraldsConverted());
    }
}
