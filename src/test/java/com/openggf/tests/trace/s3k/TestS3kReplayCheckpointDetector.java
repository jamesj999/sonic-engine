package com.openggf.tests.trace.s3k;

import com.openggf.trace.TraceEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kReplayCheckpointDetector {

    @Test
    void introBeginEmitsAtReplayFrameZero() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        TraceEvent.Checkpoint hit = detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));

        assertNotNull(hit);
        assertEquals("intro_begin", hit.name());
        assertNull(hit.actualZoneId());
    }

    @Test
    void requiredCheckpointsEmitOnceInFixedOrder() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        assertEquals("intro_begin", detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false)).name());
        assertEquals("gameplay_start", detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true)).name());
        assertEquals("aiz1_fire_transition_begin", detector.observe(probe(
                1651, 0, 0, 0, 12, 0, false, true, true, false, false, true, false)).name());
        assertEquals("aiz2_reload_resume", detector.observe(probe(
                5610, 0, 1, 0, 12, 5, true, false, false, false, true, false)).name());

        assertTrue(detector.requiredCheckpointNamesReached().contains("aiz1_fire_transition_begin"));
    }

    @Test
    void optionalCheckpointsAreReportedButNotRequired() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));
        detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true));
        detector.observe(probe(
                260, 0, 1, 0, 12, 0, false, false, false, false, true, false));
        detector.observe(probe(
                320, 0, 1, 1, 12, 0, false, true, false, false, true, false));

        TraceEvent.Checkpoint hit = detector.observe(probe(
                900, 0, 1, 1, 12, 0, false, false, true, false, true, false));

        assertNotNull(hit);
        assertEquals("aiz2_signpost_begin", hit.name());
        assertFalse(detector.requiredCheckpointNamesReached().contains("aiz2_signpost_begin"));
    }

    @Test
    void requiredCheckpointWinsWhenOptionalAndRequiredPredicatesOverlap() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));
        detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true));

        TraceEvent.Checkpoint hit = detector.observe(probe(
                260, 0, 1, 0, 12, 0, false, true, true, false, true, false));

        assertNotNull(hit);
        assertEquals("aiz2_reload_resume", hit.name());
    }

    @Test
    void hczHandoffCompleteRequiresZoneActAndMoveLockClear() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();
        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));
        detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true));
        detector.observe(probe(
                260, 0, 1, 0, 12, 10, true, false, false, false, true, false));
        detector.observe(probe(
                320, 0, 1, 0, 12, 0, false, false, false, false, true, false));

        assertNull(detector.observe(probe(
                1700, 1, 0, 0, 12, 5, false, false, false, false, true, false)));
        TraceEvent.Checkpoint hit = detector.observe(probe(
                1701, 1, 0, 0, 12, 0, false, false, false, false, true, false));

        assertNotNull(hit);
        assertEquals("hcz_handoff_complete", hit.name());
    }

    @Test
    void gameplayStartBeginsWhenInLevelTitleCardOverlayAppears() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));

        assertNull(detector.observe(probe(
                1499, 0, 0, 0, 12, 0, false, false, false, false, true, false)));
        assertEquals("gameplay_start", detector.observe(probe(
                1500, 0, 0, 0, 12, 0, false, false, false, false, true, true)).name());
    }

    @Test
    void gameplayStartWaitsForIntroObjectControlAndHiddenStateToClear() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));

        assertNull(detector.observe(probeWithIntroState(
                1190, 0, 0, 0, 12, 0, false, false, false, true, false, true, false)));
        assertNull(detector.observe(probeWithIntroState(
                1191, 0, 0, 0, 12, 0, false, false, false, false, true, true, false)));
        assertEquals("gameplay_start", detector.observe(probeWithIntroState(
                1500, 0, 0, 0, 12, 0, false, false, false, false, false, true, true)).name());
    }

    @Test
    void fireTransitionBeginIsRequiredCheckpoint() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));
        detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true));

        TraceEvent.Checkpoint hit = detector.observe(probe(
                1651, 0, 0, 0, 12, 0, false, true, true, false, false, true, false));

        assertNotNull(hit);
        assertEquals("aiz1_fire_transition_begin", hit.name());
        assertTrue(detector.requiredCheckpointNamesReached().contains("aiz1_fire_transition_begin"));
    }

    @Test
    void fireTransitionCheckpointIgnoresIntroRefreshEventsFg5Pulse() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));
        detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true));

        assertNull(detector.observe(probe(
                1651, 0, 0, 0, 12, 0, false, true, false, false, false, true, false)));
        assertFalse(detector.requiredCheckpointNamesReached().contains("aiz1_fire_transition_begin"));
    }

    @Test
    void fireTransitionCheckpointTracksActiveFireTransitionEventsFg5Pulse() {
        S3kReplayCheckpointDetector detector = new S3kReplayCheckpointDetector();

        detector.observe(probe(
                0, null, null, null, 12, 0, false, false, false, false, false, false));
        detector.observe(probe(
                10, 0, 0, 0, 12, 0, false, false, false, false, true, true));

        TraceEvent.Checkpoint hit = detector.observe(probe(
                1651, 0, 0, 0, 12, 0, false, true, true, false, false, true, false));

        assertNotNull(hit);
        assertEquals("aiz1_fire_transition_begin", hit.name());
    }

    private static S3kCheckpointProbe probe(int replayFrame,
                                            Integer actualZoneId,
                                            Integer actualAct,
                                            Integer apparentAct,
                                            Integer gameMode,
                                            int moveLock,
                                            boolean ctrlLocked,
                                            boolean eventsFg5,
                                            boolean signpostActive,
                                            boolean resultsActive,
                                            boolean levelStarted,
                                            boolean titleCardOverlayActive) {
        return new S3kCheckpointProbe(
                replayFrame,
                actualZoneId,
                actualAct,
                apparentAct,
                gameMode,
                moveLock,
                ctrlLocked,
                false,
                false,
                eventsFg5,
                false,
                false,
                signpostActive,
                resultsActive,
                levelStarted,
                titleCardOverlayActive);
    }

    private static S3kCheckpointProbe probe(int replayFrame,
                                            Integer actualZoneId,
                                            Integer actualAct,
                                            Integer apparentAct,
                                            Integer gameMode,
                                            int moveLock,
                                            boolean ctrlLocked,
                                            boolean eventsFg5,
                                            boolean fireTransitionActive,
                                            boolean signpostActive,
                                            boolean resultsActive,
                                            boolean levelStarted,
                                            boolean titleCardOverlayActive) {
        return new S3kCheckpointProbe(
                replayFrame,
                actualZoneId,
                actualAct,
                apparentAct,
                gameMode,
                moveLock,
                ctrlLocked,
                false,
                false,
                eventsFg5,
                fireTransitionActive,
                false,
                signpostActive,
                resultsActive,
                levelStarted,
                titleCardOverlayActive);
    }

    private static S3kCheckpointProbe probeWithIntroState(int replayFrame,
                                                          Integer actualZoneId,
                                                          Integer actualAct,
                                                          Integer apparentAct,
                                                          Integer gameMode,
                                                          int moveLock,
                                                          boolean ctrlLocked,
                                                          boolean eventsFg5,
                                                          boolean fireTransitionActive,
                                                          boolean objectControlled,
                                                          boolean hidden,
                                                          boolean levelStarted,
                                                          boolean titleCardOverlayActive) {
        return new S3kCheckpointProbe(
                replayFrame,
                actualZoneId,
                actualAct,
                apparentAct,
                gameMode,
                moveLock,
                ctrlLocked,
                objectControlled,
                hidden,
                eventsFg5,
                fireTransitionActive,
                false,
                false,
                false,
                levelStarted,
                titleCardOverlayActive);
    }
}
