package com.openggf.trace.replay;

import com.openggf.game.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestVisualTraceLaunchPhase {

    @Test
    void titleCardControlReleaseAdmitsReplayWhileOverlayTailRemainsActive() {
        VisualTraceLaunchPhase phase = new VisualTraceLaunchPhase();
        phase.beginTitleCardPresentation();

        assertFalse(phase.beginReplayBootstrapIfReady(GameMode.LEVEL),
                "a mode value alone is not a structural release");
        assertTrue(phase.claimTitleCardControlRelease());
        assertFalse(phase.claimTitleCardControlRelease(),
                "the release barrier is one-shot");
        assertFalse(phase.beginReplayBootstrapIfReady(GameMode.TITLE_CARD));
        assertTrue(phase.beginReplayBootstrapIfReady(GameMode.LEVEL));
        assertFalse(phase.beginReplayBootstrapIfReady(GameMode.LEVEL),
                "replay bootstrap must be admitted exactly once");
    }

    @Test
    void activeAndAbortedPhasesDoNotRetainPresentationEarlyExitState() {
        VisualTraceLaunchPhase active = new VisualTraceLaunchPhase();
        active.beginTitleCardPresentation();
        assertTrue(active.claimTitleCardControlRelease());
        assertTrue(active.beginReplayBootstrapIfReady(GameMode.LEVEL));
        active.markActive();
        assertFalse(active.isPresentingTitleCard());

        VisualTraceLaunchPhase aborted = new VisualTraceLaunchPhase();
        aborted.beginTitleCardPresentation();
        aborted.abort();
        assertFalse(aborted.ownsEarlyExit());
        assertFalse(aborted.claimTitleCardControlRelease());
        assertFalse(aborted.beginReplayBootstrapIfReady(GameMode.LEVEL));
    }
}
