package com.openggf.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLevelTransitionCoordinator {

    @Test
    void inLevelTitleCardCarriesDisplayResetIntentAndDispatchOffset() {
        LevelTransitionCoordinator transitions = new LevelTransitionCoordinator();

        transitions.requestInLevelTitleCard(1, 1, true, 7, 3, true, 5, 2);

        assertTrue(transitions.consumeInLevelTitleCardRequest());
        assertTrue(transitions.consumeInLevelTitleCardLevelGamestateResetRequest());
        assertEquals(7, transitions.consumeInLevelTitleCardResetAdditionalDispatches());
        assertEquals(3, transitions.consumeInLevelTitleCardResetPhaseOneDispatchOverlap());
        assertTrue(transitions.consumeInLevelTitleCardPlayerControlLockRequest());
        assertEquals(5, transitions.consumeInLevelTitleCardExitAdditionalDispatches());
        assertEquals(2, transitions.consumeInLevelTitleCardExitPhaseOneDispatchOverlap());
        assertFalse(transitions.consumeInLevelTitleCardRequest());
        assertFalse(transitions.consumeInLevelTitleCardLevelGamestateResetRequest());
        assertEquals(0, transitions.consumeInLevelTitleCardResetAdditionalDispatches());
        assertEquals(0, transitions.consumeInLevelTitleCardResetPhaseOneDispatchOverlap());
        assertFalse(transitions.consumeInLevelTitleCardPlayerControlLockRequest());
        assertEquals(0, transitions.consumeInLevelTitleCardExitAdditionalDispatches());
        assertEquals(0, transitions.consumeInLevelTitleCardExitPhaseOneDispatchOverlap());
    }

    @Test
    void zoneActRequestRetainsItsPostLoadMusicIntent() {
        LevelTransitionCoordinator transitions = new LevelTransitionCoordinator();

        transitions.requestZoneAndAct(2, 0, true, 0x05);

        assertTrue(transitions.consumeZoneActRequest());
        assertEquals(2, transitions.getRequestedZone());
        assertEquals(0, transitions.getRequestedAct());
        assertEquals(0x05, transitions.getRequestedMusicId());
    }
}
