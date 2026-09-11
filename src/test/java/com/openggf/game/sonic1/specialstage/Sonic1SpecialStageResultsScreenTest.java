package com.openggf.game.sonic1.specialstage;

import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@FullReset
@ExtendWith(SingletonResetExtension.class)
public class Sonic1SpecialStageResultsScreenTest {

    private GameStateManager gameState;

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        gameState = GameServices.gameState();
        gameState.configureSpecialStageProgress(6, 6);
        gameState.resetSession();
    }

    @AfterEach
    public void tearDown() {
        gameState.resetSession();
        SessionManager.clear();
    }

    @Test
    public void testFailedScenarioUsesSpecialStageMessage() {
        Sonic1SpecialStageResultsScreen screen =
                new Sonic1SpecialStageResultsScreen(0, false, 0, 0);

        assertEquals(11, screen.getScenarioFrameForTesting(), "Failed run should show SPECIAL STAGE message frame");
    }

    @Test
    public void testGotEmeraldScenarioUsesChaosEmeraldsMessage() {
        Sonic1SpecialStageResultsScreen screen =
                new Sonic1SpecialStageResultsScreen(12, true, 1, 3);

        assertEquals(10, screen.getScenarioFrameForTesting(), "Emerald run should show CHAOS EMERALDS message frame");
    }

    @Test
    public void testGotAllEmeraldsScenarioUsesGotThemAllMessage() {
        Sonic1SpecialStageResultsScreen screen =
                new Sonic1SpecialStageResultsScreen(20, true, 5, 6);

        assertEquals(12, screen.getScenarioFrameForTesting(), "All-emerald run should show SONIC GOT THEM ALL frame");
    }

    @Test
    public void testRingBonusTalliesIntoScoreAndCompletes() {
        Sonic1SpecialStageResultsScreen screen =
                new Sonic1SpecialStageResultsScreen(5, true, 0, 1);

        int startScore = gameState.getScore();

        int frame = 0;
        while (!screen.isComplete() && frame < 1000) {
            frame++;
            screen.update(frame, null);
        }

        assertTrue(screen.isComplete(), "Results flow should complete within expected frame budget");
        assertEquals(0, screen.getRingBonus(), "Ring bonus should fully count down");
        assertEquals(startScore + 50, gameState.getScore(), "Score should increase by ring bonus total");
    }
}

