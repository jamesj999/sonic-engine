package com.openggf.tests.trace.runs;

import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.RunBoundarySignal;
import com.openggf.trace.replay.runs.RunPlaybackObservation;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestTraceRunPlaybackTranscriptParity {

    @Test
    void headlessAndVisualAdaptersReceiveTheSameStructuralTranscript() {
        TraceRunManifest run = run();

        List<TraceRunPlaybackCoordinator.Action> headless =
                drive(new HeadlessPolicyAdapter(run));
        List<TraceRunPlaybackCoordinator.Action> visual =
                drive(new VisualPolicyAdapter(run));

        assertEquals(headless, visual);
        assertEquals(List.of(
                "AdmitDestination", "CloseSegment", "EnterTransitionGap",
                "AdmitDestination", "CloseSegment", "CompleteRun"),
                headless.stream().map(action -> action.getClass().getSimpleName()).toList());
    }

    private static List<TraceRunPlaybackCoordinator.Action> drive(PolicyAdapter adapter) {
        List<TraceRunPlaybackCoordinator.Action> transcript = new ArrayList<>();
        transcript.addAll(adapter.activate(level(false)));
        adapter.boundary(new RunBoundarySignal.BonusRequest(10, BonusStageType.GUMBALL));
        transcript.addAll(adapter.publish(level(true)));
        transcript.addAll(adapter.admit(bonus(false)));
        transcript.addAll(adapter.publish(bonus(true)));
        return transcript;
    }

    private interface PolicyAdapter {
        List<TraceRunPlaybackCoordinator.Action> activate(RunPlaybackObservation observation);

        void boundary(RunBoundarySignal signal);

        List<TraceRunPlaybackCoordinator.Action> publish(RunPlaybackObservation observation);

        List<TraceRunPlaybackCoordinator.Action> admit(RunPlaybackObservation observation);
    }

    private abstract static class CoordinatorAdapter implements PolicyAdapter {
        final TraceRunPlaybackCoordinator coordinator;

        CoordinatorAdapter(TraceRunManifest run) {
            coordinator = new TraceRunPlaybackCoordinator(
                    run, TracePlaybackProfile.DISABLED, 30);
        }

        @Override
        public List<TraceRunPlaybackCoordinator.Action> activate(
                RunPlaybackObservation observation) {
            return coordinator.activateInitialLevel(observation);
        }

        @Override
        public List<TraceRunPlaybackCoordinator.Action> publish(
                RunPlaybackObservation observation) {
            return coordinator.afterProduction(observation);
        }

        @Override
        public List<TraceRunPlaybackCoordinator.Action> admit(
                RunPlaybackObservation observation) {
            return coordinator.beforeAdmission(observation);
        }
    }

    private static final class HeadlessPolicyAdapter extends CoordinatorAdapter {
        HeadlessPolicyAdapter(TraceRunManifest run) {
            super(run);
        }

        @Override
        public void boundary(RunBoundarySignal signal) {
            coordinator.observeBoundary(signal);
        }
    }

    private static final class VisualPolicyAdapter extends CoordinatorAdapter {
        VisualPolicyAdapter(TraceRunManifest run) {
            super(run);
        }

        @Override
        public void boundary(RunBoundarySignal signal) {
            // The visual adapter receives the same semantic event from a
            // GameLoop seam instead of a headless stepping loop.
            coordinator.observeBoundary(signal);
        }
    }

    private static TraceRunManifest run() {
        var level = new TraceRunManifest.Segment(
                "level", "level", "complete_run", 0, 10,
                0, 1, null, null);
        var bonus = new TraceRunManifest.Segment(
                "bonus", "bonus_stage", "s3k_bonus_stage", 10, 10,
                19, 1, null, "gumball");
        var transition = new TraceRunManifest.Transition(
                0, 1, "starpost_bonus", 10,
                null, null, null, null, null, null, null, null);
        return new TraceRunManifest("s3k", "run", "movie.bk2",
                "checksum", List.of(level, bonus),
                List.of(transition));
    }

    private static RunPlaybackObservation level(boolean exhausted) {
        return new RunPlaybackObservation(GameMode.LEVEL, 0, exhausted ? 2 : 0,
                new RunPlaybackObservation.LevelIdentity(1, 0, 0, 0),
                false, null, null, false, exhausted, 0, false, 4, 5);
    }

    private static RunPlaybackObservation bonus(boolean exhausted) {
        return new RunPlaybackObservation(GameMode.BONUS_STAGE, 10,
                exhausted ? 4 : 3, null, false,
                new RunPlaybackObservation.BonusIdentity(
                        19, 0, BonusStageType.GUMBALL),
                null, false, exhausted, 0, false, 6, 7);
    }
}
