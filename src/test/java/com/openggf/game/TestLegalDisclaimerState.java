package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLegalDisclaimerState {

    @Test
    void initialStateIsFadeIn() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        assertEquals(LegalDisclaimerState.Phase.FADE_IN, state.getPhase());
        assertFalse(state.isDismissed());
        assertFalse(state.isDismissPromptVisible());
    }

    @Test
    void onFadeInCompleteAdvancesToReadingAndResetsFrameCounter() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        for (int i = 0; i < 50; i++) {
            state.tick(false);
        }
        state.onFadeInComplete();
        assertEquals(LegalDisclaimerState.Phase.READING, state.getPhase());
        assertEquals(0, state.getReadingFrameCounter());
        assertFalse(state.isDismissPromptVisible());
    }

    @Test
    void readingIgnoresKeyPressesForFullGate() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES - 1; i++) {
            state.tick(true);
            assertEquals(LegalDisclaimerState.Phase.READING, state.getPhase());
            assertFalse(state.isDismissed());
        }
    }

    @Test
    void readingAdvancesToDismissibleAtFrame300() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        assertEquals(LegalDisclaimerState.Phase.DISMISSIBLE, state.getPhase());
        assertTrue(state.isDismissPromptVisible());
    }

    @Test
    void dismissibleAdvancesToExitingOnKeyPress() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(true);
        assertEquals(LegalDisclaimerState.Phase.EXITING, state.getPhase());
        assertFalse(state.isDismissed());
    }

    @Test
    void exitingIgnoresFurtherKeyPresses() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(true);
        LegalDisclaimerState.Phase phaseBefore = state.getPhase();
        state.tick(true);
        state.tick(true);
        assertEquals(phaseBefore, state.getPhase());
    }

    @Test
    void onFadeOutCompleteSetsDismissed() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(true);
        state.onFadeOutComplete();
        assertTrue(state.isDismissed());
    }

    @Test
    void fadeInIgnoresKeyPresses() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.tick(true);
        state.tick(true);
        assertEquals(LegalDisclaimerState.Phase.FADE_IN, state.getPhase());
    }

    @Test
    void readingFrameCounterAdvancesEachTick() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        state.tick(false);
        state.tick(false);
        state.tick(false);
        assertEquals(3, state.getReadingFrameCounter());
    }

    @Test
    void dismissibleFrameCounterStartsAtZeroOnTransition() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        assertEquals(LegalDisclaimerState.Phase.DISMISSIBLE, state.getPhase());
        assertEquals(0, state.getDismissibleFrameCounter());
    }

    @Test
    void dismissibleFrameCounterAdvancesEachTickInDismissible() {
        LegalDisclaimerState state = new LegalDisclaimerState();
        state.onFadeInComplete();
        for (int i = 0; i < LegalDisclaimerState.READING_FRAMES; i++) {
            state.tick(false);
        }
        state.tick(false);
        state.tick(false);
        state.tick(false);
        assertEquals(3, state.getDismissibleFrameCounter());
    }
}
