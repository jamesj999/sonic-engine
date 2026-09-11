package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameStateManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that end-of-level flags are properly reset between act transitions.
 *
 * <p>Root cause of the "fast results" bug: AIZ1 results set endOfLevelFlag=true,
 * the fire transition reloads the level as AIZ2 via executeActTransition() but
 * never cleared the flag. When the AIZ2 egg capsule later checked the flag, it
 * was already true, causing the post-boss cutscene to fire during the tally.
 */
class TestEndOfLevelFlagReset {
    private GameStateManager gameState;

    @BeforeEach
    void setUp() {
        gameState = new GameStateManager();
    }

    @Test
    void resetForLevel_clearsEndOfLevelFlag() {
        gameState.setEndOfLevelFlag(true);
        assertTrue(gameState.isEndOfLevelFlag());

        gameState.resetForLevel();
        assertFalse(gameState.isEndOfLevelFlag(),
                "endOfLevelFlag must be cleared between act transitions");
    }

    @Test
    void resetForLevel_clearsEndOfLevelActive() {
        gameState.setEndOfLevelActive(true);
        assertTrue(gameState.isEndOfLevelActive());

        gameState.resetForLevel();
        assertFalse(gameState.isEndOfLevelActive(),
                "endOfLevelActive must be cleared between act transitions");
    }

    @Test
    void resetForLevel_preservesScore() {
        gameState.addScore(1000);
        gameState.setEndOfLevelFlag(true);

        gameState.resetForLevel();

        assertEquals(1000, gameState.getScore(),
                "Score must persist across act transitions");
    }

    /**
     * Simulates the stale flag scenario:
     * 1. AIZ1 results complete → endOfLevelFlag = true
     * 2. resetForLevel() called during seamless transition
     * 3. AIZ2 egg capsule starts results → endOfLevelActive = true
     * 4. Egg capsule checks endOfLevelFlag → must be false
     */
    @Test
    void staleFlagScenario_resetPreventsEarlyRelease() {
        // Step 1: AIZ1 results screen onExitReady()
        gameState.setEndOfLevelActive(false);
        gameState.setEndOfLevelFlag(true);

        // Step 2: Seamless transition calls resetForLevel()
        gameState.resetForLevel();

        // Step 3: AIZ2 egg capsule startResults()
        gameState.setEndOfLevelActive(true);

        // Step 4: Egg capsule checks — flag must NOT be stale
        assertFalse(gameState.isEndOfLevelFlag(),
                "endOfLevelFlag must be false after seamless transition reset; " +
                "stale flag would cause AIZ2 post-boss cutscene to fire during tally");
    }
}
