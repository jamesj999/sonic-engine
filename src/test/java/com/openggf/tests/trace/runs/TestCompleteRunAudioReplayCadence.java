package com.openggf.tests.trace.runs;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCompleteRunAudioReplayCadence {

    @Test
    void consumesSegmentsGapLagAndTerminalTailAsOneContiguousEpoch()
            throws Exception {
        SyntheticDriver driver = SyntheticDriver.compactRun();
        RecordingObserver observer = new RecordingObserver(driver.events);

        VisualRunReplayHarness.CompleteAudioCadenceResult result =
                VisualRunReplayHarness.driveCompleteAudioCadence(
                        new VisualRunReplayHarness.CompleteAudioStop(2, 12, 2),
                        observer, driver);

        assertEquals(12, result.exclusiveCursor());
        assertEquals(10, result.semanticRows());
        assertEquals(2, result.bootstrapPresentations());
        assertEquals(12, result.outerPresentations());
        assertEquals(12, result.audioUpdates());
        assertEquals(List.of(2, 3, 4, 5, 6, 7, 8, 9, 10, 11),
                observer.semantic.stream()
                        .map(VisualRunReplayHarness.FrameView::consumedBk2Cursor)
                        .toList());
        assertEquals(List.of(0, 0, -1, -1, -1, 1, 1, 1, -1, -1),
                observer.semantic.stream()
                        .map(VisualRunReplayHarness.FrameView::segmentIndex)
                        .toList());
        assertNull(observer.semantic.get(2).segment());
        assertNull(observer.semantic.get(8).segment());
        assertNull(observer.semantic.get(9).segment(),
                "the normal completion includes the terminal-tail row");
        assertTrue(observer.semantic.get(6).lag(), "row 8 is the recorded lag row");
        assertTrue(observer.semantic.stream()
                .allMatch(VisualRunReplayHarness.FrameView::semanticRow));
        assertEquals(2, observer.diagnostic.size());
        assertTrue(observer.diagnostic.stream()
                .noneMatch(VisualRunReplayHarness.FrameView::semanticRow));
        assertEquals(2, observer.baseline.consumedBk2Cursor());

        int baseline = driver.events.indexOf("baseline:2");
        assertTrue(baseline >= 0);
        assertEquals("step:2", driver.events.get(baseline + 1),
                "baseline must be the callback immediately before epoch input");
        assertTrue(driver.events.get(baseline + 2).startsWith("present:"));
        assertTrue(driver.events.get(baseline + 3).startsWith("audio:"));
    }

    @Test
    void intervalBudgetHasNoLegacySixtyThousandRowCap() throws Exception {
        SyntheticDriver driver = new SyntheticDriver(
                810, 434_417, List.of(), 0);

        VisualRunReplayHarness.CompleteAudioCadenceResult result =
                VisualRunReplayHarness.driveCompleteAudioCadence(
                        new VisualRunReplayHarness.CompleteAudioStop(
                                810, 434_417, 512),
                        VisualRunReplayHarness.FrameObserver.NONE, driver);

        assertEquals(433_607, result.semanticRows());
        assertEquals(434_417, result.exclusiveCursor());
        assertEquals(0, result.bootstrapPresentations());
    }

    @Test
    void rejectsFastForwardPauseAbortAndPrematureMovieEnd() {
        SyntheticDriver fastForward = SyntheticDriver.compactRun();
        fastForward.rateDisplay = "< 2x >";
        assertFailureContains(fastForward, "fast-forward");

        SyntheticDriver paused = SyntheticDriver.compactRun();
        paused.pauseAtRow = 4;
        assertFailureContains(paused, "paused");

        SyntheticDriver aborted = SyntheticDriver.compactRun();
        aborted.abortAtRow = 4;
        assertFailureContains(aborted, "trace replay aborted");

        SyntheticDriver premature = SyntheticDriver.compactRun();
        premature.stopPlaybackAtRow = 4;
        assertFailureContains(premature, "premature movie end");
    }

    @Test
    void rejectsCursorJumpAndExtraOrMissingOuterAudioWork() {
        SyntheticDriver jump = SyntheticDriver.compactRun();
        jump.jumpAtRow = 4;
        assertFailureContains(jump, "cursor jump");

        SyntheticDriver extraPresentation = SyntheticDriver.compactRun();
        extraPresentation.presentationDelta = 2;
        assertFailureContains(extraPresentation,
                "exactly one outer presentation");

        SyntheticDriver missingUpdate = SyntheticDriver.compactRun();
        missingUpdate.audioUpdateDelta = 0;
        assertFailureContains(missingUpdate, "exactly one audio update");
    }

    @Test
    void rejectsUnboundedBootstrapWrongMovieEndAndIncompleteCoordinator() {
        SyntheticDriver longBootstrap = new SyntheticDriver(
                2, 12, SyntheticDriver.COMPACT_SEGMENTS, 3);
        assertFailureContains(longBootstrap, "bootstrap allowance");

        SyntheticDriver wrongMovieEnd = SyntheticDriver.compactRun();
        wrongMovieEnd.movieFrameCount = 11;
        assertFailureContains(wrongMovieEnd, "exclusive end");

        SyntheticDriver incomplete = SyntheticDriver.compactRun();
        incomplete.completeAtMovieEnd = false;
        assertFailureContains(incomplete, "coordinator");
    }

    @Test
    void rejectsCoordinatorCompletionBeforeTheFinalRow() {
        for (int earlyRow : List.of(8, 4, 10)) {
            SyntheticDriver driver = SyntheticDriver.compactRun();
            driver.completeAtRow = earlyRow;

            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> VisualRunReplayHarness.driveCompleteAudioCadence(
                            new VisualRunReplayHarness.CompleteAudioStop(
                                    2, 12, 2),
                            VisualRunReplayHarness.FrameObserver.NONE, driver),
                    "early completion at row " + earlyRow);

            assertTrue(failure.getMessage().contains(
                    "coordinator completed before final row"),
                    failure.getMessage());
        }
    }

    @Test
    void observesCompletionAfterFinalPresentationButReturnsExclusiveCursor()
            throws Exception {
        SyntheticDriver driver = SyntheticDriver.compactRun();

        VisualRunReplayHarness.CompleteAudioCadenceResult result =
                VisualRunReplayHarness.driveCompleteAudioCadence(
                        new VisualRunReplayHarness.CompleteAudioStop(2, 12, 2),
                        VisualRunReplayHarness.FrameObserver.NONE, driver);

        assertEquals(11, driver.cursor(),
                "production cursor remains pinned to the final valid row");
        assertEquals(12, result.exclusiveCursor(),
                "capture result exposes the semantic exclusive cursor");
        int completion = driver.events.indexOf("complete:11");
        assertTrue(completion >= 0);
        assertTrue(driver.events.get(completion + 1).startsWith("present:"));
        assertTrue(driver.events.get(completion + 2).startsWith("audio:"));
    }

    @Test
    void rejectsRawCursorAdvancingToExclusiveEnd() {
        SyntheticDriver driver = SyntheticDriver.compactRun();
        driver.rawExclusiveAtMovieEnd = true;

        assertFailureContains(driver, "raw terminal cursor");
    }

    private static void assertFailureContains(
            SyntheticDriver driver, String expected) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> VisualRunReplayHarness.driveCompleteAudioCadence(
                        new VisualRunReplayHarness.CompleteAudioStop(2, 12, 2),
                        VisualRunReplayHarness.FrameObserver.NONE, driver));
        assertTrue(failure.getMessage().contains(expected), failure.getMessage());
    }

    private static final class RecordingObserver
            implements VisualRunReplayHarness.FrameObserver {
        private final List<String> events;
        private final List<VisualRunReplayHarness.FrameView> semantic =
                new ArrayList<>();
        private final List<VisualRunReplayHarness.FrameView> diagnostic =
                new ArrayList<>();
        private VisualRunReplayHarness.FrameView baseline;

        private RecordingObserver(List<String> events) {
            this.events = events;
        }

        @Override
        public void beforeFirstEpochRow(VisualRunReplayHarness.FrameView frame) {
            baseline = frame;
            events.add("baseline:" + frame.consumedBk2Cursor());
        }

        @Override
        public void afterOuterFrame(VisualRunReplayHarness.FrameView frame) {
            (frame.semanticRow() ? semantic : diagnostic).add(frame);
        }
    }

    private static final class SyntheticDriver
            implements VisualRunReplayHarness.CompleteAudioDriver {
        private static final List<Segment> COMPACT_SEGMENTS = List.of(
                new Segment(0, 2, 4, -1),
                new Segment(1, 7, 10, 8));

        private final int firstFrame;
        private final int exclusiveEnd;
        private final List<Segment> segments;
        private final int bootstrapSteps;
        private final List<String> events = new ArrayList<>();
        private int cursor;
        private int steps;
        private int movieFrameCount;
        private boolean playing;
        private boolean paused;
        private boolean complete;
        private String abortDiagnostic;
        private String rateDisplay = "< 1x >";
        private int pauseAtRow = -1;
        private int abortAtRow = -1;
        private int stopPlaybackAtRow = -1;
        private int jumpAtRow = -1;
        private int completeAtRow = -1;
        private int presentationDelta = 1;
        private int audioUpdateDelta = 1;
        private boolean completeAtMovieEnd = true;
        private boolean rawExclusiveAtMovieEnd;
        private long presentations;
        private long audioUpdates;

        private SyntheticDriver(int firstFrame, int exclusiveEnd,
                                List<Segment> segments, int bootstrapSteps) {
            this.firstFrame = firstFrame;
            this.exclusiveEnd = exclusiveEnd;
            this.segments = List.copyOf(segments);
            this.bootstrapSteps = bootstrapSteps;
            this.movieFrameCount = exclusiveEnd;
            this.cursor = bootstrapSteps == 0 ? firstFrame : 0;
            this.playing = bootstrapSteps == 0;
        }

        private static SyntheticDriver compactRun() {
            return new SyntheticDriver(2, 12, COMPACT_SEGMENTS, 2);
        }

        @Override
        public int cursor() {
            return cursor;
        }

        @Override
        public int movieFrameCount() {
            return movieFrameCount;
        }

        @Override
        public boolean playbackPlaying() {
            return playing;
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public String playbackRateDisplay() {
            return rateDisplay;
        }

        @Override
        public String abortDiagnostic() {
            return abortDiagnostic;
        }

        @Override
        public boolean coordinatorComplete() {
            return complete;
        }

        @Override
        public long outerPresentationCount() {
            return presentations;
        }

        @Override
        public long audioUpdateCount() {
            return audioUpdates;
        }

        @Override
        public void step() {
            events.add("step:" + cursor);
            if (steps < bootstrapSteps) {
                steps++;
                if (steps == bootstrapSteps) {
                    cursor = firstFrame;
                    playing = true;
                }
                return;
            }
            int consumed = cursor;
            if (consumed == pauseAtRow) {
                paused = true;
            }
            if (consumed == abortAtRow) {
                abortDiagnostic = "synthetic mismatch";
            }
            if (consumed == completeAtRow) {
                complete = true;
            }
            if (consumed == jumpAtRow) {
                cursor += 2;
                return;
            }
            if (consumed == exclusiveEnd - 1) {
                events.add("complete:" + consumed);
                playing = false;
                complete = completeAtMovieEnd;
                if (rawExclusiveAtMovieEnd) {
                    cursor = exclusiveEnd;
                }
            } else {
                cursor++;
                if (consumed == stopPlaybackAtRow) {
                    playing = false;
                }
            }
        }

        @Override
        public void presentOuterFrame() {
            events.add("present:" + cursorForEvent());
            presentations += presentationDelta;
        }

        @Override
        public void updateAudio() {
            events.add("audio:" + cursorForEvent());
            audioUpdates += audioUpdateDelta;
        }

        private int cursorForEvent() {
            if (cursor == firstFrame || steps <= bootstrapSteps) {
                return cursor;
            }
            return Math.max(firstFrame, cursor - 1);
        }

        @Override
        public VisualRunReplayHarness.FrameView frameView(
                int consumedCursor, int loopStep, boolean semanticRow) {
            Segment segment = segments.stream()
                    .filter(candidate -> consumedCursor >= candidate.start
                            && consumedCursor < candidate.end)
                    .findFirst().orElse(null);
            VisualRunReplayHarness.SegmentCoordinate coordinate = segment == null
                    ? null
                    : new VisualRunReplayHarness.SegmentCoordinate(
                            segment.index, consumedCursor - segment.start);
            boolean lag = segment != null && consumedCursor == segment.lagRow;
            return new VisualRunReplayHarness.FrameView(
                    consumedCursor, coordinate, loopStep, lag, true, semanticRow);
        }

        private record Segment(int index, int start, int end, int lagRow) {
        }
    }
}
