package com.openggf.game;

import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests for GameStateManager. No ROM or OpenGL dependencies.
 */
public class TestGameStateManager {

    private GameStateManager gsm;

    @BeforeAll
    static void configureEngineServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
        gsm = GameServices.gameState();
        // Reset to defaults before each test
        gsm.configureSpecialStageProgress(7, 7);
        gsm.resetSession();
    }

    @AfterEach
    public void tearDown() {
        // Restore singleton to clean defaults so other tests are not affected
        gsm.configureSpecialStageProgress(7, 7);
        gsm.resetSession();
        SessionManager.clear();
    }

    @Test
    public void testInitialState() {
        assertEquals(3, gsm.getLives(), "Initial lives should be 3");
        assertEquals(0, gsm.getContinues(), "Initial continues should be 0");
        assertEquals(0, gsm.getScore(), "Initial score should be 0");
        assertEquals(0, gsm.getEmeraldCount(), "Initial emerald count should be 0");
    }

    @Test
    public void testMarkEmeraldCollected() {
        gsm.markEmeraldCollected(0);
        assertEquals(1, gsm.getEmeraldCount(), "Emerald count should be 1 after collecting one");
        assertTrue(gsm.hasEmerald(0), "Should have emerald 0");
        assertFalse(gsm.hasEmerald(1), "Should not have emerald 1");
        assertEquals(java.util.List.of(0), gsm.getCollectedChaosEmeraldIndices(),
                "Chaos emerald identities should be persisted");
    }

    @Test
    public void testMarkEmeraldCollectedDuplicate() {
        gsm.markEmeraldCollected(2);
        assertEquals(1, gsm.getEmeraldCount());

        gsm.markEmeraldCollected(2);
        assertEquals(1, gsm.getEmeraldCount(), "Collecting same emerald twice should not increment count");
    }

    @Test
    public void testMarkEmeraldCollectedOutOfBounds() {
        int countBefore = gsm.getEmeraldCount();

        // Negative index should not crash or change count
        gsm.markEmeraldCollected(-1);
        assertEquals(countBefore, gsm.getEmeraldCount(), "Negative index should not change count");

        // Overflow index should not crash or change count
        gsm.markEmeraldCollected(100);
        assertEquals(countBefore, gsm.getEmeraldCount(), "Overflow index should not change count");

        // Boundary index (equal to array length) should not crash
        gsm.markEmeraldCollected(7);
        assertEquals(countBefore, gsm.getEmeraldCount(), "Boundary index should not change count");
    }

    @Test
    public void testHasAllEmeralds() {
        assertFalse(gsm.hasAllEmeralds(), "Should not have all emeralds initially");

        for (int i = 0; i < 7; i++) {
            gsm.markEmeraldCollected(i);
        }

        assertEquals(7, gsm.getEmeraldCount(), "Should have 7 emeralds");
        assertTrue(gsm.hasAllEmeralds(), "Should have all emeralds");
    }

    @Test
    public void testConfigureSpecialStageProgress() {
        gsm.configureSpecialStageProgress(8, 6);

        assertEquals(8, gsm.getSpecialStageCount(), "Stage count should be 8");
        assertEquals(6, gsm.getChaosEmeraldCount(), "Emerald target should be 6");
        assertEquals(0, gsm.getEmeraldCount(), "Emerald count should reset to 0");

        // Verify emerald array resized - collecting index 5 should work, index 6 should not
        gsm.markEmeraldCollected(5);
        assertEquals(1, gsm.getEmeraldCount());
        gsm.markEmeraldCollected(6);
        assertEquals(1, gsm.getEmeraldCount(), "Index 6 should be out of bounds for 6-emerald config");

        // All 6 emeralds collected = hasAllEmeralds
        for (int i = 0; i < 6; i++) {
            gsm.markEmeraldCollected(i);
        }
        assertTrue(gsm.hasAllEmeralds(), "6 of 6 emeralds = all");
    }

    @Test
    public void testSessionReset() {
        gsm.addScore(5000);
        gsm.addLife();
        gsm.addContinue();
        gsm.markEmeraldCollected(0);
        gsm.markEmeraldCollected(3);
        gsm.markSuperEmeraldCollected(2);

        gsm.resetSession();

        assertEquals(0, gsm.getScore(), "Score should reset to 0");
        assertEquals(3, gsm.getLives(), "Lives should reset to 3");
        assertEquals(0, gsm.getContinues(), "Continues should reset to 0");
        assertEquals(0, gsm.getEmeraldCount(), "Emerald count should reset to 0");
        assertFalse(gsm.hasEmerald(0), "Emerald 0 should be cleared");
        assertFalse(gsm.hasEmerald(3), "Emerald 3 should be cleared");
        assertTrue(gsm.getCollectedSuperEmeraldIndices().isEmpty(), "Super emeralds should be cleared");
    }

    @Test
    public void testSuperEmeraldsTrackSpecificIndices() {
        gsm.markSuperEmeraldCollected(4);
        gsm.markSuperEmeraldCollected(1);
        gsm.markSuperEmeraldCollected(4);

        assertEquals(java.util.List.of(1, 4), gsm.getCollectedSuperEmeraldIndices(),
                "Super emerald identities should be unique and sorted");
        assertTrue(gsm.hasSuperEmerald(1), "Should have super emerald 1");
        assertFalse(gsm.hasSuperEmerald(0), "Should not have super emerald 0");
    }

    @Test
    public void testAddLife() {
        assertEquals(3, gsm.getLives());
        gsm.addLife();
        assertEquals(4, gsm.getLives(), "Lives should be 4 after addLife");
    }

    @Test
    public void testAddContinue() {
        assertEquals(0, gsm.getContinues());
        gsm.addContinue();
        assertEquals(1, gsm.getContinues(), "Continues should increment");
    }

    @Test
    public void testLoseLife() {
        assertEquals(3, gsm.getLives());
        gsm.loseLife();
        assertEquals(2, gsm.getLives(), "Lives should be 2 after loseLife");

        // Lose all lives
        gsm.loseLife();
        gsm.loseLife();
        assertEquals(0, gsm.getLives(), "Lives should be 0");

        // Should not go below 0
        gsm.loseLife();
        assertEquals(0, gsm.getLives(), "Lives should not go below 0");
    }

    @Test
    public void testAddScore() {
        gsm.addScore(100);
        assertEquals(100, gsm.getScore(), "Score should be 100");

        gsm.addScore(250);
        assertEquals(350, gsm.getScore(), "Score should accumulate to 350");

        // Negative amount should not change score
        gsm.addScore(-50);
        assertEquals(350, gsm.getScore(), "Negative score should be ignored");
    }

    @Test
    public void testRestoreSaveProgressRestoresLivesContinuesAndEmeraldIdentities() {
        gsm.restoreSaveProgress(7, 4, java.util.List.of(0, 2, 6), java.util.List.of(2, 6));

        assertEquals(7, gsm.getLives(), "Lives should restore from save payload");
        assertEquals(4, gsm.getContinues(), "Continues should restore from save payload");
        assertEquals(3, gsm.getEmeraldCount(), "Emerald count should reflect restored chaos emerald identities");
        assertEquals(java.util.List.of(0, 2, 6), gsm.getCollectedChaosEmeraldIndices(),
                "Chaos emerald identities should restore from save payload");
        assertEquals(java.util.List.of(2, 6), gsm.getCollectedSuperEmeraldIndices(),
                "Super emerald identities should restore from save payload");
    }

    @Test
    public void explicitConvertedSaveStateSupportsZeroSuperEmeralds() {
        gsm.restoreSaveProgress(3, 0,
                java.util.List.of(0, 1, 2, 3, 4, 5, 6), java.util.List.of(), true);

        assertTrue(gsm.isEmeraldsConverted());
        assertTrue(gsm.getCollectedSuperEmeraldIndices().isEmpty());
    }

    @Test
    public void legacySaveInfersConversionFromSuperEmeralds() {
        gsm.restoreSaveProgress(3, 0,
                java.util.List.of(0, 1, 2, 3, 4, 5, 6), java.util.List.of(2), null);

        assertTrue(gsm.isEmeraldsConverted());
    }

    @Test
    public void resetSessionClearsEmeraldConversion() {
        gsm.setEmeraldsConverted(true);

        gsm.resetSession();

        assertFalse(gsm.isEmeraldsConverted());
    }

    @Test
    public void collectingFirstSuperEmeraldMarksChaosEmeraldsConverted() {
        gsm.markEmeraldCollected(0);

        gsm.markSuperEmeraldCollected(0);

        assertTrue(gsm.isEmeraldsConverted());
    }

    @Test
    public void testS3kSpecialStageSelectionSkipsCollectedChaosEmeraldStages() {
        gsm.markEmeraldCollected(0);
        gsm.markEmeraldCollected(1);
        gsm.markEmeraldCollected(2);

        assertEquals(3, gsm.consumeCurrentSpecialStageIndexAndAdvanceSkippingCollected(false),
                "S3K should skip already-collected chaos emerald stages");
        assertEquals(4, gsm.getCurrentSpecialStageIndex(),
                "Next S3K special stage should advance after the selected uncollected stage");
    }

    @Test
    public void testS3kSpecialStageSelectionWrapsToNextUncollectedChaosStage() {
        for (int i = 0; i < 5; i++) {
            assertEquals(i, gsm.consumeCurrentSpecialStageIndexAndAdvance(),
                    "Precondition: generic stage rotation should advance to index 5");
        }
        gsm.markEmeraldCollected(5);
        gsm.markEmeraldCollected(6);
        gsm.markEmeraldCollected(0);
        gsm.markEmeraldCollected(1);

        assertEquals(2, gsm.consumeCurrentSpecialStageIndexAndAdvanceSkippingCollected(false),
                "S3K should wrap around to the next uncollected chaos emerald stage");
        assertEquals(3, gsm.getCurrentSpecialStageIndex(),
                "Next S3K special stage should advance after the wrapped selection");
    }

    @Test
    public void testS3kSpecialStageSelectionSkipsCollectedSuperEmeraldStages() {
        for (int i = 0; i < 7; i++) {
            gsm.markEmeraldCollected(i);
        }
        gsm.markSuperEmeraldCollected(0);
        gsm.markSuperEmeraldCollected(1);
        gsm.markSuperEmeraldCollected(2);

        assertEquals(3, gsm.consumeCurrentSpecialStageIndexAndAdvanceSkippingCollected(true),
                "S3K super stage selection should skip already-collected super emerald stages");
        assertEquals(4, gsm.getCurrentSpecialStageIndex(),
                "Next S3K super special stage should advance after the selected uncollected stage");
    }
}
