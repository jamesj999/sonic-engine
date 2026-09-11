package com.openggf.tests.trace;

import com.openggf.level.objects.ObjectManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.s2.TestS2Ehz1TraceReplay;
import com.openggf.tests.trace.s3k.TestS3kAizZoneSliceTraceReplay;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFrame;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceReplayReferenceClosureGuard {
    private static final TraceFrame FRAME = TraceFrame.executionTestFrame(123, 4, 5, 0);

    @Test
    void sonic1IsOutsideTraceClosureGuardScope() {
        assertFalse(AbstractTraceReplayTest.shouldValidateRewindReferenceClosure(
                SonicGame.SONIC_1));
    }

    @Test
    void sonic2IsInsideTraceClosureGuardScope() {
        assertTrue(AbstractTraceReplayTest.shouldValidateRewindReferenceClosure(
                SonicGame.SONIC_2));
    }

    @Test
    void sonic3kIsInsideTraceClosureGuardScope() {
        assertTrue(AbstractTraceReplayTest.shouldValidateRewindReferenceClosure(
                SonicGame.SONIC_3K));
    }

    @Test
    void generalStepValidatesBeforeComparison() {
        List<String> events = new ArrayList<>();

        int input = TraceReplayFrameClosureDriver.driveGeneral(
                TraceExecutionPhase.FULL_LEVEL_FRAME,
                () -> { events.add("step"); return 7; },
                () -> { events.add("skip"); return 9; },
                () -> events.add("validate"));
        events.add("compare");

        assertEquals(List.of("step", "validate", "compare"), events);
        assertEquals(7, input);
    }

    @Test
    void s3kOrdinaryStepValidatesBeforeComparison() {
        List<String> events = new ArrayList<>();

        int input = TraceReplayFrameClosureDriver.driveS3k(
                TraceExecutionPhase.FULL_LEVEL_FRAME,
                false,
                () -> { events.add("step"); return 11; },
                () -> { events.add("previous"); return 12; },
                () -> { events.add("skip"); return 13; },
                () -> { events.add("consume"); return 14; },
                () -> events.add("animate"),
                () -> events.add("suppress"),
                () -> events.add("validate"));
        events.add("compare");

        assertEquals(List.of("step", "validate", "compare"), events);
        assertEquals(11, input);
    }

    @Test
    void s3kPreviousInputStepValidatesBeforeComparison() {
        List<String> events = new ArrayList<>();

        int input = TraceReplayFrameClosureDriver.driveS3k(
                TraceExecutionPhase.FULL_LEVEL_FRAME,
                true,
                () -> { events.add("step"); return 11; },
                () -> { events.add("previous"); return 12; },
                () -> { events.add("skip"); return 13; },
                () -> { events.add("consume"); return 14; },
                () -> events.add("animate"),
                () -> events.add("suppress"),
                () -> events.add("validate"));
        events.add("compare");

        assertEquals(List.of("previous", "validate", "compare"), events);
        assertEquals(12, input);
    }

    @Test
    void advanceOnlyConsumesInputWithoutTickingResidentLevel() {
        List<String> events = new ArrayList<>();

        int input = TraceReplayFrameClosureDriver.driveS3k(
                TraceExecutionPhase.ADVANCE_ONLY,
                true,
                () -> { events.add("step"); return 11; },
                () -> { events.add("previous"); return 12; },
                () -> { events.add("skip"); return 13; },
                () -> { events.add("consume"); return 14; },
                () -> events.add("animate"),
                () -> events.add("suppress"),
                () -> events.add("validate"));
        events.add("compare");

        assertEquals(List.of("consume", "compare"), events,
                "an input-latch row advances the BK2 cursor without dispatching "
                        + "the already-resident level or mutating timing counters");
        assertEquals(14, input);
    }

    @Test
    void vblankOnlySkipsWithoutValidation() {
        List<String> generalEvents = new ArrayList<>();
        List<String> s3kEvents = new ArrayList<>();

        int generalInput = TraceReplayFrameClosureDriver.driveGeneral(
                TraceExecutionPhase.VBLANK_ONLY,
                () -> { generalEvents.add("step"); return 7; },
                () -> { generalEvents.add("skip"); return 9; },
                () -> generalEvents.add("validate"));
        int s3kInput = TraceReplayFrameClosureDriver.driveS3k(
                TraceExecutionPhase.VBLANK_ONLY,
                true,
                () -> { s3kEvents.add("step"); return 11; },
                () -> { s3kEvents.add("previous"); return 12; },
                () -> { s3kEvents.add("skip"); return 13; },
                () -> { s3kEvents.add("consume"); return 14; },
                () -> s3kEvents.add("animate"),
                () -> s3kEvents.add("suppress"),
                () -> s3kEvents.add("validate"));

        assertEquals(List.of("skip"), generalEvents);
        assertEquals(9, generalInput);
        assertEquals(List.of("skip"), s3kEvents);
        assertEquals(13, s3kInput);
    }

    @Test
    void playableAnimationSliceSkipsThenAnimatesAndValidates() {
        List<String> events = new ArrayList<>();

        int input = TraceReplayFrameClosureDriver.driveS3k(
                TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY,
                false,
                () -> { events.add("step"); return 11; },
                () -> { events.add("previous"); return 12; },
                () -> { events.add("skip"); return 13; },
                () -> { events.add("consume"); return 14; },
                () -> events.add("animate"),
                () -> events.add("suppress"),
                () -> events.add("validate"));
        events.add("compare");

        assertEquals(List.of("skip", "animate", "validate", "compare"), events);
        assertEquals(13, input);
    }

    @Test
    void heldSidekickAnimationSuppressesBeforeFullStepAndValidation() {
        List<String> events = new ArrayList<>();

        int input = TraceReplayFrameClosureDriver.driveS3k(
                TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD,
                false,
                () -> { events.add("step"); return 11; },
                () -> { events.add("previous"); return 12; },
                () -> { events.add("skip"); return 13; },
                () -> { events.add("consume"); return 14; },
                () -> events.add("animate"),
                () -> events.add("suppress"),
                () -> events.add("validate"));
        events.add("compare");

        assertEquals(List.of("suppress", "step", "validate", "compare"), events);
        assertEquals(11, input);
    }

    @Test
    void currentLevelContextIsResolvedAgainForEveryValidation() {
        CountingObjectManager first = new CountingObjectManager(null);
        CountingObjectManager second = new CountingObjectManager(null);
        AtomicReference<TraceReplayFrameClosureDriver.CurrentLevelContext> current =
                new AtomicReference<>(
                        new TraceReplayFrameClosureDriver.CurrentLevelContext(first, 0, 0));

        TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                current::get, "s3k", "AIZ", 1, 10, FRAME,
                TraceExecutionPhase.FULL_LEVEL_FRAME);
        current.set(new TraceReplayFrameClosureDriver.CurrentLevelContext(second, 0, 1));
        TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                current::get, "s3k", "AIZ", 1, 11, FRAME,
                TraceExecutionPhase.FULL_LEVEL_FRAME);

        assertEquals(1, first.validations);
        assertEquals(1, second.validations);
    }

    @Test
    void swappedCurrentLevelContextReportsPostTransitionZoneAndAct() {
        CountingObjectManager first = new CountingObjectManager(null);
        IllegalStateException cause = new IllegalStateException("second manager missing id");
        CountingObjectManager second = new CountingObjectManager(cause);
        AtomicReference<TraceReplayFrameClosureDriver.CurrentLevelContext> current =
                new AtomicReference<>(
                        new TraceReplayFrameClosureDriver.CurrentLevelContext(first, 0, 0));

        TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                current::get, "s3k", "AIZ", 1, 10, FRAME,
                TraceExecutionPhase.FULL_LEVEL_FRAME);
        current.set(new TraceReplayFrameClosureDriver.CurrentLevelContext(second, 0, 1));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                        current::get, "s3k", "AIZ", 1, 11, FRAME,
                        TraceExecutionPhase.FULL_LEVEL_FRAME));

        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("currentZone=0"));
        assertTrue(failure.getMessage().contains("currentAct=1"));
        assertTrue(failure.getMessage().contains("traceZone=AIZ"));
        assertTrue(failure.getMessage().contains("traceAct=1"));
    }

    @Test
    void missingCurrentManagerIsSafe() {
        assertDoesNotThrow(() -> TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                () -> null, "s2", "EHZ", 0, 1, FRAME,
                TraceExecutionPhase.FULL_LEVEL_FRAME));
    }

    @Test
    void closureFailureAddsTraceContextAndPreservesCause() {
        IllegalStateException cause = new IllegalStateException("missing rewind id");
        CountingObjectManager manager = new CountingObjectManager(cause);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TraceReplayFrameClosureDriver.validateCurrentObjectManager(
                        () -> new TraceReplayFrameClosureDriver.CurrentLevelContext(
                                manager, 0, 1),
                        "s3k", "AIZ", 2, 37, FRAME,
                        TraceExecutionPhase.FULL_LEVEL_FRAME));

        assertSame(cause, failure.getCause());
        assertTrue(failure.getMessage().contains("game=s3k"));
        assertTrue(failure.getMessage().contains("currentZone=0"));
        assertTrue(failure.getMessage().contains("currentAct=1"));
        assertTrue(failure.getMessage().contains("traceZone=AIZ"));
        assertTrue(failure.getMessage().contains("traceAct=2"));
        assertTrue(failure.getMessage().contains("traceIndex=37"));
        assertTrue(failure.getMessage().contains("romFrame=123"));
        assertTrue(failure.getMessage().contains("phase=FULL_LEVEL_FRAME"));
    }

    private static final class CountingObjectManager extends ObjectManager {
        private final IllegalStateException failure;
        private int validations;

        private CountingObjectManager(IllegalStateException failure) {
            super(List.of(), null, 0, null, null, null, null, null);
            this.failure = failure;
        }

        @Override
        public void validateRewindReferenceClosure() {
            validations++;
            if (failure != null) {
                throw failure;
            }
        }
    }
}

@RequiresRom(SonicGame.SONIC_2)
class TestS2ReplayReferenceClosureIntegration extends TestS2Ehz1TraceReplay {
    private int validations;

    @Override
    protected void onRewindReferenceClosureValidated(
            int traceIndex, TraceFrame frame, TraceExecutionPhase phase) {
        validations++;
    }

    @Test
    @Override
    public void replayMatchesTrace() throws Exception {
        super.replayMatchesTrace();
        assertTrue(validations > 0, "general replay loop must validate rewind closure");
    }
}

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kReplayReferenceClosureIntegration extends TestS3kAizZoneSliceTraceReplay {
    private int validations;

    @Override
    protected void onRewindReferenceClosureValidated(
            int traceIndex, TraceFrame frame, TraceExecutionPhase phase) {
        validations++;
    }

    @Test
    @Override
    public void replayMatchesTrace() throws Exception {
        super.replayMatchesTrace();
        assertTrue(validations > 0, "S3K replay loop must validate rewind closure");
    }
}
