package com.openggf.tests.trace.runs;

import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;
import com.openggf.trace.replay.runs.TraceRunFrameDriver.Disposition;
import com.openggf.trace.replay.runs.TraceRunFrameDriver.Step;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.SegmentExecutionPolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunFrameDriver {

    @Test
    void productionRowOrdersHardwareBeforeLifecycleAndAdvancesBeforeComparison() {
        RecordingHooks hooks = new RecordingHooks();
        TraceRunFrameDriver driver = new TraceRunFrameDriver();

        driver.execute(new Step(Disposition.PRESENTATION_VBLANK, 8705, false), hooks);

        assertEquals(List.of(
                "prepare-row", "prepare-hardware", "capture-before",
                "production", "advance", "capture-after", "compare", "after-step"),
                hooks.events);
    }

    @Test
    void specialStageEntryCanRetainPhysicalRowUntilDestinationAdmission() {
        RecordingHooks hooks = new RecordingHooks() {
            private boolean specialStageEntered;

            @Override
            public void runProductionLifecycle(Step step) {
                super.runProductionLifecycle(step);
                specialStageEntered = true;
            }

            @Override
            public boolean shouldAdvancePhysicalRow(Step step) {
                return !specialStageEntered;
            }
        };

        new TraceRunFrameDriver().execute(
                new Step(Disposition.SHARED_GAP, 4976, false), hooks);

        assertEquals(List.of(
                "prepare-row", "prepare-hardware", "capture-before",
                "production", "capture-after", "compare", "after-step"),
                hooks.events);
    }

    @Test
    void advanceOnlyBypassesWholeOuterProductionLifecycleButStillAdvancesAndCompares() {
        RecordingHooks hooks = new RecordingHooks();
        TraceRunFrameDriver driver = new TraceRunFrameDriver();

        driver.execute(new Step(
                Disposition.PRESENTATION_ADVANCE_ONLY, 8706, false), hooks);

        assertEquals(List.of(
                "prepare-row", "prepare-hardware", "capture-before",
                "advance", "capture-after", "compare", "after-step"),
                hooks.events);
    }

    @Test
    void offsetHandoffCannotConsumeAHostStep() {
        assertThrows(IllegalStateException.class,
                () -> new TraceRunFrameDriver().execute(
                        new Step(Disposition.OFFSET_HANDOFF, 9741, false),
                        new RecordingHooks()));
    }

    @Test
    void currentStepExposesOnlyStructuralBoundaryDeferral() {
        TraceRunFrameDriver driver = new TraceRunFrameDriver();
        RecordingHooks hooks = new RecordingHooks() {
            @Override
            public void runProductionLifecycle(Step step) {
                assertTrue(driver.defersBoundaryCommitAfterCurrentRow());
                super.runProductionLifecycle(step);
            }
        };

        driver.execute(new Step(
                Disposition.PRESENTATION_VBLANK, 59, false, true), hooks);

        assertFalse(driver.defersBoundaryCommitAfterCurrentRow());
    }

    @Test
    void boundaryCommitWaitsUntilAfterANonAdvancingVblankSpan() {
        assertTrue(TraceRunFrameDriver.shouldDeferBoundaryCommit(true, false));
        assertTrue(TraceRunFrameDriver.shouldDeferBoundaryCommit(false, false));
        assertTrue(TraceRunFrameDriver.shouldDeferBoundaryCommit(false, true));
        assertFalse(TraceRunFrameDriver.shouldDeferBoundaryCommit(true, true));
    }

    @Test
    void deferredBoundaryResumesOnFirstVblankAfterTheSpan() {
        assertTrue(TraceRunFrameDriver
                .shouldCommitDeferredBoundaryAfterClosure(false, true));
        assertFalse(TraceRunFrameDriver
                .shouldCommitDeferredBoundaryAfterClosure(true, true));
        assertFalse(TraceRunFrameDriver
                .shouldCommitDeferredBoundaryAfterClosure(false, false));
    }

    @Test
    void dispositionSelectionIsPolicyAndCoordinatorPhaseDriven() {
        assertEquals(Disposition.GAMEPLAY_SHARED,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.GAMEPLAY,
                        TraceExecutionPhase.FULL_LEVEL_FRAME));
        assertEquals(Disposition.SPECIAL_LOCAL,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.SPECIAL_LOCAL,
                        TraceExecutionPhase.VBLANK_ONLY));
        assertEquals(Disposition.PRESENTATION_VBLANK,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                        TraceExecutionPhase.VBLANK_ONLY));
        assertEquals(Disposition.PRESENTATION_SUPPRESSED_CLOSURE,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                        TraceExecutionPhase.VBLANK_ONLY,
                        false));
        assertEquals(Disposition.PRESENTATION_SUPPRESSED_CLOSURE,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                        TraceExecutionPhase.VBLANK_ONLY,
                        true,
                        true,
                        true));
        assertEquals(Disposition.PRESENTATION_SUPPRESSED_CLOSURE,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                        TraceExecutionPhase.VBLANK_ONLY,
                        true,
                        false));
        assertEquals(Disposition.PRESENTATION_VBLANK,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                        TraceExecutionPhase.FULL_LEVEL_FRAME));
        assertEquals(Disposition.PRESENTATION_ADVANCE_ONLY,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                        SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE,
                        TraceExecutionPhase.ADVANCE_ONLY));
        assertEquals(Disposition.SHARED_GAP,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.TRANSITION_GAP,
                        SegmentExecutionPolicy.GAMEPLAY,
                        TraceExecutionPhase.FULL_LEVEL_FRAME));
        assertEquals(Disposition.TERMINAL_TAIL,
                TraceRunFrameDriver.selectDisposition(
                        TraceRunPlaybackCoordinator.Phase.TERMINAL_TAIL,
                        SegmentExecutionPolicy.GAMEPLAY,
                        TraceExecutionPhase.FULL_LEVEL_FRAME));
    }

    private static class RecordingHooks
            implements TraceRunFrameDriver.Hooks<String> {
        private final List<String> events = new ArrayList<>();

        @Override
        public void preparePhysicalRow(Step step) {
            events.add("prepare-row");
        }

        @Override
        public void prepareHardwareTiming(Step step) {
            events.add("prepare-hardware");
        }

        @Override
        public String captureBefore(Step step) {
            events.add("capture-before");
            return "before";
        }

        @Override
        public void runProductionLifecycle(Step step) {
            events.add("production");
        }

        @Override
        public boolean shouldAdvancePhysicalRow(Step step) {
            return true;
        }

        @Override
        public void advancePhysicalRow(Step step) {
            events.add("advance");
        }

        @Override
        public String captureAfter(Step step) {
            events.add("capture-after");
            return "after";
        }

        @Override
        public void compare(Step step, String before, String after) {
            assertEquals("before", before);
            assertEquals("after", after);
            events.add("compare");
        }

        @Override
        public void afterStep(Step step) {
            events.add("after-step");
        }
    }
}
