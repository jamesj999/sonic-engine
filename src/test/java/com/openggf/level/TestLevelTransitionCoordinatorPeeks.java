package com.openggf.level;

import com.openggf.game.BonusStageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestLevelTransitionCoordinatorPeeks {

    @Test
    void bonusPeekDoesNotConsume() {
        LevelTransitionCoordinator c = new LevelTransitionCoordinator();
        assertNull(c.peekBonusStageRequest());
        c.requestBonusStageEntry(BonusStageType.GUMBALL);
        assertEquals(BonusStageType.GUMBALL, c.peekBonusStageRequest());
        assertEquals(BonusStageType.GUMBALL, c.peekBonusStageRequest()); // still pending
        assertEquals(BonusStageType.GUMBALL, c.consumeBonusStageRequest()); // consumer still works
        assertNull(c.peekBonusStageRequest()); // cleared by consume, not by peek
    }

    @Test
    void specialStagePeekDoesNotConsume() {
        LevelTransitionCoordinator c = new LevelTransitionCoordinator();
        assertFalse(c.isSpecialStageRequested());
        c.requestSpecialStageEntry();
        assertTrue(c.isSpecialStageRequested());
        assertTrue(c.isSpecialStageRequested()); // still pending
        assertTrue(c.consumeSpecialStageRequest());
        assertFalse(c.isSpecialStageRequested());
    }

    /**
     * ROM {@code Got_Wait} spends the expiry frame advancing {@code obRoutine};
     * {@code Got_NextLevel}'s game-mode write runs on the next one.
     */
    @Test
    void resultsCardEntryRoutineIsServicedByTheFollowingFrame() {
        LevelTransitionCoordinator c = new LevelTransitionCoordinator();
        c.advanceToSpecialStageEntryRoutine();
        assertTrue(c.isSpecialStageRequested(), "the advance is observable immediately");
        assertFalse(c.consumeSpecialStageRequest(), "Got_NextLevel has not run yet");
        assertTrue(c.isSpecialStageRequested());
        assertTrue(c.consumeSpecialStageRequest(), "Got_NextLevel runs one frame later");
        assertFalse(c.isSpecialStageRequested());
        assertFalse(c.consumeSpecialStageRequest());
    }

    /** The zone/act write belongs to the same frame as Got_NextLevel's mode change. */
    @Test
    void resultsCardEntryCarriesItsLevelAdvanceToTheRoutineFrame() {
        LevelTransitionCoordinator c = new LevelTransitionCoordinator();
        c.advanceToSpecialStageEntryRoutine();
        assertFalse(c.consumeSpecialStageEntryLevelAdvance(),
                "the arming frame must not advance the level");
        assertFalse(c.consumeSpecialStageRequest());
        assertTrue(c.consumeSpecialStageRequest());
        assertTrue(c.consumeSpecialStageEntryLevelAdvance());
        assertFalse(c.consumeSpecialStageEntryLevelAdvance(), "one-shot");
    }

    /** An immediate entry (S2 star post, S3K flash) never advances the level. */
    @Test
    void immediateEntryDoesNotAdvanceTheLevel() {
        LevelTransitionCoordinator c = new LevelTransitionCoordinator();
        c.requestSpecialStageEntry();
        assertTrue(c.consumeSpecialStageRequest());
        assertFalse(c.consumeSpecialStageEntryLevelAdvance());
    }

    @Test
    void resetStateClearsAnArmedEntryRoutine() {
        LevelTransitionCoordinator c = new LevelTransitionCoordinator();
        c.advanceToSpecialStageEntryRoutine();
        c.resetState();
        assertFalse(c.isSpecialStageRequested());
        assertFalse(c.consumeSpecialStageRequest());
    }
}
