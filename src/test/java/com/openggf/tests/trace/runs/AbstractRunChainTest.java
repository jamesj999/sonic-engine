package com.openggf.tests.trace.runs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openggf.GameLoop;
import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameStep;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.OscillationManager;
import com.openggf.game.SpecialStageInputMapper;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.resources.DynamicArtGapDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtGapTransition;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.TraceSessionLauncher;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SessionInvocationExtension;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.trace.SpecialStageRunObjectsPassBinder;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceData;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.live.LiveTraceComparator;
import com.openggf.trace.VerificationGroup;
import com.openggf.trace.live.MismatchEntry;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.replay.TraceReplayDriver;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.runs.ActiveSegmentPayload;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunVblankClock;
import com.openggf.trace.replay.runs.DestinationAdmissionReceipt;
import com.openggf.trace.replay.runs.RunBoundarySignal;
import com.openggf.trace.replay.runs.RunPlaybackObservation;
import com.openggf.trace.replay.runs.TraceRunBoundaryComparator;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapComparator;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapJournal;
import com.openggf.trace.replay.runs.RunLevelLoadTracker;
import com.openggf.trace.replay.runs.TraceRunPlaybackCoordinator;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;
import com.openggf.trace.replay.runs.TraceRunPresentationClosure;
import com.openggf.trace.replay.runs.SpecialStageRecordedPassPacing;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRowDriver;
import com.openggf.trace.replay.runs.TraceStructuralRowComparator;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.BoundaryEntryMode;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.BoundaryObservation;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.BoundaryProbe;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.HardwareTimingCoordinator;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.HardwareTimingSegment;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.ReturnAssertionMode;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable base for multi-stage trace RUN chain tests (spec/API contract:
 * "Decisions locked with the owner" and Component 2 in
 * docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md). Drives
 * ONE continuous {@link GameLoop} through EVERY segment of a
 * {@link TraceRunManifest} — with NO hardcoded segment count and NO
 * zone/route/frame carve-out — and asserts that the engine organically raises
 * each transition and that boundary state (position / checkpoint / rings /
 * emeralds) carries over.
 *
 * <p>All three committed runs drive through this one {@link #runChain} body,
 * each via its own lane subclass: {@link TestS3kMegaRunChain}
 * for {@code s3-knux-multibonus-ss} (25 seg), {@link TestS1GhzMazeRoundTripChain}
 * for {@code s1-ghz-maze-roundtrip} (3 seg), {@link TestS2EhzHalfpipeRoundTripChain}
 * for {@code s2-ehz-halfpipe-roundtrip} (5 seg). A lane subclass supplies only
 * its run directory and {@code @RequiresRom} game; every behavioral branch keys
 * on manifest data ({@code segment.kind()} / {@code transition.entryKind()} /
 * field presence), never on identity.
 *
 * <p>Comparison-only throughout: no trace field is ever hydrated into engine
 * state; every comparator and boundary assertion observes an engine that reached
 * its position by replaying the recorded BK2 inputs.
 */
@ExtendWith(SessionInvocationExtension.class)
abstract class AbstractRunChainTest {

    /**
     * Failing axes accumulated during one {@link #assertChainReplay} walk.
     *
     * <p><b>Why this exists.</b> Every chain assertion used to throw at its own
     * site, which serialized the run's failure axes: the FIRST one to fire
     * aborted the walk, and everything downstream of it -- including
     * {@code DynamicArtGapJournalProbe#verify}, which only runs after the walk
     * completes -- was never evaluated at all. With the returned-level physics
     * assertion live, that meant the transition-gap ledger comparison had never
     * been reached on either S2 chain, so a comparison that is computed and
     * recorded was grading nothing. A run now enumerates every failing axis it
     * can observe, each attributed, and fails once at the end.
     *
     * <p>This defers WHEN a failure is reported, never WHETHER: each axis keeps
     * exactly the predicate it had, and a run with any recorded axis failure
     * still fails.
     */
    private final List<String> chainAxisFailures = new ArrayList<>();
    private LiveTraceComparator productionComparator;
    private ReplayPrefixTarget activeReplayPrefixTarget;
    private int activeReplaySegmentIndex = -1;
    private LiveTraceComparator activeHeadlessComparator;
    private TraceStructuralRowComparator activeStructuralComparator;
    private TraceRunSpecialStageRowDriver activeSpecialDriver;
    private TraceRunSpecialStageRows activeSpecialRows;
    private SpecialStageRunObjectsPassBinder activeSpecialPassBinder;
    private TraceRunReplayWalker.DynamicArtSegmentComparison
            activeDynamicArtComparison;
    private BoundaryProbe activeBoundaryProbe;
    private ActiveSegmentPayload activeSegmentPayload;

    @FunctionalInterface
    interface HeadlessBoundaryAction {
        void run();
    }

    @FunctionalInterface
    interface HeadlessPayloadAttachment<T> {
        T attach(ActiveSegmentPayload payload) throws Exception;
    }

    interface HeadlessSlotProbe {
        void observe(int traceFrame,
                     com.openggf.level.objects.ObjectManager objectManager);

        void close();
    }

    @FunctionalInterface
    interface HeadlessSlotProbeFactory {
        HeadlessSlotProbe create(TraceData trace, String label);
    }

    @FunctionalInterface
    interface ActiveSegmentFactory {
        ActiveSegmentPayload open(TraceRunSegmentDescriptor descriptor,
                                  int segmentIndex) throws IOException;

        default void close(ActiveSegmentPayload payload) {
            payload.close();
        }
    }

    ActiveSegmentFactory activeSegmentFactory =
            TraceRunReplayWalker::openActiveSegment;
    Runnable afterInitialHeadlessPayloadOpen = () -> { };
    Runnable afterProductionStep = () -> { };
    IntConsumer productionOutputRowObserver = row -> { };
    HeadlessSlotProbeFactory slotProbeFactory =
            AbstractRunChainTest::createSlotProbe;
    /**
     * Comparison-only ROM-vs-engine SST occupancy diff for the chain walk, armed
     * per segment and off unless {@code OGGF_SLOT_PROBE=1}.
     *
     * <p>{@link com.openggf.tests.trace.SlotOccupancyProbe} was already wired into
     * the single-segment replay path but not into the chain, so the recorder's
     * {@code slot_dump} ground truth was unreachable for any fixture that only
     * exists as a chain segment -- which is most complete-run material.
     */
    private HeadlessSlotProbe slotOccupancyProbe;
    private HeadlessPayloadReleaseAudit payloadReleaseAudit;
    /** Names the slot-probe output file; set once the run manifest is known. */
    private String slotProbeRunId = "run";
    /**
     * The run's hardware-timing coordinator, so the walk can declare which
     * segment it owns at the same places it attaches and releases its row
     * owner. Membership is drive-owned; see
     * {@link HardwareTimingCoordinator#enterTransitionGap()}.
     */
    private HardwareTimingCoordinator activeHardwareTiming;
    private HeadlessRunCoordinatorAdapter activeRunCoordinator;
    /**
     * Segment indices whose comparator was attached by
     * {@link #attachReturnedLevelSegment} -- i.e. the level a run RETURNS to
     * after an interior (special stage / bonus). Their physics comparator error
     * count is asserted in {@link #maybeWriteReport}.
     *
     * <p><b>Why this exists.</b> Both S2 run chains carried a returned-level
     * segment with tens of thousands of physics errors (58184 on
     * {@code s2-sonic-tails-complete-emeralds} seg2, 39645 on
     * {@code s2-ehz-halfpipe-roundtrip} seg2) while the chain reported the
     * segment {@code complete} and walked straight past it: the only things
     * asserted at that seam were the boundary observation and dynamic art, never
     * the comparator's own {@code errorCount()} -- which the chain had already
     * computed and written into
     * the session-owned {@code run-chain} report directory. Thirteen rounds of
     * candidate fixes were consequently judged by which route a broken-but-
     * unasserted segment happened to take downstream, which measured noise.
     *
     * <p>{@code TestS2Ehz1Seg2CompleteEmeraldsSegmentTraceReplay} is the oracle:
     * it replays the IDENTICAL 3377 rows of that same fixture standalone to ZERO
     * errors. So the rows can replay clean, and the divergence is owned by the
     * chain's return path, not by the recording.
     *
     * <p>Turning this assertion on makes both S2 chains fail at seg2. That is
     * not a regression -- it is the true state of the return path becoming
     * visible, and it replaces symptom-chasing with one measurable target.
     *
     * <p><b>2026-08-12 -- extended to level_advance entries.</b> The same hole
     * existed one boundary kind over. Segment 7 (seg5_ehz2) of
     * {@code TestS2CompleteEmeraldRunChain} is entered by a {@code level_advance}
     * (LEVEL_LOAD) boundary rather than a special-stage return, so it was never
     * registered here -- and its own comparator report
     * ({@code s2-sonic-tails-complete-emeralds_seg7_report.json}) carried
     * errorCount = 149522 over 6046 rows with {@code complete: true} while the
     * chain walked past it and reported only the missed exit boundary. Every
     * level-boundary attach now registers the segment it attaches, so the chain
     * reports the segment truthfully. THE CHAIN GOES REDDER AS A RESULT: that is
     * the deliverable, not a regression. Segments 0/2/4/6 (the EHZ1 levels) are
     * at 0 errors and are unaffected; {@code TestS2EhzHalfpipeRoundTripChain} has
     * no {@code level_advance} boundary at all and is untouched.
     */
    private final java.util.Set<Integer> assertedPhysicsSegmentIndices =
            new java.util.HashSet<>();

    /**
     * Comparator summary JSON written by this walk, by segment index -- the
     * race-free source for {@link #writtenSegmentReport}.
     */
    private final Map<Integer, String> writtenSegmentReports =
            new LinkedHashMap<>();
    private final Map<Integer, Path> writtenSegmentReportPaths =
            new LinkedHashMap<>();

    protected record DynamicArtGapJournalEvidence(
            int transitionCountAfterFirstArm,
            long lastEdgeOrdinalAfterFirstArm,
            List<DynamicArtStructuralGapEvidence> structuralGaps,
            List<TraceRunPlaybackCoordinator.Action> coordinatorActions) {
        protected DynamicArtGapJournalEvidence {
            structuralGaps = List.copyOf(structuralGaps);
            coordinatorActions = List.copyOf(coordinatorActions);
        }

        protected DynamicArtStructuralGapEvidence structuralGap(
                String representedSegmentDir,
                String nextSegmentDir) {
            return structuralGaps.stream()
                    .filter(gap -> gap.representedSegmentDir()
                            .equals(representedSegmentDir))
                    .filter(gap -> gap.nextSegmentDir()
                            .equals(nextSegmentDir))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "missing dynamic-art structural gap "
                                    + representedSegmentDir + " -> "
                                    + nextSegmentDir));
        }
    }

    protected record DynamicArtStructuralGapEvidence(
            String representedSegmentDir,
            String nextSegmentDir,
            int gapStartMovieLogicalFrame,
            int nextSegmentArmMovieLogicalFrame,
            int transitionCountAtGapStart,
            long lastEdgeOrdinalAtGapStart,
            int transitionCountAfterNextArm,
            long lastEdgeOrdinalAfterNextArm,
            TraceRunDynamicArtGapComparator.StructuralOrder structuralOrder,
            List<DynamicArtTransfer.Descriptor> openingLedger,
            List<DynamicArtGapTransition> transitionsAddedAcrossBoundary) {
        protected DynamicArtStructuralGapEvidence {
            openingLedger = List.copyOf(openingLedger);
            transitionsAddedAcrossBoundary =
                    List.copyOf(transitionsAddedAcrossBoundary);
        }
    }

    private static final class DynamicArtGapJournalProbe {
        private final DynamicArtLifecycleService lifecycle;
        private final int transitionCountAfterFirstArm;
        private final long lastEdgeOrdinalAfterFirstArm;
        private final List<DynamicArtStructuralGapEvidence> structuralGaps =
                new ArrayList<>();
        /**
         * Terminal-tail divergences, recorded rather than thrown so this axis
         * is additive with the segment-physics and structural-gap axes (see
         * {@link AbstractRunChainTest#chainAxisFailures}).
         */
        private final List<String> terminalFailures = new ArrayList<>();

        private String representedSegmentDir;
        private Integer gapStartMovieLogicalFrame;
        private int transitionCountAtGapStart;
        private long lastEdgeOrdinalAtGapStart;
        private long structuralOrdinal;
        private Long sourceClosedOrdinal;
        private Long gapOpenedOrdinal;
        private List<DynamicArtTransfer.Descriptor> openingLedger = List.of();
        /**
         * Gap-ledger length, movie row and outstanding ledger observed the
         * instant the source segment closed, before the lifecycle entered the
         * gap.
         *
         * <p>Closing a comparison segment flushes work the segment submitted
         * whose completion falls after its last compared row, and the close
         * itself appends those edges to the gap ledger. Snapshotting after the
         * close starts counting past them, so each is dropped from the gap's
         * compared slice and its transfer is already absent from the opening
         * ledger that resolves an edge's submission origin. A gap begins where
         * its source ended, so the opening state is taken there.
         *
         * <p>Mirrors {@link TraceRunDynamicArtGapJournal}, which implements the
         * same contract for the production visual run path.
         */
        /**
         * Unannounced rows counted when the gap opened, on the same clock the
         * gap's edges carry. A frozen cursor stamps the gap's start with the
         * same stale row it stamps every edge with, so the gap's own start row
         * is recovered the same way at admission.
         */
        private Integer gapOpenedUnannouncedRows;
        private Integer sourceClosedTransitionCount;
        private Integer sourceClosedMovieLogicalFrame;
        private Long sourceClosedLastEdgeOrdinal;
        private List<DynamicArtTransfer.Descriptor> sourceClosedLedger;

        private DynamicArtGapJournalProbe(DynamicArtLifecycleService lifecycle) {
            this.lifecycle = lifecycle;
            List<DynamicArtGapTransition> firstArmTransitions =
                    lifecycle.gapTransitions();
            transitionCountAfterFirstArm = firstArmTransitions.size();
            lastEdgeOrdinalAfterFirstArm =
                    lastEdgeOrdinal(firstArmTransitions);
        }

        private void sourceClosed(String segmentDir) {
            if (sourceClosedOrdinal != null) {
                throw new AssertionError(
                        "dynamic-art source closed twice for " + segmentDir);
            }
            representedSegmentDir = segmentDir;
            sourceClosedOrdinal = ++structuralOrdinal;
            DynamicArtGapDiagnosticsSnapshot atSourceClose =
                    lifecycle.gapOpeningSnapshot();
            sourceClosedTransitionCount = atSourceClose.transitions().size();
            sourceClosedLastEdgeOrdinal = lastEdgeOrdinal(atSourceClose.transitions());
            sourceClosedMovieLogicalFrame = atSourceClose.movieLogicalFrame();
            sourceClosedLedger = atSourceClose.ledger().stream()
                    .map(DynamicArtGapJournalProbe::toGapDescriptor)
                    .toList();
        }

        private void gapOpened(String segmentDir) {
            if (gapStartMovieLogicalFrame != null) {
                if (!representedSegmentDir.equals(segmentDir)) {
                    throw new AssertionError(
                            "dynamic-art gap changed represented segment from "
                                    + representedSegmentDir + " to " + segmentDir);
                }
                return;
            }
            if (sourceClosedOrdinal == null
                    || !Objects.equals(representedSegmentDir, segmentDir)) {
                throw new AssertionError(
                        "dynamic-art gap opened before source close for " + segmentDir);
            }
            gapOpenedOrdinal = ++structuralOrdinal;
            gapOpenedUnannouncedRows =
                    lifecycle.capture().unannouncedRows();
            if (sourceClosedTransitionCount != null) {
                gapStartMovieLogicalFrame = sourceClosedMovieLogicalFrame;
                openingLedger = sourceClosedLedger;
                transitionCountAtGapStart = sourceClosedTransitionCount;
                lastEdgeOrdinalAtGapStart = sourceClosedLastEdgeOrdinal;
            } else {
                List<DynamicArtGapTransition> atGapStart =
                        lifecycle.gapTransitions();
                gapStartMovieLogicalFrame =
                        lifecycle.capture().movieLogicalFrame();
                openingLedger = lifecycle.capture().ledger().stream()
                        .map(DynamicArtGapJournalProbe::toTraceDescriptor)
                        .toList();
                transitionCountAtGapStart = atGapStart.size();
                lastEdgeOrdinalAtGapStart = lastEdgeOrdinal(atGapStart);
            }
        }

        private void nextSegmentArmed(String nextSegmentDir) {
            if (gapStartMovieLogicalFrame == null) {
                throw new AssertionError(
                        "dynamic-art next segment armed without a structural gap");
            }
            int nextSegmentArmMovieLogicalFrame =
                    lifecycle.capture().movieLogicalFrame();
            int nextSegmentArmUnannouncedRows =
                    lifecycle.capture().unannouncedRows();
            long destinationOpenedOrdinal = ++structuralOrdinal;
            List<DynamicArtGapTransition> afterNextArm =
                    lifecycle.gapTransitions();
            List<DynamicArtGapTransition> added =
                    afterNextArm.size() >= transitionCountAtGapStart
                            ? afterNextArm.subList(
                                    transitionCountAtGapStart,
                                    afterNextArm.size())
                            : List.of();
            added = TraceRunDynamicArtGapJournal.rowsCountedBackFromAdmission(
                    added, nextSegmentArmMovieLogicalFrame,
                    nextSegmentArmUnannouncedRows);
            // The same recovery for the gap's own first row: a frozen cursor
            // reports the gap's END row at both ends of the span.
            int recoveredGapStartMovieLogicalFrame =
                    TraceRunDynamicArtGapJournal.rowCountedBackFromAdmission(
                            gapStartMovieLogicalFrame,
                            nextSegmentArmUnannouncedRows,
                            gapOpenedUnannouncedRows);
            structuralGaps.add(new DynamicArtStructuralGapEvidence(
                    representedSegmentDir,
                    nextSegmentDir,
                    recoveredGapStartMovieLogicalFrame,
                    nextSegmentArmMovieLogicalFrame,
                    transitionCountAtGapStart,
                    lastEdgeOrdinalAtGapStart,
                    afterNextArm.size(),
                    lastEdgeOrdinal(afterNextArm),
                    new TraceRunDynamicArtGapComparator.StructuralOrder(
                            sourceClosedOrdinal,
                            gapOpenedOrdinal,
                            destinationOpenedOrdinal),
                    openingLedger,
                    added));
            representedSegmentDir = null;
            gapStartMovieLogicalFrame = null;
            gapOpenedUnannouncedRows = null;
            sourceClosedOrdinal = null;
            gapOpenedOrdinal = null;
            openingLedger = List.of();
            sourceClosedTransitionCount = null;
            sourceClosedMovieLogicalFrame = null;
            sourceClosedLastEdgeOrdinal = null;
            sourceClosedLedger = null;
        }

        /**
         * Compares every recorded structural gap and returns one attributed
         * failure line per divergent gap.
         *
         * <p>Returns rather than throws so a run enumerates ALL divergent gaps
         * in one pass instead of one per run, and so the caller can report this
         * axis alongside the segment-physics axis. The comparison itself is
         * unchanged and every divergent gap still fails the build.
         */
        private List<String> collectVerificationFailures(TraceRunManifest run) {
            List<String> failures = new ArrayList<>();
            for (int sourceIndex = 0;
                    sourceIndex < structuralGaps.size(); sourceIndex++) {
                DynamicArtStructuralGapEvidence evidence =
                        structuralGaps.get(sourceIndex);
                FrameComparison comparison =
                        TraceRunDynamicArtGapComparator.compare(
                                evidence.gapStartMovieLogicalFrame(),
                                run,
                                sourceIndex,
                                new TraceRunDynamicArtGapComparator.RuntimeGap(
                                        evidence.representedSegmentDir(),
                                        evidence.nextSegmentDir(),
                                        evidence.structuralOrder(),
                                        evidence.openingLedger(),
                                        evidence.transitionsAddedAcrossBoundary()));
                if (comparison.hasError()) {
                    failures.add("[dynamic-art-gap] shared dynamic-art gap"
                            + " comparison failed for "
                            + evidence.representedSegmentDir() + " -> "
                            + evidence.nextSegmentDir() + ": "
                            + comparison.divergentFields());
                }
            }
            return failures;
        }

        /**
         * Compares the run's terminal dynamic-art tail.
         *
         * <p><b>Callers must gate this on the coordinator having actually
         * begun a terminal tail</b> ({@code terminalPlan() != null}), which is
         * the same manifest-declared opt-in the walker and the production
         * launcher use. {@code TraceRunReplayWalker.planTerminalMovieTail}
         * states the contract outright -- "an unspecified endpoint
         * deliberately leaves both tail replay and terminal assertion
         * disabled" -- and {@code
         * TraceSessionLauncher.compareRunTerminalDynamicArtTail} is reachable
         * only from the {@code BeginTerminalTail} action, so a manifest with
         * no {@code expected_movie_end_mode} never compares a tail in
         * production. This probe used to compare unconditionally, which asked
         * the engine for art from movie rows the harness had declined to drive
         * at all: with an unspecified endpoint the coordinator emits {@code
         * CompleteRun} straight after the final segment closes, zero tail rows
         * are stepped, and no production submission can occur. Runs whose
         * manifest does declare an endpoint (currently the two S1 runs) are
         * unaffected and still assert their tail.
         */
        private void verifyTerminal(
                TraceRunManifest run, int movieFrameCount) {
            if (gapStartMovieLogicalFrame == null
                    || sourceClosedOrdinal == null
                    || gapOpenedOrdinal == null) {
                throw new AssertionError(
                        "terminal dynamic-art tail has no open source gap");
            }
            // Mirrors the launcher's terminal tail: the movie ends before this
            // level reaches its main loop, so a transfer still held for the
            // pre-main-loop tail settles at the tail's earliest row.
            lifecycle.releaseUnclaimedPreMainLoopPlayerTransfer();
            int transitionCount = lifecycle.gapTransitions().size();
            List<DynamicArtGapTransition> added = transitionCount
                    >= transitionCountAtGapStart
                    ? lifecycle.gapTransitions().subList(
                            transitionCountAtGapStart, transitionCount)
                    : List.of();
            int sourceIndex = run.segments().size() - 1;
            FrameComparison comparison =
                    TraceRunDynamicArtGapComparator.compareTerminalTail(
                            gapStartMovieLogicalFrame, run, sourceIndex,
                            movieFrameCount,
                            new TraceRunDynamicArtGapComparator.RuntimeTerminalTail(
                                    representedSegmentDir,
                                    sourceClosedOrdinal,
                                    gapOpenedOrdinal,
                                    ++structuralOrdinal,
                                    openingLedger,
                                    added));
            if (comparison.hasError()) {
                terminalFailures.add("[dynamic-art-terminal-tail] shared"
                        + " terminal dynamic-art comparison failed for "
                        + representedSegmentDir + ": "
                        + comparison.divergentFields());
            }
            representedSegmentDir = null;
            gapStartMovieLogicalFrame = null;
            sourceClosedOrdinal = null;
            gapOpenedOrdinal = null;
            openingLedger = List.of();
            sourceClosedTransitionCount = null;
            sourceClosedMovieLogicalFrame = null;
            sourceClosedLastEdgeOrdinal = null;
            sourceClosedLedger = null;
        }

        private DynamicArtGapJournalEvidence evidence(
                List<TraceRunPlaybackCoordinator.Action> coordinatorActions) {
            if (structuralGaps.isEmpty()) {
                throw new AssertionError(
                        "run did not arm a next segment after its first structural gap");
            }
            return new DynamicArtGapJournalEvidence(
                    transitionCountAfterFirstArm,
                    lastEdgeOrdinalAfterFirstArm,
                    structuralGaps,
                    coordinatorActions);
        }

        private static long lastEdgeOrdinal(
                List<DynamicArtGapTransition> transitions) {
            return transitions.isEmpty()
                    ? -1
                    : transitions.getLast().edge().edgeOrdinal();
        }

        /**
         * Mirrors {@code TraceRunDynamicArtGapJournal#toTraceDescriptor}: the
         * descriptor keeps the submission origin the lifecycle recorded, which
         * is what resolves a completion edge's origin during the gap.
         */
        private static DynamicArtTransfer.Descriptor toGapDescriptor(
                DynamicArtGapDiagnosticsSnapshot.Descriptor descriptor) {
            return new DynamicArtTransfer.Descriptor(
                    descriptor.transferId(),
                    descriptor.owner(),
                    descriptor.mappingFrame(),
                    descriptor.submissionOrigin(),
                    descriptor.requests().stream()
                            .map(request -> new DynamicArtTransfer.Request(
                                    request.romSourceAddress(),
                                    request.sourceTileIndex(),
                                    request.ramSourceAddress(),
                                    request.vramDestination(),
                                    request.byteLength()))
                            .toList(),
                    null);
        }

        private static DynamicArtTransfer.Descriptor toTraceDescriptor(
                DynamicArtLifecycleService.Descriptor descriptor) {
            return new DynamicArtTransfer.Descriptor(
                    descriptor.transferId(),
                    descriptor.owner(),
                    descriptor.mappingFrame(),
                    "segment",
                    descriptor.requests().stream()
                            .map(request -> new DynamicArtTransfer.Request(
                                    request.romSourceAddress(),
                                    request.sourceTileIndex(),
                                    request.ramSourceAddress(),
                                    request.vramDestination(),
                                    request.byteLength()))
                            .toList(),
                    null);
        }
    }

    /**
     * Test-side executor for the shared run policy. It samples only production
     * identities and lifecycle generations, then requires every legacy
     * handoff to earn a coordinator admission receipt before a new comparator
     * is allowed to remain attached.
     */
    private static final class HeadlessRunCoordinatorAdapter {
        private final TraceRunManifest run;
        private final TraceRunPlaybackCoordinator coordinator;
        private final List<TraceRunSegmentDescriptor> descriptors;
        private final List<TraceRunPlaybackCoordinator.Action> actions =
                new ArrayList<>();
        private final RunLevelLoadTracker levelLoads;
        private final GameLoop loop;
        private long admittedStepOrdinal;
        private RunPlaybackObservation productionOwnerObservation;
        private int productionOwnerSegmentIndex = -1;
        private long reportedSeamlessAdvanceOrdinal;

        private HeadlessRunCoordinatorAdapter(
                TraceRunManifest run, Bk2Movie movie,
                List<TraceRunSegmentDescriptor> descriptors, GameLoop loop) {
            this.run = run;
            this.loop = loop;
            this.descriptors = List.copyOf(descriptors);
            this.coordinator = TraceRunPlaybackCoordinator.fromDescriptors(
                    run,
                    GameServices.module().getTracePlaybackProfile(),
                    movie.getFrameCount(), descriptors);
            // The eager SegmentPlan coordinator constructor now has no
            // headless-chain caller; Task 7 removes it after its repository-wide
            // caller proof, together with the eager catalog compatibility path.
            this.levelLoads = SessionManager.getCurrentGameplayMode()
                    .runLevelLoads();
            this.levelLoads.prime(GameServices.level());
            this.reportedSeamlessAdvanceOrdinal =
                    levelLoads.seamlessAdvanceOrdinal();
        }

        private void activateInitial(GameMode mode) {
            List<TraceRunPlaybackCoordinator.Action> emitted =
                    coordinator.activateInitialLevel(
                            observation(mode, false, 0, false));
            requireAdmission(emitted, 0, 0);
        }

        private boolean sourceComparatorExhausted(
                LiveTraceComparator comparator) {
            int source = coordinator.currentSegmentIndex();
            return comparator != null
                    && source >= 0
                    && comparator.cursor()
                            >= descriptors.get(source).levelLoopRowCount();
        }

        private void closeCurrent(GameMode mode, boolean publicationComplete) {
            int source = coordinator.currentSegmentIndex();
            if (!publicationComplete) {
                throw new AssertionError(
                        "cannot close represented segment " + source
                                + " before its comparator publication completed");
            }
            RunPlaybackObservation current = observation(mode, true, 0, false);
            RunPlaybackObservation production = productionOwnerSegmentIndex == source
                    && productionOwnerObservation != null
                    ? withProductionOwner(current, productionOwnerObservation)
                    : current;
            List<TraceRunPlaybackCoordinator.Action> emitted =
                    coordinator.afterProduction(production);
            actions.addAll(emitted);
            if (emitted.isEmpty()
                    || !(emitted.getFirst()
                            instanceof TraceRunPlaybackCoordinator.CloseSegment close)
                    || close.segmentIndex() != source) {
                throw new AssertionError(
                        "coordinator did not close represented segment " + source);
            }
        }

        private void beforeProduction(GameMode mode) {
            productionOwnerSegmentIndex = coordinator.currentSegmentIndex();
            productionOwnerObservation = observation(mode, false, 0, false);
        }

        private void afterStep(GameMode mode) {
            admittedStepOrdinal++;
            reportInLevelAdvance();
            RunPlaybackObservation current = observation(mode, false, 0, false);
            RunPlaybackObservation step = productionOwnerSegmentIndex
                    == coordinator.currentSegmentIndex()
                    && productionOwnerObservation != null
                    ? withProductionOwner(current, productionOwnerObservation)
                    : current;
            List<TraceRunPlaybackCoordinator.Action> emitted =
                    coordinator.afterStep(step);
            actions.addAll(emitted);
            if (!emitted.isEmpty()) {
                TraceRunPlaybackCoordinator.Action action = emitted.getFirst();
                if (action instanceof TraceRunPlaybackCoordinator.FailRun failure) {
                    throw new AssertionError(failure.diagnostic());
                }
                throw new AssertionError(
                        "unexpected coordinator action after a headless step: " + action);
            }
        }

        /**
         * Forwards a seamless in-level act advance production applied inside
         * the step that just ran, so the coordinator can keep source ownership
         * across it. The tracker is gameplay-session owned and is written by
         * {@code LevelIterationAdmissionController} on the transition frame.
         */
        private void reportInLevelAdvance() {
            long ordinal = levelLoads.seamlessAdvanceOrdinal();
            if (ordinal == reportedSeamlessAdvanceOrdinal) {
                return;
            }
            reportedSeamlessAdvanceOrdinal = ordinal;
            levelLoads.seamlessAdvance()
                    .ifPresent(coordinator::observeInLevelAdvance);
        }

        private static RunPlaybackObservation withProductionOwner(
                RunPlaybackObservation current,
                RunPlaybackObservation owner) {
            return new RunPlaybackObservation(
                    owner.mode(), current.sharedBk2Cursor(),
                    current.admittedStepOrdinal(), owner.level(),
                    current.initialTitleCardPending(), owner.bonus(),
                    owner.specialStageIndex(), current.productionOpen(),
                    current.currentSegmentExhausted(),
                    current.destinationRowsConsumed(),
                    current.lagOnlySameLevelContinuation(),
                    current.timingScheduleGeneration(),
                    current.dynamicArtGeneration());
        }

        private DestinationAdmissionReceipt admitInterior(
                TraceRunManifest.Transition boundary,
                int observedBk2Frame,
                GameMode mode,
                int rowsConsumed) {
            int destinationIndex = coordinator.currentSegmentIndex() + 1;
            RunBoundarySignal signal = switch (boundary.entryKind()) {
                case "starpost_bonus" -> {
                    var bonus = GameServices.bonusStageOrNull();
                    if (bonus == null
                            || bonus.getActiveType() == BonusStageType.NONE) {
                        throw new AssertionError(
                                "production bonus identity is unavailable at admission");
                    }
                    yield new RunBoundarySignal.BonusRequest(
                            observedBk2Frame, bonus.getActiveType());
                }
                case "giant_ring", "starpost_special" ->
                        new RunBoundarySignal.SpecialStageRequest(
                                observedBk2Frame,
                                GameServices.module().getSpecialStageProvider()
                                        .getCurrentStage());
                default -> throw new AssertionError(
                        "not an interior entry boundary: " + boundary.entryKind());
            };
            coordinator.observeBoundary(signal);
            return requireAdmission(
                    coordinator.beforeAdmission(
                            observation(mode, false, rowsConsumed, false)),
                    destinationIndex,
                    rowsConsumed);
        }

        private DestinationAdmissionReceipt admitLevel(
                TraceRunManifest.Transition boundary,
                int observedBk2Frame,
                GameMode mode,
                int rowsConsumed,
                boolean lagOnlyContinuation,
                RunLevelLoadTracker.Receipt observedLoad) {
            int destinationIndex = coordinator.currentSegmentIndex() + 1;
            if (lagOnlyContinuation) {
                return requireAdmission(
                        coordinator.beforeAdmission(observation(
                                mode, false, rowsConsumed, true)),
                        destinationIndex, rowsConsumed);
            }
            RunLevelLoadTracker.Receipt load = Objects.requireNonNull(
                    observedLoad, "production load receipt");
            if (boundary != null && "stage_exit".equals(boundary.entryKind())) {
                coordinator.observeBoundary(
                        new RunBoundarySignal.StageExit(observedBk2Frame));
            }
            RunPlaybackObservation observed =
                    observation(mode, false, rowsConsumed, false);
            RunBoundarySignal.LevelLoaded loaded =
                    new RunBoundarySignal.LevelLoaded(
                            observedBk2Frame, load.cause(), load.identity());
            List<TraceRunPlaybackCoordinator.Action> emitted =
                    coordinator.beforeLoadedLevelActivation(loaded, observed);
            if (emitted.isEmpty()) {
                emitted = coordinator.beforeAdmission(observed);
            }
            return requireAdmission(
                    emitted, destinationIndex, rowsConsumed);
        }

        /**
         * Offers a level destination for admission without demanding one, so a
         * caller can keep stepping while the coordinator legitimately denies.
         * Returns true only when the coordinator actually admitted.
         */
        boolean tryAdmitLevel(
                TraceRunManifest.Transition boundary,
                int observedBk2Frame,
                GameMode mode,
                int rowsConsumed,
                RunLevelLoadTracker.Receipt observedLoad) {
            int destinationIndex = coordinator.currentSegmentIndex() + 1;
            if (boundary != null && "stage_exit".equals(boundary.entryKind())) {
                coordinator.observeBoundary(
                        new RunBoundarySignal.StageExit(observedBk2Frame));
            }
            RunPlaybackObservation observed =
                    observation(mode, false, rowsConsumed, false);
            RunBoundarySignal.LevelLoaded loaded =
                    new RunBoundarySignal.LevelLoaded(
                            observedBk2Frame, observedLoad.cause(),
                            observedLoad.identity());
            List<TraceRunPlaybackCoordinator.Action> emitted =
                    coordinator.beforeLoadedLevelActivation(loaded, observed);
            if (emitted.isEmpty()) {
                emitted = coordinator.beforeAdmission(observed);
            }
            if (emitted.isEmpty()) {
                return false;
            }
            requireAdmission(emitted, destinationIndex, rowsConsumed);
            return true;
        }

        private DestinationAdmissionReceipt admitPresentationBridge(
                TraceRunManifest.Transition boundary,
                int observedBk2Frame,
                GameMode mode) {
            int destinationIndex = coordinator.currentSegmentIndex() + 1;
            return requireAdmission(
                    coordinator.beforeAdmission(
                            observation(mode, false, 0, false)),
                    destinationIndex, 0);
        }

        private TraceRunReplayWalker.TerminalMovieTailPlan terminalPlan() {
            if (actions.isEmpty()) {
                throw new AssertionError("coordinator emitted no terminal action");
            }
            TraceRunPlaybackCoordinator.Action last = actions.getLast();
            if (last instanceof TraceRunPlaybackCoordinator.BeginTerminalTail tail) {
                return tail.plan();
            }
            if (last instanceof TraceRunPlaybackCoordinator.CompleteRun) {
                return null;
            }
            throw new AssertionError(
                    "coordinator did not choose a terminal plan: " + last);
        }

        private void finishTerminal(GameMode actualMode) {
            List<TraceRunPlaybackCoordinator.Action> emitted =
                    coordinator.finishTerminalTail(actualMode);
            if (!emitted.isEmpty()) {
                actions.addAll(emitted);
                if (!(emitted.getFirst()
                        instanceof TraceRunPlaybackCoordinator.CompleteRun)) {
                    throw new AssertionError(
                            "coordinator rejected terminal mode " + actualMode);
                }
            }
            if (coordinator.phase()
                    != TraceRunPlaybackCoordinator.Phase.COMPLETE) {
                throw new AssertionError(
                        "coordinator did not complete the run: "
                                + coordinator.phase());
            }
        }

        private List<TraceRunPlaybackCoordinator.Action> actions() {
            return List.copyOf(actions);
        }

        private RunLevelLoadTracker.Receipt latestLoadReceipt() {
            return levelLoads.latest().orElseThrow(() -> new AssertionError(
                    "production did not publish a level-load receipt"));
        }

        private DestinationAdmissionReceipt requireAdmission(
                List<TraceRunPlaybackCoordinator.Action> emitted,
                int segmentIndex,
                int rowsConsumed) {
            actions.addAll(emitted);
            if (emitted.size() != 1
                    || !(emitted.getFirst()
                            instanceof TraceRunPlaybackCoordinator.AdmitDestination admit)) {
                throw new AssertionError(
                        "coordinator denied segment " + segmentIndex
                                + " admission in phase " + coordinator.phase());
            }
            DestinationAdmissionReceipt receipt = admit.receipt();
            TraceRunManifest.Segment segment = run.segments().get(segmentIndex);
            if (receipt.segmentIndex() != segmentIndex
                    || receipt.rowsConsumed() != rowsConsumed
                    || receipt.absoluteBk2Row()
                            != segment.bk2FrameOffset() + rowsConsumed) {
                throw new AssertionError(
                        "coordinator admitted the wrong destination row: " + receipt);
            }
            settlePreMainLoopPlayerTransferAtAdmission(receipt);
            return receipt;
        }

        /**
         * Settles a player transfer the level routine staged for its counted
         * pre-main-loop tail, at the same point production settles it
         * ({@code TraceSessionLauncher#settlePreMainLoopPlayerTransferAtAdmission},
         * called from {@code applyRunDestinationAdmission}).
         *
         * <p>An in-run level load stages the player's tiles in the
         * {@code Level_LoadObj} {@code ExecuteObjects} pass
         * (docs/s1disasm/sonic.asm:2895-2897), and the V-int that performs the
         * transfer is the first row of the counted {@code Level_Delay} /
         * {@code PalFadeIn_Alt} tail (docs/s1disasm/sonic.asm:2957-2966). The
         * transfer therefore belongs {@code preLevelMainLoopDelayFrames} rows
         * before the level's first main-loop row — inside the transition gap,
         * not on the destination's first compared row.
         *
         * <p>The chain drove every admission without this call, so the held
         * preparation survived to the destination's first
         * {@code serviceProductionVBlank}, which drops the tail and flushes at
         * its own row. That put the load's transfer inside the destination
         * segment, where the ROM records no edge at all, and left the gap short
         * the two edges the ROM does record there.
         */
        private void settlePreMainLoopPlayerTransferAtAdmission(
                DestinationAdmissionReceipt receipt) {
            DynamicArtLifecycleService lifecycle =
                    SessionManager.getCurrentGameplayMode()
                            .dynamicArtLifecycle();
            if (lifecycle != null && lifecycle.isRunActive()) {
                lifecycle.settlePendingPlayerPreparationBeforeLevelMainLoop(
                        receipt.absoluteBk2Row() - receipt.rowsConsumed());
            }
        }

        private RunPlaybackObservation observation(
                GameMode mode,
                boolean exhausted,
                int rowsConsumed,
                boolean lagOnlyContinuation) {
            var levelManager = GameServices.level();
            Object currentLevel = levelManager.getCurrentLevel();
            RunPlaybackObservation.LevelIdentity levelIdentity =
                    currentLevel == null ? null
                            : new RunPlaybackObservation.LevelIdentity(
                                    levelLoads.generation(),
                                    levelManager.getCurrentZone(),
                                    levelManager.getRomZoneId(),
                                    levelManager.getCurrentAct());
            RunPlaybackObservation.BonusIdentity bonusIdentity = null;
            var bonus = GameServices.bonusStageOrNull();
            if (mode == GameMode.BONUS_STAGE && bonus != null
                    && bonus.getActiveType() != BonusStageType.NONE) {
                bonusIdentity = new RunPlaybackObservation.BonusIdentity(
                        levelManager.getRomZoneId(),
                        levelManager.getCurrentAct(),
                        bonus.getActiveType());
            }
            // The stage identity the recorded segment is cut on, which the
            // live provider stops reporting once the engine's results phase
            // deinitialises it -- see GameLoop#recordedSpecialStageIdentity.
            // Production reads exactly the same accessor through
            // TraceSessionLauncher#getActiveSpecialStageIndex.
            Integer specialStageIndex =
                    RunPlaybackObservation.insideRecordedSpecialStageMode(mode)
                            ? loop.recordedSpecialStageIdentity(mode)
                            : null;
            DynamicArtLifecycleService lifecycle =
                    SessionManager.getCurrentGameplayMode()
                            .dynamicArtLifecycle();
            var lifecycleState = lifecycle.capture();
            long stepOrdinal = admittedStepOrdinal;
            long dynamicGeneration =
                    GameServices.captureDynamicArtDiagnostics()
                            .segmentGeneration();
            return new RunPlaybackObservation(
                    mode,
                    Math.max(0,
                            GameServices.playbackDebug().getCursorFrame()),
                    stepOrdinal,
                    levelIdentity,
                    levelManager.isTitleCardRequested(),
                    bonusIdentity,
                    specialStageIndex,
                    lifecycleState.comparisonSegmentOpen(),
                    exhausted,
                    rowsConsumed,
                    lagOnlyContinuation,
                    Math.max(0, coordinator.currentSegmentIndex()),
                    dynamicGeneration);
        }
    }

    // -------------------------------------------------------------------------
    // Drive
    // -------------------------------------------------------------------------

    /**
     * The whole chain drive — the only method a lane subclass must call. Loads
     * and plans the run, boots segment 0, then walks every segment, awaiting
     * each boundary the engine raises and asserting return-boundary carry-over.
     */
    protected DynamicArtGapJournalEvidence assertChainReplay(Path runDir)
            throws Exception {
        return assertChainReplay(runDir, null);
    }

    protected DynamicArtGapJournalEvidence assertChainReplayThroughSegmentRow(
            Path runDir, int segmentIndex, int committedRows) throws Exception {
        if (segmentIndex < 0 || committedRows <= 0) {
            throw new IllegalArgumentException(
                    "prefix target requires a nonnegative segment and positive row count");
        }
        return assertChainReplay(
                runDir, new ReplayPrefixTarget(segmentIndex, committedRows));
    }

    /**
     * Boots the run <em>at</em> {@code startSegmentIndex} and walks forward from
     * there, instead of walking to it from segment 0.
     *
     * <p>This is the control instrument for "is this axis newly visible, or did
     * my change cause it?". When a change lets the chain reach segments it never
     * reached before, the ordinary chain gives no control arm for those
     * segments: the unchanged code terminates earlier and never executes them,
     * so their axes cannot be attributed either way.
     * {@link #assertChainReplayThroughSegmentRow} does not help -- it replays
     * <em>through</em> a prefix and still walks from segment 0.
     *
     * <p>Booting at a segment is already an exercised path rather than a new
     * one: segment 0 of every run boots at its own non-zero
     * {@code bk2FrameOffset} (860 for {@code s1-sonic-complete-withemeralds}),
     * and every boot-time input is taken from the boot segment's own plan.
     *
     * <p><strong>What it does and does not prove.</strong> A run started at
     * segment K carries none of the engine state segments 0..K-1 would have
     * left. An axis that reproduces identically from a cold start at K is
     * therefore owned by K's own entry conditions and is not carry-in -- and,
     * in particular, is not caused by whatever let the chain arrive there. An
     * axis that does <em>not</em> reproduce is not thereby proven to be
     * carry-in, because the cold start is a different starting state, not the
     * chain's state minus one change.
     */
    protected DynamicArtGapJournalEvidence assertChainReplayFromSegment(
            Path runDir, int startSegmentIndex) throws Exception {
        if (startSegmentIndex < 0) {
            throw new IllegalArgumentException(
                    "start segment must be nonnegative: " + startSegmentIndex);
        }
        return assertChainReplay(runDir, null, startSegmentIndex);
    }


    /**
     * Returns the run as it would read if it began at {@code startSegmentIndex}:
     * that segment becomes segment 0, and every transition and dynamic-art gap
     * that belongs to a dropped segment is dropped with it.
     *
     * <p>Re-basing the manifest rather than teaching the walk to start partway
     * is deliberate. Every index in the replay -- segment indices, the
     * coordinator's transcript, exit-boundary lookups, the gap journal's
     * positional pairing of transitions to dynamic-art gaps -- is relative to
     * the run it was given. Handing the machinery a run that genuinely starts
     * here keeps all of that arithmetic correct by construction, and needs no
     * change in {@code src/main}: the production coordinator still activates
     * "segment 0" and still refuses anything else.
     *
     * <p>Transitions are re-based by subtracting the offset. Dynamic-art gap
     * transitions carry no segment index and are paired to transitions
     * positionally, so they are sliced by the same count.
     */
    private static TraceRunManifest rebaseRunFromSegment(
            TraceRunManifest run, int startSegmentIndex, Path runDir) {
        List<TraceRunManifest.Segment> segments = run.segments();
        assertTrue(
                startSegmentIndex < segments.size(),
                "start segment " + startSegmentIndex + " outside run ("
                        + segments.size() + " segments): " + runDir);
        assertEquals(
                "level", segments.get(startSegmentIndex).kind(),
                "start segment " + startSegmentIndex
                        + " must be a level to boot from: " + runDir);
        int droppedTransitions = 0;
        List<TraceRunManifest.Transition> transitions = new ArrayList<>();
        for (TraceRunManifest.Transition t : run.transitions()) {
            if (t.fromSegment() < startSegmentIndex) {
                droppedTransitions++;
                continue;
            }
            transitions.add(new TraceRunManifest.Transition(
                    t.fromSegment() - startSegmentIndex,
                    t.toSegment() - startSegmentIndex,
                    t.entryKind(), t.modeChangeBk2Frame(),
                    t.specialBonusEntryFlag(), t.savedXPos(), t.savedYPos(),
                    t.lastStarPostHit(), t.ringsBefore(), t.ringsAfter(),
                    t.emeraldsBefore(), t.emeraldsAfter(),
                    t.gapAdmissionRuns()));
        }
        List<DynamicArtTransfer.GapTransition> gaps =
                run.dynamicArtGapTransitions();
        List<DynamicArtTransfer.GapTransition> rebasedGaps =
                droppedTransitions >= gaps.size()
                        ? List.of()
                        : List.copyOf(gaps.subList(droppedTransitions, gaps.size()));
        return new TraceRunManifest(
                run.game(), run.runId(), run.sourceBk2(), run.romChecksum(),
                List.copyOf(segments.subList(startSegmentIndex, segments.size())),
                List.copyOf(transitions),
                rebasedGaps,
                run.expectedMovieEndMode());
    }

    private DynamicArtGapJournalEvidence assertChainReplay(
            Path runDir, ReplayPrefixTarget prefixTarget) throws Exception {
        return assertChainReplay(runDir, prefixTarget, 0);
    }

    private DynamicArtGapJournalEvidence assertChainReplay(
            Path runDir, ReplayPrefixTarget prefixTarget, int startSegmentIndex)
            throws Exception {
        chainAxisFailures.clear();
        // --- Step 1: load + validate manifest, plan segments (manifest-driven) --
        TraceRunManifest run;
        try {
            run = TraceRunManifest.load(runDir.resolve("run_manifest.json"));
        } catch (IOException e) {
            throw new AssertionError("Failed to load run manifest: " + runDir, e);
        }
        if (startSegmentIndex > 0) {
            run = rebaseRunFromSegment(run, startSegmentIndex, runDir);
        }
        List<TraceRunSegmentDescriptor> descriptors;
        try {
            descriptors = TraceRunReplayWalker.planDescriptors(run, runDir);
        } catch (IllegalStateException e) {
            throw new AssertionError("Manifest validation failed for " + runDir, e);
        }
        assertFalse(descriptors.isEmpty(), "Run has no segments: " + runDir);

        // Run-scoped step cap for every await/mode-wait; a frozen cursor fails
        // fast instead of hanging (contract section 4).
        int stepCap = TraceRunReplayWalker.interSegmentStepCap(run);

        TraceRunSegmentDescriptor first = descriptors.get(0);
        assertEquals("level", first.segment().kind(), "First segment must be a level: " + runDir);
        Integer bootZone = first.segment().zoneId();
        Integer manifestBootAct = first.segment().act();
        assertNotNull(bootZone, "First segment must declare a zone_id: " + runDir);
        assertNotNull(manifestBootAct, "First segment must declare an act: " + runDir);
        // Manifest `act` is 1-based (matches TraceMetadata/metadata.json convention,
        // e.g. "act": 1 == Act 1); engine act indices are 0-based (see
        // TraceCatalog#resolveTraceEntry: engineAct = meta.act() - 1, and every
        // standalone lane's AbstractTraceReplayTest#act() override returns 0 for
        // Act 1). Convert once here so the boot call sites below never see the
        // raw manifest value.
        // No game module exists until the ROM bootstrap below, so resolve the
        // first segment through the manifest game id. Subsequent seams use the
        // active module's profile.
        var bootProfile = "s1".equals(run.game())
                ? com.openggf.game.profiles.trace.TracePlaybackProfile.SONIC_1
                : com.openggf.game.profiles.trace.TracePlaybackProfile.DISABLED;
        var bootIdentity = bootProfile.resolveRecordedLevel(bootZone, manifestBootAct);
        Integer bootAct = bootIdentity.act();
        bootZone = bootIdentity.zone();
        // --- Step 2: boot segment 0 ---------------------------------------------
        ActiveSegmentPayload initialPayload;
        try {
            initialPayload = openHeadlessPayload(first, 0);
        } catch (IOException e) {
            throw new AssertionError("Failed to open initial run segment", e);
        }
        try {
            afterInitialHeadlessPayloadOpen.run();
        TraceData trace0 = initialPayload.trace();
        Path bk2Path = resolveRunBk2(runDir, run.sourceBk2());
        Bk2Movie movie = new Bk2MovieLoader().load(bk2Path);

        // Must run before the FIRST HeadlessTestFixture build below (recorded
        // team, cross-game off, S3K intro-skip derived from trace metadata) --
        // not just before driver.start()'s internal loadZoneAndAct. The
        // throwaway build still performs a real team registration + level load
        // via its own bootstrap path; running it against a leftover/default
        // team (e.g. Sonic+Tails) instead of the recorded one would register
        // the wrong sidekick roster before driver.start()'s reset. Configuring
        // the team before EVERY level load -- including the throwaway one --
        // matches the standalone AbstractTraceReplayTest ordering
        // (prepareConfiguration before its one and only fixture build).
        TraceReplaySessionBootstrap.prepareConfiguration(trace0, trace0.metadata());
        boolean recordedHardwareTiming =
                TraceRunReplayWalker.hasDescriptorHardwareTimingStream(
                        descriptors);
        // The eager SegmentPlan timing helpers now have no headless-chain
        // caller. Task 7 removes them only after the whole-repository proof.

        // Mirrors TestPachinkoTitleCardIntegration's engine setup: a
        // HeadlessTestFixture build initializes the headless engine before a real
        // GameLoop is constructed. Its sprite()/gameplayMode() are never read
        // afterward -- TraceReplayDriver.start() performs its own full reset +
        // team registration + level load, so a stale cached sprite reference
        // would desync from the engine's actual roster.
        HeadlessTestFixture.builder()
                .withZoneAndAct(bootZone, bootAct)
                .withHardwareReadinessAdmissionPolicy(
                        recordedHardwareTiming
                                ? HardwareReadinessAdmissionPolicy.RECORDED
                                : HardwareReadinessAdmissionPolicy.LIVE)
                .build();
        InputHandler inputHandler = new InputHandler();
        GameLoop loop = new GameLoop(inputHandler);

        LiveEngineFixture fixture = new LiveEngineFixture(movie);
        TraceReplayDriver driver = new TraceReplayDriver(
                trace0, movie, fixture, loop, fixture::sprite, () -> { },
                recordedHardwareTiming);
        driver.start(bootZone, bootAct);
        int initialComparisonCursor = driver.initialCursor();

        HardwareTimingCoordinator hardwareTiming =
                new HardwareTimingCoordinator(
                        fixture,
                        TraceRunReplayWalker.descriptorHardwareTimingSegments(
                                descriptors),
                        com.openggf.trace.timing.HardwareTimingInterstitialStreamLoader
                                .load(runDir));
        activeHardwareTiming = hardwareTiming;
        GameplayModeContext gameplayMode =
                SessionManager.getCurrentGameplayMode();
        gameplayMode.plcFrameLifecycle()
                .setComparisonSegmentsExternallyManaged(true);
        boolean[] firstDynamicArtWindow = {true};
        var dynamicArtSegments =
                new TraceRunReplayWalker.DynamicArtSegmentController(
                        new TraceRunReplayWalker.DynamicArtSegmentWindow() {
                            @Override
                            public void open() {
                                if (firstDynamicArtWindow[0]) {
                                    firstDynamicArtWindow[0] = false;
                                    gameplayMode.dynamicArtLifecycle()
                                            .serviceProductionVBlank();
                                }
                                gameplayMode.dynamicArtLifecycle()
                                        .openComparisonSegment();
                            }

                            @Override
                            public void close() {
                                // The window may already have closed itself on
                                // the ROM iteration that wrote the
                                // special-stage game mode (LevelManager's
                                // Obj79_Star seam, docs/s2disasm/s2.asm:44877);
                                // that iteration still runs to completion, so
                                // the structural close arrives afterwards.
                                gameplayMode.endDynamicArtComparisonSegment();
                            }
                        });
        // The run's movie spans the first level's load, so the transfer that
        // load staged for the player belongs to the run — unlike a
        // segment-scoped replay, which starts at Level_MainLoop and never owns
        // it. It lands on the row the counted pre-main-loop tail puts it on:
        // the first main-loop row minus preLevelMainLoopDelayFrames.
        gameplayMode.dynamicArtLifecycle()
                .publishInitialLevelLoadPlayerTransfer(
                        first.segment().bk2FrameOffset(),
                        GameServices.module().getLevelInitProfile()
                                .preLevelMainLoopDelayFrames());
        dynamicArtSegments.beginSegment();
        // The initial standalone bootstrap may omit a recorded pre-level prefix.
        // Keep the read-only dynamic-art publication clock on the same first
        // compared row as the comparator/input cursor. This is initial adapter
        // alignment outside the coordinator transcript; later segments must earn
        // their publication generation through ordinary close/gap/open actions.
        gameplayMode.dynamicArtLifecycle()
                .advanceComparisonCursor(initialComparisonCursor);
        DynamicArtGapJournalProbe dynamicArtGapJournal =
                new DynamicArtGapJournalProbe(
                        gameplayMode.dynamicArtLifecycle());
        HeadlessRunCoordinatorAdapter runCoordinator =
                new HeadlessRunCoordinatorAdapter(
                        run, movie, descriptors, loop);
        activeRunCoordinator = runCoordinator;
        Throwable primaryFailure = null;
        boolean prefixReached = false;
        // Hoisted out of the walk so the abort path below can name the segment
        // that was live when the walk failed, and hand its comparator to the
        // report writer. See the catch clause for why that matters.
        LiveTraceComparator activeComparator = driver.comparator();
        int i = 0;
        try {
            PlaybackDebugManager playback = GameServices.playbackDebug();
            RealEngineHooks hooks = new RealEngineHooks(loop);
            BoundaryProbe probe = new BoundaryProbe(hooks);
            probe.setBeforeFrameObserver(hardwareTiming::beginPlaybackFrame);
            // Replace the raw comparator TraceReplayDriver.start() installed with the
            // probe; the probe is the only observer for the rest of the chain,
            // delegating comparison to whichever segment comparator is attached.
            playback.setFrameObserver(probe);
            slotProbeRunId = run.runId();
            attachHeadlessComparator(
                    probe, driver.comparator(), trace0, 0,
                    () -> declareHardwareTimingSegment(0));
            runCoordinator.activateInitial(loop.getCurrentGameMode());
            trace0 = null;
            driver = null;
            initialPayload = null;

            // --- Step 3: walk every segment -------------------------------------
            // The cursor each active segment's comparator was attached at, so the
            // pinned-tail diagnostics can report rows-consumed honestly instead of
            // echoing the current cursor back at itself.
            int activeSegmentInitialCursor = initialComparisonCursor;
            TraceRunSegmentDescriptor uncomparedInteriorSourceLevel = null;
            int uncomparedInteriorSourceVblank = 0;
            i = 0;
            while (i < descriptors.size()) {
            activeReplayPrefixTarget = prefixTarget;
            activeReplaySegmentIndex = i;
            TraceRunSegmentDescriptor seg = descriptors.get(i);
            Object levelAtSegmentStart = GameServices.level().getCurrentLevel();
            TraceRunManifest.Transition exit = seg.exitBoundary();
            boolean last = (i == descriptors.size() - 1);

            if (exit == null) {
                // Last segment, OR a plain level->level boundary (no transition
                // record). Compare through this segment's recorded frames.
                int remainingFrames = TraceRunReplayWalker.remainingSegmentFrames(
                        seg.rowCount(), activeComparator.cursor());
                stepFrames(loop, remainingFrames);
                topUpUnconsumedSegmentRows(
                        loop, activeComparator, seg.rowCount(), stepCap);
                activeComparator.finalizeTerminalDynamicArtComparison();
                requireComparatorComplete(seg, activeComparator);
                dynamicArtSegments.enterGap();
                // Observed after the window actually closes: the close is what
                // records the state the gap opens on, so reading the snapshot
                // first leaves the source's own final edges out of it.
                dynamicArtGapJournal.sourceClosed(seg.segment().dir());
                runCoordinator.closeCurrent(
                        loop.getCurrentGameMode(),
                        runCoordinator.sourceComparatorExhausted(
                                activeComparator));
                maybeWriteReport(run.runId(), i, activeComparator);
                closeHeadlessPayloadOrThrow();
                activeComparator = null;
                dynamicArtGapJournal.gapOpened(seg.segment().dir());
                // Re-arm the production one-row latch that decides which gap
                // rows still belong to the source level's own main loop; the
                // launcher does this from its EnterTransitionGap action.
                TraceSessionLauncher.beginDriverOnlyRunTransitionGap();
                if (last) {
                    productionComparator = null;
                    TraceRunFrameDriver terminalRows =
                            new TraceRunFrameDriver();
                    gameplayMode.installTraceRunFrameDriver(terminalRows);
                    try {
                        replayTerminalMovieTail(
                                runCoordinator.terminalPlan(), loop,
                                inputHandler, movie, playback, fixture,
                                terminalRows, runCoordinator);
                        if (runCoordinator.terminalPlan() != null) {
                            dynamicArtGapJournal.verifyTerminal(
                                    run, movie.getFrameCount());
                        }
                    } finally {
                        gameplayMode.clearTraceRunFrameDriver(terminalRows);
                    }
                    dynamicArtSegments.close();
                    hardwareTiming.close();
                    runCoordinator.finishTerminal(loop.getCurrentGameMode());
                    break;
                }
                TraceRunSegmentDescriptor next = descriptors.get(i + 1);
                if (TraceRunReplayWalker.isLagOnlySameLevelContinuation(
                        seg.segment(), next.segment(), seg.rowCount(),
                        seg.laggedRows().cardinality())) {
                    int sourceTailVblank = TraceRunReplayWalker.sourceTailVblankAtBoundary(
                            seg.segment(), playback.getCursorFrame(),
                            GameServices.level().getObjectManager().getVblaCounter());
                    completeInterLevelVblankBudget(seg, next, 0, sourceTailVblank);
                    OscillationManager.suppressNextFrames(1);
                    runCoordinator.admitLevel(
                            null, playback.getCursorFrame(),
                            loop.getCurrentGameMode(), 0, true, null);
                    int destinationIndex = i + 1;
                    activeComparator = openAndAttachHeadlessPayload(
                            next, destinationIndex,
                            payload -> attachLevelSegment(
                                    playback, probe, movie, next, fixture,
                                    destinationIndex));
                    activeSegmentInitialCursor = cursorOrZero(activeComparator);
                    dynamicArtSegments.beginSegment();
                    dynamicArtGapJournal.nextSegmentArmed(next.segment().dir());
                    i++;
                    continue;
                }
                // Plain level->level: cross the act/zone title-card cycle and
                // rebind onto the next level segment.
                int rowsConsumed = prepareAcrossLevelBoundary(
                        loop, playback, probe, movie, seg, next, stepCap,
                        levelAtSegmentStart);
                admitPlainLevelBoundaryWhenReady(
                        loop, playback, runCoordinator, next, rowsConsumed,
                        stepCap);
                int destinationIndex = i + 1;
                activeComparator = openAndAttachHeadlessPayload(
                        next, destinationIndex,
                        payload -> attachPreparedLevelSegment(
                                playback, probe, movie, next, fixture,
                                rowsConsumed, destinationIndex));
                activeSegmentInitialCursor = cursorOrZero(activeComparator);
                dynamicArtSegments.beginSegment();
                gameplayMode.dynamicArtLifecycle()
                        .advanceComparisonCursor(rowsConsumed);
                dynamicArtGapJournal.nextSegmentArmed(next.segment().dir());
                i++;
                continue;
            }

            BoundaryEntryMode entryMode = TraceRunReplayWalker.boundaryEntryMode(exit.entryKind());
            if (entryMode == BoundaryEntryMode.LEVEL_MODE) {
                TraceRunSegmentDescriptor returnSegment =
                        descriptors.get(i + 1);
                if (TraceRunReplayWalker.isUncomparedInterior(seg.segment())
                        && returnSegment.executionPolicy()
                                == TraceRunReplayWalker.SegmentExecutionPolicy
                                        .LEVEL_PRESENTATION_BRIDGE) {
                    PresentationBridgeResult bridge =
                            replaySpecialStagePresentationBridge(
                                    runDir, run, descriptors, i, loop, inputHandler,
                                    movie, playback, probe, fixture,
                                    hardwareTiming, gameplayMode,
                                    dynamicArtSegments, dynamicArtGapJournal,
                                    runCoordinator, prefixTarget, stepCap,
                                    uncomparedInteriorSourceLevel,
                                    uncomparedInteriorSourceVblank);
                    uncomparedInteriorSourceLevel = null;
                    if (bridge.runComplete()) {
                        break;
                    }
                    activeComparator = bridge.gameplayComparator();
                    activeSegmentInitialCursor = cursorOrZero(activeComparator);
                    i = bridge.gameplaySegmentIndex();
                    continue;
                }
                // This segment is an INTERIOR; its exit (stage_exit) returns to a
                // level. Await the mode==LEVEL poll, assert carry-over, rebind.
                //
                // An uncompared interior (special_stage, SS-INTERIOR POLICY v1) is
                // NOT driven by PlaybackDebugManager's forced-input bridge --
                // GameLoop.isDriving() only forces input for LEVEL/BONUS_STAGE
                // (syncPlaybackInputBridge), and the shared BK2 cursor itself
                // freezes in SPECIAL_STAGE mode (onLevelFrameAdvanced is only
                // called from the LEVEL/BONUS_STAGE tick paths). Left undriven, the
                // special stage runs with a neutral/no-op InputHandler for its
                // whole duration, producing an engine-organic outcome that does not
                // match the recorded run. Feed the SAME recorded BK2 rows this
                // segment was captured from as a logical-input override -- the
                // identical mechanism S1SpecialStageReplayHarness/LiveRewindStepper/
                // SpecialStageStepper/TraceSessionLauncher already use via
                // RecordedInputSnapshots.fromBk2 -- via a segment-local row counter
                // (independent of the frozen shared cursor) so the engine actually
                // replays the special stage the recorded inputs drove. Still
                // comparison-only: the trace's control input is read to drive the
                // engine, exactly like the LEVEL/BONUS_STAGE forced-input path
                // already does; no trace FIELD is ever hydrated into engine state,
                // and no gameplay field comparison happens during this segment
                // (attachInteriorComparator keeps returning null for special_stage).
                // When the trace advertises the optional DPLC heartbeat, the
                // structural row driver compares only that diagnostic channel.
                boolean uncomparedInterior = TraceRunReplayWalker.isUncomparedInterior(seg.segment());
                Runnable stepOneFrame;
                UncomparedInteriorBoundaryDrive uncomparedDrive = null;
                if (uncomparedInterior) {
                    uncomparedDrive = new UncomparedInteriorBoundaryDrive(
                            run.runId(), i, seg, loop, inputHandler, movie,
                            playback, probe, fixture, hardwareTiming,
                            gameplayMode, dynamicArtSegments,
                            dynamicArtGapJournal, runCoordinator, prefixTarget,
                            returnSegment.segment().bk2FrameOffset());
                    stepOneFrame = uncomparedDrive;
                } else {
                    stepOneFrame = () -> stepEngineFrame(loop);
                }
                int returnOffset = descriptors.get(i + 1)
                        .segment().bk2FrameOffset();
                // The shared BK2 cursor is handled OPPOSITELY for the two interior
                // kinds, because they advance it oppositely:
                //
                //  * UNCOMPARED (special_stage): the shared cursor FREEZES at the
                //    interior-ENTRY offset for the whole interior -- SPECIAL_STAGE /
                //    RESULTS / fade / title-card never call onLevelFrameAdvanced -- and
                //    the SS itself is driven by uncomparedInteriorStep's own segment-local
                //    counter. Pre-seek the frozen cursor to the RETURN level segment's
                //    gameplay-unlock offset here so the ONE title-card-exit fall-through
                //    LEVEL frame (updateTitleCardMode releases control and FALLS THROUGH to
                //    LEVEL processing in the SAME loop.step(): GameLoop "Continue to LEVEL
                //    mode processing this frame") reads that segment's recorded frame-0
                //    input instead of the STALE entry-offset row (the star-post touch frame,
                //    which for this run holds a direction press) -- reading the stale row
                //    would accelerate the player one frame from rest and corrupt the return
                //    level's frame-0 physics. framesConsumed then == 1 (the fall-through
                //    frame advanced the seeked cursor by one).
                //
                //  * COMPARED (bonus_stage): the shared cursor is LIVE -- BONUS_STAGE runs
                //    on the level pipeline (GameLoop.updateBonusStageMode) and calls
                //    onLevelFrameAdvanced every frame, so the cursor drives the bonus stage
                //    forward from the interior offset and organically arrives at the return
                //    offset as the stage exits (each recorded segment gap == trace_frame_count
                //    + 1, the +1 being exactly that single fall-through boundary frame). It
                //    must NOT be pre-seeked: startSession(returnOffset) would jump the very
                //    cursor that drives the bonus stage straight to the stage_exit edge
                //    (returnOffset == modeChangeBk2Frame for every stage_exit in this run),
                //    so the first driven bonus frame pushes the cursor PAST the edge and
                //    awaitBoundary returns NOT_OBSERVED before the stage ever completes.
                //    Leaving it live lets the fade/title-card freeze (no onLevelFrameAdvanced)
                //    and the fall-through frame supply the +1, landing the cursor exactly on
                //    returnOffset (framesConsumed == 0).
                //
                // Comparison-only either way: this only positions the INPUT cursor, exactly
                // as handoffIntoInterior/attachLevelSegment already do -- no trace FIELD is
                // hydrated into engine state.
                BoundaryObservation obs =
                        TraceRunReplayWalker.awaitBoundary(probe, exit, stepCap, stepOneFrame);
                stepOneFrame = null;
                if (uncomparedDrive != null) {
                    uncomparedDrive.finishInterstitialRows();
                }
                boolean dynamicArtGapOpened = uncomparedDrive != null
                        && uncomparedDrive.gapOpened();
                boolean interiorCoordinatorSourceClosed = uncomparedDrive != null
                        && uncomparedDrive.sourceClosed();
                if (uncomparedDrive != null) {
                    uncomparedDrive.releasePayloadBackedLocals();
                    uncomparedDrive = null;
                }
                if (activeComparator != null) {
                    completeSourceTailReportingOnFailure(
                            run.runId(), i, loop, activeComparator, seg, stepCap,
                            activeComparator.cursor(), levelAtSegmentStart);
                }
                // Write the interior's comparator report BEFORE asserting the
                // boundary was observed -- a boundary miss is frequently caused
                // by an upstream interior divergence, and without this report a
                // failed assertTrue below would otherwise leave no artifact to
                // triage it from (maybeWriteReport is a no-op for uncompared
                // special-stage interiors).
                if (activeComparator != null) {
                    activeComparator.finalizeTerminalDynamicArtComparison();
                    requireComparatorComplete(seg, activeComparator);
                }
                maybeWriteReport(run.runId(), i, activeComparator);
                if (!dynamicArtGapOpened) {
                    dynamicArtSegments.enterGap();
                    // Observed after the window actually closes: the close is what
                    // records the state the gap opens on, so reading the snapshot
                    // first leaves the source's own final edges out of it.
                    dynamicArtGapJournal.sourceClosed(seg.segment().dir());
                }
                if (!interiorCoordinatorSourceClosed) {
                    runCoordinator.closeCurrent(
                            loop.getCurrentGameMode(), uncomparedInterior
                                    ? dynamicArtGapOpened
                                    : activeComparator != null
                                            && runCoordinator
                                                    .sourceComparatorExhausted(
                                                            activeComparator));
                }
                assertTrue(obs.observed(),
                        "Interior exit boundary (stage_exit) was never observed within the "
                                + "boundary window for " + runDir);
                assertReturnBoundary(descriptors, i, runDir);
                closeHeadlessPayloadOrThrow();
                activeComparator = null;
                dynamicArtGapJournal.gapOpened(seg.segment().dir());
                // Attach the return comparator, keying on interior kind.
                int returnRowsConsumed;
                // True only when the playback cursor ARRIVED at the consumed
                // row by running it, which is the sole footing on which an
                // opening row can be adopted. Production's
                // destinationRowsConsumedForAdmission() is cursor-derived and
                // therefore always organic; OPTION B below re-anchors the
                // cursor past rows the engine never ran, so its "1" names no
                // executed row zero.
                boolean returnCursorArrivedOrganically;
                if (uncomparedInterior) {
                    // Pre-seeked SS interior: its single title-card-exit fall-through
                    // frame consumed the return segment's frame 0 (framesConsumed == 1)
                    // and the cursor is already in lockstep -- attach WITHOUT re-seeking.
                    // Computed BEFORE the anchor because the anchor now consumes it:
                    // the budget runs to the last consumed destination row.
                    int framesConsumed = playback.getCursorFrame() - returnOffset;
                    // The V-blank anchor below consumes framesConsumed, so it is
                    // computed first. uncomparedInteriorReturnVblankBudget now carries
                    // the consumed-row term its sibling interLevelVblankBudget always
                    // had, instead of hardcoding a count of one -- which is what made
                    // the anchor depend on the seam's row layout.
                    if (GameServices.module().getTracePlaybackProfile()
                            .alignUncomparedInteriorReturnVblank()) {
                        if (uncomparedInteriorSourceLevel == null) {
                            throw new AssertionError(
                                    "Uncompared interior return has no source-level clock anchor");
                        }
                        // Scoped to the anchored games ON PURPOSE. The anchor and
                        // attachReturnedLevelSegment's comparator base are one
                        // contract, and the thing that keeps them coherent is that
                        // BOTH consume this same framesConsumed: the comparator bases
                        // at destination row framesConsumed, and the budget targets
                        // returnOffset + framesConsumed - 1, which is the counter
                        // value entering that row. That holds at every non-negative
                        // count, zero included -- a return that hands its host
                        // iteration back rather than fusing the destination's frame 0
                        // into it anchors on the row before frame 0, exactly as the
                        // level_advance admissions do through the arithmetically
                        // identical interLevelVblankBudget. What must NOT happen is a
                        // count that names rows the engine never ran (see OPTION B
                        // below, which re-anchors the cursor and is therefore excluded
                        // from this branch), or a cursor that has left the destination
                        // segment altogether -- either breaks the pairing by making
                        // the comparator's base and the anchor's target disagree about
                        // which row the engine is standing on.
                        //
                        // A game whose profile leaves alignUncomparedInteriorReturnVblank
                        // false runs no anchor here, so it has no pairing at all. S3K
                        // takes GameModule's default TracePlaybackProfile.DISABLED
                        // (GameModule.java:334-336) and reaches its aiz_2 return with
                        // framesConsumed == 0 -- a legitimate second return shape, not
                        // a defect. An earlier version of this assertion sat outside
                        // this guard and aborted the whole S3K chain at segment 1 for
                        // exactly that reason; it was reverted in 34e58af86. Do not
                        // widen it.
                        assertTrue(framesConsumed >= 0
                                        && framesConsumed
                                                <= descriptors.get(i + 1)
                                                        .segment().traceFrameCount(),
                                "Uncompared interior return's organic cursor must lie "
                                        + "within the destination segment before the "
                                        + "comparator attaches (cursor "
                                        + playback.getCursorFrame()
                                        + ", offset " + returnOffset + ", recorded rows "
                                        + descriptors.get(i + 1)
                                                .segment().traceFrameCount()
                                        + ", segment "
                                        + descriptors.get(i + 1)
                                                .segment().dir() + "). The "
                                        + "comparator bases at framesConsumed and the "
                                        + "V-blank anchor targets the counter entering "
                                        + "that same row; outside the segment the two "
                                        + "name different rows and every compared row "
                                        + "is read off by one. For " + runDir);
                        alignUncomparedInteriorReturnVblank(
                                uncomparedInteriorSourceLevel,
                                descriptors.get(i + 1),
                                uncomparedInteriorSourceVblank, framesConsumed);
                        uncomparedInteriorSourceLevel = null;
                    }
                    runCoordinator.admitLevel(
                            exit, obs.observedBk2Frame(),
                            loop.getCurrentGameMode(), framesConsumed, false,
                            runCoordinator.latestLoadReceipt());
                    int destinationIndex = i + 1;
                    activeComparator = openAndAttachHeadlessPayload(
                            returnSegment, destinationIndex,
                            payload -> attachReturnedLevelSegment(
                                    probe, returnSegment, fixture,
                                    framesConsumed, destinationIndex));
                    activeSegmentInitialCursor = cursorOrZero(activeComparator);
                    returnRowsConsumed = framesConsumed;
                    returnCursorArrivedOrganically = true;
                } else {
                    // OPTION B (bonus interior): the engine's bonus-exit sequence is
                    // shorter than the recorded post-catch BONUS_STAGE tail, and ~80 of
                    // those recorded rows are the ROM's clearRAM/level-reload frames that
                    // the engine performs synchronously (loadZoneAndAct is one frame) --
                    // so the cursor cannot organically reach returnOffset (see
                    // docs/S3K_KNOWN_DISCREPANCIES.md, gumball exit choreography). The
                    // BONUS->LEVEL title-card-exit fall-through already ran the return
                    // segment's frame 0. Re-anchor the cursor to returnOffset+1 and
                    // compare the return level from frame 1. Input-cursor alignment only.
                    playback.startSession(movie, returnOffset + 1);
                    runCoordinator.admitLevel(
                            exit, obs.observedBk2Frame(),
                            loop.getCurrentGameMode(), 1, false,
                            runCoordinator.latestLoadReceipt());
                    int destinationIndex = i + 1;
                    activeComparator = openAndAttachHeadlessPayload(
                            returnSegment, destinationIndex,
                            payload -> attachReturnedLevelSegment(
                                    probe, returnSegment, fixture, 1,
                                    destinationIndex));
                    activeSegmentInitialCursor = cursorOrZero(activeComparator);
                    returnRowsConsumed = 1;
                    returnCursorArrivedOrganically = false;
                }
                dynamicArtSegments.beginSegment();
                if (returnRowsConsumed == 1 && returnCursorArrivedOrganically) {
                    // The interior's fall-through iteration already ran the
                    // return segment's row zero; adopt it rather than skipping
                    // past it, so its art is stamped and compared as segment
                    // work instead of staying gap-resident.
                    gameplayMode.dynamicArtLifecycle()
                            .adoptGapResidentOpeningRow();
                    activeComparator.compareAdoptedOpeningRow(0,
                            gameplayMode.dynamicArtLifecycle()
                                    .latestSnapshot());
                } else {
                    gameplayMode.dynamicArtLifecycle()
                            .advanceComparisonCursor(returnRowsConsumed);
                }
                dynamicArtGapJournal.nextSegmentArmed(
                        returnSegment.segment().dir());
                i++;
            } else if (entryMode == BoundaryEntryMode.LEVEL_LOAD) {
                TraceRunSegmentDescriptor next = descriptors.get(i + 1);
                RunLevelLoadTracker.Receipt[] observedLoad = {null};
                // The source comparison segment closes when the run movie clock
                // LEAVES the source segment's manifest-declared recorded coverage
                // (bk2_frame_offset + trace_frame_count) -- exactly the predicate
                // TraceRunDynamicArtGapComparator.gapSlice partitions the EXPECTED
                // slice on, and exactly what the special-stage interior branch
                // above already does at rowDriver.isComplete(). Closing on the
                // observed level-load boundary instead left every production
                // iteration between coverage exhaustion and that boundary inside
                // the comparison window, so transfers submitted there were
                // buffered as segment edges rather than gap edges. Manifest
                // structure only: no frame index, zone, route or measured value.
                boolean[] sourceArtWindowClosed = {false};
                LiveTraceComparator[] sourceComparator = {activeComparator};
                String sourceRunId = run.runId();
                int sourceSegmentIndex = i;
                Runnable closeSourceArtWindow = () -> {
                    if (sourceArtWindowClosed[0]
                            || !runCoordinator.sourceComparatorExhausted(
                                    sourceComparator[0])) {
                        return;
                    }
                    LiveTraceComparator closing = sourceComparator[0];
                    closing.finalizeTerminalDynamicArtComparison();
                    requireComparatorComplete(seg, closing);
                    try {
                        maybeWriteReport(
                                sourceRunId, sourceSegmentIndex, closing);
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "failed to write completed source report", e);
                    }
                    dynamicArtSegments.enterGap();
                    // Observed after the window actually closes: the close is what
                    // records the state the gap opens on, so reading the snapshot
                    // first leaves the source's own final edges out of it.
                    dynamicArtGapJournal.sourceClosed(seg.segment().dir());
                    sourceArtWindowClosed[0] = true;
                    closeHeadlessPayloadOrThrow();
                    sourceComparator[0] = null;
                };
                closeSourceArtWindow.run();
                BoundaryObservation obs = TraceRunReplayWalker.awaitBoundary(
                        probe, exit, stepCap, () -> {
                            // ROM Pal_FadeToBlack spends its fade inside the
                            // Level: load path, and every one of its iterations
                            // is a counted V-blank: "move.w #$15,d4" then
                            // "bsr.w WaitForVint" per pass
                            // (docs/s2disasm/s2.asm:3370-3383, called from
                            // Level: at :4765). Those 22 passes therefore
                            // consume 22 movie rows before the title card is
                            // created at :4912. This boundary wait used to step
                            // the engine through them with the shared movie
                            // clock frozen, so the destination's title card and
                            // every art edge after it were stamped 22 rows
                            // early. The predicate is the fade's own liveness,
                            // not a zone, route or frame index.
                            int rowBeingPlayed = playback.getCursorFrame();
                            com.openggf.graphics.FadeManager boundaryFade =
                                    GameServices.fadeOrNull();
                            // Only rows past the source segment's declared
                            // recorded coverage are the gap's to spend; a fade
                            // that starts while the source comparator still has
                            // rows to consume would otherwise skip them.
                            boolean fadeVblankRow = sourceArtWindowClosed[0]
                                    && boundaryFade != null && boundaryFade.isActive();
                            var fadeObjects =
                                    GameServices.level().getObjectManager();
                            int fadeVblank = fadeObjects.getVblaCounter();
                            stepEngineFrame(loop);
                            if (fadeVblankRow) {
                                // Every one of Pal_FadeToBlack's passes is a
                                // V-int that reaches VintRet; see
                                // serviceSuppressedRowVint.
                                serviceSuppressedRowVint(
                                        fadeObjects, fadeVblank);
                                playback.onLevelFrameAdvanced();
                            }
                            latchSourceTailVblank(seg.segment(), rowBeingPlayed);
                            closeSourceArtWindow.run();
                            if (isNewActiveLevelSegment(
                                    next, levelAtSegmentStart)) {
                                RunLevelLoadTracker.Receipt receipt =
                                        runCoordinator.latestLoadReceipt();
                                observedLoad[0] = receipt;
                                probe.observeSignal(
                                        new RunBoundarySignal.LevelLoaded(
                                                playback.getCursorFrame(),
                                                receipt.cause(),
                                                receipt.identity()));
                            }
                        });
                // Normally already closed inside the boundary wait, the moment
                // recorded coverage was exhausted; this covers a source whose
                // comparator only exhausts in the pinned tail above.
                if (!sourceArtWindowClosed[0]) {
                    completeSourceTailReportingOnFailure(
                            run.runId(), i, loop, sourceComparator[0], seg,
                            stepCap, activeSegmentInitialCursor,
                            levelAtSegmentStart);
                }
                closeSourceArtWindow.run();
                runCoordinator.closeCurrent(
                        loop.getCurrentGameMode(), sourceArtWindowClosed[0]);
                assertTrue(obs.observed(),
                        "Segment " + i + " (" + seg.segment().dir()
                                + ") semantic level-load boundary ("
                                + exit.entryKind()
                                + ") was never observed within the boundary window for "
                                + runDir);
                closeHeadlessPayloadOrThrow();
                activeComparator = null;
                dynamicArtGapJournal.gapOpened(seg.segment().dir());
                int semanticSourceTailVblank =
                        observedSourceTailVblank(seg.segment(), playback);
                int prepared = prepareAcrossLevelBoundary(
                        loop, playback, probe, movie, seg, next, stepCap,
                        levelAtSegmentStart);
                int rowsConsumed = admitLevelWhenReady(
                        gameplayMode, loop, playback, runCoordinator, exit, obs, next,
                        prepared, stepCap,
                        Objects.requireNonNull(observedLoad[0],
                                "production level-load receipt was not observed"),
                        i, runDir);
                // The admission wait above runs engine frames the manifest
                // budget has already accounted for. Reconcile once more, on the
                // same source anchor and with the rows the destination really
                // consumed, so those choreography frames cannot leave the
                // destination clock adrift -- the same second-pass reconcile
                // prepareAcrossLevelBoundary already performs after its own
                // title-card settle. Movie-clock pacing only; no trace field is
                // read into engine state.
                completeInterLevelVblankBudget(
                        seg, next, rowsConsumed, semanticSourceTailVblank);
                int destinationIndex = i + 1;
                activeComparator = openAndAttachHeadlessPayload(
                        next, destinationIndex,
                        payload -> attachPreparedLevelSegment(
                                playback, probe, movie, next, fixture,
                                rowsConsumed, destinationIndex));
                activeSegmentInitialCursor = cursorOrZero(activeComparator);
                dynamicArtSegments.beginSegment();
                gameplayMode.dynamicArtLifecycle()
                        .advanceComparisonCursor(rowsConsumed);
                dynamicArtGapJournal.nextSegmentArmed(next.segment().dir());
                i++;
            } else {
                // This segment is a LEVEL; its exit is an ENTRY boundary into the
                // interior at i+1. Await the transient entry request, then hand
                // off into the interior mode.
                TraceRunSegmentDescriptor interior = descriptors.get(i + 1);
                boolean anchorUncomparedInterior =
                        TraceRunReplayWalker.isUncomparedInterior(interior.segment())
                                && GameServices.module().getTracePlaybackProfile()
                                        .alignUncomparedInteriorReturnVblank();
                BoundaryObservation obs =
                        TraceRunReplayWalker.awaitBoundary(
                                probe, exit, stepCap, () -> stepEngineFrame(loop));
                completeSourceTailReportingOnFailure(
                        run.runId(), i, loop, activeComparator, seg, stepCap,
                        activeSegmentInitialCursor, levelAtSegmentStart);
                // Report BEFORE asserting -- see the stage_exit branch above for
                // why: a level segment's own interior divergence is the usual
                // cause of a missed entry boundary, and this is the only report
                // this segment's comparator will ever get if the assert throws.
                activeComparator.finalizeTerminalDynamicArtComparison();
                requireComparatorComplete(seg, activeComparator);
                dynamicArtSegments.enterGap();
                // Observed after the window actually closes: the close is what
                // records the state the gap opens on, so reading the snapshot
                // first leaves the source's own final edges out of it.
                dynamicArtGapJournal.sourceClosed(seg.segment().dir());
                runCoordinator.closeCurrent(
                        loop.getCurrentGameMode(),
                        runCoordinator.sourceComparatorExhausted(
                                activeComparator));
                maybeWriteReport(run.runId(), i, activeComparator);
                assertTrue(obs.observed(), "Segment " + i + " (" + seg.segment().dir()
                        + ") exit boundary (" + exit.entryKind()
                        + ") was never observed within the boundary window for " + runDir);
                if (anchorUncomparedInterior) {
                    uncomparedInteriorSourceLevel = seg;
                    uncomparedInteriorSourceVblank =
                            TraceRunReplayWalker.sourceTailVblankAtBoundary(
                                    seg.segment(), obs.observedBk2Frame(),
                                    GameServices.level().getObjectManager().getVblaCounter());
                }
                closeHeadlessPayloadOrThrow();
                activeComparator = null;
                dynamicArtGapJournal.gapOpened(seg.segment().dir());
                int rowsConsumed = prepareIntoInterior(
                        loop, playback, probe, movie, interior, stepCap);
                runCoordinator.admitInterior(
                        exit, obs.observedBk2Frame(),
                        loop.getCurrentGameMode(), rowsConsumed);
                int destinationIndex = i + 1;
                activeComparator = openAndAttachHeadlessPayload(
                        interior, destinationIndex,
                        payload -> attachPreparedInterior(
                                probe, interior, fixture, rowsConsumed,
                                destinationIndex));
                activeSegmentInitialCursor = cursorOrZero(activeComparator);
                dynamicArtSegments.beginSegment();
                gameplayMode.dynamicArtLifecycle()
                        .advanceComparisonCursor(rowsConsumed);
                dynamicArtGapJournal.nextSegmentArmed(interior.segment().dir());
                i++;
            }
            }
            dynamicArtSegments.close();
            hardwareTiming.close();
        } catch (ReplayPrefixReached reached) {
            prefixReached = true;
            hardwareTiming.abort();
        } catch (Exception | Error failure) {
            // Recorded, not rethrown here, so the gap-ledger axis below is
            // still evaluated and reported alongside the walk failure. The
            // failure is rethrown (or attached to the combined report) at the
            // end of this method; nothing is swallowed.
            primaryFailure = failure;
            writeAbortedSegmentReport(
                    run.runId(), i, descriptors, activeComparator, failure);
        } finally {
            activeRunCoordinator = null;
            productionComparator = null;
            activeReplayPrefixTarget = null;
            activeReplaySegmentIndex = -1;
            primaryFailure = detachAndCloseHeadlessPayload(primaryFailure);
            try {
                dynamicArtSegments.close();
            } catch (RuntimeException | Error closeFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
            try {
                hardwareTiming.close();
            } catch (RuntimeException | Error closeFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(closeFailure);
                } else {
                    throw closeFailure;
                }
            }
        }
        // --- Step 4: evaluate EVERY axis, then fail once ------------------------
        // The gap-ledger comparison is reached whatever happened above: a
        // segment physics divergence or a walk failure no longer aborts before
        // it. A prefix-target run stops deliberately mid-chain, so its
        // structural gaps are incomplete and are not compared -- unchanged
        // behaviour.
        List<String> gapFailures = new ArrayList<>();
        if (!prefixReached) {
            gapFailures.addAll(
                    dynamicArtGapJournal.collectVerificationFailures(run));
        }
        gapFailures.addAll(dynamicArtGapJournal.terminalFailures);
        List<String> axisFailures = new ArrayList<>(chainAxisFailures);
        axisFailures.addAll(gapFailures);
        try {
            writeChainGapReport(run.runId(), dynamicArtGapJournal, gapFailures);
        } catch (IOException reportFailure) {
            axisFailures.add("[gap-report-io] failed to write the dynamic-art"
                    + " gap report: " + reportFailure);
        }
        if (!axisFailures.isEmpty() || primaryFailure != null) {
            if (axisFailures.isEmpty()) {
                if (primaryFailure instanceof Error error) {
                    throw error;
                }
                throw (Exception) primaryFailure;
            }
            StringBuilder message = new StringBuilder("chain replay of ")
                    .append(run.runId())
                    .append(" failed on ")
                    .append(axisFailures.size()
                            + (primaryFailure == null ? 0 : 1))
                    .append(" axis/axes:");
            if (primaryFailure != null) {
                message.append("\n  - [walk-failure] ")
                        .append(primaryFailure);
            }
            for (String failure : axisFailures) {
                message.append("\n  - ").append(failure);
            }
            AssertionError combined = new AssertionError(message.toString());
            if (primaryFailure != null) {
                combined.addSuppressed(primaryFailure);
            }
            throw combined;
        }
        return dynamicArtGapJournal.evidence(runCoordinator.actions());
        } catch (Exception | Error setupOrReplayFailure) {
            Throwable failure = detachAndCloseHeadlessPayload(
                    setupOrReplayFailure);
            if (failure instanceof Error error) {
                throw error;
            }
            throw (Exception) failure;
        }
    }

    private record ReplayPrefixTarget(int segmentIndex, int committedRows) {
    }

    private record PresentationBridgeResult(
            boolean runComplete,
            int gameplaySegmentIndex,
            LiveTraceComparator gameplayComparator) {
    }

    private static final class ReplayPrefixReached extends RuntimeException {
        private ReplayPrefixReached() {
            super(null, null, false, false);
        }
    }

    final ActiveSegmentPayload openHeadlessPayload(
            TraceRunSegmentDescriptor descriptor, int segmentIndex)
            throws IOException {
        if (activeSegmentPayload != null) {
            throw new IllegalStateException(
                    "cannot open headless run segment " + segmentIndex
                            + " while another payload is active");
        }
        ActiveSegmentPayload opened =
                activeSegmentFactory.open(descriptor, segmentIndex);
        if (opened == null) {
            throw new IllegalStateException(
                    "active segment factory returned null for " + segmentIndex);
        }
        try {
            if (opened.descriptor() != descriptor) {
                throw new IllegalStateException(
                        "active segment factory returned the wrong descriptor for "
                                + segmentIndex);
            }
            activeSegmentPayload = opened;
            return opened;
        } catch (RuntimeException | Error failure) {
            try {
                activeSegmentFactory.close(opened);
            } catch (RuntimeException | Error closeFailure) {
                if (closeFailure != failure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    final <T> T openAndAttachHeadlessPayload(
            TraceRunSegmentDescriptor descriptor,
            int segmentIndex,
            HeadlessPayloadAttachment<T> attachment) throws Exception {
        ActiveSegmentPayload payload = openHeadlessPayload(
                descriptor, segmentIndex);
        try {
            return attachment.attach(payload);
        } catch (Exception | Error attachmentFailure) {
            Throwable failure = detachAndCloseHeadlessPayload(
                    attachmentFailure);
            if (failure instanceof Error error) {
                throw error;
            }
            throw (Exception) failure;
        }
    }

    final boolean hasActivePayload() {
        return activeSegmentPayload != null;
    }

    private ActiveSegmentPayload requireActivePayload(
            TraceRunSegmentDescriptor descriptor) {
        ActiveSegmentPayload payload = activeSegmentPayload;
        if (payload == null) {
            throw new IllegalStateException(
                    "run segment " + descriptor.segment().dir()
                            + " has no active payload");
        }
        if (payload.descriptor() != descriptor) {
            throw new IllegalStateException(
                    "active payload does not own run segment "
                            + descriptor.segment().dir());
        }
        return payload;
    }

    private TraceData activeTrace(TraceRunSegmentDescriptor descriptor) {
        return requireActivePayload(descriptor).trace();
    }

    private TraceRunSpecialStageRows activeSpecialRows(
            TraceRunSegmentDescriptor descriptor) {
        return requireActivePayload(descriptor).specialStageRows();
    }

    final void installHeadlessPayloadAliases(
            BoundaryProbe probe,
            LiveTraceComparator comparator,
            TraceStructuralRowComparator structural,
            TraceRunSpecialStageRowDriver specialDriver,
            TraceRunSpecialStageRows specialRows,
            SpecialStageRunObjectsPassBinder specialPassBinder,
            TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt) {
        activeBoundaryProbe = probe;
        activeHeadlessComparator = comparator;
        activeStructuralComparator = structural;
        activeSpecialDriver = specialDriver;
        activeSpecialRows = specialRows;
        activeSpecialPassBinder = specialPassBinder;
        activeDynamicArtComparison = dynamicArt;
        if (comparator != null) {
            productionComparator = comparator;
        }
    }

    final LiveTraceComparator attachHeadlessComparator(
            BoundaryProbe probe,
            LiveTraceComparator comparator,
            TraceData trace,
            int segmentIndex,
            Runnable timingAttachment) {
        installHeadlessPayloadAliases(
                probe, comparator, null, null, null, null, null);
        try {
            probe.setDelegate(comparator);
            timingAttachment.run();
            return installProductionComparator(
                    comparator, trace, segmentIndex);
        } catch (RuntimeException | Error attachmentFailure) {
            Throwable failure = detachAndCloseHeadlessPayload(
                    attachmentFailure);
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw (Error) failure;
        }
    }

    final void finishHeadlessBoundary(HeadlessBoundaryAction action) {
        Throwable failure = null;
        try {
            action.run();
        } catch (RuntimeException | Error boundaryFailure) {
            failure = boundaryFailure;
        } finally {
            failure = detachAndCloseHeadlessPayload(failure);
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException(failure);
        }
    }

    final Throwable detachAndCloseHeadlessPayload(Throwable primary) {
        ActiveSegmentPayload payload = activeSegmentPayload;
        Throwable failure = primary;
        if (payloadReleaseAudit != null) {
            payloadReleaseAudit.capture(
                    payload, activeStructuralComparator,
                    activeSpecialDriver, activeSpecialRows,
                    activeSpecialPassBinder, activeDynamicArtComparison);
        }
        if (activeBoundaryProbe != null) {
            try {
                activeBoundaryProbe.detachDelegate();
            } catch (RuntimeException | Error cleanupFailure) {
                failure = suppressOrPromote(failure, cleanupFailure);
            }
        }
        productionComparator = null;
        activeHeadlessComparator = null;
        activeStructuralComparator = null;
        activeSpecialDriver = null;
        activeSpecialRows = null;
        activeSpecialPassBinder = null;
        activeDynamicArtComparison = null;
        activeBoundaryProbe = null;
        try {
            closeSlotOccupancyProbe();
        } catch (RuntimeException | Error cleanupFailure) {
            failure = suppressOrPromote(failure, cleanupFailure);
        }
        activeSegmentPayload = null;
        if (payload != null) {
            try {
                activeSegmentFactory.close(payload);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = suppressOrPromote(failure, cleanupFailure);
            }
        }
        if (payloadReleaseAudit != null) {
            try {
                payloadReleaseAudit.assertCapturedReleased();
            } catch (RuntimeException | Error cleanupFailure) {
                failure = suppressOrPromote(failure, cleanupFailure);
            }
        }
        return failure;
    }

    private void closeHeadlessPayloadOrThrow() {
        finishHeadlessBoundary(() -> { });
    }

    private static Throwable suppressOrPromote(
            Throwable primary, Throwable cleanupFailure) {
        if (primary != null) {
            if (cleanupFailure != primary) {
                primary.addSuppressed(cleanupFailure);
            }
            return primary;
        }
        return cleanupFailure;
    }

    private static final class HeadlessPayloadReleaseAudit {
        private ActiveSegmentPayload lease;
        private List<WeakReference<?>> captured = List.of();

        private void capture(
                ActiveSegmentPayload activeLease,
                TraceStructuralRowComparator structural,
                TraceRunSpecialStageRowDriver specialDriver,
                TraceRunSpecialStageRows specialRows,
                SpecialStageRunObjectsPassBinder specialPassBinder,
                TraceRunReplayWalker.DynamicArtSegmentComparison dynamicArt) {
            if (activeLease == null) {
                return;
            }
            assertNoCapturedAliases();
            lease = activeLease;
            List<WeakReference<?>> aliases = new ArrayList<>();
            add(aliases, structural);
            add(aliases, specialDriver);
            add(aliases, specialRows);
            add(aliases, specialPassBinder);
            add(aliases, dynamicArt);
            captured = List.copyOf(aliases);
        }

        private static void add(
                List<WeakReference<?>> aliases, Object referent) {
            if (referent == null) {
                return;
            }
            WeakReference<Object> reference = new WeakReference<>(referent);
            assertNotNull(reference.get(),
                    "payload alias must be reachable before cleanup");
            aliases.add(reference);
        }

        private void assertCapturedReleased() {
            if (lease == null) {
                return;
            }
            for (WeakReference<?> reference : captured) {
                awaitCollected(reference);
            }
            Reference.reachabilityFence(lease);
            lease = null;
            captured = List.of();
        }

        private void assertNoCapturedAliases() {
            assertNull(lease, "closed lease audit");
            assertTrue(captured.isEmpty(), "closed payload alias audit");
        }

        private static void awaitCollected(WeakReference<?> reference) {
            for (int attempt = 0;
                    attempt < 80 && reference.get() != null;
                    attempt++) {
                System.gc();
                System.runFinalization();
                byte[] pressure = new byte[64 * 1024];
                pressure[0] = 1;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                            "interrupted while auditing payload release", e);
                }
            }
            assertNull(reference.get(),
                    "payload alias remained reachable after lease close");
        }
    }

    final void assertHeadlessPayloadAliasesCleared() {
        assertNull(activeSegmentPayload, "active lease");
        assertNull(activeBoundaryProbe, "boundary probe");
        assertNull(productionComparator, "production comparator");
        assertNull(activeHeadlessComparator, "active comparator");
        assertNull(activeStructuralComparator, "structural comparator");
        assertNull(activeSpecialDriver, "special driver");
        assertNull(activeSpecialRows, "special rows");
        assertNull(activeSpecialPassBinder, "special pass binder");
        assertNull(activeDynamicArtComparison, "dynamic-art comparison");
        assertNull(slotOccupancyProbe, "slot probe");
    }

    protected final void assertHeadlessPayloadReleased() {
        assertHeadlessPayloadAliasesCleared();
        if (payloadReleaseAudit != null) {
            payloadReleaseAudit.assertNoCapturedAliases();
        }
    }

    protected final void auditHeadlessPayloadReleaseAtBoundaries() {
        payloadReleaseAudit = new HeadlessPayloadReleaseAudit();
    }

    private final class UncomparedInteriorBoundaryDrive implements Runnable {
        private final String runId;
        private final int segmentIndex;
        private final TraceRunSegmentDescriptor segment;
        private final GameLoop loop;
        private final PlaybackDebugManager playback;
        private final LiveEngineFixture fixture;
        private final HardwareTimingCoordinator hardwareTiming;
        private final GameplayModeContext gameplayMode;
        private final TraceRunReplayWalker.DynamicArtSegmentController
                dynamicArtSegments;
        private final DynamicArtGapJournalProbe dynamicArtGapJournal;
        private final HeadlessRunCoordinatorAdapter runCoordinator;
        private final ReplayPrefixTarget prefixTarget;
        private final List<UncomparedInteriorPhysicalRow> physicalRows;
        private final int destinationOffset;
        private int physicalRowIndex;
        private TraceRunSpecialStageRows specialRows;
        private TraceRunSpecialStageRowDriver rowDriver;
        private SpecialStageRunObjectsPassBinder passBinder;
        private IntConsumer driveInterior;
        private boolean gapOpened;
        private boolean sourceClosed;

        private UncomparedInteriorBoundaryDrive(
                String runId,
                int segmentIndex,
                TraceRunSegmentDescriptor segment,
                GameLoop loop,
                InputHandler inputHandler,
                Bk2Movie movie,
                PlaybackDebugManager playback,
                BoundaryProbe probe,
                LiveEngineFixture fixture,
                HardwareTimingCoordinator hardwareTiming,
                GameplayModeContext gameplayMode,
                TraceRunReplayWalker.DynamicArtSegmentController
                        dynamicArtSegments,
                DynamicArtGapJournalProbe dynamicArtGapJournal,
                HeadlessRunCoordinatorAdapter runCoordinator,
                ReplayPrefixTarget prefixTarget,
                int destinationOffset) {
            this.runId = runId;
            this.segmentIndex = segmentIndex;
            this.segment = segment;
            this.loop = loop;
            this.playback = playback;
            this.fixture = fixture;
            this.hardwareTiming = hardwareTiming;
            this.gameplayMode = gameplayMode;
            this.dynamicArtSegments = dynamicArtSegments;
            this.dynamicArtGapJournal = dynamicArtGapJournal;
            this.runCoordinator = runCoordinator;
            this.prefixTarget = prefixTarget;
            this.destinationOffset = destinationOffset;
            specialRows = activeSpecialRows(segment);
            assertEquals(segment.segment().traceFrameCount(),
                    specialRows.rowCount(),
                    "special-stage row policy must cover the represented segment");
            rowDriver = new TraceRunSpecialStageRowDriver(
                    specialRows, activeTrace(segment));
            passBinder = specialRows.newRunObjectsPassBinder().orElse(null);
            installHeadlessPayloadAliases(
                    probe, null, null, rowDriver, specialRows,
                    passBinder, null);
            driveInterior = uncomparedInteriorStep(
                    loop, inputHandler, movie, segment, specialRows,
                    passBinder);
            physicalRows = uncomparedInteriorPhysicalRows(
                    segment.segment().bk2FrameOffset(), specialRows.rowCount(),
                    destinationOffset,
                    expandGapAdmissionCensus(segment.exitBoundary()));
        }

        @Override
        public void run() {
            try {
                if (rowDriver != null) {
                    driveRepresentedRow();
                    if (rowDriver != null && rowDriver.isComplete()) {
                        completeRepresentedRows();
                        releasePayloadBackedLocals();
                        dynamicArtSegments.enterGap();
                        dynamicArtGapJournal.sourceClosed(
                                segment.segment().dir());
                        runCoordinator.closeCurrent(
                                loop.getCurrentGameMode(), true);
                        sourceClosed = true;
                        finishHeadlessBoundary(() -> { });
                        dynamicArtGapJournal.gapOpened(
                                segment.segment().dir());
                        gapOpened = true;
                    }
                    return;
                }
                fixture.enterHardwareTimingGap();
                if (!gapOpened) {
                    dynamicArtSegments.enterGap();
                    dynamicArtGapJournal.gapOpened(
                            segment.segment().dir());
                    gapOpened = true;
                }
                driveInterstitialRow();
            } catch (RuntimeException | Error failure) {
                releasePayloadBackedLocals();
                throw failure;
            }
        }

        private void driveRepresentedRow() {
            if (!insideRecordedSpecialStageGameMode(
                    loop.getCurrentGameMode())) {
                throw new AssertionError(
                        "special stage exited with "
                                + (segment.segment().traceFrameCount()
                                - rowDriver.cursor())
                                + " represented rows remaining in "
                                + segment.segment().dir());
            }
            UncomparedInteriorPhysicalRow physicalRow = nextPhysicalRow(true);
            int representedRow = rowDriver.cursor();
            assertEquals(segment.segment().bk2FrameOffset() + representedRow,
                    physicalRow.movieRow(),
                    "represented special-stage row must retain its movie row");
            TraceRunFrameDriver driver = new TraceRunFrameDriver();
            gameplayMode.installTraceRunFrameDriver(driver);
            try {
                driver.execute(
                        new TraceRunFrameDriver.Step(
                                TraceRunFrameDriver.Disposition.SPECIAL_LOCAL,
                                physicalRow.movieRow(),
                                representedRow == specialRows.rowCount() - 1),
                        new TraceRunFrameDriver.Hooks<DynamicArtDiagnosticsSnapshot>() {
                            @Override
                            public void preparePhysicalRow(
                                    TraceRunFrameDriver.Step step) {
                                assertEquals(step.movieRow(),
                                        playback.getCursorFrame(),
                                        "special-stage physical cursor");
                                stateMovieLogicalRow(step);
                            }

                            @Override
                            public void prepareHardwareTiming(
                                    TraceRunFrameDriver.Step step) {
                                var admission = specialRows.admission(
                                        representedRow);
                                if (admission.admitHardwareTiming()) {
                                    hardwareTiming.beginSegmentRow(
                                            segmentIndex, representedRow);
                                } else {
                                    fixture.enterHardwareTimingGap();
                                }
                            }

                            @Override
                            public DynamicArtDiagnosticsSnapshot captureBefore(
                                    TraceRunFrameDriver.Step step) {
                                DynamicArtDiagnosticsSnapshot before =
                                        GameServices.captureDynamicArtDiagnostics();
                                rowDriver.admitCurrentRow(before);
                                return before;
                            }

                            @Override
                            public void runProductionLifecycle(
                                    TraceRunFrameDriver.Step step) {
                                driveInterior.accept(representedRow);
                            }

                            @Override
                            public boolean shouldAdvancePhysicalRow(
                                    TraceRunFrameDriver.Step step) {
                                return playback.getCursorFrame()
                                        == step.movieRow();
                            }

                            @Override
                            public void advancePhysicalRow(
                                    TraceRunFrameDriver.Step step) {
                                playback.onLevelFrameAdvanced();
                            }

                            @Override
                            public DynamicArtDiagnosticsSnapshot captureAfter(
                                    TraceRunFrameDriver.Step step) {
                                return GameServices.captureDynamicArtDiagnostics();
                            }

                            @Override
                            public void compare(
                                    TraceRunFrameDriver.Step step,
                                    DynamicArtDiagnosticsSnapshot before,
                                    DynamicArtDiagnosticsSnapshot after) {
                                rowDriver.publishAdmittedRow(after);
                                if (prefixTarget != null
                                        && prefixTarget.segmentIndex()
                                                == segmentIndex
                                        && prefixTarget.committedRows()
                                                == rowDriver.cursor()) {
                                    throw new ReplayPrefixReached();
                                }
                            }

                            @Override
                            public void afterStep(
                                    TraceRunFrameDriver.Step step) {
                            }
                        });
            } finally {
                gameplayMode.clearTraceRunFrameDriver(driver);
            }
        }

        private void driveInterstitialRow() {
            UncomparedInteriorPhysicalRow physicalRow = nextPhysicalRow(false);
            assertEquals(physicalRow.movieRow(), playback.getCursorFrame(),
                    "interstitial physical cursor");
            fixture.enterHardwareTimingGap();
            stepEngineFrameInTransitionGap(
                    gameplayMode, loop, playback, physicalRow.movieRow(),
                    physicalRow.lagGapRow());
        }

        private UncomparedInteriorPhysicalRow nextPhysicalRow(
                boolean represented) {
            if (physicalRowIndex >= physicalRows.size()) {
                throw new AssertionError(
                        "uncompared-interior physical walk exceeded destination "
                                + destinationOffset);
            }
            UncomparedInteriorPhysicalRow row =
                    physicalRows.get(physicalRowIndex++);
            assertEquals(represented, row.representedSpecialRow(),
                    "uncompared-interior row phase");
            return row;
        }

        private void finishInterstitialRows() {
            while (physicalRowIndex < physicalRows.size()) {
                if (physicalRows.get(physicalRowIndex)
                        .representedSpecialRow()) {
                    throw new AssertionError(
                            "stage-exit latched before all represented rows");
                }
                driveInterstitialRow();
            }
            assertEquals(destinationOffset, playback.getCursorFrame(),
                    "uncompared-interior walk must stop at destination offset");
        }

        private void completeRepresentedRows() {
            rowDriver.verifyComplete();
            if (rowDriver.comparisons().isEmpty()) {
                return;
            }
            try {
                writeDynamicArtInteriorReport(
                        runId, segmentIndex, rowDriver.comparisons());
            } catch (IOException e) {
                throw new IllegalStateException(
                        "failed to write special-stage comparison report", e);
            }
        }

        private void releasePayloadBackedLocals() {
            driveInterior = null;
            passBinder = null;
            rowDriver = null;
            specialRows = null;
        }

        private boolean gapOpened() {
            return gapOpened;
        }

        private boolean sourceClosed() {
            return sourceClosed;
        }
    }

    private void drivePresentationSpecialSegment(
            TraceRunManifest run,
            TraceRunSegmentDescriptor special,
            int specialIndex,
            GameLoop loop,
            InputHandler inputHandler,
            Bk2Movie movie,
            PlaybackDebugManager playback,
            BoundaryProbe probe,
            LiveEngineFixture fixture,
            HardwareTimingCoordinator hardwareTiming,
            TraceRunFrameDriver physicalRows,
            HeadlessRunCoordinatorAdapter runCoordinator,
            ReplayPrefixTarget prefixTarget) throws IOException {
        TraceRunSpecialStageRows specialRows = activeSpecialRows(special);
        TraceRunSpecialStageRowDriver specialDriver =
                new TraceRunSpecialStageRowDriver(
                        specialRows, activeTrace(special));
        SpecialStageRunObjectsPassBinder passBinder =
                specialRows.newRunObjectsPassBinder().orElse(null);
        installHeadlessPayloadAliases(
                probe, null, null, specialDriver, specialRows,
                passBinder, null);
        IntConsumer driveSpecial = uncomparedInteriorStep(
                loop, inputHandler, movie, special, specialRows, passBinder);
        assertEquals(special.segment().bk2FrameOffset(),
                playback.getCursorFrame(),
                "special-stage shared clock must enter at its manifest offset");

        while (!specialDriver.isComplete()) {
            int localRow = specialDriver.cursor();
            int movieRow = playback.getCursorFrame();
            boolean[] hostStepRan = {false};
            physicalRows.execute(
                    new TraceRunFrameDriver.Step(
                            TraceRunFrameDriver.Disposition.SPECIAL_LOCAL,
                            movieRow,
                            localRow == specialDriver.rowCount() - 1),
                    new TraceRunFrameDriver.Hooks<DynamicArtDiagnosticsSnapshot>() {
                        @Override
                        public void preparePhysicalRow(
                                TraceRunFrameDriver.Step step) {
                            assertEquals(
                                    special.segment().bk2FrameOffset()
                                            + localRow,
                                    step.movieRow());
                            stateMovieLogicalRow(step);
                        }

                        @Override
                        public void prepareHardwareTiming(
                                TraceRunFrameDriver.Step step) {
                            var admission = specialRows.admission(localRow);
                            if (admission.admitHardwareTiming()) {
                                hardwareTiming.beginSegmentRow(
                                        specialIndex, localRow);
                            } else {
                                fixture.enterHardwareTimingGap();
                            }
                        }

                        @Override
                        public DynamicArtDiagnosticsSnapshot captureBefore(
                                TraceRunFrameDriver.Step step) {
                            DynamicArtDiagnosticsSnapshot before =
                                    GameServices.captureDynamicArtDiagnostics();
                            specialDriver.admitCurrentRow(before);
                            return before;
                        }

                        @Override
                        public void runProductionLifecycle(
                                TraceRunFrameDriver.Step step) {
                            hostStepRan[0] = specialRows.admission(localRow)
                                    .executeGameplay();
                            driveSpecial.accept(localRow);
                        }

                        @Override
                        public void advancePhysicalRow(
                                TraceRunFrameDriver.Step step) {
                            playback.onLevelFrameAdvanced();
                        }

                        @Override
                        public DynamicArtDiagnosticsSnapshot captureAfter(
                                TraceRunFrameDriver.Step step) {
                            return GameServices.captureDynamicArtDiagnostics();
                        }

                        @Override
                        public void compare(
                                TraceRunFrameDriver.Step step,
                                DynamicArtDiagnosticsSnapshot before,
                                DynamicArtDiagnosticsSnapshot after) {
                            specialDriver.publishAdmittedRow(after);
                        }

                        @Override
                        public void afterStep(
                                TraceRunFrameDriver.Step step) {
                            if (!hostStepRan[0]) {
                                runCoordinator.afterStep(
                                        loop.getCurrentGameMode());
                            }
                        }
                    });
            if (prefixTarget != null
                    && prefixTarget.segmentIndex() == specialIndex
                    && prefixTarget.committedRows()
                            == specialDriver.cursor()) {
                throw new ReplayPrefixReached();
            }
        }
        specialDriver.verifyComplete();
        if (!specialDriver.comparisons().isEmpty()) {
            writeDynamicArtInteriorReport(
                    run.runId(), specialIndex,
                    specialDriver.comparisons());
        }
    }

    private void drivePresentationBridgeSegment(
            TraceRunSegmentDescriptor bridge,
            int bridgeIndex,
            GameLoop loop,
            InputHandler inputHandler,
            Bk2Movie movie,
            PlaybackDebugManager playback,
            BoundaryProbe probe,
            HardwareTimingCoordinator hardwareTiming,
            TraceRunFrameDriver physicalRows,
            HeadlessRunCoordinatorAdapter runCoordinator,
            ReplayPrefixTarget prefixTarget,
            TraceRunReplayWalker.DynamicArtSegmentController dynamicArtSegments,
            DynamicArtGapJournalProbe dynamicArtGapJournal) {
        TraceStructuralRowComparator structural =
                new TraceStructuralRowComparator(
                        activeTrace(bridge), ToleranceConfig.DEFAULT, 0);
        installHeadlessPayloadAliases(
                probe, null, structural, null, null, null, null);
        List<FrameComparison> comparisons = new ArrayList<>();
        while (!structural.allRowsConsumed()) {
            int localRow = structural.cursor();
            int movieRow = playback.getCursorFrame();
            TraceRunFrameDriver.Step bridgeStep =
                    TraceRunFrameDriver.presentationBridgeStep(
                            activeTrace(bridge), localRow, movieRow,
                            bridge.executionPolicy(),
                            loop.getCurrentGameMode() == GameMode.LEVEL,
                            localRow == bridge.rowCount() - 1);
            boolean[] hostStepRan = {false};
            physicalRows.execute(
                    bridgeStep,
                    new TraceRunFrameDriver.Hooks<DynamicArtDiagnosticsSnapshot>() {
                        @Override
                        public void preparePhysicalRow(
                                TraceRunFrameDriver.Step step) {
                            stateMovieLogicalRow(step);
                            Bk2FrameInput current =
                                    playback.currentFrameOrThrow();
                            Bk2FrameInput previous = movieRow > 0
                                    ? movie.getFrame(movieRow - 1) : null;
                            inputHandler.setLogicalOverride(
                                    RecordedInputSnapshots.fromBk2(
                                            current, previous));
                            structural.prepareRow(current);
                        }

                        @Override
                        public void prepareHardwareTiming(
                                TraceRunFrameDriver.Step step) {
                            hardwareTiming.beginPlaybackFrame(
                                    playback.currentFrameOrThrow());
                        }

                        @Override
                        public DynamicArtDiagnosticsSnapshot captureBefore(
                                TraceRunFrameDriver.Step step) {
                            return GameServices.captureDynamicArtDiagnostics();
                        }

                        @Override
                        public void runProductionLifecycle(
                                TraceRunFrameDriver.Step step) {
                            hostStepRan[0] = true;
                            assertTrue(inputHandler.hasLogicalOverride(),
                                    "presentation production must receive "
                                            + "the physical BK2 input");
                            if (step.disposition()
                                    == TraceRunFrameDriver.Disposition
                                            .PRESENTATION_SUPPRESSED_CLOSURE) {
                                runCoordinator.beforeProduction(
                                        loop.getCurrentGameMode());
                                TraceRunPresentationClosure.execute(loop, step);
                                runCoordinator.afterStep(
                                        loop.getCurrentGameMode());
                            } else {
                                stepEngineFrame(loop);
                            }
                        }

                        @Override
                        public void advancePhysicalRow(
                                TraceRunFrameDriver.Step step) {
                            playback.onLevelFrameAdvanced();
                        }

                        @Override
                        public DynamicArtDiagnosticsSnapshot captureAfter(
                                TraceRunFrameDriver.Step step) {
                            return GameServices.captureDynamicArtDiagnostics();
                        }

                        @Override
                        public void compare(
                                TraceRunFrameDriver.Step step,
                                DynamicArtDiagnosticsSnapshot before,
                                DynamicArtDiagnosticsSnapshot after) {
                            FrameComparison comparison =
                                    structural.completePostProduction(
                                            before, after,
                                            step.disposition()
                                                    .runsProductionLifecycle());
                            if (comparison != null) {
                                comparisons.add(comparison);
                            }
                            if (prefixTarget != null
                                    && prefixTarget.segmentIndex()
                                            == bridgeIndex
                                    && prefixTarget.committedRows()
                                            == structural.cursor()) {
                                assertNotEquals(GameMode.SPECIAL_STAGE,
                                        loop.getCurrentGameMode(),
                                        "presentation bridge must leave the special stage");
                                throw new ReplayPrefixReached();
                            }
                        }

                        @Override
                        public void afterStep(
                                TraceRunFrameDriver.Step step) {
                            try {
                                if (!hostStepRan[0]) {
                                    runCoordinator.afterStep(
                                            loop.getCurrentGameMode());
                                }
                            } finally {
                                inputHandler.clearLogicalOverride();
                            }
                        }
                    });
        }

        dynamicArtSegments.enterGap();
        dynamicArtGapJournal.sourceClosed(bridge.segment().dir());
        FrameComparison terminal = structural.finalizeSegment(
                GameServices.captureDynamicArtDiagnostics());
        if (terminal != null) {
            comparisons.add(terminal);
        }
        assertStructuralComparisonsGreen(
                bridge.segment().dir(), comparisons);
        runCoordinator.closeCurrent(
                loop.getCurrentGameMode(), structural.isComplete());
    }

    /**
     * Drives a special-stage return and its recorded native presentation on one
     * continuous physical BK2 clock. Both the visual launcher and this adapter
     * use {@link TraceRunFrameDriver}; only the production hook differs.
     */
    private PresentationBridgeResult replaySpecialStagePresentationBridge(
            Path runDir,
            TraceRunManifest run,
            List<TraceRunSegmentDescriptor> descriptors,
            int specialIndex,
            GameLoop loop,
            InputHandler inputHandler,
            Bk2Movie movie,
            PlaybackDebugManager playback,
            BoundaryProbe probe,
            LiveEngineFixture fixture,
            HardwareTimingCoordinator hardwareTiming,
            GameplayModeContext gameplayMode,
            TraceRunReplayWalker.DynamicArtSegmentController dynamicArtSegments,
            DynamicArtGapJournalProbe dynamicArtGapJournal,
            HeadlessRunCoordinatorAdapter runCoordinator,
            ReplayPrefixTarget prefixTarget,
            int stepCap,
            TraceRunSegmentDescriptor sourceLevel,
            int sourceVblank) throws Exception {
        TraceRunSegmentDescriptor special = descriptors.get(specialIndex);
        int bridgeIndex = specialIndex + 1;
        TraceRunSegmentDescriptor bridge = descriptors.get(bridgeIndex);
        TraceRunFrameDriver physicalRows = new TraceRunFrameDriver();
        gameplayMode.installTraceRunFrameDriver(physicalRows);
        probe.setDelegate(null);
        // An uncompared special stage has no comparator, but the walk still
        // OWNS its rows -- it drives them itself below. Declaring entry here is
        // what makes the interior a segment the coordinator is inside, so the
        // bridge that follows is an ordinary next-segment entry rather than a
        // jump over a segment nothing ever declared.
        declareHardwareTimingSegment(specialIndex);
        productionComparator = null;
        try {
            drivePresentationSpecialSegment(
                    run, special, specialIndex, loop, inputHandler, movie,
                    playback, probe, fixture, hardwareTiming, physicalRows,
                    runCoordinator, prefixTarget);

            dynamicArtSegments.enterGap();
            // Observed after the window actually closes: the close is what
            // records the state the gap opens on, so reading the snapshot
            // first leaves the source's own final edges out of it.
            dynamicArtGapJournal.sourceClosed(special.segment().dir());
            runCoordinator.closeCurrent(
                    loop.getCurrentGameMode(), true);
            closeHeadlessPayloadOrThrow();
            dynamicArtGapJournal.gapOpened(special.segment().dir());

            int bridgeOffset = bridge.segment().bk2FrameOffset();
            // The stage's rows are done and the bridge's have not started: the
            // rows driven below are transition rows the recording does not
            // cover, whatever the shared cursor reads.
            declareHardwareTimingTransitionGap();
            while (playback.getCursorFrame() < bridgeOffset) {
                if (playback.getCursorFrame() + 1 > bridgeOffset) {
                    throw new AssertionError(
                            "physical clock crossed presentation offset ") ;
                }
                driveHeadlessTransitionRow(
                        physicalRows,
                        TraceRunFrameDriver.Disposition.SHARED_GAP,
                        loop, inputHandler, movie, playback, fixture,
                        runCoordinator);
            }
            int stageExitFrame = playback.getCursorFrame();
            runCoordinator.admitPresentationBridge(
                    special.exitBoundary(), stageExitFrame,
                    loop.getCurrentGameMode());
            if (sourceLevel != null) {
                alignPresentationBridgeEntryVblank(
                        sourceLevel, bridge, sourceVblank);
            }
            // The clock the bridge's own recorded rows start on. Its tail is
            // projected from here at the bridge's exit rather than observed,
            // for the reason TraceRunVblankClock.sourceTailVblank states.
            int bridgeEntryVblank =
                    GameServices.level().getObjectManager().getVblaCounter();

            openHeadlessPayload(bridge, bridgeIndex);
            dynamicArtSegments.beginSegment();
            declareHardwareTimingSegment(bridgeIndex);
            dynamicArtGapJournal.nextSegmentArmed(bridge.segment().dir());

            drivePresentationBridgeSegment(
                    bridge, bridgeIndex, loop, inputHandler, movie, playback,
                    probe, hardwareTiming, physicalRows, runCoordinator,
                    prefixTarget, dynamicArtSegments, dynamicArtGapJournal);
            closeHeadlessPayloadOrThrow();
            dynamicArtGapJournal.gapOpened(bridge.segment().dir());

            if (bridgeIndex == descriptors.size() - 1) {
                TraceRunReplayWalker.TerminalMovieTailPlan tail =
                        runCoordinator.terminalPlan();
                if (tail != null) {
                    // rowsToReplay counts rows, so tailStart + rowsToReplay is
                    // the movie's frame COUNT -- one past the last row a cursor
                    // can ever hold. Playback pins on its last valid row and
                    // stops, so waiting for the count itself never returns:
                    // this loop hung the whole class rather than failing it.
                    // Drive until the cursor stops advancing, then prove it
                    // stopped because the movie ended.
                    int previousCursor = -1;
                    while (playback.getCursorFrame()
                                    < tail.tailStart() + tail.rowsToReplay()
                            && playback.getCursorFrame() != previousCursor) {
                        previousCursor = playback.getCursorFrame();
                        driveHeadlessTransitionRow(
                                physicalRows,
                                TraceRunFrameDriver.Disposition.TERMINAL_TAIL,
                                loop, inputHandler, movie, playback, fixture,
                                runCoordinator);
                    }
                    assertEquals(movie.getFrameCount() - 1,
                            playback.getCursorFrame(),
                            "terminal tail must consume the movie to its last row");
                    assertEquals(tail.expectedMode(),
                            loop.getCurrentGameMode(),
                            "Complete movie must finish in the manifest-declared mode");
                }
                assertReturnBoundary(descriptors, specialIndex, runDir);
                if (tail != null) {
                    dynamicArtGapJournal.verifyTerminal(
                            run, movie.getFrameCount());
                }
                dynamicArtSegments.close();
                hardwareTiming.close();
                runCoordinator.finishTerminal(
                        loop.getCurrentGameMode());
                return new PresentationBridgeResult(true, -1, null);
            }

            int gameplayIndex = bridgeIndex + 1;
            TraceRunSegmentDescriptor gameplay =
                    descriptors.get(gameplayIndex);
            int gameplayOffset = gameplay.segment().bk2FrameOffset();
            while (playback.getCursorFrame() < gameplayOffset) {
                driveHeadlessTransitionRow(
                        physicalRows,
                        TraceRunFrameDriver.Disposition.SHARED_GAP,
                        loop, inputHandler, movie, playback, fixture,
                        runCoordinator);
            }
            assertEquals(gameplayOffset, playback.getCursorFrame(),
                    "gameplay destination must open at its exact physical row");
            if (loop.getCurrentGameMode() != GameMode.LEVEL
                    || GameServices.level().isTitleCardRequested()) {
                throw new AssertionError(
                        "gameplay destination was not ready at its recorded offset");
            }
            assertReturnBoundary(descriptors, specialIndex, runDir);
            completeBridgeExitVblankBudget(
                    bridge, gameplay, bridgeEntryVblank);
            runCoordinator.admitLevel(
                    null, gameplayOffset, loop.getCurrentGameMode(),
                    0, true, null);
            LiveTraceComparator comparator = openAndAttachHeadlessPayload(
                    gameplay, gameplayIndex,
                    payload -> attachReturnedLevelSegment(
                            probe, gameplay, fixture, 0, gameplayIndex));
            dynamicArtSegments.beginSegment();
            dynamicArtGapJournal.nextSegmentArmed(gameplay.segment().dir());
            return new PresentationBridgeResult(
                    false, gameplayIndex, comparator);
        } finally {
            gameplayMode.clearTraceRunFrameDriver(physicalRows);
        }
    }

    /**
     * States the physical BK2 row a dynamic-art gap edge is stamped with, the
     * way {@code TraceSessionLauncher.driveRunPhysicalRow} does for a live run.
     *
     * <p>{@code DynamicArtLifecycleService.movieLogicalFrame} otherwise counts
     * production iterations, and a chain drives whole spans of rows —
     * transition gaps, shared gaps, the terminal tail — that run no production
     * iteration at all. Every such row is silently lost from the stamp, so a
     * gap edge raised late in a chain reports a row hundreds short of the one
     * the recorder wrote. The recorder's contract is the movie row it has
     * consumed ("tools/tracechaser/bizhawk-headless/src/Recording/S1RunCaptureRunner.cs":
     * 199-215), so the driver states it rather than inferring it.
     */
    private void stateMovieLogicalRow(TraceRunFrameDriver.Step step) {
        productionOutputRowObserver.accept(step.movieRow() + 1);
        SessionManager.getCurrentGameplayMode()
                .dynamicArtLifecycle()
                .setMovieLogicalFrame(step.movieRow());
    }

    /**
     * States the physical row for every other engine step the chain drives.
     *
     * <p>{@link #stateMovieLogicalRow(TraceRunFrameDriver.Step)} only covers
     * the rows a {@code TraceRunFrameDriver} owns; ordinary segment rows,
     * mode waits and boundary crossings run through {@link #stepEngineFrame}
     * and stated nothing, so the service fell back to counting production
     * iterations for them. A chain runs a different number of iterations than
     * the movie has rows — the pre-segment prefix runs none, a special-stage
     * segment runs more than one per row — so the counter is not the movie
     * clock and never converges on it. The shared playback cursor is that
     * clock, and it is the same value the production visual path announces
     * per physical row (TraceSessionLauncher.driveRunPhysicalRow).
     */
    private void stateMovieLogicalRow() {
        productionOutputRowObserver.accept(
                GameServices.playbackDebug().getCursorFrame() + 1);
        var lifecycle = GameServices.dynamicArtLifecycleOrNull();
        if (lifecycle == null || !lifecycle.isRunActive()) {
            return;
        }
        lifecycle.setMovieLogicalFrame(
                GameServices.playbackDebug().getCursorFrame());
    }

    private void driveHeadlessTransitionRow(
            TraceRunFrameDriver driver,
            TraceRunFrameDriver.Disposition disposition,
            GameLoop loop,
            InputHandler inputHandler,
            Bk2Movie movie,
            PlaybackDebugManager playback,
            LiveEngineFixture fixture,
            HeadlessRunCoordinatorAdapter coordinator) {
        int movieRow = playback.getCursorFrame();
        driver.execute(
                new TraceRunFrameDriver.Step(disposition, movieRow, false),
                new TraceRunFrameDriver.Hooks<DynamicArtDiagnosticsSnapshot>() {
                    @Override
                    public void preparePhysicalRow(
                            TraceRunFrameDriver.Step step) {
                        stateMovieLogicalRow(step);
                        Bk2FrameInput current = playback.currentFrameOrThrow();
                        Bk2FrameInput previous = movieRow > 0
                                ? movie.getFrame(movieRow - 1) : null;
                        inputHandler.setLogicalOverride(
                                RecordedInputSnapshots.fromBk2(
                                        current, previous));
                    }

                    @Override
                    public void prepareHardwareTiming(
                            TraceRunFrameDriver.Step step) {
                        fixture.enterHardwareTimingGap();
                    }

                    @Override
                    public DynamicArtDiagnosticsSnapshot captureBefore(
                            TraceRunFrameDriver.Step step) {
                        return GameServices.captureDynamicArtDiagnostics();
                    }

                    @Override
                    public void runProductionLifecycle(
                            TraceRunFrameDriver.Step step) {
                        stepEngineFrame(loop);
                    }

                    @Override
                    public void advancePhysicalRow(
                            TraceRunFrameDriver.Step step) {
                        playback.onLevelFrameAdvanced();
                    }

                    @Override
                    public DynamicArtDiagnosticsSnapshot captureAfter(
                            TraceRunFrameDriver.Step step) {
                        return GameServices.captureDynamicArtDiagnostics();
                    }

                    @Override
                    public void compare(
                            TraceRunFrameDriver.Step step,
                            DynamicArtDiagnosticsSnapshot before,
                            DynamicArtDiagnosticsSnapshot after) {
                    }

                    @Override
                    public void afterStep(
                            TraceRunFrameDriver.Step step) {
                        inputHandler.clearLogicalOverride();
                        if (!step.disposition().runsProductionLifecycle()) {
                            coordinator.afterStep(loop.getCurrentGameMode());
                        }
                    }
                });
    }

    private static void assertStructuralComparisonsGreen(
            String segmentDir,
            List<FrameComparison> comparisons) {
        FrameComparison firstFailure = comparisons.stream()
                .filter(comparison -> comparison.divergentFields().stream()
                        .anyMatch(field -> field.severity()
                                == com.openggf.trace.Severity.ERROR))
                .findFirst()
                .orElse(null);
        if (firstFailure != null) {
            throw new AssertionError(
                    "structural presentation comparison failed for "
                            + segmentDir + " at row " + firstFailure.frame()
                            + ": " + firstFailure.divergentFields());
        }
    }

    // -------------------------------------------------------------------------
    // Handoffs
    // -------------------------------------------------------------------------

    /**
     * ENTRY-side twin of the bonus-exit OPTION B fact (see the {@code stage_exit}
     * branch): a source segment that ends in a level reload records the ROM's
     * whole {@code Level:} prologue -- {@code Pal_FadeToBlack}, the
     * {@code Clear_DisplayData} RAM wipe and the DESTINATION's load -- inside the
     * SOURCE segment, because the recorder cuts at the destination's first
     * gameplay row. Those rows are not LevelLoop iterations of the source level,
     * the engine has left LEVEL by then, and it performs the reload synchronously
     * (loadZoneAndAct is one frame), so demanding the source comparator consume
     * them is unsatisfiable by construction.
     *
     * <p>The row split comes from recorded ROM state (the terminal
     * {@code zone_act_state} {@code game_mode} reload bit of
     * {@code bset #7,(Game_mode).w}) -- see
     * {@link TraceRunReplayWalker#levelLoopRowCount}. Segments that do not end in
     * a reload are unaffected: their in-level row count is the whole trace.
     */
    /**
     * Runs {@link #completePinnedSourceTailAfterBoundary} and, if the pinned
     * tail cannot complete, emits this segment's comparator evidence before
     * rethrowing.
     *
     * <p>The tail assertion aborts the whole walk, so a segment that fails it
     * never reaches its own {@link #maybeWriteReport} call further down the
     * branch: its report is never written and its already-computed physics
     * error count is never asserted, leaving the segment-physics axis silently
     * absent from the failure. That is the same reporting-before-asserting
     * order the interior and entry branches already use ("Write the interior's
     * comparator report BEFORE asserting the boundary was observed"), applied
     * to the one assertion that still preceded it.
     *
     * <p>Nothing is weakened: the tail failure is rethrown unchanged and still
     * fails the run. {@link #assertSegmentPhysics} only records into
     * {@code chainAxisFailures}, so both axes are reported together, additively.
     */
    /**
     * The cursor a freshly attached segment comparator starts at, tolerating the
     * detached (null) comparator an uncompared special-stage interior installs.
     */
    private static int cursorOrZero(LiveTraceComparator comparator) {
        return comparator == null ? 0 : comparator.cursor();
    }

    private void completeSourceTailReportingOnFailure(
            String runId, int segmentIndex, GameLoop loop,
            LiveTraceComparator comparator,
            TraceRunSegmentDescriptor segment, int stepCap,
            int initialCursor, Object sourceLevel) throws IOException {
        try {
            completePinnedSourceTailAfterBoundary(
                    loop, comparator, segment, stepCap, initialCursor,
                    sourceLevel);
        } catch (AssertionError tailFailure) {
            maybeWriteReport(runId, segmentIndex, comparator);
            throw tailFailure;
        }
    }

    private void completePinnedSourceTailAfterBoundary(
            GameLoop loop, LiveTraceComparator comparator,
            TraceRunSegmentDescriptor segment, int stepCap, int initialCursor,
            Object sourceLevel) {
        int steps = 0;
        int startCursor = comparator.cursor();
        List<GameMode> modePath = new ArrayList<>();
        while (!activeRunCoordinator.sourceComparatorExhausted(comparator)
                && steps < stepCap) {
            GameMode mode = loop.getCurrentGameMode();
            if (modePath.isEmpty() || modePath.getLast() != mode) {
                modePath.add(mode);
            }
            GameMode sourceMode = TraceRunReplayWalker.expectedMode(
                    segment.segment());
            boolean levelOwnershipChanged = "level".equals(segment.segment().kind())
                    && GameServices.level().getCurrentLevel() != sourceLevel;
            if (mode != sourceMode || levelOwnershipChanged) {
                throw new AssertionError(
                        "source comparator cannot exhaust after boundary for "
                                + segment.segment().dir()
                                + ": production ownership already left " + sourceMode
                                + " at tail step " + steps + ", comparator cursor "
                                + comparator.cursor() + " of "
                                + segment.rowCount()
                                + " (bootstrap initial cursor " + initialCursor
                                + "), mode path=" + modePath
                                + ", level ownership changed="
                                + levelOwnershipChanged);
            }
            try {
                stepEngineFrame(loop);
            } catch (RuntimeException | Error failure) {
                throw new AssertionError(
                        "pinned source-tail production failed for "
                                + segment.segment().dir() + " at tail step "
                                + steps + ", comparator cursor "
                                + comparator.cursor() + " of "
                                + segment.rowCount()
                                + " (bootstrap initial cursor " + initialCursor
                                + "), mode path="
                                + modePath + ", current mode=" + mode,
                        failure);
            }
            steps++;
        }
        if (!activeRunCoordinator.sourceComparatorExhausted(comparator)) {
            throw new AssertionError(
                    "source comparator did not exhaust after boundary for "
                            + segment.segment().dir()
                            + ": cursor " + startCursor + " -> "
                            + comparator.cursor() + " of "
                            + segment.rowCount()
                            + " (bootstrap initial cursor " + initialCursor + ")"
                            + " after " + steps + " pinned tail steps; mode path="
                            + modePath + ", final mode="
                            + loop.getCurrentGameMode());
        }
    }

    private void requireComparatorComplete(
            TraceRunSegmentDescriptor segment,
            LiveTraceComparator comparator) {
        if (!activeRunCoordinator.sourceComparatorExhausted(comparator)) {
            throw new AssertionError(
                    "source comparator is not complete for "
                            + segment.segment().dir() + ": cursor "
                            + comparator.cursor() + " of "
                            + segment.rowCount());
        }
    }

    /**
     * Hands off from a level segment INTO an interior (bonus/special). Detaches
     * the comparator across the uncompared fade/title-card transition, waits for
     * the interior's expected mode, re-seeks the BK2 cursor to the interior's
     * offset, then attaches the interior comparator (or leaves it detached for a
     * special stage -- see {@link #attachInteriorComparator}).
     *
     * <p>The comparator's initial cursor for each interior kind (compared-bonus
     * ENTRY = 1, special = uncompared) follows the COMPARATOR FRAME BASE contract
     * on {@link #attachReturnedLevelSegment}.
     */
    private int prepareIntoInterior(
            GameLoop loop, PlaybackDebugManager playback, BoundaryProbe probe,
            Bk2Movie movie, TraceRunSegmentDescriptor interior, int stepCap) {
        probe.setDelegate(null);
        declareHardwareTimingTransitionGap();
        productionComparator = null;
        int offset = interior.segment().bk2FrameOffset();
        GameMode target = TraceRunReplayWalker.expectedMode(interior.segment());
        if (!TraceRunReplayWalker.isUncomparedInterior(interior.segment())) {
            // COMPARED interior (bonus stage). The interior's FIRST gameplay tick is
            // the single title-card-exit fall-through frame: GameLoop.exitTitleCard
            // releases control and flips into BONUS_STAGE in the SAME loop.step(),
            // then that step's LevelFrameStep ticks the player. That fall-through
            // frame IS the recorded interior's frame 0, and for the S3K bonus
            // machines it is load-bearing: the player enters air-forced-false for
            // exactly one frame and, if the recorded frame-0 direction is pressed,
            // does a single grounded ground-move (e.g. the gumball's g_speed -0x0C
            // left nudge) before the ground probe finds no floor and flips it
            // airborne. If that tick reads a neutral/stale input the grounded nudge
            // is lost, the player free-falls with air-accel from frame 0, and the
            // interior trajectory diverges enough to push the stage exit past the
            // boundary window.
            //
            // Seek to the interior's recorded offset BEFORE waiting out the fade +
            // title card. The shared cursor is frozen across the fade/title-card
            // (those non-LEVEL/BONUS frames never call onLevelFrameAdvanced), so it
            // stays parked at this offset until the fall-through frame -- unlike
            // seeking AFTER waitForMode, which left the fall-through reading the
            // stale pre-entry row. GameLoop.exitTitleCard's bonus branch re-arms the
            // playback forced-input bridge right after flipping to BONUS_STAGE (the
            // step-top syncPlaybackInputBridge ran while still TITLE_CARD, which
            // PlaybackDebugManager.isDriving does not drive), so the fall-through
            // player tick then samples this parked offset = recorded frame 0. Input
            // alignment via the same forced-input bridge the interior already uses,
            // never trace-field hydration.
            playback.startSession(movie, offset);
            waitForMode(loop, target, stepCap);
            primeInteriorEntryRngFromMetadata(interior);
            // The fall-through frame already reproduced recorded frame 0 (the grounded
            // entry tick) and advanced the cursor to offset+1, so compare from frame 1
            // (the compared-bonus-ENTRY case of the COMPARATOR FRAME BASE contract on
            // attachReturnedLevelSegment: initialCursor == cursorFrame - offset == 1).
            return 1;
        }
        // UNCOMPARED interior (special stage): the SS is driven separately by
        // uncomparedInteriorStep; the shared cursor is not read for its physics, so
        // keep the simple seek-after-mode handoff.
        waitForMode(loop, target, stepCap);
        playback.startSession(movie, offset);
        primeInteriorEntryRngFromMetadata(interior);
        return 0;
    }

    private LiveTraceComparator attachPreparedInterior(
            BoundaryProbe probe, TraceRunSegmentDescriptor interior,
            LiveEngineFixture fixture, int rowsConsumed, int segmentIndex) {
        LiveTraceComparator comparator;
        if (TraceRunReplayWalker.isUncomparedInterior(interior.segment())) {
            if (rowsConsumed != 0) {
                throw new AssertionError(
                        "uncompared interior admission consumed "
                                + rowsConsumed + " rows");
            }
            comparator = attachInteriorComparator(interior, fixture);
        } else {
            if (rowsConsumed != 1) {
                throw new AssertionError(
                        "compared interior admission expected one published row but saw "
                                + rowsConsumed);
            }
            comparator = new LiveTraceComparator(
                    activeTrace(interior), ToleranceConfig.DEFAULT,
                    rowsConsumed, fixture::sprite);
        }
        return attachHeadlessComparator(
                probe, comparator, activeTrace(interior), segmentIndex,
                () -> declareHardwareTimingSegment(segmentIndex));
    }

    /**
     * Primes the interior segment's entry-time RNG state from its OWN recorded
     * {@code metadata.rng_seed}, at the interior boundary -- the SAME
     * comparison-bootstrap seam the standalone bonus/special fixtures already run
     * once per replay (TraceReplaySessionBootstrap.performTraceReplayBootstrap ->
     * applyInitialRngSeedForReplay, TraceReplaySessionBootstrap.java:375).
     *
     * <p>Why the chain needs this and the standalone gets it for free: the ROM's
     * S3K bonus machines seed their RNG from the free-running hardware
     * {@code V_int_run_count} at machine init -- the gumball does
     * {@code move.l (V_int_run_count).w,(RNG_seed).w} (sonic3k.asm:127412), folding
     * power-on run history (menu time, prior acts) into the ball-subtype roll
     * (sub_612A8, sonic3k.asm:127988-128008). A standalone bonus trace boots
     * directly into the interior and its bootstrap applies the recorded frame-0
     * {@code rng_seed} (== that run's {@code V_int_run_count} at entry, e.g.
     * 0x1598 for gumball #1) before the first interior frame. The chain instead
     * reaches the interior ORGANICALLY from the preceding level replay, so the
     * shared engine RNG carries whatever state the preceding level left it in --
     * which is NOT the recorded run's entry seed, because the
     * engine has no faithful persistent global {@code V_int_run_count} (a
     * documented deferred gap; see docs/S3K_KNOWN_DISCREPANCIES.md). Without this
     * prime the gumball's ball series diverges mid-interior (f442) and dispenses a
     * different reward ball, so the on-return ring carry-over is off by one ball's
     * award (59 vs the recorded 69). Re-establishing the recorded entry seed at
     * the boundary reproduces the recorded ball series.
     *
     * <p>Comparison-only and NOT a carve-out: it reads one frame-0 bootstrap
     * datum ({@code metadata.rng_seed}) and applies it exactly once at the segment
     * boundary via the established {@code applyInitialRngSeedForReplay} helper --
     * identical to loading a save-state at the interior's BK2 start. No per-frame
     * trace field is ever hydrated into engine state, and it keys purely on
     * manifest metadata, never on zone/route/segment identity (the helper is a
     * no-op for any interior whose metadata lacks {@code rng_seed}). This is the
     * gumball-family (RNG_seed reseed) counterpart of the slots-family
     * {@code primeVIntRunCountForReplay} seam in
     * {@code TraceReplaySessionBootstrap.applyBonusStageEntry}: both re-establish
     * the recorded entry-time {@code V_int_run_count}-derived state the organic
     * chain entry cannot reproduce, each modelling the exact ROM read its machine
     * performs (gumball reseeds RNG_seed once; slots reads V_int_run_count live per
     * reel cycle).
     */
    private void primeInteriorEntryRngFromMetadata(
            TraceRunSegmentDescriptor interior) {
        TraceReplaySessionBootstrap.applyInitialRngSeedForReplay(
                interior.metadata());
    }

    /**
     * Rebinds onto a level segment reached on RETURN from an interior. The mode
     * is already LEVEL (the stage_exit latched exactly when currentMode()==LEVEL),
     * so no wait is needed -- just re-seek and attach the return comparator.
     */
    private LiveTraceComparator attachLevelSegment(
            PlaybackDebugManager playback, BoundaryProbe probe,
            Bk2Movie movie, TraceRunSegmentDescriptor level,
            LiveEngineFixture fixture,
            int segmentIndex) {
        assertedPhysicsSegmentIndices.add(segmentIndex);
        playback.startSession(movie, level.segment().bk2FrameOffset());
        LiveTraceComparator comparator = new LiveTraceComparator(
                activeTrace(level), ToleranceConfig.DEFAULT, 0,
                fixture::sprite);
        return attachHeadlessComparator(
                probe, comparator, activeTrace(level), segmentIndex,
                () -> { });
    }

    /**
     * Attaches the comparator for a level segment reached on RETURN from an
     * interior, when the engine's title-card-exit fall-through has already run
     * (and faithfully reproduced) {@code framesConsumed} leading LEVEL frames of
     * that segment from its own recorded input.
     *
     * <p>Unlike {@link #attachLevelSegment}, this method does not itself re-seek
     * the BK2 cursor. Its caller first establishes the required alignment: an
     * uncompared special-stage return keeps the pre-seeked cursor advanced by its
     * fall-through frame, while the bonus/Option-B return explicitly re-anchors
     * to {@code returnOffset + 1}. In both cases the cursor reaches
     * {@code returnOffset + framesConsumed} before this method attaches.
     * Starting the comparator at {@code framesConsumed} keeps the recording-frame
     * index and the BK2 cursor in lockstep, so the already-run leading frame(s)
     * are neither replayed a second time (which would double-apply a moving
     * gameplay-unlock frame like {@code seg3_ehz1} frame 0) nor skipped.
     *
     * <p><b>COMPARATOR FRAME BASE — authoritative contract for the whole chain.</b>
     * Every {@link LiveTraceComparator} in this class is constructed with an
     * <em>initial cursor</em> (its third argument) equal to the trace-frame index
     * of the FIRST frame the segment will compare. That index equals
     * {@code framesConsumed}: the number of leading segment frames the transition
     * machinery already ran — and faithfully reproduced from the segment's OWN
     * recorded input — before the comparator attached. Equivalently, the BK2 input
     * cursor at attach time sits at {@code segmentOffset + framesConsumed}, so the
     * comparator's initial cursor and {@code (cursorFrame - segmentOffset)} are
     * ALWAYS equal at attach. Keeping them in lockstep is what prevents replaying
     * an already-run frame (double-applying a moving gameplay-unlock frame) or
     * skipping one — the recurring off-by-one that corrupts a return segment's
     * frame-0 physics. Values by transition kind (each cited at its site; do not
     * re-derive them — reference this block):
     * <ul>
     *   <li><b>0</b> — plain level entry or a level rebind via
     *       {@link #attachLevelSegment}: the comparator attaches before the
     *       segment's frame 0 and the cursor is (re-)seeked to
     *       {@code segmentOffset}.</li>
     *   <li><b>1</b> — a compared bonus interior ENTRY
     *       ({@link #handoffIntoInterior}'s bonus/Option-B branch): the single
     *       title-card-exit fall-through frame already reproduced the interior's
     *       recorded frame 0, so comparison starts at frame 1.</li>
     *   <li><b>1</b> — a level RETURN after a special-stage (uncompared) interior
     *       (this method): the pre-seeked frozen cursor's one fall-through frame
     *       consumed the return segment's frame 0.</li>
     *   <li><b>1</b> — a level RETURN after a compared bonus interior (this
     *       method): Option B has already run the return segment's frame 0, then
     *       explicitly re-seeks the input cursor to {@code returnOffset + 1}
     *       before attaching the comparator at frame 1.</li>
     * </ul>
     */
    private LiveTraceComparator attachReturnedLevelSegment(
            BoundaryProbe probe, TraceRunSegmentDescriptor level,
            LiveEngineFixture fixture, int framesConsumed,
            int segmentIndex) {
        assertedPhysicsSegmentIndices.add(segmentIndex);
        LiveTraceComparator comparator = new LiveTraceComparator(
                activeTrace(level), ToleranceConfig.DEFAULT,
                framesConsumed, fixture::sprite);
        return attachHeadlessComparator(
                probe, comparator, activeTrace(level), segmentIndex,
                () -> declareHardwareTimingSegment(segmentIndex));
    }

    /**
     * Crosses a plain level->level boundary that carries NO transition record
     * (e.g. S3K AIZ->HCZ seg 8->9, HCZ->MGZ seg 18->19). The engine runs an
     * act/zone title-card cycle. If that cycle completed inside the preceding
     * segment's recorded tail, the target level is already active and we rebind
     * immediately. Otherwise wait out of LEVEL and back, then rebind.
     */
    private int prepareAcrossLevelBoundary(
            GameLoop loop, PlaybackDebugManager playback, BoundaryProbe probe,
            Bk2Movie movie, TraceRunSegmentDescriptor currentLevel,
            TraceRunSegmentDescriptor nextLevel,
            int stepCap,
        Object levelAtSegmentStart) {
        int sourceTailVblank =
                observedSourceTailVblank(currentLevel.segment(), playback);
        probe.setDelegate(null);
        declareHardwareTimingTransitionGap();
        if (!isNewActiveLevelSegment(nextLevel, levelAtSegmentStart)) {
            int offset = nextLevel.segment().bk2FrameOffset();
            playback.scheduleSessionAtNextLevelLoad(movie, offset);
            waitForModeToLeaveOrLevelActivate(
                    loop, GameMode.LEVEL, nextLevel, levelAtSegmentStart, stepCap);
            int firstGameplayFrame = playback.getCursorFrame() - offset;
            if (firstGameplayFrame < 0 || firstGameplayFrame > 1) {
                // Name the boundary. Without it the axis says only how far the
                // cursor moved, and locating it costs a bisect -- which is how
                // this one stayed undetermined.
                throw new AssertionError("Destination playback cursor advanced "
                        + firstGameplayFrame + " frames during level-load handoff"
                        + " (" + currentLevel.segment().dir() + " -> "
                        + nextLevel.segment().dir() + ", destination offset "
                        + offset + ", cursor " + playback.getCursorFrame() + ")");
            }
            completeInterLevelVblankBudget(
                    currentLevel, nextLevel, firstGameplayFrame, sourceTailVblank);
            if (loop.getCurrentGameMode() == GameMode.TITLE_CARD
                    || GameServices.level().isTitleCardRequested()) {
                if (loop.getCurrentGameMode() == GameMode.LEVEL) {
                    waitForModeToLeave(loop, GameMode.LEVEL, stepCap);
                }
                waitForMode(loop, GameMode.LEVEL, stepCap);
            }
            int settledFramesConsumed = playback.getCursorFrame() - offset;
            if (settledFramesConsumed < firstGameplayFrame || settledFramesConsumed > 1) {
                throw new AssertionError("Destination playback cursor advanced "
                        + settledFramesConsumed + " frames after title-card handoff");
            }
            // The level can become active while its title card still owns the
            // loop. Reconcile once more after that choreography settles so any
            // same-step LEVEL fall-through is included in the manifest-derived
            // budget and cannot leave the destination clock one tick adrift.
            completeInterLevelVblankBudget(
                    currentLevel, nextLevel, settledFramesConsumed, sourceTailVblank);
            return settledFramesConsumed;
        }
		var profile = GameServices.module().getTracePlaybackProfile();
		if (profile.reinitializeOscillationAtLoadedLevelAttach()) {
			// The destination Level has already loaded inside the source segment's
			// death/exit tail. Re-establish the ROM's OscillateNumInit boundary at
			// the point where the destination movie segment attaches; otherwise
			// engine-only choreography frames advance v_oscillate before recorded
			// gameplay frame 0. This is movie-clock lifecycle pacing only: no trace
			// field is read into engine state.
			OscillationManager.resetForSonic1();
        }
        completeInterLevelVblankBudget(currentLevel, nextLevel, 0, sourceTailVblank);
        return 0;
    }

    /**
     * Offers the destination for admission every frame, stepping the engine
     * until the coordinator accepts -- exactly what production does, where
     * {@code TraceSessionLauncher#runCoordinatorTick} polls
     * {@link TraceRunPlaybackCoordinator#beforeAdmission} on every tick and
     * simply keeps stepping while it is denied.
     *
     * <p>The chain previously admitted one-shot. That is correct only when the
     * destination happens to be admissible the instant its level became active;
     * at a level-advance boundary whose act title card is still running, and
     * whose shared movie cursor has not yet reached the destination's first
     * recorded row, the coordinator legitimately denies and the one-shot call
     * failed the run. This is additive: a boundary that admits immediately
     * exits on iteration zero with the prepared row count unchanged.
     *
     * <p>Nothing here weakens the admission rule -- the engine must genuinely
     * become admissible. The loop is bounded by the same manifest-derived
     * {@code stepCap} every other await uses, and each step runs the coordinator's
     * own {@code afterStep} transition timeout.
     */
    private int admitLevelWhenReady(
            GameplayModeContext gameplayMode,
            GameLoop loop,
            PlaybackDebugManager playback,
            HeadlessRunCoordinatorAdapter runCoordinator,
            TraceRunManifest.Transition exit,
            BoundaryObservation obs,
            TraceRunSegmentDescriptor next,
            int preparedRowsConsumed,
            int stepCap,
            RunLevelLoadTracker.Receipt observedLoad,
            int segmentIndex,
            Path runDir) {
        int destinationOffset = next.segment().bk2FrameOffset();
        // Arm the production one-row gap latch HERE: this loop is the only
        // place the chain steps rows under SHARED_GAP, so arming anywhere else
        // leaves the answer to whatever the previous gap (or the previous test
        // class in this fork) happened to leave behind.
        gameplayMode.beginRunTransitionGap();
        // CONTRACT 1. The recorder's per-transition admission census covers
        // exactly the movie rows this loop walks: from the source segment's
        // end through the row before the destination's first recorded row. It
        // is run-length encoded and alternates starting non-lag, so its sum is
        // the gap length and its origin is derivable without carrying any
        // recorded row index -- the destination offset minus that sum.
        boolean[] gapLag = expandGapAdmissionCensus(exit);
        int gapOrigin = destinationOffset - gapLag.length;
        // CONTRACT 1, ordering. The ROM's level-entry load creates the
        // playables at its very END: Level: reaches InitPlayers
        // (docs/s2disasm/s2.asm:4946) only after LoadZoneTiles,
        // loadZoneBlockMaps, LoadAnimatedBlocks, DrawInitialBG,
        // ConvertCollisionArray, LoadCollisionIndexes and WaterEffects
        // (:4938-4945), and everything after it -- the leave loop at
        // :5060-5066 -- waits on V-int every pass, so the main loop is
        // admitted on every remaining row of the gap. The span that ends with
        // the players' creation is therefore the census's LAST non-admitted
        // run, located structurally and not by any length. The engine runs the
        // same load with no frame cost, so its playables take their first art
        // decision while the ROM was still loading; holding that decision
        // until this row moves only WHEN engine-created work becomes visible.
        int loadCompletionIndex = lastNonAdmittedRow(gapLag);
        holdPlayerArtForLevelEntryLoad(loadCompletionIndex >= 0);
        for (int step = 0; step <= stepCap; step++) {
            int cursor = playback.getCursorFrame();
            int rowsConsumed = step == 0
                    ? preparedRowsConsumed
                    : cursor - destinationOffset;
            if (rowsConsumed >= 0 && rowsConsumed <= 1
                    && runCoordinator.tryAdmitLevel(
                            exit, obs.observedBk2Frame(),
                            loop.getCurrentGameMode(),
                            rowsConsumed, observedLoad)) {
                // Fail-safe: a gap that admitted before its load-completion
                // row publishes the held decisions here rather than leaking
                // them into the next segment.
                releasePlayerArtForLevelEntryLoad();
                return rowsConsumed;
            }
            if (rowsConsumed > 1) {
                throw new AssertionError(
                        "Segment " + segmentIndex + " destination "
                                + next.segment().dir()
                                + " cursor advanced past its first recorded row"
                                + " without admission (cursor " + cursor
                                + ", offset " + destinationOffset + ") for "
                                + runDir);
            }
            int gapIndex = cursor - gapOrigin;
            boolean lagRow = gapIndex >= 0 && gapIndex < gapLag.length
                    && gapLag[gapIndex];
            stepEngineFrameInTransitionGap(
                    gameplayMode, loop, playback, playback.getCursorFrame(),
                    lagRow, gapIndex == loadCompletionIndex);
        }
        releasePlayerArtForLevelEntryLoad();
        throw new AssertionError(
                "Segment " + segmentIndex + " destination "
                        + next.segment().dir()
                        + " never became admissible within " + stepCap
                        + " steps (cursor " + playback.getCursorFrame()
                        + ", offset " + destinationOffset + ", mode "
                        + loop.getCurrentGameMode() + ") for " + runDir);
    }

    private LiveTraceComparator attachPreparedLevelSegment(
            PlaybackDebugManager playback, BoundaryProbe probe, Bk2Movie movie,
            TraceRunSegmentDescriptor nextLevel,
            LiveEngineFixture fixture, int rowsConsumed,
            int segmentIndex) {
        assertedPhysicsSegmentIndices.add(segmentIndex);
        int expectedCursor = nextLevel.segment().bk2FrameOffset() + rowsConsumed;
        if (playback.getCursorFrame() != expectedCursor) {
            if (rowsConsumed != 0) {
                throw new AssertionError(
                        "prepared destination cursor expected " + expectedCursor
                                + " but was " + playback.getCursorFrame());
            }
            playback.startSession(movie, expectedCursor);
        }
        LiveTraceComparator comparator = new LiveTraceComparator(
                activeTrace(nextLevel), ToleranceConfig.DEFAULT,
                rowsConsumed, fixture::sprite);
        return attachHeadlessComparator(
                probe, comparator, activeTrace(nextLevel), segmentIndex,
                () -> declareHardwareTimingSegment(segmentIndex));
    }

    /**
     * Declares that the walk has entered {@code segmentIndex} -- called where
     * the walk attaches that segment's row owner. The coordinator latches rows
     * only for the segment the walk says it is in, so this is the single thing
     * that admits a destination's rows.
     */
    private void declareHardwareTimingSegment(int segmentIndex) {
        if (activeHardwareTiming != null) {
            activeHardwareTiming.enterSegment(segmentIndex);
        }
    }

    /** Declares that the walk has released its row owner and is between segments. */
    private void declareHardwareTimingTransitionGap() {
        if (activeHardwareTiming != null) {
            activeHardwareTiming.enterTransitionGap();
        }
    }

    private void completeInterLevelVblankBudget(
            TraceRunSegmentDescriptor currentLevel,
            TraceRunSegmentDescriptor nextLevel,
            int nextFramesConsumed,
            int sourceTailVblank) {
        var profile = GameServices.module().getTracePlaybackProfile();
        if (!profile.alignsInterLevelVblank()) {
            return;
        }
        int requiredTicks = TraceRunReplayWalker.interLevelVblankBudget(
                currentLevel.segment(), nextLevel.segment(), nextFramesConsumed,
                TraceRunVblankClock.maskedLevelEntryLoss(
                        profile, nextLevel.segment()));
        var objectManager = GameServices.level().getObjectManager();
        int actualTicks = objectManager.getVblaCounter() - sourceTailVblank;
        if (actualTicks != requiredTicks) {
            // This is movie-clock pacing, not trace-field hydration: the target
            // comes only from BK2/manifest row counts plus the game profile's
            // measured non-advancing rows. It both fills shortened engine
            // transitions and removes synthetic host ticks that the ROM did not
            // count (notably S1's six-row death/act seam).
            objectManager.initVblaCounter(sourceTailVblank + requiredTicks);
        }
    }

    /**
     * Anchors the object V-blank clock on the first recorded row of the
     * presentation bridge a special stage exits through.
     *
     * <p>This is {@link TraceRunReplayWalker#presentationBridgeEntryVblankBudget}
     * -- the budget written for exactly this anchor -- and NOT the
     * {@code uncomparedInteriorReturnVblankBudget} a stage return uses when it
     * lands straight on a gameplay level. The two differ by one tick, and the
     * bridge budget's own contract says why: the anchor is the counter value in
     * effect ENTERING the row after the source level's final row, and the target
     * is the value entering the bridge's first row, so the budget is the rows
     * strictly between them. Using the gameplay-return budget here left the
     * whole post-bridge chain running one tick high -- invisible while the
     * bridge swallowed the clock outright, and phase-shifting once
     * {@link #completeBridgeExitVblankBudget} carries it forward, because
     * Sonic 1's spilled-ring floor probe gates on
     * {@code (v_vblank_byte + d7) & 3} ("_incObj/25, 37 Rings.asm":321-324).
     *
     * <p>Movie-clock pacing only: the target is the production counter plus a
     * manifest/BK2 row distance.
     */
    private void alignPresentationBridgeEntryVblank(
            TraceRunSegmentDescriptor sourceLevel,
            TraceRunSegmentDescriptor bridge,
            int sourceVblank) {
        if (!GameServices.module().getTracePlaybackProfile()
                .alignsStageResultsPresentationVblank()) {
            return;
        }
        int requiredTicks =
                TraceRunReplayWalker.presentationBridgeEntryVblankBudget(
                        sourceLevel.segment(), bridge.segment());
        GameServices.level().getObjectManager()
                .initVblaCounter(Math.addExact(sourceVblank, requiredTicks));
    }

    /**
     * Reconciles the object V-blank clock across a presentation bridge's EXIT
     * into the gameplay level that follows it.
     *
     * <p>The bridge's entry is already anchored (the special stage's return
     * alignment lands the counter on the bridge's first recorded row), but the
     * bridge itself is driven as physical rows: the engine's level loop does not
     * run on them, so the production counter does not tick across the bridge and
     * every one of its rows was silently lost from the clock. That is the whole
     * of Sonic 1's inter-level drift after a special stage -- it accumulates once
     * per stage return and never recovers.
     *
     * <p>Neither half of the projection is a new number. The bridge's own span
     * is {@link TraceRunReplayWalker#presentationBridgeVblankSpan}: one tick per
     * recorded row, less the rows the ROM builds the results screen through with
     * interrupts disabled ({@code SS_Finish}'s {@code disable_ints ...
     * enable_ints} block, docs/s1disasm/sonic.asm:3369-3383, so the V-int never
     * reaches {@code VBlank_Exit}'s unconditional {@code addq.l
     * #1,(v_vblank_count).w} at :684) -- the count the game profile already owns
     * as {@code stageResultsEntryNonAdvancingMovieRows}. The gap from the
     * bridge's tail to the destination level's first row is then the ORDINARY
     * level -> level budget, masked by the destination's own {@code Level:}
     * entry cost, exactly as any other level -> level boundary in the chain.
     *
     * <p>Movie-clock pacing only, like its two neighbours: the targets come from
     * the production counter plus manifest/BK2 row distances and the profile's
     * measured masks. No trace field is read back into engine state.
     */
    private void completeBridgeExitVblankBudget(
            TraceRunSegmentDescriptor bridge,
            TraceRunSegmentDescriptor gameplay,
            int bridgeEntryVblank) {
        var profile = GameServices.module().getTracePlaybackProfile();
        if (!profile.alignsStageResultsPresentationVblank()) {
            return;
        }
        int bridgeTailVblank = Math.addExact(
                bridgeEntryVblank,
                TraceRunReplayWalker.presentationBridgeVblankSpan(
                        bridge.segment(),
                        profile.stageResultsEntryNonAdvancingMovieRows()));
        completeInterLevelVblankBudget(bridge, gameplay, 0, bridgeTailVblank);
    }

    private void alignUncomparedInteriorReturnVblank(
            TraceRunSegmentDescriptor sourceLevel,
            TraceRunSegmentDescriptor returnLevel,
            int sourceVblank,
            int returnFramesConsumed) {
        var profile = GameServices.module().getTracePlaybackProfile();
        if (!profile.alignUncomparedInteriorReturnVblank()) {
            return;
        }
        int requiredTicks = TraceRunReplayWalker.uncomparedInteriorReturnVblankBudget(
                sourceLevel.segment(), returnLevel.segment(), returnFramesConsumed,
                TraceRunVblankClock.specialStageReturnLoss(
                        profile, returnLevel.segment()));
        // Movie-clock pacing only: the target derives from the source engine
        // counter and manifest/BK2 row distance. No trace field is read back
        // into engine state.
        GameServices.level().getObjectManager().initVblaCounter(sourceVblank + requiredTicks);
    }


    /**
     * SS-INTERIOR SEAM (policy v1 = ADVANCE-UNCOMPARED). Per-frame special-stage
     * field comparison is an explicitly LATER workflow; when it lands, build the
     * special-stage comparator HERE instead of returning {@code null} for a
     * {@code special_stage} segment. See "Decisions locked with the owner" item 1
     * in docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md.
     *
     * <p>v1: a {@code bonus_stage} interior returns a per-frame
     * {@link LiveTraceComparator}; a {@code special_stage} interior returns
     * {@code null} so the boundary probe forwards to no gameplay comparator
     * across the whole special-stage phase. Its independent structural row
     * driver may still compare an advertised DPLC heartbeat.
     *
     * <p>The bonus comparator's initial cursor is {@code 0} (compares from the
     * interior's body frame 0); see the COMPARATOR FRAME BASE contract on
     * {@link #attachReturnedLevelSegment}.
     */
    protected LiveTraceComparator attachInteriorComparator(
            TraceRunSegmentDescriptor interior,
            LiveEngineFixture fixture) {
        if (TraceRunReplayWalker.isUncomparedInterior(interior.segment())) {
            return null;
        }
        return new LiveTraceComparator(
                activeTrace(interior), ToleranceConfig.DEFAULT, 0,
                fixture::sprite);
    }

    // -------------------------------------------------------------------------
    // Return-boundary assertions (per contract section 3.2)
    // -------------------------------------------------------------------------

    /**
     * Asserts the carry-over state after a {@code stage_exit}, switching on
     * {@link TraceRunReplayWalker#returnAssertionMode} of the INTERIOR's entry
     * transition. Overridable so a lane can adjust game-specific accessors.
     *
     * @param descriptors   the full planned segment list
     * @param interiorIndex index of the interior segment that just exited
     */
    protected void assertReturnBoundary(
            List<TraceRunSegmentDescriptor> descriptors,
            int interiorIndex, Path runDir) {
        TraceRunSegmentDescriptor interior = descriptors.get(interiorIndex);
        TraceRunManifest.Transition entry = interior.entryBoundary();
        TraceRunManifest.Transition exit = interior.exitBoundary();
        assertNotNull(entry, "Interior segment must have an entry boundary: " + runDir);
        assertNotNull(exit, "Interior segment must have an exit boundary: " + runDir);
        TraceRunSegmentDescriptor returnLevel =
                descriptors.get(interiorIndex + 1);
        TraceRunSegmentDescriptor preEntry =
                descriptors.get(entry.fromSegment());
        assertTrue(returnLevel.rowCount() > 0,
                "Return level segment has no recorded frames: " + runDir);
        Integer resolvedReturnZone = returnLevel.segment().zoneId() == null
                ? null
                : GameServices.module().getTracePlaybackProfile()
                        .resolveRecordedLevel(
                                returnLevel.segment().zoneId(),
                                returnLevel.segment().act())
                        .zone();
        AbstractPlayableSprite sprite =
                GameServices.camera().getFocusedSprite();
        assertNotNull(sprite,
                "Focused sprite missing on special-stage return for " + runDir);
        TraceRunBoundaryComparator.ExpectedBoundary expected =
                new TraceRunBoundaryComparator.ExpectedBoundary(
                        entry,
                        exit,
                        preEntry.segment(),
                        returnLevel.segment(),
                        returnLevel.openingFrame(),
                        resolvedReturnZone,
                        returnLevel.metadata().startX() & 0xFFFF,
                        returnLevel.metadata().startY() & 0xFFFF);
        TraceRunBoundaryComparator.ActualBoundary actual =
                new TraceRunBoundaryComparator.ActualBoundary(
                        (int) sprite.getCentreX(),
                        (int) sprite.getCentreY(),
                        GameServices.level().getCheckpointState()
                                .getLastCheckpointIndex(),
                        GameServices.level().getCurrentZone(),
                        GameServices.level().getCurrentAct(),
                        GameServices.level().getLevelGamestate().getRings(),
                        GameServices.gameState().getEmeraldCount(),
                        emeraldCarryOverIsVerifiable(interior),
                        GameServices.playbackDebug().getCursorFrame()
                                - returnLevel.segment().bk2FrameOffset());
        FrameComparison comparison = TraceRunBoundaryComparator.compare(
                exit.modeChangeBk2Frame(), expected, actual);
        List<com.openggf.trace.FieldComparison> errors =
                comparison.divergentFields().stream()
                        .filter(field -> field.severity()
                                == com.openggf.trace.Severity.ERROR)
                        .toList();
        assertTrue(errors.isEmpty(),
                "Shared return-boundary comparison failed for " + runDir
                        + ": " + errors);
    }

    /**
     * When the live engine emerald count is NOT an organically verifiable boundary
     * (an advance-uncompared {@code special_stage} whose win/lose outcome the chain
     * does not reproduce — see
     * {@link #emeraldCarryOverIsVerifiable(TraceRunSegmentDescriptor)}),
     * this asserts the one emerald fact that IS verifiable: the RECORDED manifest's
     * own emerald progression across the interior. It does not touch engine state.
     *
     * <p>An S2 special stage banks exactly one emerald when cleared, so a recorded
     * run that entered with {@code emeralds_before} and returned with
     * {@code emeralds_after} must satisfy {@code emeralds_after == emeralds_before + 1}
     * (the committed {@code s2-ehz-halfpipe-roundtrip} run clears both halfpipes,
     * banking 0->1 then 1->2). Asserting this recorded-truth invariant replaces the
     * former silent no-op, so a manifest that fails to record the emerald award (or
     * records an impossible delta) is caught even while the live count stays a
     * diagnostic. Gated on both recorded fields being present and overridable for a
     * lane whose recorded special stage was NOT a clear.
     */
    protected void assertRecordedEmeraldProgression(
            TraceRunManifest.Transition entry, TraceRunManifest.Transition exit, Path runDir) {
        if (entry.emeraldsBefore() == null || exit.emeraldsAfter() == null) {
            return;
        }
        assertEquals(entry.emeraldsBefore() + 1, exit.emeraldsAfter().intValue(),
                "Recorded emerald bank across a cleared special stage (emeralds_after == "
                        + "emeralds_before + 1) for " + runDir);
    }

    /**
     * Whether the emerald carry-over across this interior is an organically
     * VERIFIABLE boundary (a comparison against engine state the replayed inputs
     * produced), rather than only a recorded manifest datum.
     *
     * <p>An emerald is awarded only when the interior stage is <em>won</em>, so the
     * post-return emerald count is a faithful comparison target only when the
     * interior was faithfully reproduced. A {@code bonus_stage} interior IS
     * reproduced (it is driven with a per-frame {@link LiveTraceComparator}, see
     * {@link #attachInteriorComparator}), so its emerald delta is asserted. A
     * {@code special_stage} interior is <b>advance-uncompared</b> under SS-INTERIOR
     * policy v1 ({@link TraceRunReplayWalker#isUncomparedInterior}) — it is phased
     * through without per-frame reproduction — so its win/lose outcome, and hence
     * the emerald it would award, is NOT organically re-derivable.
     *
     * <p>This is compounded, and made unrecoverable in code, by a property of the
     * committed run fixtures: the multi-segment run recorder captured each
     * special-stage segment's {@code physics.csv} (frames + lag column) but wrote
     * no {@code run_objects_end} pass snapshots. (The aux stream is <b>not</b>
     * empty — the committed {@code s2-ehz-halfpipe-roundtrip/ss/aux_state.jsonl.gz}
     * carries 6762 records, including 5733 {@code dynamic_art_transfer_state} rows,
     * both {@code control_state} transitions, {@code checkpoint},
     * {@code stage_finished} and {@code results_started}. Exactly one family is
     * missing, and it is the pacing one.) The S2 half-pipe is ROM-object-pass
     * paced: the standalone must-stay-green {@code TestS2SpecialStageTraceReplay}
     * drives it via {@code SpecialStageRunObjectsPassBinder}, binding each
     * recurring RunObjects pass to the exact BK2 row the ROM's V-int sampled.
     *
     * <p><b>The 36-vs-40 checkpoint failure was neither a recorder gap nor a pacing
     * gap: it was a stale ring read, and it is now fixed.</b> Probing the interior
     * row by row against the recorded {@code ss} rows showed the engine reproducing
     * the recording exactly — {@code current_segment}, {@code speed_factor},
     * {@code track_anim_frame} and the combined ring count all matched every row up
     * to the eject. The engine had 42 rings by segment-local row 1588, just as the
     * ROM did; it simply evaluated checkpoint 1 against the count captured when the
     * checkpoint marker was passed (36, at row ~1538) rather than the count at the
     * end of the checkpoint rainbow. The ROM reads {@code (Ring_count)} and
     * {@code (Ring_count_2P)} live at {@code loc_35978} (docs/s2disasm/s2.asm:
     * 71843-71853), reached only when the rainbow object's x reaches {@code $E8} and
     * it deletes itself, so rings picked up during the rainbow still count. With
     * {@code Sonic2SpecialStageCheckpoint} resolving against a live ring supplier,
     * checkpoint 1 passes and the interior advances from row 2027 to row 5213 of
     * 5733.
     *
     * <p><b>Remaining, and now the frontier for this lane:</b> the engine leaves
     * {@code GameMode.SPECIAL_STAGE} 519 represented rows early. The recorded
     * {@code check_rings_flag} rises at segment-local frame 5191 and the ROM then
     * spends the rest of the segment (through frame 5732) still in special-stage
     * mode running the post-flag tail of {@code SS_MainLoop} — emerald/perfect
     * accounting, {@code Pal_FadeToWhite}, the results screen build and its
     * {@code Obj6F} tally (s2.asm:6721-6800). The engine reaches its own finish at
     * row 5213 (22 rows late) and then exits the mode almost immediately. Closing
     * this needs the special-stage results phase to occupy the ROM's V-blanks; it is
     * unrelated to ring collection.
     *
     * <p>The pacing pieces themselves are now in place:
     *
     * <ol>
     *   <li><b>The re-record.</b> The native harness at HEAD emits run-mode
     *       special-stage pass records. A scratch re-capture of this run's own
     *       movie reproduces all five committed segments' {@code physics.csv}
     *       byte-for-byte and every level segment's aux byte-for-byte, and adds
     *       2991 {@code run_objects_end} records to {@code ss} and 3291 to
     *       {@code ss_2} — a strict superset, the rest of each aux stream is
     *       unchanged — plus a sixth {@code seg4_ehz2} tail segment the committed
     *       fixture stops short of. Publishing those bytes is a fixture-publication
     *       decision, not a code one, and the sixth segment widens what the fixture
     *       claims to cover, so it is not folded in silently.</li>
     *   <li><b>A pass-paced interior.</b> Landed: {@link #uncomparedInteriorStep}
     *       now consumes {@code newRunObjectsPassBinder}/{@code passPacedFromRow}.
     *       It is inert on a segment that recorded no passes, so every committed
     *       fixture keeps its previous one-step-per-row behaviour.</li>
     *   <li><b>A V-blank body that can run 0..n object passes.</b> Landed: pass
     *       pacing cannot be one {@code GameLoop.step()} per pass, because a step
     *       is one V-blank row and an audited segment's
     *       {@code TraceRunSpecialStageRowDriver} requires each admitted row to
     *       publish exactly one dynamic-art delivery ({@code publishAdmittedRow}:
     *       "advertised special-stage row N was not published atomically"). On the
     *       re-capture, {@code ss} rows own zero passes 3341 times, one pass 1793
     *       times and two passes 599 times, so row-to-step and pass-to-step cannot
     *       both hold. {@link GameLoop.SpecialStageObservationPacing} makes the
     *       special-stage body run the row's completed-pass count inside its single
     *       PLC lifecycle iteration, the same split
     *       {@code S2SpecialStageReplayHarness.stepPasses} already makes.</li>
     * </ol>
     *
     * <p>The recorded
     * {@code emeralds_after} therefore
     * stays a diagnostic for an advance-uncompared special stage. The always-safe
     * carry-overs — the ROM's on-return position restore and ring zero-out, which
     * happen whether the stage was won or lost — remain asserted.
     *
     * <p>Overridable so a lane whose special-stage interior IS faithfully drivable
     * from its own fixture (or a future policy that compares special stages
     * per-frame) can re-enable the emerald assertion. Keyed purely on the manifest
     * segment kind via {@code isUncomparedInterior} — not on zone/route/game.
     */
    protected boolean emeraldCarryOverIsVerifiable(
            TraceRunSegmentDescriptor interior) {
        return !TraceRunReplayWalker.isUncomparedInterior(interior.segment());
    }

    /**
     * S2 starpost_special: player restored to Saved_x/y (ROM {@code Obj79_LoadData}:
     * {@code move.w (Saved_x_pos).w,(MainCharacter+x_pos).w} /
     * {@code move.w (Saved_y_pos).w,(MainCharacter+y_pos).w}).
     *
     * <p>The comparison target is the RETURN LEVEL segment's own recorded frame 0
     * (ground truth captured live from the ROM), not the entry transition's raw
     * {@code saved_x_pos}/{@code saved_y_pos} fields. Those fields are a snapshot of
     * the ROM {@code Saved_x_pos}/{@code Saved_y_pos} RAM cells taken at CHECKPOINT
     * TOUCH time (ROM {@code Obj79_SaveData}: {@code move.w x_pos(a0),(Saved_x_pos).w}
     * where {@code a0} is the checkpoint OBJECT, not the player) -- i.e. the
     * checkpoint's own static placement, which is not guaranteed to sit exactly on
     * the floor. X is unaffected by gravity so both sources agree, but after the
     * {@code Obj79_LoadData} write restores Y verbatim, the ROM's own level physics
     * keeps running every frame through the ensuing title-card phase (player input
     * is locked but gravity is not), settling the player onto the actual floor
     * before {@code mode_change_bk2_frame}/{@code stage_exit} is reached. Confirmed
     * against the committed run's {@code seg2_ehz1} physics.csv: frame 0 already
     * reads {@code player_y=00AD} (173), not the transition's {@code saved_y_pos=170}
     * -- so asserting the raw entry-time snapshot against the settled return
     * position would fail on ROM-faithful engine output. The return level's frame 0
     * is recorded at the exact same {@code mode_change_bk2_frame}
     * ({@code segment.bk2FrameOffset() == transition.modeChangeBk2Frame()} for every
     * {@code stage_exit} in this run), so it is the correct ground truth for this
     * boundary.
     */
    protected void assertPositionalRestore(
            TraceRunManifest.Transition entry,
            TraceRunSegmentDescriptor returnLevel, Path runDir) {
        AbstractPlayableSprite sprite = GameServices.camera().getFocusedSprite();
        assertNotNull(sprite, "Focused sprite missing on special-stage return for " + runDir);
        assertTrue(returnLevel.rowCount() > 0,
                "Return level segment has no recorded frames: " + runDir);
        var restored = returnLevel.openingFrame();
        if (entry.savedXPos() != null) {
            assertEquals(restored.x(), (int) sprite.getCentreX(),
                    "Player X restore after special-stage return for " + runDir);
        }
        if (entry.savedYPos() != null) {
            assertEquals(restored.y(), (int) sprite.getCentreY(),
                    "Player Y restore after special-stage return for " + runDir);
        }
    }

    /** S3K starpost_bonus: checkpoint index restored from last_star_post_hit. */
    protected void assertCheckpointRestore(TraceRunManifest.Transition entry, Path runDir) {
        if (entry.lastStarPostHit() != null) {
            int actual = GameServices.level().getCheckpointState().getLastCheckpointIndex();
            assertEquals(entry.lastStarPostHit().intValue(), actual,
                    "Star-post restore after bonus-stage return for " + runDir);
        }
    }

    /**
     * S1 giant_ring (no saved position): the special-stage return is a NEXT-ACT
     * advance, not a positional resume. Asserts the engine settled into the
     * return level segment's declared zone/act AND that it differs from the
     * pre-entry level segment (proving an advance). Overridable if a lane's
     * engine zone/act index differs from the manifest's zone_id/act encoding.
     */
    protected void assertNextActAdvance(
            List<TraceRunSegmentDescriptor> descriptors,
            int interiorIndex, TraceRunSegmentDescriptor returnLevel,
            Path runDir) {
        TraceRunManifest.Transition entry =
                descriptors.get(interiorIndex).entryBoundary();
        TraceRunSegmentDescriptor preEntry =
                descriptors.get(entry.fromSegment());
        Integer returnAct = returnLevel.segment().act();
        Integer preAct = preEntry.segment().act();
        if (returnAct != null && preAct != null) {
            assertNotEquals(preAct.intValue(), returnAct.intValue(),
                    "Manifest next-act shape: return act must differ from pre-entry act for " + runDir);
            // Manifest `act` is 1-based; GameServices.level().getCurrentAct() is the
            // engine's 0-based act index (see the boot-act conversion note in
            // runChain / engineAct below) -- convert before comparing.
            assertEquals(engineAct(returnAct).intValue(), GameServices.level().getCurrentAct(),                    "Next-act advance (act) after special-stage return for " + runDir);
        }
        Integer returnZone = returnLevel.segment().zoneId();
        if (returnZone != null) {
            int expectedZone = GameServices.module().getTracePlaybackProfile()
                    .resolveRecordedLevel(returnZone, returnLevel.segment().act()).zone();
            assertEquals(expectedZone, GameServices.level().getCurrentZone(),
                    "Next-act advance (zone) after special-stage return for " + runDir);
        }
    }

    /**
     * Rings/emeralds carry-over. A recorded {@code rings_after == 0} (S2 zeroes
     * rings on special-stage return) is ROM truth and asserted like any value.
     * The ring carry-over is always asserted (the ROM sets it on return regardless
     * of whether the interior stage was won or lost). The emerald carry-over is
     * asserted only when {@code assertEmeralds} is true — see
     * {@link #emeraldCarryOverIsVerifiable(TraceRunSegmentDescriptor)} for why an
     * advance-uncompared special stage passes {@code false} here.
     *
     * @param assertEmeralds whether the emerald delta is an organically verifiable
     *                       boundary for this interior (true for a reproduced
     *                       {@code bonus_stage}; false for an advance-uncompared
     *                       {@code special_stage})
     */
    protected void assertRingsAndEmeralds(
            TraceRunManifest.Transition exit, Path runDir, boolean assertEmeralds) {
        if (exit.ringsAfter() != null) {
            int actualRings = GameServices.level().getLevelGamestate().getRings();
            assertEquals(exit.ringsAfter().intValue(), actualRings,
                    "Ring carry-over after stage exit for " + runDir);
        }
        if (assertEmeralds && exit.emeraldsAfter() != null) {
            int actualEmeralds = GameServices.gameState().getEmeraldCount();
            assertEquals(exit.emeraldsAfter().intValue(), actualEmeralds,
                    "Emerald count after stage exit for " + runDir);
        }
    }

    /**
     * Converts a manifest {@code act} (1-based, matching the recorder's
     * {@code metadata.json}/{@code run_manifest.json} convention -- e.g.
     * {@code "act": 1} means Act 1) to the engine's 0-based act index consumed
     * by {@code loadZoneAndAct}/{@code getCurrentAct()} and every standalone
     * lane's {@code AbstractTraceReplayTest#act()} override (which returns 0
     * for Act 1). Mirrors {@code TraceCatalog}'s {@code engineAct = meta.act() - 1}
     * conversion so the chain driver never feeds a raw 1-based manifest value
     * to an engine act parameter.
     */
    private static Integer engineAct(Integer manifestAct) {
        return manifestAct == null ? null : Math.max(0, manifestAct - 1);
    }

    // -------------------------------------------------------------------------
    // Stepping helpers
    // -------------------------------------------------------------------------

    /**
     * Builds the step function used to drive an uncompared (special_stage)
     * interior. Overridable so a lane with a per-game special-stage trace
     * format (carrying a per-row lag flag, e.g. {@code SpecialStageTraceData}
     * for S2 or {@code Sonic1SpecialStageTraceData} for S1) can skip lag rows
     * the way the standalone SS trace-replay harnesses do. Default: feed
     * EVERY recorded BK2 row as a full physics tick via
     * {@link #specialStageDrivenStep} (no lag-skip) -- correct as long as the
     * interior's actual outcome carry-over is not asserted, or a lane
     * overrides this.
     *
     * <p>When the segment recorded ROM {@code RunObjects} completions, the
     * interior is <b>pass-paced</b> from {@link
     * TraceRunSpecialStageRows#passPacedFromRow()} onwards: each admitted row
     * still costs exactly one {@link GameLoop#step()} (one V-blank row, one
     * dynamic-art publication, which {@code TraceRunSpecialStageRowDriver}
     * requires), but that step's special-stage body runs however many object
     * passes the ROM completed for the row, via
     * {@link GameLoop.SpecialStageObservationPacing}. This is the same split
     * the standalone {@code TestS2SpecialStageTraceReplay} makes through
     * {@code S2SpecialStageReplayHarness.stepPasses}. Frame-pacing instead —
     * one pass per admitted row — advances the S2 half-pipe track roughly 1.77x
     * too fast, so the recorded ring-requirement reloads land against the wrong
     * internal frame and the player under-collects at checkpoint 1. A segment
     * with no recorded passes keeps the previous one-step-per-row behaviour.
     */
    /**
     * Whether the engine is still inside the game mode a recorded
     * {@code special_stage} segment represents.
     *
     * <p>The run recorder cuts an {@code ss} segment on the raw ROM byte:
     * it opens on the first {@code Game_Mode == GameModeID_SpecialStage}
     * ($10) frame and closes on the first frame that is no longer $10
     * ({@code S2RunCaptureRunner} Blocks 1 and 2). In the ROM that single
     * mode spans more than the half-pipe: {@code SS_MainLoop} exits its
     * object loop when {@code SS_Check_Rings_flag} rises, and the
     * emerald/perfect accounting, {@code Pal_FadeToWhite}, the results-screen
     * build and the whole {@code Obj6F} tally loop all run below it, still
     * under $10 — {@code Game_Mode} is not rewritten until the
     * {@code move.b #GameModeID_Level,(Game_Mode).w} at the very end
     * (docs/s2disasm/s2.asm:6721-6800). S1 is the same shape
     * ({@code GM_Special} owns {@code SS_Finish} through
     * {@code sonic.asm:3419-3421}).
     *
     * <p>The engine splits that one ROM mode into two of its own,
     * {@code SPECIAL_STAGE} and {@code SPECIAL_STAGE_RESULTS}, so a plain
     * {@code == SPECIAL_STAGE} test reports a premature exit at the internal
     * boundary even when the engine is faithfully still in the ROM's mode.
     * Both engine modes therefore map onto the one recorded segment.
     */
    private static boolean insideRecordedSpecialStageGameMode(GameMode mode) {
        return RunPlaybackObservation.insideRecordedSpecialStageMode(mode);
    }

    protected IntConsumer uncomparedInteriorStep(
            GameLoop loop,
            InputHandler inputHandler,
            Bk2Movie movie,
            TraceRunSegmentDescriptor interior,
            TraceRunSpecialStageRows rows,
            SpecialStageRunObjectsPassBinder passBinder) {
        int bk2FrameOffset = interior.segment().bk2FrameOffset();
        int passPacedFromRow = passBinder == null
                ? Integer.MAX_VALUE : rows.passPacedFromRow();
        return localRow -> {
            // The recorder hooks both halves of SpecialStage_MainLoop
            // (docs/s2disasm/s2.asm:6674-6721), so the pre-start loop's passes
            // are in the stream too. They are already executed by the one-step-
            // per-row pacing this interior uses before passPacedFromRow, so the
            // cursor is advanced over them rather than re-stepped -- but it must
            // be advanced, because the binder rejects a skipped observation.
            List<SpecialStageRunObjectsPassBinder.CompletedPass> observationPasses =
                    passBinder == null
                            ? List.of() : passBinder.passesForObservation(localRow);
            TraceRunSpecialStageRows.SpecialStageRowAdmission admission =
                    rows.admission(localRow);
            // An observation that executes a RunObjects pass is never a lag
            // V-blank, whatever the recorder's lag heuristic reports:
            // SS_MainLoop sets VintID_S2SS and waits on it immediately before
            // the pass (docs/s2disasm/s2.asm:6694-6706), so V_Int cannot have
            // taken the Vint_Lag branch -- that branch runs only while
            // Vint_routine is still 0 (docs/s2disasm/s2.asm:483-484). The
            // binder already relies on this to place the terminal
            // stage-finished pass on its raw-lag observation
            // (TraceRunSpecialStageRows.S2Rows.newRunObjectsPassBinder), so
            // the row driver must execute that observation rather than skip
            // it -- otherwise the stage's last pass, and the player DPLC pair
            // it submits, land outside the compared window entirely. Same rule
            // as S2SpecialStageReplayHarness.stepPasses / observationPhase.
            boolean ownsCompletedPass = !observationPasses.isEmpty();
            boolean executeGameplay = admission.executeGameplay() || ownsCompletedPass;
            var beforeManager = GameServices.level().getObjectManager();
            int beforeVblank = beforeManager.getVblaCounter();
            if (!ownsCompletedPass) {
                admission.syntheticPlcPhase().ifPresent(
                        AbstractRunChainTest::stepUncomparedInteriorLifecycleRow);
            }
            if (executeGameplay
                    && loop.getCurrentGameMode()
                            == GameMode.SPECIAL_STAGE_RESULTS) {
                // Still inside the recorded segment (ROM Game_Mode is still
                // GameModeID_SpecialStage; see
                // insideRecordedSpecialStageGameMode), but past the half-pipe
                // itself: the ROM's post-flag tail runs RunObjects /
                // BuildSprites / RunPLC_RAM per V-int and samples no player
                // control at all (docs/s2disasm/s2.asm:6795-6800), so drive a
                // bare engine frame with no recorded-input override.
                stepEngineFrame(loop);
            } else if (executeGameplay
                    && loop.getCurrentGameMode() == GameMode.SPECIAL_STAGE) {
                int absoluteRow = bk2FrameOffset + localRow;
                Bk2FrameInput current = movie.getFrame(absoluteRow);
                Bk2FrameInput previous = absoluteRow > 0
                        ? movie.getFrame(absoluteRow - 1) : null;
                inputHandler.setLogicalOverride(
                        RecordedInputSnapshots.fromBk2(current, previous));
                if (localRow >= passPacedFromRow) {
                    // The recorded pass stream and lag-row admission are the
                    // scheduling authority. Keep the legacy provider
                    // notification, but do not derive gameplay state from it.
                    loop.getActiveSpecialStageProvider().setLagCompensation(0);
                    loop.setSpecialStageObservationPacing(
                            recordedPassPacing(movie, observationPasses, localRow));
                }
                try {
                    stepEngineFrame(loop);
                } finally {
                    loop.setSpecialStageObservationPacing(null);
                    inputHandler.clearLogicalOverride();
                }
            }
            var afterManager = GameServices.level().getObjectManager();
            if (admission.advancePreservedVblankIfUnchanged()
                    && afterManager.getVblaCounter() == beforeVblank) {
                afterManager.advanceVblaCounter();
            }
        };
    }

    /**
     * Pacing for one observation, delegated to the shared owner both this
     * harness and the production {@link com.openggf.TraceSessionLauncher}
     * replay path use.
     */
    private static GameLoop.SpecialStageObservationPacing recordedPassPacing(
            Bk2Movie movie, List<SpecialStageRunObjectsPassBinder.CompletedPass> passes,
            int observationFrame) {
        return SpecialStageRecordedPassPacing.forObservation(
                movie, passes, observationFrame);
    }

    /** Replays and asserts only the terminal behavior declared by the run manifest. */
    private void replayTerminalMovieTail(
            TraceRunReplayWalker.TerminalMovieTailPlan tailPlan,
            GameLoop loop, InputHandler inputHandler, Bk2Movie movie,
            PlaybackDebugManager playback, LiveEngineFixture fixture,
            TraceRunFrameDriver driver,
            HeadlessRunCoordinatorAdapter coordinator) {
        if (tailPlan == null) {
            return;
        }
        for (int row = 0; row < tailPlan.rowsToReplay(); row++) {
            assertEquals(tailPlan.tailStart() + row,
                    playback.getCursorFrame(),
                    "terminal tail must retain the continuous movie cursor");
            driveHeadlessTransitionRow(
                    driver, TraceRunFrameDriver.Disposition.TERMINAL_TAIL,
                    loop, inputHandler, movie, playback, fixture, coordinator);
        }

        System.out.printf("[TRACE-RUN-TAIL] rows=%d finalMode=%s%n",
                tailPlan.rowsToReplay(), loop.getCurrentGameMode());
        assertEquals(tailPlan.expectedMode(), loop.getCurrentGameMode(),
                "Complete movie must finish in the manifest-declared mode");
    }

    /**
     * Builds a step function that drives an uncompared (special_stage)
     * interior with the SAME recorded BK2 rows the segment was captured
     * from, using a segment-local row counter starting at
     * {@code bk2FrameOffset} -- independent of the shared
     * {@code PlaybackDebugManager} cursor, which never advances in
     * SPECIAL_STAGE mode (see the call-site comment in {@link #runChain}).
     * Sets an {@link InputHandler} logical override for the duration of one
     * {@link GameLoop#step()} call and clears it immediately after, mirroring
     * {@code S1SpecialStageReplayHarness.stepFrame} / {@code
     * TraceSessionLauncher#applySpecialStageTraceInputIfActive}. The "previous"
     * row for press-edge detection is always the immediately preceding
     * physical BK2 row, matching the same rule those callers use.
     *
     * <p>Package-visible (not private) so a lane's {@link #uncomparedInteriorStep}
     * override can build its own lag-aware variant of this same input-feed/
     * step/clear sequence (see {@code stepEngineFrame}, also package-visible
     * for the same reason) instead of duplicating {@link #runChain}'s
     * plumbing.
     */
    IntConsumer specialStageDrivenStep(
            GameLoop loop, InputHandler inputHandler, Bk2Movie movie,
            int bk2FrameOffset, int recordedFrameCount) {
        return localRow -> {
            var beforeManager = GameServices.level().getObjectManager();
            int beforeVblank = beforeManager.getVblaCounter();
            int absoluteRow = bk2FrameOffset + localRow;
            Bk2FrameInput current = movie.getFrame(absoluteRow);
            Bk2FrameInput previous = absoluteRow > 0 ? movie.getFrame(absoluteRow - 1) : null;
            inputHandler.setLogicalOverride(RecordedInputSnapshots.fromBk2(current, previous));
            try {
                stepEngineFrame(loop);
            } finally {
                inputHandler.clearLogicalOverride();
            }
            // Special-stage policy v1 deliberately advances without a field
            // comparator. Account for at most the recorded number of interior
            // VBlank rows. If the simplified engine choreography takes extra
            // host steps to reach LEVEL, those steps are not extra BK2 VBlanks;
            // if a step already advanced the preserved ObjectManager clock (for
            // example the title-card-exit LEVEL fall-through), do not double-tick.
            var afterManager = GameServices.level().getObjectManager();
            if (localRow < recordedFrameCount
                    && afterManager.getVblaCounter() == beforeVblank) {
                afterManager.advanceVblaCounter();
            }
        };
    }

    /**
     * Represents one recorded row the ROM's main loop did not complete an
     * iteration for.
     *
     * <p>A LAG row is run through {@code runSuppressedLagIteration}, not the
     * ordinary logical iteration, because a lag V-blank does not run the mode's
     * handler at all: {@code V_Int} branches to {@code Vint_Lag} while
     * {@code Vint_routine} is still 0 (docs/s2disasm/s2.asm:483-484, 529) —
     * S1's {@code VBlank_Lag} (docs/s1disasm/sonic.asm:709) has the same shape
     * — so an in-progress blocking fade's per-V-blank work
     * ({@code Pal_FadeFromWhite}, s2.asm:3460-3482, whose body runs in the main
     * loop between its own {@code WaitForVint}s) does not advance on that
     * V-blank and must not claim the row. The ordinary path lets the active
     * native blocking fade claim {@code PALETTE_FADE} first, which both steals
     * the row from {@code LAG} and makes it publish as a non-lag row, so any
     * dynamic-art edge the row's V-blank produced lands one row early instead
     * of being carried to the next represented closure. This is the same split
     * {@code TraceRunPresentationClosure} already makes for a carried lag
     * closure.
     */
    private static void stepUncomparedInteriorLifecycleRow(
            PlcLifecyclePhase phase) {
        var lifecycle =
                SessionManager.getCurrentGameplayMode().plcFrameLifecycle();
        if (phase == PlcLifecyclePhase.LAG) {
            lifecycle.runSuppressedLagIteration(row -> {
                if (row.claim(phase)) {
                    row.prepareAfterLoop(phase);
                }
                return null;
            });
            return;
        }
        lifecycle.runLogicalIteration(() -> {
        }, row -> {
            if (row.claim(phase)) {
                row.prepareAfterLoop(phase);
            }
            return null;
        });
    }

    /**
     * Steps on past a segment's frame budget while it still has recorded rows
     * left to consume.
     *
     * <p>The budget above is a count of FRAMES; the cursor it is meant to land
     * is a count of ROWS. They agree for a segment whose every step consumes one
     * row, which is nearly every segment. They do not agree where the drive
     * spends a step WITHOUT consuming a row -- the transition freeze's
     * request-consume frame is one, and asking for the freeze is itself another
     * -- so the walk stopped short of the segment's declared end by however many
     * such frames it spent, and the shared cursor never reached the row the next
     * segment is admitted from.
     *
     * <p>Strictly additive: the budgeted frames are still stepped first and this
     * loop exits immediately for a segment whose rows are already consumed, so no
     * boundary that works today steps a frame fewer. {@code
     * hasUnconsumedRecordedRows} is the comparator's own answer about its own
     * cursor -- the same predicate the freeze itself consults -- so no row count,
     * tail length or segment identity is encoded here. The loop is bounded by
     * the manifest-derived {@code stepCap} every other await in this class uses.
     */
    /**
     * Admits the destination of a plain level-&gt;level boundary, stepping the
     * engine while the coordinator legitimately denies -- the same shape
     * {@link #admitLevelWhenReady} already applies to boundaries that carry a
     * transition record, and the same thing production does, where
     * {@code TraceSessionLauncher#runCoordinatorTick} polls
     * {@code beforeAdmission} every tick and keeps stepping while it is denied.
     *
     * <p>This branch admitted one-shot, which is correct only when the
     * destination is admissible the instant its level became active. The
     * recorder leaves movie rows between adjacent segments -- every adjacent
     * pair in the committed S3K Sonic-and-Tails manifest does -- and the
     * destination is not admissible until the shared cursor reaches its first
     * recorded row. Where the source's frame budget happened to exceed the rows
     * it owed, the surplus frames carried the cursor across that gap and the
     * one-shot admission worked by luck; where the budget matched the rows
     * exactly, as at the S3K seg 8 -&gt; 9 seam, nothing crossed it and the
     * destination was refused one row short.
     *
     * <p>The gap is crossed by stepping real engine frames, never by seeking the
     * cursor: a seek would step over recorded rows at a boundary whose gap is
     * long (S2's {@code seg4_ehz1 -> seg5_ehz2} spans 171 rows), and the engine
     * must genuinely become admissible either way. Additive: a boundary that
     * admits immediately exits on iteration zero having stepped nothing, and the
     * loop is bounded by the manifest-derived {@code stepCap}.
     */
    private void admitPlainLevelBoundaryWhenReady(
            GameLoop loop, PlaybackDebugManager playback,
            HeadlessRunCoordinatorAdapter runCoordinator,
            TraceRunSegmentDescriptor next,
            int rowsConsumed, int stepCap) {
        for (int step = 0; step < stepCap; step++) {
            if (runCoordinator.tryAdmitLevel(
                    null, playback.getCursorFrame(),
                    loop.getCurrentGameMode(), rowsConsumed,
                    runCoordinator.latestLoadReceipt())) {
                return;
            }
            stepEngineFrame(loop);
        }
        runCoordinator.admitLevel(
                null, playback.getCursorFrame(),
                loop.getCurrentGameMode(), rowsConsumed, false,
                runCoordinator.latestLoadReceipt());
    }

    private void topUpUnconsumedSegmentRows(
            GameLoop loop, LiveTraceComparator comparator,
            int segmentFrameCount, int stepCap) {
        int steps = 0;
        while (comparator.hasUnconsumedRecordedRows()) {
            if (steps++ >= stepCap) {
                throw new AssertionError(
                        "segment did not consume its remaining recorded rows"
                                + " within " + stepCap + " extra steps (cursor "
                                + comparator.cursor() + " of "
                                + segmentFrameCount + ")");
            }
            stepEngineFrame(loop);
        }
    }

    private void stepFrames(GameLoop loop, int frameCount) {
        for (int f = 0; f < frameCount; f++) {
            stepEngineFrame(loop);
        }
    }

    /**
     * Converts a manifest's 1-based ROM act number (act 1 / act 2, as the
     * recorder writes it) to the engine's 0-based act index used by
     * {@code LevelManager.loadZoneAndAct} and {@code getCurrentAct()}. This is the
     * same convention every standalone {@code *CompleteRunTraceReplay} lane
     * encodes by hand (AIZ act 1 -&gt; {@code act()==0}, GHZ act 2 -&gt;
     * {@code act()==1}); the manifest keeps the ROM-facing value so it stays
     * human-readable and game-agnostic. Level segments only (special-stage
     * segments carry act 0 and never reach a level load through this path).
     */
    private static int engineAct(int manifestAct) {
        return manifestAct - 1;
    }

    /**
     * Steps until the mode reaches {@code target} or the step cap is exhausted.
     * The cap is the same manifest-derived bound {@link TraceRunReplayWalker#awaitBoundary}
     * uses, so a hung transition fails fast instead of looping forever.
     */
    protected int waitForMode(GameLoop loop, GameMode target, int maxSteps) {
        int steps = 0;
        while (loop.getCurrentGameMode() != target) {
            stepEngineFrame(loop);
            steps++;
            if (steps >= maxSteps) {
                throw new AssertionError("Mode never reached " + target + " within "
                        + maxSteps + " frames (frozen transition?)");
            }
        }
        return steps;
    }

    private void waitForModeToLeaveOrLevelActivate(
            GameLoop loop, GameMode from,
            TraceRunSegmentDescriptor targetLevel,
            Object levelAtSegmentStart, int maxSteps) {
        int steps = 0;
        while (loop.getCurrentGameMode() == from
                && !isNewActiveLevelSegment(targetLevel, levelAtSegmentStart)) {
            stepEngineFrame(loop);
            steps++;
            if (steps >= maxSteps) {
				var segment = targetLevel.segment();
				var identity = GameServices.module().getTracePlaybackProfile()
						.resolveRecordedLevel(segment.zoneId(), segment.act());
                throw new AssertionError("Mode never left " + from + " within "
                        + maxSteps + " frames and target level never activated"
						+ " (target=" + segment.dir() + " " + identity.zone() + ":" + identity.act()
						+ ", actual=" + GameServices.level().getCurrentZone() + ":"
						+ GameServices.level().getCurrentAct() + ")");
            }
        }
    }

    private static boolean isNewActiveLevelSegment(
            TraceRunSegmentDescriptor targetLevel,
            Object levelAtSegmentStart) {
        var segment = targetLevel.segment();
        if (!"level".equals(segment.kind()) || segment.zoneId() == null || segment.act() == null) {
            return false;
        }
        var identity = GameServices.module().getTracePlaybackProfile()
                .resolveRecordedLevel(segment.zoneId(), segment.act());
        return GameServices.level().getCurrentLevel() != levelAtSegmentStart
                && GameServices.level().getCurrentZone() == identity.zone()
                && GameServices.level().getCurrentAct() == identity.act();
    }

    private void waitForModeToLeave(GameLoop loop, GameMode from, int maxSteps) {
        int steps = 0;
        while (loop.getCurrentGameMode() == from) {
            stepEngineFrame(loop);
            if (++steps >= maxSteps) {
                throw new AssertionError("Mode never left " + from + " within "
                        + maxSteps + " frames (expected title-card entry)");
            }
        }
    }

    /**
     * Drives one shared-transition-gap row.
     *
     * <p>The visual launcher runs every gap row under
     * {@link TraceRunFrameDriver.Disposition#SHARED_GAP}, which is what makes
     * {@code GameLoop.suppressesRunNativeLevelBody} stop the source level's
     * body once its own main loop has ended -- the ROM spends a level-advance
     * gap inside the blocking {@code Level:} load path
     * (docs/s2disasm/s2.asm:4757-4926), not in {@code Level_MainLoop}, and the
     * players do not even exist there until {@code InitPlayers}
     * (docs/s2disasm/s2.asm:4945). This adapter used to step the gap with a
     * bare {@code loop.step()}, so the level body kept running and kept
     * animating the players for the whole gap. Same contract, two drivers; the
     * disposition is the only thing that differed.
     *
     * <p>Exactly which rows the body still owns is decided by production, in
     * {@code TraceSessionLauncher.runGapRowContinuesSourceLevelMainLoop}: the
     * first gap row, and no other.
     */
    /**
     * Expands a transition's run-length admission census into one flag per
     * movie row of its gap. The census alternates, starting with a NON-lag
     * run, so even indices are executed-main-loop runs and odd indices are
     * lag runs. An absent census expands to no rows, which leaves the gap walk
     * exactly as it behaved before any census existed.
     */
    private static boolean[] expandGapAdmissionCensus(
            TraceRunManifest.Transition transition) {
        if (transition == null) {
            return new boolean[0];
        }
        List<Integer> runs = transition.gapAdmissionRuns();
        int total = 0;
        for (int run : runs) {
            if (run < 0) {
                throw new AssertionError(
                        "admission census run length must be non-negative");
            }
            total += run;
        }
        boolean[] flags = new boolean[total];
        int cursor = 0;
        for (int index = 0; index < runs.size(); index++) {
            boolean lag = (index & 1) == 1;
            for (int i = 0; i < runs.get(index); i++) {
                flags[cursor++] = lag;
            }
        }
        return flags;
    }

    record UncomparedInteriorPhysicalRow(
            int movieRow, boolean representedSpecialRow, boolean lagGapRow) {
    }

    static List<UncomparedInteriorPhysicalRow> uncomparedInteriorPhysicalRows(
            int specialOffset, int representedRowCount, int destinationOffset,
            boolean[] gapLag) {
        if (specialOffset < 0 || representedRowCount < 0
                || destinationOffset < specialOffset + representedRowCount) {
            throw new IllegalArgumentException(
                    "invalid uncompared-interior physical row bounds");
        }
        int specialExclusiveEnd = specialOffset + representedRowCount;
        int gapRows = destinationOffset - specialExclusiveEnd;
        if (gapLag.length != 0 && gapLag.length != gapRows) {
            throw new IllegalArgumentException(
                    "gap admission census must cover every interstitial row");
        }
        List<UncomparedInteriorPhysicalRow> rows = new ArrayList<>(
                representedRowCount + gapRows);
        for (int row = specialOffset; row < specialExclusiveEnd; row++) {
            rows.add(new UncomparedInteriorPhysicalRow(row, true, false));
        }
        for (int index = 0; index < gapRows; index++) {
            rows.add(new UncomparedInteriorPhysicalRow(
                    specialExclusiveEnd + index, false,
                    gapLag.length != 0 && gapLag[index]));
        }
        return List.copyOf(rows);
    }

    /**
     * The whole of a lag row's engine-visible V-int work: the ROM's
     * {@code VintRet} increments {@code Vint_runcount} (s2.asm:505-506) on
     * every dispatch, lag included, and that counter is what
     * {@code ObjectInstance.update} sees as {@code vblaCounter}. Everything
     * else {@code Vint_Lag} does on the in-level path (sound-driver input,
     * the water palette DMA, the H-int flag) is presentation or audio and
     * owns no gameplay state.
     */
    private void serviceLagRowVint() {
        GameplayModeContext gameplayMode =
                SessionManager.getCurrentGameplayMode();
        serviceLagRowVint(
                LevelFrameContext.from(gameplayMode),
                gameplayMode.plcFrameLifecycle(),
                GameServices.level().getObjectManager());
    }

    static void serviceLagRowVint(
            LevelFrameContext context,
            com.openggf.game.resources.PlcFrameLifecycleCoordinator lifecycle,
            com.openggf.level.objects.ObjectManager objectManager) {
        lifecycle.runSuppressedLagIteration(frame -> {
            LevelFrameStep.serviceVBlankOnly(
                    context, frame, PlcLifecyclePhase.LAG);
            objectManager.initVblaCounter(objectManager.getVblaCounter() + 1);
            return null;
        });
    }

    /**
     * The object V-blank counter observed on the source segment's FINAL recorded
     * row, latched the moment that row is played, plus the BK2 row it belongs to.
     */
    private int sourceTailVblankObservation = 0;
    private int sourceTailVblankObservationRow = -1;

    /**
     * Latches the source segment's tail V-blank anchor as its final recorded row
     * is played, rather than reconstructing it afterwards.
     *
     * <p>{@link TraceRunReplayWalker#sourceTailVblankAtBoundary} projects the
     * anchor BACKWARDS from wherever the boundary wait happens to stop, charging
     * one tick to every row in between. That premise does not hold across the
     * boundary window: the window steps the engine through the destination's
     * load, and {@link #suppressedRowOwesVint} deliberately declines to service
     * the row on which the load replaces the object manager. The dropped tick is
     * then subtracted out of the anchor, so every value derived from it —
     * destination clock included — lands one tick low.
     *
     * <p>Observing the anchor where it is defined removes the reconstruction
     * entirely: on the source's final recorded row the engine's counter is the
     * one the segment's own comparison ran on, so no assumption about
     * choreography rows can contaminate it. This is not trace hydration — the
     * value read is the ENGINE's own clock, never a recorded field.
     */
    private void latchSourceTailVblank(
            TraceRunManifest.Segment source, int rowBeingPlayed) {
        int sourceFinalRow =
                source.bk2FrameOffset() + source.traceFrameCount() - 1;
        if (rowBeingPlayed != sourceFinalRow
                || sourceTailVblankObservationRow == sourceFinalRow) {
            return;
        }
        sourceTailVblankObservationRow = sourceFinalRow;
        sourceTailVblankObservation =
                GameServices.level().getObjectManager().getVblaCounter();
    }

    /**
     * The source tail anchor: the latched observation when the chain stepped
     * through the source's final recorded row one row at a time, and otherwise
     * the walker's backward projection, which is exact for the paths that stop
     * with the cursor still on that row.
     */
    private int observedSourceTailVblank(
            TraceRunManifest.Segment source, PlaybackDebugManager playback) {
        int sourceFinalRow =
                source.bk2FrameOffset() + source.traceFrameCount() - 1;
        if (sourceTailVblankObservationRow == sourceFinalRow) {
            return sourceTailVblankObservation;
        }
        return TraceRunReplayWalker.sourceTailVblankAtBoundary(
                source, playback.getCursorFrame(),
                GameServices.level().getObjectManager().getVblaCounter());
    }

    /**
     * The same V-int service for a NON-lag transition row: the ROM's main loop
     * DID run on this row, but the engine plays it with the level body
     * suppressed, so nothing moves the object-visible V-blank clock.
     *
     * <p>{@code VintRet: addq.l #1,(Vint_runcount).w}
     * (docs/s2disasm/s2.asm:507-508) sits AFTER the
     * {@code jsr Vint_SwitchTbl(pc,d0.w)} dispatch (:504) and is reached by
     * every V-int the ROM takes, whichever routine the dispatch selected —
     * {@code Vint_Lag}, {@code Vint_Level}, {@code Vint_TitleCard} or
     * {@code Vint_Fade}. The object-visible clock therefore advances exactly
     * once on every emulated frame, transitions included, and
     * {@link #serviceLagRowVint}'s argument for the lag subset is that
     * whole-frame argument restricted to one branch of the same dispatch.
     *
     * <p>The concrete span this closes is {@code Pal_FadeToBlack}
     * (docs/s2disasm/s2.asm:3370-3380), which the level-entry path runs before
     * the destination title card is created: {@code move.w #$15,d4} then a
     * {@code .nextframe} loop whose every pass sets
     * {@code Vint_routine = VintID_Fade} and {@code bsr.w WaitForVint}
     * (:3376-3377, {@code WaitForVint} at :3957-3962). Each of those passes is
     * a V-int that reaches {@code VintRet}. The engine spends the same rows
     * with its level body suppressed.
     *
     * <p>Rather than assume which rows the engine already ticks, this states
     * the invariant directly: a transition MOVIE row ends with the clock
     * exactly one ahead of where it started. Both call sites are reached once
     * per movie row the transition spends -- the boundary wait only on the
     * fade rows it counts, the gap driver on every gap row -- so the rule
     * counts frames, never engine steps. A row whose body already ticked is left
     * alone, so the call can never double-count — which matters, because most
     * rows of a gap DO tick.
     *
     * <p>A row on which the destination level's own load replaced the object
     * manager is skipped: there the V-int belongs to the load's own seeding of
     * the counter, not to this adapter.
     *
     * <p>This carries no position, speed, object state or any physics/aux
     * comparison value, and reads no trace field: the only input is the
     * engine's own counter before the row.
     */
    private static void serviceSuppressedRowVint(
            Object objectManagerBefore, int counterBefore) {
        var objectManager = GameServices.level().getObjectManager();
        if (suppressedRowOwesVint(
                objectManager == objectManagerBefore,
                counterBefore,
                objectManager.getVblaCounter())) {
            objectManager.advanceVblaCounter();
        }
    }

    /**
     * Whether a transition row the engine has just played still owes the ROM's
     * {@code VintRet} tick, given where the object-visible clock stood before
     * the row and where the engine's own step left it. Pure, so the invariant
     * {@link #serviceSuppressedRowVint} enforces is assertable without a ROM,
     * a level or a trace fixture.
     *
     * <p>The invariant is the ROM's, stated once: a frame on which the
     * hardware took a V-int reaches {@code VintRet}
     * (docs/s2disasm/s2.asm:507-508) exactly once, so the clock ends one
     * ahead. This is not a correction of a measured difference — the same rule
     * owes nothing whenever the engine already performed the tick, which is
     * what most rows of a gap do.
     */
    static boolean suppressedRowOwesVint(
            boolean sameObjectManager, int counterBefore, int counterAfter) {
        // A row on which the destination level's load replaced the object
        // manager re-seeded the counter; that V-int belongs to the load.
        return sameObjectManager && counterAfter == counterBefore;
    }

    private void stepEngineFrameInTransitionGap(
            GameplayModeContext gameplayMode, GameLoop loop,
            PlaybackDebugManager playback, int movieRow) {
        stepEngineFrameInTransitionGap(gameplayMode, loop, playback, movieRow, false);
    }

    /**
     * CONTRACT 1 (main-loop admission). When {@code lag} is set, this physical
     * row is one the ROM's main loop never ran on: the recorded admission
     * census for this transition's movie gap says the emulator polled no
     * controller, which for S2 is exactly the ROM's own {@code Vint_Lag}
     * classification ({@code Vint_Lag} performs no {@code ReadJoypads},
     * s2.asm:529-642, whereas the routines {@code WaitForVint} dispatches do,
     * e.g. {@code Vint_TitleCard} at s2.asm:1008).
     *
     * <p>Such a row therefore services the interrupt and nothing else: the ROM
     * still reaches {@code VintRet} and executes
     * {@code addq.l #1,(Vint_runcount).w} (s2.asm:505-506), which is the
     * object-visible V-blank clock, while {@code Level_frame_counter} is
     * advanced only by the level main loop that did not run
     * ({@code Level_MainLoop}, s2.asm:5092). No gameplay lifecycle runs, and
     * no controller word is republished.
     *
     * <p>The consumed quantity is one bit per physical frame — whether the
     * main loop ran. It carries no position, speed, object state, or any
     * physics/aux comparison value, and it changes only WHEN engine-created
     * work becomes ready.
     */
    private void stepEngineFrameInTransitionGap(
            GameplayModeContext gameplayMode, GameLoop loop,
            PlaybackDebugManager playback, int movieRow, boolean lag) {
        stepEngineFrameInTransitionGap(gameplayMode, loop, playback, movieRow,
                lag, false);
    }

    /**
     * Index of the last row of a gap the main loop was not admitted on, or
     * {@code -1} when the census records no such row. In the ROM's level-entry
     * sequence that row is the one {@code InitPlayers} runs on
     * (docs/s2disasm/s2.asm:4946): every span after it waits on V-int per pass
     * (the leave loop, :5060-5066) and so admits the main loop.
     */
    private static int lastNonAdmittedRow(boolean[] gapLag) {
        for (int index = gapLag.length - 1; index >= 0; index--) {
            if (gapLag[index]) {
                return index;
            }
        }
        return -1;
    }

    private static void holdPlayerArtForLevelEntryLoad(boolean arm) {
        var lifecycle = GameServices.dynamicArtLifecycleOrNull();
        if (!arm || lifecycle == null || !lifecycle.isRunActive()) {
            return;
        }
        lifecycle.holdPlayerArtDuringLevelEntryLoad();
    }

    private static void releasePlayerArtForLevelEntryLoad() {
        var lifecycle = GameServices.dynamicArtLifecycleOrNull();
        if (lifecycle == null || !lifecycle.isRunActive()) {
            return;
        }
        lifecycle.releasePlayerArtHeldDuringLevelEntryLoad();
    }

    private void stepEngineFrameInTransitionGap(
            GameplayModeContext gameplayMode, GameLoop loop,
            PlaybackDebugManager playback, int movieRow, boolean lag,
            boolean levelEntryLoadCompletes) {
        TraceRunFrameDriver gapRows = new TraceRunFrameDriver();
        gameplayMode.installTraceRunFrameDriver(gapRows);
        try {
            gapRows.execute(
                    new TraceRunFrameDriver.Step(
                            TraceRunFrameDriver.Disposition.SHARED_GAP,
                            movieRow, false),
                    new TraceRunFrameDriver.Hooks<Void>() {
                        @Override
                        public void preparePhysicalRow(TraceRunFrameDriver.Step step) {
                            stateMovieLogicalRow(step);
                        }

                        @Override
                        public void prepareHardwareTiming(TraceRunFrameDriver.Step step) {
                        }

                        @Override
                        public Void captureBefore(TraceRunFrameDriver.Step step) {
                            return null;
                        }

                        @Override
                        public void runProductionLifecycle(TraceRunFrameDriver.Step step) {
                            if (lag) {
                                serviceLagRowVint();
                                if (levelEntryLoadCompletes) {
                                    // The ROM's level-entry load finishes on
                                    // this row, at InitPlayers
                                    // (docs/s2disasm/s2.asm:4946). Publishing
                                    // the held playable art here runs no
                                    // gameplay lifecycle: it only makes work
                                    // the engine already created visible on
                                    // the row the ROM created it on.
                                    stateMovieLogicalRow(step);
                                    releasePlayerArtForLevelEntryLoad();
                                }
                                return;
                            }
                            var gapObjects =
                                    GameServices.level().getObjectManager();
                            int gapVblank = gapObjects.getVblaCounter();
                            stepEngineFrame(loop);
                            // TRANSITION_GAP suppresses the source level body,
                            // so a non-lag gap row can end without the ROM's
                            // VintRet tick; see serviceSuppressedRowVint.
                            serviceSuppressedRowVint(gapObjects, gapVblank);
                            if (levelEntryLoadCompletes) {
                                releasePlayerArtForLevelEntryLoad();
                            }
                        }

                        @Override
                        public boolean shouldAdvancePhysicalRow(TraceRunFrameDriver.Step step) {
                            // The shared clock advances once per gap row
                            // whoever moved it: the source level's own body on
                            // the one row it still owns, and this adapter on
                            // every suppressed row after it.
                            return playback.getCursorFrame() == movieRow;
                        }

                        @Override
                        public void advancePhysicalRow(TraceRunFrameDriver.Step step) {
                            playback.onLevelFrameAdvanced();
                        }

                        @Override
                        public Void captureAfter(TraceRunFrameDriver.Step step) {
                            return null;
                        }

                        @Override
                        public void compare(TraceRunFrameDriver.Step step, Void before, Void after) {
                        }

                        @Override
                        public void afterStep(TraceRunFrameDriver.Step step) {
                        }
                    });
        } finally {
            gameplayMode.clearTraceRunFrameDriver(gapRows);
        }
    }

    /**
     * Installs {@code comparator} as the production comparator and re-arms the
     * slot-occupancy probe on the segment's own trace, so each segment's diff
     * lands in its own report keyed by segment index.
     */
    private LiveTraceComparator installProductionComparator(
            LiveTraceComparator comparator, TraceData trace, int segmentIndex) {
        productionComparator = comparator;
        closeSlotOccupancyProbe();
        if (trace != null) {
            slotOccupancyProbe = slotProbeFactory.create(
                    trace, slotProbeRunId + "_seg" + segmentIndex);
        }
        return comparator;
    }

    private void closeSlotOccupancyProbe() {
        HeadlessSlotProbe closing = slotOccupancyProbe;
        slotOccupancyProbe = null;
        if (closing != null) {
            closing.close();
        }
    }

    private static HeadlessSlotProbe createSlotProbe(
            TraceData trace, String label) {
        com.openggf.tests.trace.SlotOccupancyProbe delegate =
                com.openggf.tests.trace.SlotOccupancyProbe.createIfEnabled(
                        trace, label);
        if (delegate == null) {
            return null;
        }
        return new HeadlessSlotProbe() {
            @Override
            public void observe(
                    int traceFrame,
                    com.openggf.level.objects.ObjectManager objectManager) {
                delegate.observe(traceFrame, objectManager);
            }

            @Override
            public void close() {
                delegate.close();
            }
        };
    }

    /** Advances one engine frame through the same outer PLC/fade lifecycle as live play. */
    void stepEngineFrame(GameLoop loop) {
        stateMovieLogicalRow();
        DynamicArtDiagnosticsSnapshot before =
                GameServices.captureDynamicArtDiagnostics();
        HeadlessRunCoordinatorAdapter coordinator = activeRunCoordinator;
        if (coordinator != null) {
            coordinator.beforeProduction(loop.getCurrentGameMode());
        }
        // Capture the row index BEFORE production: the single-segment path
        // observes with the index of the frame just produced, and the comparator
        // may or may not have advanced its cursor inside loop.step() depending on
        // the phase. Sampling the index first is correct either way.
        LiveTraceComparator preStepComparator = productionComparator;
        int slotProbeRowIndex = slotOccupancyProbe != null && preStepComparator != null
                ? preStepComparator.cursor()
                : -1;
        loop.step();
        afterProductionStep.run();
        if (slotProbeRowIndex >= 0) {
            var slotProbeLevel = GameServices.levelOrNull();
            if (slotProbeLevel != null) {
                slotOccupancyProbe.observe(slotProbeRowIndex, slotProbeLevel.getObjectManager());
            }
        }
        LiveTraceComparator comparator = productionComparator;
        if (comparator != null) {
            comparator.consumePostProductionPlayableAnimationAction();
            DynamicArtDiagnosticsSnapshot after =
                    GameServices.captureDynamicArtDiagnostics();
            try {
                comparator.publishPendingDynamicArtComparison(before, after);
            } catch (IllegalStateException failure) {
                throw new IllegalStateException(
                        failure.getMessage() + "; before=" + before
                                + "; after=" + after,
                        failure);
            }
        }
        if (coordinator != null) {
            coordinator.afterStep(loop.getCurrentGameMode());
        }
        ReplayPrefixTarget prefixTarget = activeReplayPrefixTarget;
        if (prefixTarget != null
                && prefixTarget.segmentIndex() == activeReplaySegmentIndex
                && comparator != null
                && prefixTarget.committedRows() == comparator.cursor()) {
            throw new ReplayPrefixReached();
        }
    }

    // -------------------------------------------------------------------------
    // Reporting
    // -------------------------------------------------------------------------

    private void maybeWriteReport(String runId, int segmentIndex, LiveTraceComparator comparator)
            throws IOException {
        // A special-stage interior (advance-uncompared, v1) has no comparator, so
        // nothing to report; skip rather than emit an empty summary.
        if (comparator == null) {
            return;
        }
        assertCompletedSegmentComparison(segmentIndex, comparator);
        writeChainSegmentReport(runId, segmentIndex, comparator);
        assertSegmentPhysics(runId, segmentIndex, comparator);
    }

    /**
     * Asserts the physics comparator error count of any segment whose comparator
     * the chain attached over a level boundary: a level re-entered after an
     * interior ({@link #attachReturnedLevelSegment}), and -- since 2026-08-12 --
     * a level entered by a {@code level_advance} boundary
     * ({@link #attachPreparedLevelSegment} / {@link #attachLevelSegment}).
     * No new comparison logic: this asserts
     * exactly the {@code errorCount()} the chain already computed and just wrote
     * into {@code <runId>_seg<N>_report.json}.
     *
     * <p>See {@link #assertedPhysicsSegmentIndices} for why the count was going
     * unasserted, and why turning it on is the true state becoming visible
     * rather than a regression.
     */
    private void assertSegmentPhysics(
            String runId, int segmentIndex, LiveTraceComparator comparator) {
        if (!assertedPhysicsSegmentIndices.contains(segmentIndex)) {
            return;
        }
        if (comparator.errorCount() == 0) {
            return;
        }
        MismatchEntry first = comparator.firstNonCameraPhysicsMismatch();
        // Recorded, not thrown: see chainAxisFailures. The predicate is
        // unchanged (errorCount() must be 0); only the throw site moved to the
        // end of the walk so the gap-ledger axis is also evaluated.
        chainAxisFailures.add("[segment-physics] segment "
                + segmentIndex + " of " + runId
                + " diverged: " + comparator.errorCount()
                + " physics comparator errors"
                + (first == null ? "" : ", first non-camera mismatch at frame "
                        + first.frame() + " field " + first.field()
                        + " rom=" + first.romValue()
                        + " engine=" + first.engineValue())
                + "; report=" + writtenSegmentReportPaths.get(segmentIndex));
    }

    /** Adapter-neutral assertions a committed route can add at a completed source segment. */
    protected void assertCompletedSegmentComparison(
            int segmentIndex, LiveTraceComparator comparator) {
    }

    /**
     * Emits the live segment's comparator evidence when the walk aborts inside
     * it.
     *
     * <p>A segment writes its own report from {@link #maybeWriteReport} only
     * once it closes. A walk that fails mid-segment never reaches that call, so
     * shared legacy report directory ended up holding reports for exactly the
     * segments that went fine and none for the segment that failed -- triage
     * from the reports alone sees a set of clean segments and no evidence at
     * all, which is the opposite of what happened. Two separate rounds (S2 and
     * S3K) each lost time re-deriving per-row divergences by hand that the
     * comparator had already computed.
     *
     * <p>Deliberately NOT {@link #maybeWriteReport}: that also runs
     * {@link #assertCompletedSegmentComparison} and {@link #assertSegmentPhysics},
     * which would assert completion on a segment that legitimately did not
     * complete and could turn one failure into two. This writes the artifact and
     * nothing else, so it is purely additive evidence; a failure to write is
     * attached to the original failure rather than replacing it.
     */
    private void writeAbortedSegmentReport(
            String runId, int segmentIndex,
            List<TraceRunSegmentDescriptor> descriptors,
            LiveTraceComparator comparator, Throwable failure) {
        if (comparator == null || segmentIndex < 0
                || segmentIndex >= descriptors.size()) {
            return;
        }
        try {
            writeChainSegmentReport(runId, segmentIndex, comparator);
        } catch (Exception | Error reportFailure) {
            failure.addSuppressed(reportFailure);
        }
    }

    private void writeChainSegmentReport(String runId, int segmentIndex, LiveTraceComparator comparator)
            throws IOException {
        String json = buildComparatorSummaryJson(comparator);
        SessionInvocationExtension.SessionInvocation invocation =
                SessionInvocationExtension.SessionInvocation.current();
        TestSessionOutputPaths.ReportAllocation allocation =
                TestSessionOutputPaths.allocateReport("run-chain",
                        invocation.className(), invocation.methodName(),
                        invocation.parameterIndex(), invocation.invocationId(),
                        "segment-" + segmentIndex,
                        runId + "_seg" + segmentIndex, ".json");
        Path jsonPath = allocation.physicalPath();
        writtenSegmentReports.put(segmentIndex, json);
        writtenSegmentReportPaths.put(segmentIndex, jsonPath);
        TestSessionOutputPaths.publish(jsonPath, json);
        TestSessionOutputPaths.publishOwnerMetadata(allocation);
        assertTrue(Files.exists(jsonPath), "Chain segment report must be written: " + jsonPath);
    }

    /**
     * The comparator summary this walk wrote for {@code segmentIndex}, as JSON.
     *
     * <p><b>Why a test must read this and not the file.</b> Before session
     * ownership, the report path was {@code <runId>_seg<N>_report.json} --
     * keyed on the run id and the <em>re-based</em> segment index, and on
     * nothing that distinguished one lane of that run from another. Every
     * class replaying a run therefore wrote the same names into one shared
     * legacy directory: for {@code s1-sonic-complete-withemeralds}, the full
     * chain's real segment 0 and a {@link #assertChainReplayFromSegment} boot
     * segment both landed on {@code _seg0_report.json}. The trace-replay
     * profile runs {@code forkCount=4}, so those lanes ran in PARALLEL JVMs
     * and the last writer before a read won.
     *
     * <p>That made {@code TestS1ColdStartAttribution} flaky in a way that read
     * as a game result rather than an instrument fault: it saw the full chain's
     * clean GHZ1 {@code errorCount: 0} where its own boot segment's 15564
     * should have been, i.e. the pin reported the defect it exists to
     * characterise as FIXED. The in-memory record is the walk's own, so it
     * cannot be overwritten by another lane. The file is still written,
     * unchanged, for triage.
     */
    protected String writtenSegmentReport(int segmentIndex) {
        String json = writtenSegmentReports.get(segmentIndex);
        assertTrue(json != null,
                "no segment report was written for segment " + segmentIndex
                        + "; written segments=" + writtenSegmentReports.keySet());
        return json;
    }

    /**
     * Writes the transition-gap axis's report artifact, whichever axis failed.
     *
     * <p>The gap ledger was previously only ever consumed by an assertion the
     * walk never reached, so this axis had no artifact at all; a run that fails
     * on segment physics now still leaves the gap evidence on disk.
     */
    private void writeChainGapReport(
            String runId,
            DynamicArtGapJournalProbe journal,
            List<String> gapFailures) throws IOException {
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (DynamicArtStructuralGapEvidence gap : journal.structuralGaps) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("representedSegmentDir", gap.representedSegmentDir());
            row.put("nextSegmentDir", gap.nextSegmentDir());
            row.put("gapStartMovieLogicalFrame",
                    gap.gapStartMovieLogicalFrame());
            row.put("nextSegmentArmMovieLogicalFrame",
                    gap.nextSegmentArmMovieLogicalFrame());
            row.put("transitionCountAtGapStart",
                    gap.transitionCountAtGapStart());
            row.put("transitionCountAfterNextArm",
                    gap.transitionCountAfterNextArm());
            row.put("transitionsAddedAcrossBoundary",
                    gap.transitionsAddedAcrossBoundary().stream()
                            .map(String::valueOf)
                            .toList());
            gaps.add(row);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("runId", runId);
        summary.put("gapCount", gaps.size());
        summary.put("failureCount", gapFailures.size());
        summary.put("failures", List.copyOf(gapFailures));
        summary.put("gaps", gaps);
        SessionInvocationExtension.SessionInvocation invocation =
                SessionInvocationExtension.SessionInvocation.current();
        TestSessionOutputPaths.ReportAllocation allocation =
                TestSessionOutputPaths.allocateReport("run-chain",
                        invocation.className(), invocation.methodName(),
                        invocation.parameterIndex(), invocation.invocationId(),
                        "dynamic-art-gap", runId + "_dynamic_art_gap", ".json");
        TestSessionOutputPaths.publish(allocation.physicalPath(), new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(summary));
        TestSessionOutputPaths.publishOwnerMetadata(allocation);
    }

    private void writeDynamicArtInteriorReport(
            String runId,
            int segmentIndex,
            List<FrameComparison> comparisons) throws IOException {
        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (FrameComparison comparison : comparisons) {
            comparison.divergentFields().forEach(field -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("frame", comparison.frame());
                row.put("field", field.fieldName());
                row.put("expected", field.expected());
                row.put("actual", field.actual());
                row.put("severity", field.severity().name());
                mismatches.add(row);
            });
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("comparisonCount", comparisons.size());
        summary.put("errorCount", comparisons.stream()
                .flatMap(comparison -> comparison.fields().values().stream())
                .filter(field -> field.severity()
                        == com.openggf.trace.Severity.ERROR)
                .count());
        summary.put("mismatches", mismatches);
        ObjectMapper mapper =
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        SessionInvocationExtension.SessionInvocation invocation =
                SessionInvocationExtension.SessionInvocation.current();
        TestSessionOutputPaths.ReportAllocation allocation =
                TestSessionOutputPaths.allocateReport("run-chain",
                        invocation.className(), invocation.methodName(),
                        invocation.parameterIndex(), invocation.invocationId(),
                        "segment-" + segmentIndex + "-dynamic-art",
                        runId + "_seg" + segmentIndex + "_dynamic_art", ".json");
        Path jsonPath = allocation.physicalPath();
        TestSessionOutputPaths.publish(jsonPath, mapper.writeValueAsString(summary));
        TestSessionOutputPaths.publishOwnerMetadata(allocation);
        assertTrue(mismatches.isEmpty(),
                "DPLC divergence in named-run special-stage segment "
                        + segmentIndex + "; report=" + jsonPath);
    }

    /**
     * {@link LiveTraceComparator} never builds a {@code DivergenceReport} itself
     * (that is {@code TraceBinder}'s job inside {@code AbstractTraceReplayTest}),
     * so this writes a lightweight summary JSON from the comparator's own
     * accessors (error/warning/lag counts plus the recent-mismatch ring buffer).
     */
    private static String buildComparatorSummaryJson(LiveTraceComparator comparator) throws IOException {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("errorCount", comparator.errorCount());
        summary.put("warningCount", comparator.warningCount());
        summary.put("laggedFrames", comparator.laggedFrames());
        summary.put("complete", comparator.isComplete());
        MismatchEntry firstPhysics = comparator.firstNonCameraPhysicsMismatch();
        if (firstPhysics != null) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("frame", firstPhysics.frame());
            row.put("field", firstPhysics.field());
            row.put("romValue", firstPhysics.romValue());
            row.put("engineValue", firstPhysics.engineValue());
            row.put("delta", firstPhysics.delta());
            row.put("severity", firstPhysics.severity().name());
            summary.put("firstNonCameraPhysicsMismatch", row);
        }
        List<Map<String, Object>> mismatches = new ArrayList<>();
        for (MismatchEntry entry : comparator.recentMismatches()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("frame", entry.frame());
            row.put("field", entry.field());
            row.put("romValue", entry.romValue());
            row.put("engineValue", entry.engineValue());
            row.put("delta", entry.delta());
            row.put("severity", entry.severity().name());
            row.put("repeatCount", entry.repeatCount());
            mismatches.add(row);
        }
        summary.put("recentMismatches", mismatches);

        // Additive: the flat errorCount above is untouched. Chain reports used to
        // publish only that, so "does every verification group reach the total?"
        // could not be answered from the artefact -- and on 2026-08-21 that cost
        // two lanes a round and an escalation before a direct probe showed the
        // counting was correct all along. An instrument that cannot be audited
        // from its own output is a standing liability even when it is right, so
        // the breakdown the standalone reports already carry is published here
        // too, and the invariant is asserted rather than merely shown.
        Map<String, Object> groups = new LinkedHashMap<>();
        int groupSum = 0;
        for (VerificationGroup group : VerificationGroup.values()) {
            int count = comparator.errorCount(group);
            groups.put(group.id(), Map.of("error_count", count));
            groupSum += count;
        }
        summary.put("verification_groups", groups);
        summary.put("bootstrapErrorCount", comparator.bootstrapErrorCount());
        int accounted = groupSum + comparator.bootstrapErrorCount();
        assertEquals(comparator.errorCount(), accounted,
                "verification groups plus bootstrap must account for the flat error"
                        + " count exactly; groups=" + groups
                        + " bootstrap=" + comparator.bootstrapErrorCount()
                        + " total=" + comparator.errorCount());

        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(summary);
    }

    // -------------------------------------------------------------------------
    // BK2 resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves the shared BK2 for a run. Follows the shared-movie convention
     * (sibling {@code _movies/<source_bk2>} directory at the game root, one level
     * up from {@code runs/<run-id>/}), falling back to a copy inside the run
     * directory itself. Overridable for lanes that stage the BK2 elsewhere.
     */
    protected Path resolveRunBk2(Path runDir, String sourceBk2) throws IOException {
        Path gameRoot = runDir.getParent().getParent();
        Path shared = gameRoot.resolve("_movies").resolve(sourceBk2);
        if (Files.exists(shared)) {
            return shared;
        }
        Path local = runDir.resolve(sourceBk2);
        if (Files.exists(local)) {
            return local;
        }
        throw new IOException("No BK2 found for run " + runDir
                + " (checked " + shared + " and " + local + ")");
    }

    // -------------------------------------------------------------------------
    // Engine adapters (shared by every lane)
    // -------------------------------------------------------------------------

    /** Reads engine boundary state through real production accessors. */
    protected static final class RealEngineHooks implements TraceRunReplayWalker.EngineHooks {
        private final GameLoop loop;

        private RealEngineHooks(GameLoop loop) {
            this.loop = loop;
        }

        @Override
        public int currentBk2Frame() {
            return GameServices.playbackDebug().getCursorFrame();
        }

        @Override
        public BonusStageType peekBonusRequest() {
            return GameServices.level().getTransitions().peekBonusStageRequest();
        }

        @Override
        public boolean isSpecialStageRequested() {
            return GameServices.level().getTransitions().isSpecialStageRequested();
        }

        @Override
        public GameMode currentMode() {
            return loop.getCurrentGameMode();
        }
    }

    /**
     * {@link TraceReplayFixture} adapter that resolves the sprite/gameplay mode
     * LIVE from {@code GameServices}/{@code SessionManager} on every call, rather
     * than caching them at construction time -- required because
     * {@link TraceReplayDriver#start} performs its own full reset + team
     * registration + level load, which would stale a pre-cached sprite.
     *
     * <p>The BK2-driven stepping methods are unused by the chain drive (which
     * drives via {@code loop.step()} and the singleton {@code PlaybackDebugManager}
     * directly); they throw rather than silently doing the wrong thing.
     */
    protected static final class LiveEngineFixture implements TraceReplayFixture {
        private final Bk2Movie movie;
        private HardwareTimingReplayPort hardwareTimingReplayPort;
        private TraceHardwareTimingBoundaryObserver hardwareTimingObserver;
        private boolean hardwareTimingReplayClosed;

        private LiveEngineFixture(Bk2Movie movie) {
            this.movie = movie;
        }

        @Override
        public AbstractPlayableSprite sprite() {
            return GameServices.camera().getFocusedSprite();
        }

        @Override
        public GameplayModeContext gameplayMode() {
            return SessionManager.getCurrentGameplayMode();
        }

        @Override
        public void installHardwareTimingReplay(HardwareTimingReplayPort replayPort) {
            hardwareTimingReplayPort = replayPort;
            hardwareTimingObserver = new TraceHardwareTimingBoundaryObserver(replayPort);
            gameplayMode().getRewindRegistry().register(replayPort);
            gameplayMode().setHardwareTimingBoundaryObserver(hardwareTimingObserver);
            gameplayMode().setHardwareTimingReplayCloseHook(
                    this::closeHardwareTimingReplayRun);
        }

        @Override
        public void beginTraceRow(int traceIndex, int rawFrame) {
            if (hardwareTimingObserver != null) {
                hardwareTimingObserver.beginRawFrame(rawFrame);
            }
        }

        @Override
        public void enterHardwareTimingGap() {
            if (hardwareTimingObserver != null) {
                hardwareTimingObserver.enterUnrepresentedGap();
            }
        }

        @Override
        public void verifyHardwareTimingSegmentEdges() {
            if (hardwareTimingReplayPort != null) {
                hardwareTimingReplayPort.verifySegmentEdges();
            }
        }

        @Override
        public void handoffHardwareTimingReplay(HardwareTimingSchedule nextSchedule) {
            handoffHardwareTimingReplay(nextSchedule, java.util.Map.of());
        }

        @Override
        public void handoffHardwareTimingReplay(
                HardwareTimingSchedule nextSchedule,
                java.util.Map<com.openggf.game.timing.HardwareWorkKind,
                        com.openggf.game.timing.RecordedOrdinalSpan> interstitialSpans) {
            if (hardwareTimingReplayPort != null) {
                hardwareTimingReplayPort.handoffTo(nextSchedule, interstitialSpans);
            }
        }

        @Override
        public void closeHardwareTimingReplayRun() {
            if (hardwareTimingReplayClosed || hardwareTimingReplayPort == null) {
                return;
            }
            hardwareTimingReplayClosed = true;
            try {
                hardwareTimingReplayPort.verifyRunComplete();
            } finally {
                gameplayMode().setHardwareTimingBoundaryObserver(null);
                gameplayMode().getRewindRegistry()
                        .deregister(HardwareTimingReplayPort.REWIND_KEY);
                gameplayMode().clearHardwareTimingReplayCloseHook();
                hardwareTimingObserver = null;
            }
        }

        @Override
        public void abortHardwareTimingReplayRun() {
            if (hardwareTimingReplayClosed) {
                return;
            }
            hardwareTimingReplayClosed = true;
            gameplayMode().setHardwareTimingBoundaryObserver(null);
            gameplayMode().getRewindRegistry()
                    .deregister(HardwareTimingReplayPort.REWIND_KEY);
            gameplayMode().clearHardwareTimingReplayCloseHook();
            hardwareTimingObserver = null;
        }

        @Override
        public void suppressFirstSidekickAnimationOnce() {
            // Live action, honored identically to the canonical fixtures: hold the
            // first CPU sidekick's next Animate dispatch. No-op for solo runs
            // (e.g. the Knuckles mega-run) whose sidekick list is empty.
            var sprites = GameServices.sprites();
            if (!sprites.getSidekicks().isEmpty()) {
                sprites.getSidekicks().getFirst().getAnimationManager().suppressNextUpdate();
            }
        }

        @Override
        public int stepFrameFromRecording() {
            throw new UnsupportedOperationException(
                    "Chain test drives via loop.step(); fixture-driven stepping is unused");
        }

        @Override
        public int skipFrameFromRecording() {
            throw new UnsupportedOperationException(
                    "Chain test drives via loop.step(); fixture-driven stepping is unused");
        }

        @Override
        public int consumeRecordingFrameInputOnly() {
            throw new UnsupportedOperationException(
                    "Chain test drives via loop.step(); fixture-driven stepping is unused");
        }

        @Override
        public void advanceRecordingCursor(int frameCount) {
            throw new UnsupportedOperationException(
                    "Chain test drives via loop.step(); fixture-driven stepping is unused");
        }

        @Override
        public int peekRecordingInputAt(int offset) {
            if (movie == null) {
                return -1;
            }
            int targetIndex = GameServices.playbackDebug().getCursorFrame() + offset;
            if (targetIndex < 0 || targetIndex >= movie.getFrameCount()) {
                return -1;
            }
            Bk2FrameInput frameInput = movie.getFrame(targetIndex);
            int mask = frameInput.p1InputMask();
            if (frameInput.p1ActionMask() != 0) {
                mask |= AbstractPlayableSprite.INPUT_JUMP;
            }
            return mask;
        }
    }
}
