package com.openggf.tests.trace.runs;

import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.TraceSessionLauncher;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.GameServices;
import com.openggf.game.GameMode;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.TraceData;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.testmode.TraceLaunchStatus;
import com.openggf.tests.HeadlessTestFixture;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Drives a whole multi-segment trace run through the SAME owners the windowed
 * Trace Test Mode uses -- {@link TraceSessionLauncher}'s run branch, its
 * {@code TraceRunFrameDriver} hooks, and its structural row comparator -- with
 * no GLFW window and no human at the keyboard.
 * <p>
 * This exists because {@link AbstractRunChainTest} is NOT the visual path. The
 * chain test builds its own coordinator/driver loop beside the launcher and
 * calls a different {@code completePostProduction} overload, so a visual-only
 * defect (an unpublished dynamic-art row, a mode change on the wrong physical
 * row, a transition that softlocks) can be green there and still abort a real
 * session. Anything reproduced here is reproduced in production code.
 * <p>
 * The launch skips only the master-title screen: {@code TraceSessionLauncher
 * .launch} needs a {@code MasterTitleScreen} to fade through, which needs a
 * window. Everything after the game-bootstrap callback -- which is where all
 * the replay ownership lives -- is the production path, entered through
 * {@code finishRunReplayLaunch} exactly as the windowed launch enters it once
 * its fade completes.
 */
public final class VisualRunReplayHarness {

    /** Why the driven session stopped. */
    public enum Outcome {
        /** The coordinator reached {@code COMPLETE}. */
        COMPLETED,
        /** The step budget ran out with the run still mid-flight. */
        BUDGET_EXHAUSTED,
        /** A requested target segment was admitted; the run was stopped there. */
        REACHED_SEGMENT,
        /**
         * The BK2 session stopped while the coordinator still had segments to
         * walk. Stepping on would spin without advancing anything, so this is
         * reported rather than burned through -- it is what a softlock looks
         * like from outside the engine.
         */
        PLAYBACK_STALLED
    }

    public record Result(Outcome outcome, int steps, int sharedCursor,
                         TraceRunPlaybackCoordinator.Phase phase,
                         int currentSegmentIndex, List<String> timeline) {
    }

    /**
     * Read-only outer-frame observation for diagnostic captures. Coordinates
     * always identify the consumed BK2 row when {@link #semanticRow()} is
     * true; bootstrap/title-card presentations remain diagnostic-only.
     */
    /** Coordinate within a compared manifest segment; absent for gap/tail rows. */
    public record SegmentCoordinate(int segmentIndex, int segmentRow) {
        public SegmentCoordinate {
            if (segmentIndex < 0 || segmentRow < 0) {
                throw new IllegalArgumentException(
                        "segment coordinates must be non-negative");
            }
        }
    }

    public record FrameView(int consumedBk2Cursor, SegmentCoordinate segment,
                            int loopStep, boolean lag, boolean gameplay,
                            boolean semanticRow) {
        /** Compatibility accessor for focused segment-only capture clients. */
        public int segmentIndex() {
            return segment == null ? -1 : segment.segmentIndex();
        }

        /** Compatibility accessor for focused segment-only capture clients. */
        public int segmentRow() {
            return segment == null ? -1 : segment.segmentRow();
        }
    }

    /**
     * Opt-in diagnostic callback. It may install append-only audio observers
     * at the bootstrap seam but receives no gameplay or replay mutation owner.
     */
    public interface FrameObserver {
        FrameObserver NONE = new FrameObserver() { };

        /** Installs diagnostic observers before title-card/audio bootstrap. */
        default void beforeReplayBootstrap() { }

        default void beforeFirstSegmentRow(FrameView frame) { }

        /**
         * Complete-run baseline seam, immediately before the epoch's first
         * input is consumed. The legacy segment callback remains the default
         * so focused captures keep their existing observer implementation.
         */
        default void beforeFirstEpochRow(FrameView frame) {
            beforeFirstSegmentRow(frame);
        }

        default void afterOuterFrame(FrameView frame) { }
    }

    @FunctionalInterface
    interface RunSessionConstructor {
        TraceSessionLauncher create(
                TraceEntry entry, Bk2Movie movie,
                List<TraceRunSegmentDescriptor> segments,
                ActiveSegmentPayload activePayload) throws Exception;
    }

    /** Injectable lifecycle boundary used to prove ownership on either side. */
    interface RunBootstrap {
        GameLoop beforeTransfer(
                TraceEntry entry, TraceData firstTrace,
                FrameObserver observer) throws Exception;

        void afterTransfer(
                TraceSessionLauncher session, GameLoop loop) throws Exception;
    }

    /**
     * Test-only cleanup seam. The production closer preserves the active
     * payload's idempotent, no-throw lease close.
     */
    @FunctionalInterface
    interface VisualPayloadCloser {
        void close(ActiveSegmentPayload payload);
    }

    private static final RunSessionConstructor PRODUCTION_SESSION_CONSTRUCTOR =
            VisualRunReplayHarness::newRunSession;
    private static final VisualPayloadCloser PRODUCTION_PAYLOAD_CLOSER =
            ActiveSegmentPayload::close;
    private static final RunBootstrap PRODUCTION_BOOTSTRAP = new RunBootstrap() {
        @Override
        public GameLoop beforeTransfer(
                TraceEntry entry, TraceData firstTrace,
                FrameObserver observer) throws Exception {
            GraphicsManager.getInstance().resetState();
            GraphicsManager.getInstance().initHeadless();
            TraceLaunchStatus.clear();
            TraceReplaySessionBootstrap.prepareConfiguration(
                    firstTrace, firstTrace.metadata());
            HeadlessTestFixture.builder()
                    .withZoneAndAct(entry.zone(), entry.act())
                    .withHardwareReadinessAdmissionPolicy(
                            HardwareReadinessAdmissionPolicy.LIVE)
                    .build();
            observer.beforeReplayBootstrap();
            GameLoop loop = new GameLoop(new InputHandler());
            installCurrentGameLoop(loop);
            return loop;
        }

        @Override
        public void afterTransfer(
                TraceSessionLauncher session, GameLoop loop) throws Exception {
            setActiveSession(session);
            finishRunLaunch(session);
        }
    };

    /** Exact half-open complete-audio epoch and diagnostic bootstrap budget. */
    public record CompleteAudioStop(int firstFrame, int exclusiveEnd,
                                    int bootstrapPresentationAllowance) {
        public CompleteAudioStop {
            if (firstFrame < 0) {
                throw new IllegalArgumentException("firstFrame must be >= 0");
            }
            if (exclusiveEnd <= firstFrame) {
                throw new IllegalArgumentException(
                        "exclusiveEnd must be greater than firstFrame");
            }
            if (bootstrapPresentationAllowance < 0) {
                throw new IllegalArgumentException(
                        "bootstrapPresentationAllowance must be >= 0");
            }
            Math.addExact(exclusiveEnd - firstFrame,
                    bootstrapPresentationAllowance);
        }

        int iterationBudget() {
            return Math.addExact(exclusiveEnd - firstFrame,
                    bootstrapPresentationAllowance);
        }
    }

    /** Cadence proof returned only after the exclusive endpoint is verified. */
    public record CompleteAudioCadenceResult(
            int exclusiveCursor,
            int semanticRows,
            int bootstrapPresentations,
            int outerPresentations,
            int audioUpdates) {
    }

    /**
     * Narrow driver used by the real visual replay and deterministic cadence
     * tests. Counts are read back around every operation, so a duplicate or
     * omitted outer presentation/update fails at the row that caused it.
     */
    interface CompleteAudioDriver {
        int cursor();

        int movieFrameCount();

        boolean playbackPlaying();

        boolean paused();

        String playbackRateDisplay();

        String abortDiagnostic();

        boolean coordinatorComplete();

        long outerPresentationCount();

        long audioUpdateCount();

        void step() throws Exception;

        void presentOuterFrame() throws Exception;

        void updateAudio() throws Exception;

        FrameView frameView(int consumedCursor, int loopStep,
                            boolean semanticRow);

        default void verifyAfterFrame() { }
    }

    /**
     * One line per change of mode, coordinator phase, segment, or level load
     * generation. A run is tens of thousands of steps and almost all of them
     * are identical, so the interesting question -- what happened, in what
     * order, at which physical row -- is answered by the changes alone.
     */
    private static final int TIMELINE_LIMIT = 400;

    /** Steps of context retained for an abort message. */
    private static final int TRACE_WINDOW = 12;

    /** Generous enough for any single boundary; a whole run needs far more. */
    private static final int DEFAULT_MAX_STEPS = 60_000;

    /**
     * Where to stop. A run is 200,000+ frames, and a lane usually cares about
     * one boundary, so a target segment lets a test pin the frontier it has
     * actually cleared instead of driving into the next unexplored one.
     */
    public record Stop(int maxSteps, int untilSegmentIndex, boolean afterBody) {
        public Stop(int maxSteps, int untilSegmentIndex) {
            this(maxSteps, untilSegmentIndex, false);
        }
    }

    /** Stops cleanly as soon as {@code segmentIndex} is admitted. */
    public static Stop stopAfterSegment(int segmentIndex) {
        return new Stop(DEFAULT_MAX_STEPS, segmentIndex, false);
    }

    /**
     * Stops once {@code segmentIndex} has published its LAST compared row --
     * the coordinator has closed it and entered the transition gap. Admission
     * alone proves only that the boundary before a segment works; this pins a
     * lane on the segment's whole body without driving into the next
     * unexplored boundary behind it.
     */
    public static Stop stopAfterSegmentBody(int segmentIndex) {
        return new Stop(DEFAULT_MAX_STEPS, segmentIndex, true);
    }

    private VisualRunReplayHarness() {
    }

    /**
     * Boots the run's first segment headlessly, hands it to the production
     * launcher, and steps the game loop until the run completes or the budget
     * runs out.
     *
     * @throws AssertionError wrapping the replay failure if the session aborts
     */
    public static Result replay(Path runDir, int maxSteps) throws Exception {
        return replay(runDir, new Stop(maxSteps, -1), FrameObserver.NONE, false);
    }

    public static Result replay(Path runDir, Stop stop) throws Exception {
        return replay(runDir, stop, FrameObserver.NONE, false);
    }

    /**
     * Drives the normal visual route while exposing post-presentation row
     * coordinates. Fast-forward is rejected because one semantic row must
     * receive exactly one outer presentation for audio timeline capture.
     */
    public static Result replayAudio(Path runDir, Stop stop,
                                     FrameObserver observer) throws Exception {
        return replay(runDir, stop, observer, true);
    }

    /**
     * Drives the validated movie's complete comparison epoch. Unlike the
     * focused segment lane, every absolute BK2 row is semantic once the epoch
     * begins, including manifest gaps and the terminal tail.
     */
    public static CompleteAudioCadenceResult replayCompleteAudio(
            Path runDir, CompleteAudioStop stop,
            FrameObserver observer) throws Exception {
        return replayCompleteAudio(runDir, stop, observer,
                PRODUCTION_SESSION_CONSTRUCTOR, PRODUCTION_BOOTSTRAP);
    }

    static CompleteAudioCadenceResult replayCompleteAudio(
            Path runDir, CompleteAudioStop stop,
            FrameObserver observer,
            RunSessionConstructor sessionConstructor,
            RunBootstrap bootstrap) throws Exception {
        return replayCompleteAudio(runDir, stop, observer, sessionConstructor,
                bootstrap, PRODUCTION_PAYLOAD_CLOSER);
    }

    static CompleteAudioCadenceResult replayCompleteAudio(
            Path runDir, CompleteAudioStop stop,
            FrameObserver observer,
            RunSessionConstructor sessionConstructor,
            RunBootstrap bootstrap,
            VisualPayloadCloser payloadCloser) throws Exception {
        java.util.Objects.requireNonNull(stop, "stop");
        java.util.Objects.requireNonNull(observer, "observer");
        java.util.Objects.requireNonNull(sessionConstructor, "sessionConstructor");
        java.util.Objects.requireNonNull(bootstrap, "bootstrap");
        java.util.Objects.requireNonNull(payloadCloser, "payloadCloser");
        ActiveSegmentPayload localPayload = null;
        ActiveSegmentPayload leasedPayload = null;
        TraceSessionLauncher transferredSession = null;
        Throwable primary = null;
        try {
            TraceRunManifest manifest =
                    TraceRunManifest.load(runDir.resolve("run_manifest.json"));
            Path bk2 = runDir.resolve(manifest.sourceBk2());
            TraceEntry entry = TraceEntry.forRun(runDir, manifest, bk2);
            TraceCatalog.PreparedDescriptorRunLaunch prepared =
                    TraceCatalog.prepareDescriptorRunLaunch(entry);
            List<TraceRunSegmentDescriptor> segments = prepared.segments();
            Bk2Movie movie = prepared.movie();
            localPayload = TraceRunReplayWalker.openActiveSegment(
                    segments.getFirst(), 0);
            leasedPayload = localPayload;
            var seg0 = localPayload.trace();
            int manifestFirst = segments.getFirst().segment().bk2FrameOffset();
            if (manifestFirst != stop.firstFrame()) {
                throw new IllegalStateException(
                        "complete-audio first frame " + stop.firstFrame()
                                + " does not match manifest first segment row "
                                + manifestFirst);
            }

            GameLoop loop = bootstrap.beforeTransfer(entry, seg0, observer);
            transferredSession = sessionConstructor.create(
                    entry, movie, segments, localPayload);
            localPayload = null;
            TraceSessionLauncher session = transferredSession;
            bootstrap.afterTransfer(session, loop);

            ArrayDeque<String> recent = new ArrayDeque<>();
            List<String> timeline = new ArrayList<>();
            TIMELINE.set(timeline);
            String[] lastKey = {""};

            CompleteAudioDriver driver = new CompleteAudioDriver() {
                private long outerPresentations;
                private long audioUpdates;
                private int hostSteps;

                @Override
                public int cursor() {
                    return GameServices.playbackDebug().getCursorFrame();
                }

                @Override
                public int movieFrameCount() {
                    return movie.getFrameCount();
                }

                @Override
                public boolean playbackPlaying() {
                    return GameServices.playbackDebug().isSessionPlaying();
                }

                @Override
                public boolean paused() {
                    return loop.isPaused();
                }

                @Override
                public String playbackRateDisplay() {
                    return session.playbackRateDisplay();
                }

                @Override
                public String abortDiagnostic() {
                    return null;
                }

                @Override
                public boolean coordinatorComplete() {
                    return runFinished(session);
                }

                @Override
                public long outerPresentationCount() {
                    return outerPresentations;
                }

                @Override
                public long audioUpdateCount() {
                    return audioUpdates;
                }

                @Override
                public void step() {
                    loop.step();
                    hostSteps++;
                }

                @Override
                public void presentOuterFrame() {
                    loop.presentOuterFrame(false, false);
                    outerPresentations++;
                }

                @Override
                public void updateAudio() {
                    GameServices.audio().update();
                    audioUpdates++;
                }

                @Override
                public FrameView frameView(int consumedCursor, int loopStep,
                                           boolean semanticRow) {
                    return VisualRunReplayHarness.frameView(
                            consumedCursor, loopStep, loop, segments,
                            semanticRow);
                }

                @Override
                public void verifyAfterFrame() {
                    record(recent, hostSteps, session, loop);
                    recordTimeline(timeline, lastKey, hostSteps, session, loop);
                    rethrowIfAborted(session, recent);
                }
            };
            return driveCompleteAudioCadence(stop, observer, driver);
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Throwable cleanup = closeVisualPayloadLifecycle(
                    transferredSession, localPayload, leasedPayload, primary,
                    payloadCloser);
            if (primary == null) {
                rethrowCleanup(cleanup);
            }
        }
    }

    static CompleteAudioCadenceResult driveCompleteAudioCadence(
            CompleteAudioStop stop, FrameObserver observer,
            CompleteAudioDriver driver) throws Exception {
        java.util.Objects.requireNonNull(stop, "stop");
        java.util.Objects.requireNonNull(observer, "observer");
        java.util.Objects.requireNonNull(driver, "driver");
        if (driver.movieFrameCount() != stop.exclusiveEnd()) {
            throw new IllegalStateException(
                    "complete-audio exclusive end " + stop.exclusiveEnd()
                            + " does not match movie frame count "
                            + driver.movieFrameCount());
        }

        int expectedCursor = stop.firstFrame();
        int bootstrapPresentations = 0;
        int semanticRows = 0;
        int iterations = 0;
        boolean baselineObserved = false;
        while (expectedCursor < stop.exclusiveEnd()) {
            if (iterations >= stop.iterationBudget()) {
                throw new IllegalStateException(
                        "complete-audio bootstrap allowance exhausted before "
                                + "exclusive end " + stop.exclusiveEnd());
            }
            rejectFastForward(driver);
            if (driver.paused()) {
                throw new IllegalStateException(
                        "complete-audio replay paused before row "
                                + expectedCursor);
            }

            int cursorBefore = driver.cursor();
            boolean consumesEpochRow = baselineObserved;
            if (!baselineObserved) {
                if (cursorBefore > stop.firstFrame()) {
                    throw new IllegalStateException(
                            "complete-audio cursor crossed comparison epoch "
                                    + "before baseline: " + cursorBefore);
                }
                if (driver.playbackPlaying()
                        && cursorBefore < stop.firstFrame()) {
                    throw new IllegalStateException(
                            "complete-audio playback started before epoch at row "
                                    + cursorBefore);
                }
                if (driver.playbackPlaying()
                        && cursorBefore == stop.firstFrame()) {
                    FrameView baseline = driver.frameView(
                            cursorBefore, iterations + 1, false);
                    observer.beforeFirstEpochRow(baseline);
                    baselineObserved = true;
                    consumesEpochRow = true;
                }
            } else if (cursorBefore != expectedCursor) {
                throw new IllegalStateException(
                        "complete-audio cursor jump before row "
                                + expectedCursor + ": got " + cursorBefore);
            }

            long presentationsBefore = driver.outerPresentationCount();
            long updatesBefore = driver.audioUpdateCount();
            driver.step();
            driver.presentOuterFrame();
            driver.updateAudio();
            iterations++;
            requireSingleOuterWork(driver, presentationsBefore, updatesBefore,
                    expectedCursor);
            driver.verifyAfterFrame();
            rejectFastForward(driver);
            if (driver.abortDiagnostic() != null) {
                throw new IllegalStateException(
                        "complete-audio trace replay aborted: "
                                + driver.abortDiagnostic());
            }
            if (driver.paused()) {
                throw new IllegalStateException(
                        "complete-audio replay paused while consuming row "
                                + expectedCursor);
            }

            int cursorAfter = driver.cursor();
            if (!consumesEpochRow) {
                bootstrapPresentations++;
                observer.afterOuterFrame(driver.frameView(
                        cursorAfter, iterations, false));
                if (bootstrapPresentations
                        > stop.bootstrapPresentationAllowance()) {
                    throw new IllegalStateException(
                            "complete-audio bootstrap allowance exceeded: "
                                    + bootstrapPresentations + " > "
                                    + stop.bootstrapPresentationAllowance());
                }
                if (cursorAfter > stop.firstFrame()) {
                    throw new IllegalStateException(
                            "complete-audio cursor crossed comparison epoch "
                                    + "during bootstrap: " + cursorAfter);
                }
                continue;
            }

            boolean finalRow = expectedCursor == stop.exclusiveEnd() - 1;
            if (!finalRow && driver.coordinatorComplete()) {
                throw new IllegalStateException(
                        "complete-audio coordinator completed before final row "
                                + expectedCursor);
            }
            if (!finalRow) {
                if (cursorAfter != expectedCursor + 1) {
                    throw new IllegalStateException(
                            "complete-audio cursor jump after row "
                                    + expectedCursor + ": got " + cursorAfter);
                }
                if (!driver.playbackPlaying()) {
                    throw new IllegalStateException(
                            "complete-audio premature movie end after row "
                                    + expectedCursor);
                }
            } else {
                // PlaybackTimelineController pins its raw cursor to the final
                // valid row when playback ends. The semantic terminal cursor
                // is nevertheless the movie's exclusive frame count.
                if (cursorAfter != expectedCursor) {
                    throw new IllegalStateException(
                            "complete-audio raw terminal cursor must remain "
                                    + "pinned to " + expectedCursor + ", got "
                                    + cursorAfter);
                }
                if (driver.playbackPlaying()) {
                    throw new IllegalStateException(
                            "complete-audio movie still playing at exclusive end "
                                    + stop.exclusiveEnd());
                }
                // Completion is observed only after the final row's step,
                // outer presentation, audio update, and contained-abort check.
                // This keeps trace/comparator ownership live through every
                // earlier semantic row while still stopping without another
                // host presentation after the movie endpoint.
                if (!driver.coordinatorComplete()) {
                    throw new IllegalStateException(
                            "complete-audio movie reached exclusive end before "
                                    + "the run coordinator completed");
                }
            }

            observer.afterOuterFrame(driver.frameView(
                    expectedCursor, iterations, true));
            expectedCursor++;
            semanticRows++;
        }

        if (!baselineObserved) {
            throw new IllegalStateException(
                    "complete-audio epoch ended without a baseline");
        }
        int presentations = Math.toIntExact(driver.outerPresentationCount());
        int updates = Math.toIntExact(driver.audioUpdateCount());
        if (presentations != semanticRows + bootstrapPresentations
                || updates != presentations) {
            throw new IllegalStateException(
                    "complete-audio outer cadence totals disagree: rows="
                            + semanticRows + ", bootstrap="
                            + bootstrapPresentations + ", presentations="
                            + presentations + ", updates=" + updates);
        }
        return new CompleteAudioCadenceResult(
                stop.exclusiveEnd(), semanticRows, bootstrapPresentations,
                presentations, updates);
    }

    private static void rejectFastForward(CompleteAudioDriver driver) {
        if (!"< 1x >".equals(driver.playbackRateDisplay())) {
            throw new IllegalStateException(
                    "complete-audio replay rejects enabled fast-forward: "
                            + driver.playbackRateDisplay());
        }
    }

    private static void requireSingleOuterWork(
            CompleteAudioDriver driver, long presentationsBefore,
            long updatesBefore, int expectedCursor) {
        long presentationDelta =
                driver.outerPresentationCount() - presentationsBefore;
        if (presentationDelta != 1) {
            throw new IllegalStateException(
                    "complete-audio row " + expectedCursor
                            + " requires exactly one outer presentation, got "
                            + presentationDelta);
        }
        long updateDelta = driver.audioUpdateCount() - updatesBefore;
        if (updateDelta != 1) {
            throw new IllegalStateException(
                    "complete-audio row " + expectedCursor
                            + " requires exactly one audio update, got "
                            + updateDelta);
        }
    }

    public static Result replay(Path runDir, Stop stop,
                                FrameObserver observer) throws Exception {
        return replay(runDir, stop, observer, false);
    }

    private static Result replay(Path runDir, Stop stop,
                                 FrameObserver observer,
                                 boolean rejectFastForward) throws Exception {
        return replay(runDir, stop, observer, rejectFastForward,
                PRODUCTION_SESSION_CONSTRUCTOR, PRODUCTION_BOOTSTRAP);
    }

    static Result replay(Path runDir, Stop stop,
                         FrameObserver observer,
                         boolean rejectFastForward,
                         RunSessionConstructor sessionConstructor,
                         RunBootstrap bootstrap) throws Exception {
        return replay(runDir, stop, observer, rejectFastForward,
                sessionConstructor, bootstrap, PRODUCTION_PAYLOAD_CLOSER);
    }

    static Result replay(Path runDir, Stop stop,
                         FrameObserver observer,
                         boolean rejectFastForward,
                         RunSessionConstructor sessionConstructor,
                         RunBootstrap bootstrap,
                         VisualPayloadCloser payloadCloser) throws Exception {
        java.util.Objects.requireNonNull(observer, "observer");
        java.util.Objects.requireNonNull(sessionConstructor, "sessionConstructor");
        java.util.Objects.requireNonNull(bootstrap, "bootstrap");
        java.util.Objects.requireNonNull(payloadCloser, "payloadCloser");
        ActiveSegmentPayload localPayload = null;
        ActiveSegmentPayload leasedPayload = null;
        TraceSessionLauncher transferredSession = null;
        Throwable primary = null;
        try {
            TraceRunManifest manifest =
                    TraceRunManifest.load(runDir.resolve("run_manifest.json"));
            Path bk2 = runDir.resolve(manifest.sourceBk2());
            TraceEntry entry = TraceEntry.forRun(runDir, manifest, bk2);
            TraceCatalog.PreparedDescriptorRunLaunch prepared =
                    TraceCatalog.prepareDescriptorRunLaunch(entry);
            List<TraceRunSegmentDescriptor> segments = prepared.segments();
            Bk2Movie movie = prepared.movie();
            localPayload = TraceRunReplayWalker.openActiveSegment(
                    segments.getFirst(), 0);
            leasedPayload = localPayload;
            var seg0 = localPayload.trace();

            GameLoop loop = bootstrap.beforeTransfer(entry, seg0, observer);
            transferredSession = sessionConstructor.create(
                    entry, movie, segments, localPayload);
            localPayload = null;
            TraceSessionLauncher session = transferredSession;
            bootstrap.afterTransfer(session, loop);
            if (rejectFastForward
                    && !"< 1x >".equals(session.playbackRateDisplay())) {
                throw new IllegalStateException(
                        "audio timeline capture rejects enabled fast-forward");
            }

            ArrayDeque<String> recent = new ArrayDeque<>();
            List<String> timeline = new ArrayList<>();
            TIMELINE.set(timeline);
            String[] lastKey = {""};
            int steps = 0;
            boolean stalled = false;
            boolean reachedTarget = false;
            boolean baselineObserved = false;
            while (steps < stop.maxSteps() && !runFinished(session)) {
                int cursorBefore =
                        GameServices.playbackDebug().getCursorFrame();
                FrameView before = frameView(
                        cursorBefore, steps + 1, loop, segments, false);
                if (!baselineObserved && before.segmentIndex() == 0
                        && before.segmentRow() == 0) {
                    observer.beforeFirstSegmentRow(before);
                    baselineObserved = true;
                }
                loop.step();
                steps++;
                // This is the Engine-owned outer audio placement, deliberately
                // repeated for bootstrap/title-card diagnostics as well as rows.
                loop.presentOuterFrame(false, false);
                GameServices.audio().update();
                int cursorAfter =
                        GameServices.playbackDebug().getCursorFrame();
                boolean semanticStart = before.segmentIndex() >= 0
                        && before.segmentRow() >= 0;
                int consumed = semanticStart && cursorAfter == cursorBefore + 1
                        ? cursorBefore : cursorAfter;
                boolean semanticRow = semanticStart
                        && cursorAfter == cursorBefore + 1;
                if (rejectFastForward && semanticStart
                        && cursorAfter != cursorBefore + 1) {
                    throw new IllegalStateException(
                            "audio timeline capture rejects fast-forwarded BK2 rows");
                }
                observer.afterOuterFrame(frameView(
                        consumed, steps, loop, segments, semanticRow));
                record(recent, steps, session, loop);
                recordTimeline(timeline, lastKey, steps, session, loop);
                rethrowIfAborted(session, recent);
                // Only meaningful once the title card has handed off and the run
                // coordinator exists; before that there is no session to stall.
                if (loop.isPaused()) {
                    throw new AssertionError(
                            "visual run paused itself on its first comparison error"
                                    + " -- a windowed session pauses so the user can"
                                    + " read the HUD, which headlessly is a stall"
                                    + gapLedger() + window(recent));
                }
                if (coordinator(session) != null
                        && !GameServices.playbackDebug().isSessionPlaying()) {
                    stalled = true;
                    break;
                }
                // Checked LAST, so a target that lands on the same step as a pause
                // or a stall reports the failure rather than a hollow success.
                if (stop.untilSegmentIndex() >= 0
                        && reachedStopTarget(session, stop)) {
                    reachedTarget = true;
                    break;
                }
            }
            Outcome outcome = runFinished(session) ? Outcome.COMPLETED
                    : reachedTarget ? Outcome.REACHED_SEGMENT
                    : stalled ? Outcome.PLAYBACK_STALLED
                    : Outcome.BUDGET_EXHAUSTED;
            Result result = new Result(outcome, steps,
                    GameServices.playbackDebug().getCursorFrame(),
                    coordinatorPhase(session), currentSegmentIndex(session),
                    List.copyOf(timeline));
            if (outcome == Outcome.PLAYBACK_STALLED) {
                throw new AssertionError(
                        "visual run stalled: BK2 playback stopped with "
                                + "the coordinator still on segment "
                                + result.currentSegmentIndex() + " ("
                                + result.phase() + ")" + window(recent));
            }
            return result;
        } catch (Exception | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            Throwable cleanup = closeVisualPayloadLifecycle(
                    transferredSession, localPayload, leasedPayload, primary,
                    payloadCloser);
            if (primary == null) {
                rethrowCleanup(cleanup);
            }
        }
    }

    static FrameView frameView(int cursor, int loopStep, GameLoop loop,
                              List<TraceRunSegmentDescriptor> segments,
                              boolean semanticRow) {
        for (int index = 0; index < segments.size(); index++) {
            TraceRunSegmentDescriptor plan = segments.get(index);
            int start = plan.segment().bk2FrameOffset();
            int row = cursor - start;
            if (row >= 0 && row < plan.rowCount()) {
                return new FrameView(cursor,
                        new SegmentCoordinate(index, row), loopStep,
                        plan.laggedRows().get(row),
                        loop.getCurrentGameMode() == GameMode.LEVEL, semanticRow);
            }
        }
        return new FrameView(cursor, null, loopStep, false,
                loop.getCurrentGameMode() == GameMode.LEVEL, semanticRow);
    }

    /** Restores the globals the harness installs. Call from {@code @AfterEach}. */
    public static void tearDown() {
        TraceLaunchStatus.clear();
        setActiveSession(null);
        Engine.clearGlobalInstance();
        GameServices.playbackDebug().endSession();
        SessionManager.clear();
    }

    // ------------------------------------------------------------------
    // Production seams. Reflection is confined to this class so the tests
    // above it read as ordinary assertions -- these are package-private or
    // private members of production types the windowed launch reaches
    // through the master-title callback rather than a public API.
    // ------------------------------------------------------------------

    static TraceSessionLauncher newRunSession(
            TraceEntry entry, Bk2Movie movie,
            List<TraceRunSegmentDescriptor> segments,
            ActiveSegmentPayload activePayload) throws Exception {
        var ctor = TraceSessionLauncher.class.getDeclaredConstructor(
                TraceEntry.class, Bk2Movie.class, List.class,
                ActiveSegmentPayload.class,
                TraceReplaySessionBootstrap.ConfigSnapshot.class);
        ctor.setAccessible(true);
        return ctor.newInstance(entry, movie, segments, activePayload, null);
    }

    /**
     * Lets the session detach HUD/camera/observer aliases first, then closes
     * the idempotent local lease exactly once through the harness seam.
     */
    private static Throwable closeVisualPayloadLifecycle(
            TraceSessionLauncher transferredSession,
            ActiveSegmentPayload localPayload,
            ActiveSegmentPayload leasedPayload,
            Throwable primary,
            VisualPayloadCloser payloadCloser) throws Exception {
        Throwable failure = primary;
        if (transferredSession != null) {
            failure = closeTransferredRunSession(transferredSession, failure);
        }
        ActiveSegmentPayload payload = transferredSession != null
                ? leasedPayload : localPayload;
        if (payload != null) {
            failure = closeVisualPayload(payload, failure, payloadCloser);
        }
        return failure;
    }

    private static Throwable closeVisualPayload(
            ActiveSegmentPayload payload, Throwable primary,
            VisualPayloadCloser payloadCloser) {
        try {
            payloadCloser.close(payload);
            return primary;
        } catch (RuntimeException | Error cleanupFailure) {
            if (primary != null) {
                primary.addSuppressed(cleanupFailure);
                return primary;
            }
            return cleanupFailure;
        }
    }

    static Throwable closeTransferredRunSession(
            TraceSessionLauncher session, Throwable primary) throws Exception {
        Method method = TraceSessionLauncher.class.getDeclaredMethod(
                "abortIncompleteSession", Throwable.class, String.class,
                GameLoop.class);
        method.setAccessible(true);
        try {
            return (Throwable) method.invoke(session, primary,
                    "visual run harness teardown", null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cleanup = e.getCause();
            if (primary != null) {
                primary.addSuppressed(cleanup);
                return primary;
            }
            if (cleanup instanceof Exception exception) {
                throw exception;
            }
            throw (Error) cleanup;
        }
    }

    private static void rethrowCleanup(Throwable cleanup) throws Exception {
        if (cleanup == null) {
            return;
        }
        if (cleanup instanceof Exception exception) {
            throw exception;
        }
        throw (Error) cleanup;
    }

    private static void finishRunLaunch(TraceSessionLauncher session)
            throws Exception {
        Method method =
                TraceSessionLauncher.class.getDeclaredMethod("finishRunLaunch");
        method.setAccessible(true);
        method.invoke(session);
    }

    private static TraceRunPlaybackCoordinator coordinator(
            TraceSessionLauncher session) {
        return (TraceRunPlaybackCoordinator) field(session, "runCoordinator");
    }

    private static TraceRunPlaybackCoordinator.Phase coordinatorPhase(
            TraceSessionLauncher session) {
        TraceRunPlaybackCoordinator coordinator = coordinator(session);
        return coordinator == null ? null : coordinator.phase();
    }

    private static boolean reachedStopTarget(
            TraceSessionLauncher session, Stop stop) {
        int segment = currentSegmentIndex(session);
        if (!stop.afterBody()) {
            return segment >= stop.untilSegmentIndex();
        }
        return segment > stop.untilSegmentIndex()
                || (segment == stop.untilSegmentIndex()
                        && coordinatorPhase(session)
                                == TraceRunPlaybackCoordinator.Phase.TRANSITION_GAP);
    }

    private static int currentSegmentIndex(TraceSessionLauncher session) {
        TraceRunPlaybackCoordinator coordinator = coordinator(session);
        return coordinator == null ? -1 : coordinator.currentSegmentIndex();
    }

    private static boolean runFinished(TraceSessionLauncher session) {
        return coordinatorPhase(session) == TraceRunPlaybackCoordinator.Phase.COMPLETE;
    }

    /**
     * The production launcher CONTAINS replay failures: it logs them and
     * records the entry as failed rather than propagating, because a windowed
     * session must return the user to the picker instead of killing the
     * engine. A headless driver has to turn that back into a failure or it
     * would report a green run for a session that died on its first row.
     */
    /**
     * A trailing window of what the run was doing. An abort names a coordinator
     * rule, not the frames that led to it, and the frames are the diagnosis --
     * which segment owned the row, what mode the engine was in, and where the
     * shared cursor sat relative to the destination offset.
     */
    private static void record(ArrayDeque<String> recent, int step,
                               TraceSessionLauncher session, GameLoop loop) {
        if (recent.size() == TRACE_WINDOW) {
            recent.removeFirst();
        }
        recent.addLast("step=" + step
                + " cursor=" + GameServices.playbackDebug().getCursorFrame()
                + " mode=" + loop.getCurrentGameMode()
                + " phase=" + coordinatorPhase(session)
                + " segment=" + currentSegmentIndex(session)
                + " art=" + dynamicArt()
                + " paused=" + loop.isPaused()
                + diagnostics(session));
    }

    private static String dynamicArt() {
        try {
            var snap = GameServices.captureDynamicArtDiagnostics();
            return "serial=" + snap.deliverySerial()
                    + ",published=" + snap.published()
                    + ",frame=" + snap.frame()
                    + ",gen=" + snap.segmentGeneration()
                    + ",open=" + com.openggf.game.session.SessionManager
                            .getCurrentGameplayMode().dynamicArtLifecycle()
                            .isComparisonSegmentOpen();
        } catch (RuntimeException e) {
            return "n/a";
        }
    }

    /**
     * The run's own HUD diagnostics. A windowed session pauses on its first
     * error so the user can read them; headlessly that reads as a stall, so
     * the mismatch itself has to reach the failure message.
     */
    private static String diagnostics(TraceSessionLauncher session) {
        Object diag = field(session, "runExternalDiagnostics");
        if (diag == null) {
            return "";
        }
        try {
            int errors = (int) diag.getClass().getMethod("errorCount").invoke(diag);
            if (errors == 0) {
                return "";
            }
            Object recent = diag.getClass().getMethod("recentMismatches").invoke(diag);
            return " errors=" + errors + " " + recent;
        } catch (ReflectiveOperationException e) {
            return " errors=?";
        }
    }

    private static void recordTimeline(List<String> timeline, String[] lastKey,
                                       int step, TraceSessionLauncher session,
                                       GameLoop loop) {
        String key = loop.getCurrentGameMode() + "|" + coordinatorPhase(session)
                + "|" + currentSegmentIndex(session) + "|" + loadGeneration();
        if (key.equals(lastKey[0]) || timeline.size() >= TIMELINE_LIMIT) {
            return;
        }
        lastKey[0] = key;
        timeline.add("step=" + step
                + " cursor=" + GameServices.playbackDebug().getCursorFrame()
                + " mode=" + loop.getCurrentGameMode()
                + " phase=" + coordinatorPhase(session)
                + " segment=" + currentSegmentIndex(session)
                + " loadGen=" + loadGeneration());
    }

    private static String loadGeneration() {
        try {
            return String.valueOf(
                    GameServices.level().getCompletedProductionLoadGeneration());
        } catch (RuntimeException e) {
            return "n/a";
        }
    }

    /** The in-flight run's timeline, so a thrown failure can carry it. */
    private static final ThreadLocal<List<String>> TIMELINE = new ThreadLocal<>();

    private static String gapLedger() {
        try {
            var lifecycle = com.openggf.game.session.SessionManager
                    .getCurrentGameplayMode().dynamicArtLifecycle();
            var edges = lifecycle.gapTransitions();
            StringBuilder out = new StringBuilder("\n  -- gap ledger ("
                    + edges.size() + ") --");
            for (Object t : edges) {
                out.append("\n  ").append(String.valueOf(t), 0,
                        Math.min(200, String.valueOf(t).length()));
            }
            return out.toString();
        } catch (RuntimeException e) {
            return "\n  -- gap ledger unavailable --";
        }
    }

    private static String window(ArrayDeque<String> recent) {
        List<String> timeline = TIMELINE.get();
        String changes = timeline == null || timeline.isEmpty() ? ""
                : "\n  -- timeline --\n  " + String.join("\n  ", timeline);
        return "\n  " + String.join("\n  ", recent) + changes;
    }

    private static void rethrowIfAborted(TraceSessionLauncher session,
                                         ArrayDeque<String> recent) {
        TraceRunPlaybackCoordinator.Phase phase = coordinatorPhase(session);
        if (phase == TraceRunPlaybackCoordinator.Phase.FAILED) {
            throw new AssertionError("visual run aborted at segment "
                    + currentSegmentIndex(session) + ", BK2 cursor "
                    + GameServices.playbackDebug().getCursorFrame() + ": "
                    + failRunDiagnostics(session) + window(recent));
        }
        // The launcher's containment records the diagnostic here rather than
        // rethrowing, so this is the only place a contained replay failure is
        // visible from outside the session.
        TraceLaunchStatus.current().ifPresent(failure -> {
            throw new AssertionError("visual run aborted after a replay failure: "
                    + failure.reason() + window(recent));
        });
    }

    /**
     * {@code Engine.currentGameLoop()} reads {@code instance.gameLoop}, and the
     * launcher consults it on every physical row. Constructing a real
     * {@code Engine} headlessly is not an option -- its master-title paths
     * reach {@code glCreateShader}, which aborts the JVM rather than throwing
     * (see {@code Engine.clearGlobalInstance}'s javadoc). Mockito gives us an
     * instance without running the constructor, which is the same seam
     * {@code TestVisualTraceRunTerminalTail} already uses.
     */
    /** The coordinator's own reason, which the transcript retains verbatim. */
    private static String failRunDiagnostics(TraceSessionLauncher session) {
        Object transcript = field(session, "runCoordinatorTranscript");
        if (!(transcript instanceof List<?> actions)) {
            return "no coordinator transcript";
        }
        StringBuilder reasons = new StringBuilder();
        for (Object action : actions) {
            if (action instanceof TraceRunPlaybackCoordinator.FailRun failure) {
                if (!reasons.isEmpty()) {
                    reasons.append("; ");
                }
                reasons.append(failure.diagnostic());
            }
        }
        return reasons.isEmpty() ? "coordinator FAILED without a FailRun action"
                : reasons.toString();
    }

    private static void installCurrentGameLoop(GameLoop loop) {
        try {
            Engine engine = org.mockito.Mockito.mock(Engine.class);
            Field loopField = Engine.class.getDeclaredField("gameLoop");
            loopField.setAccessible(true);
            loopField.set(engine, loop);
            setStaticField(Engine.class, "instance", engine);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Engine.gameLoop seam moved", e);
        }
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (NoSuchFieldException e) {
            return null;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void setActiveSession(TraceSessionLauncher session) {
        setStaticField(TraceSessionLauncher.class, "activeSession", session);
    }

    private static void setStaticField(Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "harness seam " + type.getSimpleName() + "." + name + " moved", e);
        }
    }
}
