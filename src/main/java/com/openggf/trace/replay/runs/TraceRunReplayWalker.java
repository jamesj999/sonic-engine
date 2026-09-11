package com.openggf.trace.replay.runs;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.trace.DynamicArtSpecialStageComparator;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.timing.HardwareTimingInterstitialSpans;
import com.openggf.trace.timing.HardwareTimingSchedule;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Chained-driver core for multi-stage trace runs (spec: docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md).
 * Plans a {@link TraceRunManifest} into per-segment {@link SegmentPlan}s and drives a boundary-observing
 * {@link BoundaryProbe} across a transition. Serves both the headless chain test and the visual run session.
 *
 * Comparison-only: consumes trace/manifest data and engine-observation hooks as read-only diagnostic input; never feeds engine state.
 */
public final class TraceRunReplayWalker {

    /**
     * Tolerance window (in BK2 frames) a latched boundary observation must
     * fall within, measured backward from the manifest's recorded edge
     * ({@code mode_change_bk2_frame}). An observation strictly after the
     * recorded edge is never within the window.
     */
    public static final int BOUNDARY_WINDOW_FRAMES = 600;
    public static final int LATE_BOUNDARY_GRACE_FRAMES = 120;

    private TraceRunReplayWalker() {
    }

    /**
     * A planned segment: its manifest {@link TraceRunManifest.Segment}, its
     * loaded {@link TraceData}, and the {@link TraceRunManifest.Transition}
     * records bounding it (null when the segment starts/ends the run or
     * abuts a plain level-to-level boundary with no transition record).
     */
    public record SegmentPlan(
        TraceRunManifest.Segment segment,
        TraceData trace,
        TraceRunManifest.Transition entryBoundary,
        TraceRunManifest.Transition exitBoundary,
        TraceRunSpecialStageRows specialStageRows,
        SegmentExecutionPolicy executionPolicy
    ) {
        public SegmentPlan(
                TraceRunManifest.Segment segment,
                TraceData trace,
                TraceRunManifest.Transition entryBoundary,
                TraceRunManifest.Transition exitBoundary) {
            this(segment, trace, entryBoundary, exitBoundary, null,
                    segmentExecutionPolicy(segment, entryBoundary, trace));
        }

        public SegmentPlan(
                TraceRunManifest.Segment segment,
                TraceData trace,
                TraceRunManifest.Transition entryBoundary,
                TraceRunManifest.Transition exitBoundary,
                TraceRunSpecialStageRows specialStageRows) {
            this(segment, trace, entryBoundary, exitBoundary, specialStageRows,
                    segmentExecutionPolicy(segment, entryBoundary, trace));
        }
    }

    /** Structural execution ownership for one run segment. */
    public enum SegmentExecutionPolicy {
        GAMEPLAY,
        SPECIAL_LOCAL,
        LEVEL_PRESENTATION_BRIDGE
    }

    /**
     * Classifies a segment from manifest topology and recorded row shape. A
     * stage-exit destination is a presentation bridge only when row zero is the
     * synthetic full row caused by its missing predecessor and every later row
     * is canonically non-gameplay.
     */
    public static SegmentExecutionPolicy segmentExecutionPolicy(
            TraceRunManifest.Segment segment,
            TraceRunManifest.Transition entryBoundary,
            TraceData trace) {
        Objects.requireNonNull(segment, "segment");
        Objects.requireNonNull(trace, "trace");
        if ("special_stage".equals(segment.kind())) {
            return SegmentExecutionPolicy.SPECIAL_LOCAL;
        }
        if (!"level".equals(segment.kind())
                || entryBoundary == null
                || !"stage_exit".equals(entryBoundary.entryKind())
                || trace.frameCount() < 2) {
            return SegmentExecutionPolicy.GAMEPLAY;
        }
        TraceExecutionPhase first =
                com.openggf.trace.replay.TraceReplayRowPolicy.resolve(
                        trace, 0, segment.bk2FrameOffset()).phase();
        if (first != TraceExecutionPhase.FULL_LEVEL_FRAME) {
            return SegmentExecutionPolicy.GAMEPLAY;
        }
        for (int row = 1; row < trace.frameCount(); row++) {
            TraceExecutionPhase phase =
                    com.openggf.trace.replay.TraceReplayRowPolicy.resolve(
                            trace, row, segment.bk2FrameOffset() + row).phase();
            if (phase != TraceExecutionPhase.VBLANK_ONLY
                    && phase != TraceExecutionPhase.ADVANCE_ONLY) {
                return SegmentExecutionPolicy.GAMEPLAY;
            }
        }
        return SegmentExecutionPolicy.LEVEL_PRESENTATION_BRIDGE;
    }

    /**
     * Hardware-timing view of one structural run segment. Raw frame numbers
     * remain trace input; the coordinator forwards them only to the bounded
     * replay fixture and never to gameplay owners.
     */
    public record HardwareTimingSegment(
            int bk2FrameOffset,
            List<Integer> rawFrames,
            HardwareTimingSchedule schedule) {
        public HardwareTimingSegment {
            if (bk2FrameOffset < 0) {
                throw new IllegalArgumentException(
                        "bk2FrameOffset must be non-negative");
            }
            rawFrames = List.copyOf(
                    Objects.requireNonNull(rawFrames, "rawFrames"));
            schedule = Objects.requireNonNull(schedule, "schedule");
        }
    }

    /** True when any compact run descriptor has a recorded timing stream. */
    public static boolean hasDescriptorHardwareTimingStream(
            List<TraceRunSegmentDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        return descriptors.stream().anyMatch(descriptor ->
                descriptor.hardwareTimingSchedule().hasRecordedInput());
    }

    /** Descriptor-only timing view for production and visual run ownership. */
    public static List<HardwareTimingSegment> descriptorHardwareTimingSegments(
            List<TraceRunSegmentDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        return descriptors.stream()
                .map(descriptor -> new HardwareTimingSegment(
                        descriptor.segment().bk2FrameOffset(),
                        descriptor.rawFrames(),
                        descriptor.hardwareTimingSchedule()))
                .toList();
    }

    /**
     * Run-scoped adapter that latches physical raw frames and changes schedules
     * exactly when the playback cursor first enters the next structural
     * segment. It owns no gameplay state and delegates all validation to the
     * replay fixture/port.
     */
    public static final class HardwareTimingCoordinator {
        private final TraceReplayFixture fixture;
        private final List<HardwareTimingSegment> segments;
        private final HardwareTimingInterstitialSpans interstitialSpans;
        private int currentSegment;
        private boolean closed;
        /**
         * Whether the DRIVE currently owns a segment's rows. Membership is the
         * drive's to declare, never the cursor's to infer: across a transition
         * the drive detaches its row owner and the shared BK2 cursor keeps
         * free-running through choreography frames the recording never covered,
         * then gets re-seeked to the destination's true first row when the
         * destination's load actually happens. Segment 0 starts owned because
         * the run's initial attach is what constructs this coordinator.
         */
        private boolean insideSegment = true;

        public HardwareTimingCoordinator(
                TraceReplayFixture fixture,
                List<HardwareTimingSegment> segments) {
            this(fixture, segments, HardwareTimingInterstitialSpans.empty());
        }

        /**
         * Run-level form that also carries the recorded interstitial spans, so
         * a handoff can cross the ordinals the recording consumed between two
         * segments. A run with no interstitial sidecar supplies empty spans and
         * behaves exactly as the two-argument form.
         */
        public HardwareTimingCoordinator(
                TraceReplayFixture fixture,
                List<HardwareTimingSegment> segments,
                HardwareTimingInterstitialSpans interstitialSpans) {
            this.interstitialSpans = Objects.requireNonNull(
                    interstitialSpans, "interstitialSpans");
            this.fixture = Objects.requireNonNull(fixture, "fixture");
            this.segments = List.copyOf(
                    Objects.requireNonNull(segments, "segments"));
            if (this.segments.isEmpty()) {
                throw new IllegalArgumentException(
                        "hardware timing run requires at least one segment");
            }
            for (int i = 1; i < this.segments.size(); i++) {
                HardwareTimingSegment previous = this.segments.get(i - 1);
                HardwareTimingSegment current = this.segments.get(i);
                if (current.bk2FrameOffset() < previous.bk2FrameOffset()
                        + previous.rawFrames().size()) {
                    throw new IllegalArgumentException(
                            "hardware timing segment BK2 ranges overlap at index " + i);
                }
            }
        }

        /**
         * Called by {@link BoundaryProbe} before its comparison delegate.
         *
         * <p>The playback bridge keeps pumping frames after the run closes —
         * {@code GameLoop.syncPlaybackInputBridge} runs from
         * {@code exitTitleCard}, which can fire once the walk has finished. Once
         * closed there is no row left to latch, so such a frame is outside the
         * run by definition and takes the same gap path this method already
         * uses for a frame that falls outside every segment. Only the pump is
         * made tolerant: {@link #beginSegmentRow} still throws for a deliberate
         * caller latching after close, which is a real ordering error rather
         * than a frame arriving late.
         */
        public void beginPlaybackFrame(Bk2FrameInput frame) {
            Objects.requireNonNull(frame, "frame");
            if (closed || !insideSegment) {
                fixture.enterHardwareTimingGap();
                return;
            }
            HardwareTimingSegment segment = segments.get(currentSegment);
            int traceIndex = frame.frameIndex() - segment.bk2FrameOffset();
            if (traceIndex >= 0 && traceIndex < segment.rawFrames().size()) {
                beginSegmentRow(currentSegment, traceIndex);
            } else {
                fixture.enterHardwareTimingGap();
            }
        }

        /**
         * The drive has released its row owner: it is between segments. No
         * frame belongs to any segment until {@link #enterSegment} declares the
         * next one, however far the shared cursor runs meanwhile.
         */
        public void enterTransitionGap() {
            insideSegment = false;
        }

        /**
         * The drive has attached {@code segmentIndex}'s row owner, so its rows
         * begin here. Performs the schedule handoff, which still enforces
         * structural order.
         */
        public void enterSegment(int segmentIndex) {
            handoffToSegment(segmentIndex);
            insideSegment = true;
        }

        /**
         * Latches a row driven outside the ordinary playback observer, such as
         * an advance-uncompared special-stage segment.
         */
        public void beginSegmentRow(int segmentIndex, int traceIndex) {
            if (closed) {
                throw new IllegalStateException(
                        "hardware timing run is already closed");
            }
            if (segmentIndex < currentSegment
                    || segmentIndex > currentSegment + 1) {
                throw new IllegalStateException(
                        "hardware timing segment moved out of structural order: "
                                + currentSegment + " -> " + segmentIndex);
            }
            HardwareTimingSegment segment = segments.get(segmentIndex);
            if (traceIndex < 0 || traceIndex >= segment.rawFrames().size()) {
                throw new IndexOutOfBoundsException(
                        "trace row " + traceIndex + " outside segment "
                                + segmentIndex);
            }
            handoffToSegment(segmentIndex);
            // Latching a row IS the drive declaring it owns this segment: the
            // special-stage row driver enters that way rather than through an
            // attach.
            insideSegment = true;
            fixture.beginTraceRow(
                    traceIndex, segment.rawFrames().get(traceIndex));
        }

        /**
         * Verifies the source schedule and commits the next schedule before a
         * destination adapter opens any comparison or input owner.
         */
        public void handoffToSegment(int segmentIndex) {
            if (closed) {
                throw new IllegalStateException(
                        "hardware timing run is already closed");
            }
            if (segmentIndex < currentSegment
                    || segmentIndex > currentSegment + 1) {
                throw new IllegalStateException(
                        "hardware timing segment moved out of structural order: "
                                + currentSegment + " -> " + segmentIndex);
            }
            if (segmentIndex == currentSegment + 1) {
                fixture.handoffHardwareTimingReplay(
                        segments.get(segmentIndex).schedule(),
                        interstitialSpans.spansAfterSegment(currentSegment));
                currentSegment = segmentIndex;
                // A drive that latches the next segment's rows directly (the
                // special-stage row driver) has declared its entry by doing so.
                insideSegment = true;
            }
        }

        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            fixture.closeHardwareTimingReplayRun();
        }

        /** Abandons an intentionally truncated diagnostic prefix without verification. */
        public void abort() {
            if (closed) {
                return;
            }
            closed = true;
            fixture.abortHardwareTimingReplayRun();
        }
    }

    /**
     * Value-free production window controlled only by structural run
     * boundaries. Expected trace rows and diagnostics have no representation
     * in this interface.
     */
    public interface DynamicArtSegmentWindow {
        void open();

        void close();
    }

    public static final class DynamicArtSegmentController implements AutoCloseable {
        private final DynamicArtSegmentWindow window;
        private boolean segmentOpen;
        private boolean closed;

        public DynamicArtSegmentController(DynamicArtSegmentWindow window) {
            this.window = Objects.requireNonNull(window, "window");
        }

        public void beginSegment() {
            if (closed) {
                throw new IllegalStateException(
                        "dynamic-art segment controller is already closed");
            }
            if (segmentOpen) {
                throw new IllegalStateException(
                        "dynamic-art comparison segment is already open");
            }
            window.open();
            segmentOpen = true;
        }

        public void endSegment() {
            if (!segmentOpen) {
                return;
            }
            window.close();
            segmentOpen = false;
        }

        public void enterGap() {
            endSegment();
        }

        public void runSegment(Runnable segmentBody) {
            Objects.requireNonNull(segmentBody, "segmentBody");
            beginSegment();
            try {
                segmentBody.run();
            } finally {
                endSegment();
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            try {
                endSegment();
            } finally {
                closed = true;
            }
        }
    }

    /**
     * Pure terminal-tail policy derived from recorder-owned manifest data. A
     * null expected mode means the run did not declare a movie endpoint, so no
     * remaining rows are replayed and no mode is asserted.
     */
    public record TerminalMovieTailPlan(
        int tailStart,
        int rowsToReplay,
        GameMode expectedMode
    ) {
        public boolean shouldReplay() {
            return rowsToReplay > 0;
        }

        public boolean shouldAssertExpectedMode() {
            return expectedMode != null;
        }
    }

    /** Result of {@link #awaitBoundary}: whether the boundary was observed, and at what BK2 frame. */
    public record BoundaryObservation(boolean observed, int observedBk2Frame) {
        static final BoundaryObservation NOT_OBSERVED = new BoundaryObservation(false, -1);
    }

    /**
     * How the engine SIGNALS a boundary — the single authority for mapping a
     * manifest {@code entry_kind} to the engine predicate that detects it. Data
     * driven: keyed on {@code entry_kind} only, never on zone/route/frame.
     *
     * <ul>
     *   <li>{@link #BONUS_REQUEST} — {@code starpost_bonus}: transient
     *       {@link EngineHooks#peekBonusRequest()} raised during the frame.</li>
     *   <li>{@link #SPECIAL_STAGE_REQUEST} — {@code giant_ring} /
     *       {@code starpost_special}: transient {@link EngineHooks#isSpecialStageRequested()}.</li>
     *   <li>{@link #LEVEL_MODE} — {@code stage_exit}: persistent
     *       {@link EngineHooks#currentMode()} {@code == LEVEL}.</li>
     * </ul>
     */
    public enum BoundaryEntryMode {
        BONUS_REQUEST,
        SPECIAL_STAGE_REQUEST,
        LEVEL_MODE,
        LEVEL_LOAD
    }

    /**
     * Maps a manifest {@code entry_kind} to the engine signal that detects it.
     * The single authority consumed by {@link BoundaryProbe}; the control-flow
     * test asserts this table directly.
     */
    public static BoundaryEntryMode boundaryEntryMode(String entryKind) {
        return switch (entryKind) {
            case "starpost_bonus" -> BoundaryEntryMode.BONUS_REQUEST;
            case "giant_ring", "starpost_special" -> BoundaryEntryMode.SPECIAL_STAGE_REQUEST;
            case "stage_exit" -> BoundaryEntryMode.LEVEL_MODE;
            case "level_advance", "death_restart" -> BoundaryEntryMode.LEVEL_LOAD;
            default -> throw new IllegalArgumentException("Unknown entry_kind '" + entryKind + "'");
        };
    }

    /**
     * Which return-boundary assertions apply after a {@code stage_exit}. Keyed
     * on the ENTRY transition (the one that entered the interior), because the
     * {@code stage_exit} transition itself carries no {@code saved_x/y} or
     * {@code last_star_post_hit}. All discrimination is manifest data
     * ({@code entry_kind} + field presence) — never a game-name branch.
     *
     * <ul>
     *   <li>{@link #POSITIONAL_RESTORE} — {@code starpost_special} (S2): compare
     *       restored {@code Saved_x/y} to the sprite centre; + rings + emeralds.</li>
     *   <li>{@link #CHECKPOINT_RESTORE} — {@code starpost_bonus} (S3K bonus):
     *       compare {@code last_star_post_hit} to the restored checkpoint index;
     *       + rings + emeralds.</li>
     *   <li>{@link #NEXT_ACT} — {@code giant_ring} with no {@code saved_x_pos}
     *       (S1 SS return): NO positional assertion; assert act/zone advance;
     *       + emeralds. The manifest's exit-ring tally precedes the fresh act
     *       load and is not co-temporal with the settled return snapshot.</li>
     *   <li>{@link #RINGS_EMERALDS_ONLY} — {@code giant_ring} with a
     *       {@code saved_x_pos} (S3K SS return): rings + emeralds only.</li>
     * </ul>
     */
    public enum ReturnAssertionMode {
        POSITIONAL_RESTORE,
        CHECKPOINT_RESTORE,
        NEXT_ACT,
        RINGS_EMERALDS_ONLY
    }

    /**
     * Derives the {@link ReturnAssertionMode} from the ENTRY transition that
     * entered the interior. The {@code giant_ring} split on {@code saved_x_pos}
     * presence is manifest data (S1 records none; S3K records it), not a
     * game-name check.
     *
     * @throws IllegalArgumentException if {@code entryTransition} is a
     *         {@code stage_exit} (not an interior-entry transition).
     */
    public static ReturnAssertionMode returnAssertionMode(TraceRunManifest.Transition entryTransition) {
        String kind = entryTransition.entryKind();
        return switch (kind) {
            case "starpost_special" -> ReturnAssertionMode.POSITIONAL_RESTORE;
            case "starpost_bonus" -> ReturnAssertionMode.CHECKPOINT_RESTORE;
            case "giant_ring" -> entryTransition.savedXPos() == null
                    ? ReturnAssertionMode.NEXT_ACT
                    : ReturnAssertionMode.RINGS_EMERALDS_ONLY;
            default -> throw new IllegalArgumentException(
                    "entry_kind '" + kind + "' is not an interior-entry transition");
        };
    }

    /**
     * Expected {@link GameMode} for a segment while it is the active phase —
     * pure {@code kind}->mode mapping, no zone checks.
     */
    public static GameMode expectedMode(TraceRunManifest.Segment segment) {
        return switch (segment.kind()) {
            case "level" -> GameMode.LEVEL;
            case "bonus_stage" -> GameMode.BONUS_STAGE;
            case "special_stage" -> GameMode.SPECIAL_STAGE;
            default -> throw new IllegalStateException("Unknown segment kind '" + segment.kind() + "'");
        };
    }

    /**
     * Whether the engine has already reached a manifest level segment. Manifest
     * acts are ROM-facing/1-based; engine acts are 0-based. This lets a chain
     * rebind after a level load that completed during the preceding segment's
     * compared tail, even when the engine performs that load without exposing
     * an intermediate non-LEVEL mode to the test driver.
     */
    public static boolean isActiveLevelSegment(
            TraceRunManifest.Segment segment, int engineRomZone, int engineAct) {
        return "level".equals(segment.kind())
                && segment.zoneId() != null
                && segment.act() != null
                && segment.zoneId() == engineRomZone
                && segment.act() - 1 == engineAct;
    }

    /** True when a distinct level load has produced the expected segment. */
    public static boolean isNewActiveLevelSegment(
            TraceRunManifest.Segment segment, int engineRomZone, int engineAct,
            Object levelAtSegmentStart, Object currentLevel) {
        return currentLevel != levelAtSegmentStart
                && isActiveLevelSegment(segment, engineRomZone, engineAct);
    }

    /**
     * SS-INTERIOR POLICY v1 = ADVANCE-GAMEPLAY-UNCOMPARED. True iff the segment
     * is a {@code special_stage}: it is phased through without gameplay-field
     * comparison (the boundary probe's delegate stays detached across the
     * phase). An independently advertised DPLC heartbeat remains eligible for
     * structural comparison. {@code bonus_stage} interiors are compared
     * per-frame. Full per-frame special-stage comparison is a later workflow — see the
     * SS-interior seam documented on {@link #interSegmentStepCap} callers and
     * "Decisions locked with the owner" item 1 in
     * docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md.
     */
    public static boolean isUncomparedInterior(TraceRunManifest.Segment segment) {
        return "special_stage".equals(segment.kind());
    }

    /**
     * Compares only the advertised DPLC heartbeat for an otherwise
     * gameplay-uncompared run segment row.
     */
    public static FrameComparison compareDynamicArtRow(
            TraceData trace,
            int frame,
            DynamicArtDiagnosticsSnapshot actual) {
        return compareDynamicArtRow(
                trace, frame, actual, new DynamicArtSpecialStageComparator());
    }

    /**
     * Compares one advertised DPLC heartbeat row through a caller-owned
     * comparator.
     *
     * <p>The comparator carries the segment's dynamic-art delivery-id origin
     * (see {@link com.openggf.trace.DynamicArtIdEpoch}), so a segment must pass
     * the same instance for every one of its rows.
     */
    public static FrameComparison compareDynamicArtRow(
            TraceData trace,
            int frame,
            DynamicArtDiagnosticsSnapshot actual,
            DynamicArtSpecialStageComparator comparator) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(comparator, "comparator");
        if (!trace.metadata().hasPerFrameDynamicArtTransferState()) {
            return null;
        }
        return comparator.compare(
                trace.dynamicArtTransferStateForFrame(frame), actual);
    }

    /**
     * Ordered DPLC-only comparison accumulator for metadata-only run segments.
     *
     * <p>The run driver owns stepping and structural lifecycle closure. This
     * object consumes only the resulting immutable snapshot for each represented
     * row and rejects missing, duplicate, or out-of-order advertised rows.
     */
    public static final class DynamicArtSegmentComparison {
        private final TraceData trace;
        private final java.util.Map<Integer, TraceEvent.DynamicArtTransferState>
                expectedOverride;
        private final int expectedRows;
        private final List<FrameComparison> comparisons = new ArrayList<>();
        // One accumulator represents one run segment, so it owns that
        // segment's dynamic-art delivery-id origin.
        private final DynamicArtSpecialStageComparator comparator =
                new DynamicArtSpecialStageComparator();
        private int nextRow;

        public DynamicArtSegmentComparison(
                TraceData trace, int expectedRows) {
            this(trace, expectedRows, java.util.Map.of());
        }

        /**
         * As above, but comparing against a caller-supplied per-row expectation
         * that replaces the raw recorded rows (an empty map keeps them). The
         * only supported source is the recorder-artifact normalization in
         * {@link com.openggf.trace.DynamicArtSpillNormalization}, which rebinds
         * submission edges the ROM's object pass spilled past a V-blank back to
         * that pass -- a rewrite of the expectation, never of engine state.
         */
        public DynamicArtSegmentComparison(
                TraceData trace, int expectedRows,
                java.util.Map<Integer, TraceEvent.DynamicArtTransferState> expectedOverride) {
            this.trace = Objects.requireNonNull(trace, "trace");
            this.expectedOverride = java.util.Map.copyOf(
                    Objects.requireNonNull(expectedOverride, "expectedOverride"));
            if (expectedRows < 0) {
                throw new IllegalArgumentException(
                        "expectedRows must be non-negative");
            }
            this.expectedRows = expectedRows;
        }

        public boolean isAdvertised() {
            return trace.metadata().hasPerFrameDynamicArtTransferState();
        }

        public FrameComparison compareRow(
                int row, DynamicArtDiagnosticsSnapshot actual) {
            if (row != nextRow) {
                throw new IllegalStateException(
                        "dynamic-art segment expected row " + nextRow
                                + " but received " + row);
            }
            if (row >= expectedRows) {
                throw new IllegalStateException(
                        "dynamic-art segment received extra row " + row);
            }
            nextRow++;
            if (!isAdvertised()) {
                return null;
            }
            FrameComparison comparison = expectedOverride.isEmpty()
                    ? TraceRunReplayWalker.compareDynamicArtRow(
                            trace, row, actual, comparator)
                    : comparator.compare(expectedOverride.get(row), actual);
            comparisons.add(comparison);
            return comparison;
        }

        public void verifyComplete() {
            if (isAdvertised() && nextRow != expectedRows) {
                throw new IllegalStateException(
                        "dynamic-art segment expected " + expectedRows
                                + " rows but compared " + nextRow);
            }
        }

        public List<FrameComparison> comparisons() {
            return List.copyOf(comparisons);
        }
    }

    /**
     * True when a completed level segment is a VBlank-only bridge into another
     * capture of the same logical zone/act. Sonic 1's ROM can briefly expose
     * {@code Game_Mode == Level} after a special-stage exit, emit only lag rows,
     * leave that coarse mode again, and then begin the real next-act gameplay
     * capture. The engine's finer mode model has already settled at the latter
     * point when the stage-exit boundary becomes observable, so replay must
     * rebind the input/comparator directly instead of waiting for a second mode
     * cycle that does not exist.
     *
     * <p>The one-row allowance covers the title-card-exit fall-through frame
     * consumed before a returned-level comparator attaches. The decision is
     * driven only by manifest identity plus measured replay phase counts.
     */
    public static boolean isLagOnlySameLevelContinuation(
            TraceRunManifest.Segment current, TraceRunManifest.Segment next,
            int totalFrames, int laggedFrames) {
        return "level".equals(current.kind())
                && "level".equals(next.kind())
                && Objects.equals(current.zoneId(), next.zoneId())
                && Objects.equals(current.act(), next.act())
                && totalFrames > 0
                && laggedFrames >= totalFrames - 1;
    }

    /**
     * Returns the number of rows a segment driver still needs to step after
     * transition machinery has already consumed leading rows (most commonly
     * the title-card-exit fallthrough frame).
     */
    public static int remainingSegmentFrames(int totalFrames, int consumedFrames) {
        if (totalFrames < 0 || consumedFrames < 0) {
            throw new IllegalArgumentException("frame counts must be non-negative");
        }
        return Math.max(0, totalFrames - consumedFrames);
    }

    /**
     * @see com.openggf.trace.TraceReplayBootstrap#levelLoopRowCount(TraceData)
     */
    public static int levelLoopRowCount(TraceData trace) {
        return com.openggf.trace.TraceReplayBootstrap.levelLoopRowCount(trace);
    }

    /**
     * Plans the unrecorded tail after a run's final comparison segment. Only a
     * recorder-declared endpoint opts in; an unspecified endpoint deliberately
     * leaves both tail replay and terminal assertion disabled.
     *
     * @throws IllegalStateException when a declared terminal contract starts
     *         after the shared movie ends
     */
    public static TerminalMovieTailPlan planTerminalMovieTail(
            TraceRunManifest.ExpectedMovieEndMode expectedMovieEndMode,
            int tailStart,
            int movieFrameCount) {
        Objects.requireNonNull(expectedMovieEndMode, "expectedMovieEndMode");
        if (expectedMovieEndMode == TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED) {
            return new TerminalMovieTailPlan(tailStart, 0, null);
        }
        if (tailStart > movieFrameCount) {
            throw new IllegalStateException("Terminal tail start " + tailStart
                    + " exceeds movie frame count " + movieFrameCount);
        }
        GameMode expectedMode = switch (expectedMovieEndMode) {
            case LEVEL -> GameMode.LEVEL;
            case TITLE_SCREEN -> GameMode.TITLE_SCREEN;
            case UNSPECIFIED -> throw new IllegalStateException("unreachable");
        };
        return new TerminalMovieTailPlan(tailStart, movieFrameCount - tailStart, expectedMode);
    }

    /**
     * Calculates the ROM VBlank ticks represented by the movie gap between two
     * level captures. {@code nextFramesConsumed} includes leading destination
     * gameplay rows already executed by transition fall-through. The profiled
     * non-advancing rows represent real movie rows on which the game's VBlank
     * counter remained unchanged.
     */
    public static int interLevelVblankBudget(
            TraceRunManifest.Segment current,
            TraceRunManifest.Segment next,
            int nextFramesConsumed,
            int nonAdvancingMovieRows) {
        if (nextFramesConsumed < 0 || nonAdvancingMovieRows < 0) {
            throw new IllegalArgumentException("frame counts must be non-negative");
        }
        int currentEnd = current.bk2FrameOffset() + current.traceFrameCount();
        int targetRow = next.bk2FrameOffset() + nextFramesConsumed;
        int movieRows = targetRow - currentEnd;
        if (movieRows < 0) {
            throw new IllegalArgumentException("level segment movie ranges overlap");
        }
        return Math.max(0, movieRows - nonAdvancingMovieRows);
    }

    /**
     * Calculates the VBlank ticks represented by an uncompared interior and its
     * return choreography. The anchor is the final recorded row of the source
     * level; Sonic 1's special-stage path advances its global counter once for
     * every subsequent BK2 row through the destination level's first row.
     *
     * <p>{@code returnFramesConsumed} is the number of leading destination rows
     * the transition fall-through has already executed before the anchor is
     * applied, exactly as {@code nextFramesConsumed} is on
     * {@link #interLevelVblankBudget}. The target is the counter value in effect
     * on the LAST of those consumed rows -- equivalently, the value entering the
     * first row the comparator will compare -- so the budget runs to
     * {@code returnOffset + returnFramesConsumed - 1}.
     *
     * <p>This term was previously hardcoded to a consumed count of one, which is
     * what every uncompared return in the committed runs actually did, so the
     * arithmetic is unchanged for all of them. Carrying it explicitly is what
     * makes the anchor independent of the seam's row layout: with the count
     * fixed, any change to the number of rows the seam consumes silently
     * re-based the comparator against an anchor that had not moved, and the
     * resulting off-by-one row read was indistinguishable from a physics
     * divergence. Threading the real count is the precondition for judging such
     * a change at all.
     *
     * <p>A consumed count of zero is a legitimate return shape and is admitted
     * here, exactly as {@link #interLevelVblankBudget} admits
     * {@code nextFramesConsumed == 0}. The two budgets are the SAME expression:
     * {@code interLevelVblankBudget} counts from one past the source's final row
     * to {@code destOffset + consumed}, this one counts from the source's final
     * row to {@code destOffset + consumed - 1}, and both reduce to
     * {@code destOffset + consumed - sourceFinalRow - 1}. So the target is
     * always "the counter value entering the first row the comparator will
     * compare", whether or not a fall-through row was consumed first: at one
     * consumed row that is the value ON destination row 0, at zero consumed rows
     * it is the value on the row BEFORE destination row 0. The old
     * {@code >= 1} rejection asserted the observed shape of the committed runs,
     * not a property of the arithmetic, and a return that hands its host
     * iteration back rather than fusing the destination's first row into it is
     * the same boundary shape every {@code level_advance} admission already has
     * (all of which pass a consumed count of zero).
     */
    public static int uncomparedInteriorReturnVblankBudget(
            TraceRunManifest.Segment sourceLevel,
            TraceRunManifest.Segment returnLevel,
            int returnFramesConsumed,
            int nonAdvancingMovieRows) {
        if (sourceLevel.traceFrameCount() <= 0) {
            throw new IllegalArgumentException("source level must contain recorded frames");
        }
        if (nonAdvancingMovieRows < 0) {
            throw new IllegalArgumentException("frame counts must be non-negative");
        }
        if (returnFramesConsumed < 0) {
            throw new IllegalArgumentException("frame counts must be non-negative");
        }
        int sourceFinalRow = sourceLevel.bk2FrameOffset()
                + sourceLevel.traceFrameCount() - 1;
        int targetRow = returnLevel.bk2FrameOffset() + returnFramesConsumed - 1;
        int movieRows = targetRow - sourceFinalRow;
        if (movieRows < 0) {
            throw new IllegalArgumentException("interior return precedes source level tail");
        }
        return Math.max(0, movieRows - nonAdvancingMovieRows);
    }

    /**
     * Calculates the VBlank ticks between a source level's tail anchor and the
     * first row of the presentation bridge a special stage exits through.
     *
     * <p>The ROM's V-int increment is unconditional
     * ({@code VBlank_Exit: addq.l #1,(v_vblank_count).w},
     * docs/s1disasm/sonic.asm:684) and runs after whichever routine the mode
     * table dispatched, {@code VBlank_SpecialStage} included — so every movie
     * row of the uncompared stage interior carries exactly one tick. An anchor
     * is the value in effect ENTERING the row after the source's final row, and
     * the target is the value entering the bridge's first row, so the budget is
     * the rows strictly between them.
     */
    public static int presentationBridgeEntryVblankBudget(
            TraceRunManifest.Segment sourceLevel,
            TraceRunManifest.Segment bridge) {
        if (sourceLevel.traceFrameCount() <= 0) {
            throw new IllegalArgumentException("source level must contain recorded frames");
        }
        int sourceFinalRow = sourceLevel.bk2FrameOffset()
                + sourceLevel.traceFrameCount() - 1;
        int movieRows = bridge.bk2FrameOffset() - sourceFinalRow - 1;
        if (movieRows < 0) {
            throw new IllegalArgumentException(
                    "presentation bridge precedes source level tail");
        }
        return movieRows;
    }

    /**
     * Calculates the VBlank ticks a presentation bridge represents, from the
     * value entering its first row to the value entering the row after its
     * last. Every recorded row carries a tick except the profiled ones the ROM
     * spends building the results screen with interrupts disabled.
     */
    public static int presentationBridgeVblankSpan(
            TraceRunManifest.Segment bridge,
            int nonAdvancingMovieRows) {
        if (nonAdvancingMovieRows < 0) {
            throw new IllegalArgumentException("frame counts must be non-negative");
        }
        if (bridge.traceFrameCount() <= 0) {
            throw new IllegalArgumentException("bridge must contain recorded frames");
        }
        return Math.max(0, bridge.traceFrameCount() - nonAdvancingMovieRows);
    }

    /** Projects a source-tail VBlank from the observed playback cursor. */
    public static int sourceTailVblankAtBoundary(
            TraceRunManifest.Segment sourceLevel,
            int observedBk2Cursor,
            int observedVblank) {
        if (sourceLevel.traceFrameCount() <= 0 || observedBk2Cursor <= 0) {
            throw new IllegalArgumentException("source and observed cursor must be non-empty");
        }
        int sourceFinalRow = sourceLevel.bk2FrameOffset()
                + sourceLevel.traceFrameCount() - 1;
        int observedAppliedRow = observedBk2Cursor - 1;
        return observedVblank + sourceFinalRow - observedAppliedRow;
    }

    /**
     * The paired boundary transitions for a run, indexed by segment. A segment
     * named by no transition on a side keeps {@code null} there — a plain
     * level-to-level boundary carries no transition record.
     */
    public record BoundaryPairing(
        TraceRunManifest.Transition[] entryBoundaries,
        TraceRunManifest.Transition[] exitBoundaries
    ) {}

    /**
     * Pairs transitions to segment indices by their EXPLICIT {@code from_segment}/
     * {@code to_segment} indices — never by list position. Pure (no I/O), so the
     * control-flow test can exercise manifest-driven iteration (incl. plain
     * level-to-level gaps) without loading trace data or a ROM.
     */
    public static BoundaryPairing pairBoundaries(TraceRunManifest run) {
        int segmentCount = run.segments().size();
        TraceRunManifest.Transition[] entryBoundaries = new TraceRunManifest.Transition[segmentCount];
        TraceRunManifest.Transition[] exitBoundaries = new TraceRunManifest.Transition[segmentCount];
        if (run.transitions() != null) {
            for (TraceRunManifest.Transition transition : run.transitions()) {
                exitBoundaries[transition.fromSegment()] = transition;
                entryBoundaries[transition.toSegment()] = transition;
            }
        }
        return new BoundaryPairing(entryBoundaries, exitBoundaries);
    }

    /**
     * Frozen-cursor guard for {@link #awaitBoundary}: thrown when the max-steps
     * cap is exhausted before a boundary latches. Fails a chain test fast with a
     * diagnostic instead of hanging. Unchecked so the walker keeps its no-JUnit
     * contract; the test observes it as an ordinary test failure.
     */
    public static final class BoundaryStepCapExceededException extends RuntimeException {
        public BoundaryStepCapExceededException(String message) {
            super(message);
        }
    }

    /**
     * Run-scoped step cap for {@link #awaitBoundary} / mode waits, derived from
     * the manifest's inter-segment BK2 offset gaps (contract section 4):
     *
     * <pre>
     * max over consecutive segment pairs (i-1, i) of
     *     ( segments[i].bk2FrameOffset - segments[i-1].bk2FrameOffset )
     *   + BOUNDARY_WINDOW_FRAMES
     * </pre>
     *
     * The largest gap between consecutive segment START offsets is the most BK2
     * frames the shared movie ever spends in a single mode phase before the next
     * segment begins; any fade/title-card/results transition is far shorter, so
     * a legitimate await always latches (or hits the {@code modeChangeBk2Frame}
     * window edge) inside this bound. The {@code + BOUNDARY_WINDOW_FRAMES} addend
     * mirrors the boundary tolerance so the cap never trips inside an in-window
     * observation. A frozen cursor (no advance, mode never flips) exhausts it and
     * {@link #awaitBoundary} throws {@link BoundaryStepCapExceededException}.
     */
    public static int interSegmentStepCap(TraceRunManifest run) {
        List<TraceRunManifest.Segment> segments = run.segments();
        int maxGap = 0;
        for (int i = 1; i < segments.size(); i++) {
            int gap = segments.get(i).bk2FrameOffset() - segments.get(i - 1).bk2FrameOffset();
            maxGap = Math.max(maxGap, gap);
        }
        return maxGap + BOUNDARY_WINDOW_FRAMES;
    }

    /**
     * Engine-observation surface read INSIDE {@link BoundaryProbe#afterFrameAdvanced}
     * (for transient boundary kinds) or lazily from {@link BoundaryProbe#latched()}
     * (for the persistent {@code stage_exit} kind). See the TRANSIENT-PEEK REALITY
     * note on {@link BoundaryProbe}: the underlying coordinator peeks are only
     * non-null during the frame observer callback, never on post-step polling.
     */
    public interface EngineHooks {
        int currentBk2Frame();

        BonusStageType peekBonusRequest();

        boolean isSpecialStageRequested();

        GameMode currentMode();
    }

    /**
     * Validates the manifest, loads each segment's {@link TraceData}, and
     * pairs transitions to segments by their explicit {@code from_segment}/
     * {@code to_segment} indices — never by list position. A segment index
     * named by no transition keeps a null boundary on that side (plain
     * level-to-level boundaries carry no transition record).
     */
    public static List<SegmentPlan> plan(TraceRunManifest run, Path runDir) throws IOException {
        run.validate(runDir);
        List<TraceRunManifest.Segment> segments = run.segments();
        int segmentCount = segments.size();

        TraceData[] traces = new TraceData[segmentCount];
        TraceRunSpecialStageRows[] specialStageRows =
                new TraceRunSpecialStageRows[segmentCount];
        for (int i = 0; i < segmentCount; i++) {
            TraceRunManifest.Segment segment = segments.get(i);
            Path segmentDir = runDir.resolve(segment.dir());
            LoadedSegmentPayload payload = loadSegmentPayload(
                    segment, segmentDir, i);
            traces[i] = payload.trace();
            specialStageRows[i] = payload.specialStageRows();
        }
        run.validateDynamicArtRun(java.util.Arrays.asList(traces));

        BoundaryPairing pairing = pairBoundaries(run);

        List<SegmentPlan> plans = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            plans.add(new SegmentPlan(
                segments.get(i), traces[i],
                pairing.entryBoundaries()[i], pairing.exitBoundaries()[i],
                specialStageRows[i],
                segmentExecutionPolicy(segments.get(i),
                        pairing.entryBoundaries()[i], traces[i])));
        }
        return plans;
    }

    /**
     * Scans and validates run segments sequentially into payload-independent
     * descriptors. The eager {@link #plan} path remains only as a benchmark
     * reference; launch and replay owners use this compact boundary and open
     * one segment payload at a time.
     */
    public static List<TraceRunSegmentDescriptor> planDescriptors(
            TraceRunManifest run, Path runDir) throws IOException {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(runDir, "runDir");
        run.validate(runDir);
        BoundaryPairing pairing = pairBoundaries(run);
        TraceRunManifest.DynamicArtRunValidator dynamicArtValidator =
                run.new DynamicArtRunValidator();
        List<TraceRunSegmentDescriptor> descriptors =
                new ArrayList<>(run.segments().size());

        for (int segmentIndex = 0;
                segmentIndex < run.segments().size();
                segmentIndex++) {
            TraceRunManifest.Segment segment =
                    run.segments().get(segmentIndex);
            Path segmentDirectory = runDir.resolve(segment.dir());
            LoadedSegmentPayload payload = loadSegmentPayload(
                    segment, segmentDirectory, segmentIndex);
            TraceData trace = payload.trace();
            TraceRunSpecialStageRows specialStageRows =
                    payload.specialStageRows();

            dynamicArtValidator.accept(segmentIndex, trace);
            TraceMetadata metadata =
                    specialStageRows != null
                            ? specialStageRows.metadata()
                            : trace.metadata();
            int rowCount = specialStageRows != null
                    ? specialStageRows.rowCount()
                    : trace.frameCount();
            int levelLoopRowCount = levelLoopRowCount(trace);
            validateDescriptorManifestFields(
                    segmentIndex, segment, metadata, rowCount);

            List<Integer> rawFrames = new ArrayList<>(rowCount);
            BitSet laggedRows = new BitSet(rowCount);
            if (specialStageRows != null) {
                for (int row = 0; row < rowCount; row++) {
                    rawFrames.add(row);
                    if (!specialStageRows.admission(row).executeGameplay()) {
                        laggedRows.set(row);
                    }
                }
            } else {
                for (int row = 0; row < rowCount; row++) {
                    int rawFrame = trace.getFrame(row).frame();
                    rawFrames.add(rawFrame);
                    TraceEvent.LagState lagState =
                            trace.lagStateForFrame(rawFrame);
                    if (lagState != null && lagState.lagged()) {
                        laggedRows.set(row);
                    }
                }
            }

            descriptors.add(new TraceRunSegmentDescriptor(
                    segment,
                    segmentDirectory,
                    metadata,
                    rowCount,
                    trace.frameCount() > 0 ? trace.getFrame(0) : null,
                    rawFrames,
                    laggedRows,
                    trace.hardwareTimingSchedule(),
                    trace.terminalDynamicArtLedger(),
                    pairing.entryBoundaries()[segmentIndex],
                    pairing.exitBoundaries()[segmentIndex],
                    levelLoopRowCount,
                    segmentExecutionPolicy(
                            segment,
                            pairing.entryBoundaries()[segmentIndex],
                            trace)));
        }
        dynamicArtValidator.finish();
        return List.copyOf(descriptors);
    }

    /**
     * Opens the parsed comparison payload for one descriptor at the point a
     * replay drive takes ownership of that segment. No lease is constructed
     * until both halves of a special-stage composite load successfully.
     */
    public static ActiveSegmentPayload openActiveSegment(
            TraceRunSegmentDescriptor descriptor, int segmentIndex)
            throws IOException {
        Objects.requireNonNull(descriptor, "descriptor");
        LoadedSegmentPayload payload = loadSegmentPayload(
                descriptor.segment(), descriptor.segmentDirectory(), segmentIndex);
        return new ActiveSegmentPayload(
                descriptor, payload.trace(), payload.specialStageRows());
    }

    private static LoadedSegmentPayload loadSegmentPayload(
            TraceRunManifest.Segment segment,
            Path segmentDirectory,
            int segmentIndex) throws IOException {
        // A special_stage interior is gameplay-uncompared under SS-interior
        // policy v1 (see isUncomparedInterior): attachInteriorComparator never
        // builds a gameplay comparator from its frames, so its physics.csv --
        // which uses a per-game special-stage schema structurally distinct
        // from TraceFrame's primary-level columns -- need not parse there.
        // Metadata loading still exposes its optional DPLC heartbeat.
        try {
            if (isUncomparedInterior(segment)) {
                TraceRunSpecialStageRows specialStageRows =
                        TraceRunSpecialStageRows.load(
                                segment.traceProfile(), segmentDirectory,
                                segment.dynamicArtInitialLedgerDescriptors());
                TraceData trace = TraceData.loadMetadataOnly(
                        segmentDirectory,
                        com.openggf.trace.StoredPhysicsFrameDomain
                                .FrameEncoding.DECIMAL,
                        segment.dynamicArtInitialLedgerDescriptors());
                return new LoadedSegmentPayload(trace, specialStageRows);
            }
            return new LoadedSegmentPayload(
                    TraceData.load(
                            segmentDirectory,
                            segment.dynamicArtInitialLedgerDescriptors()),
                    null);
        } catch (IOException | RuntimeException failure) {
            throw new IOException(
                    "Segment " + segmentIndex + " parser failed for profile '"
                            + segment.traceProfile() + "': "
                            + (failure.getMessage() != null
                                    ? failure.getMessage()
                                    : failure.getClass().getSimpleName()),
                    failure);
        }
    }

    private static void validateDescriptorManifestFields(
            int segmentIndex,
            TraceRunManifest.Segment segment,
            TraceMetadata metadata,
            int rowCount) {
        boolean compatibleProfile = Objects.equals(
                segment.traceProfile(), metadata.traceProfile())
                || ("level".equals(segment.kind())
                        && "complete_run".equals(segment.traceProfile())
                        && metadata.traceProfile() == null);
        if (!compatibleProfile) {
            throw new IllegalArgumentException(
                    "Segment " + segmentIndex
                            + " profile mismatch: manifest='"
                            + segment.traceProfile() + "', metadata='"
                            + metadata.traceProfile() + "'");
        }
        if (rowCount != segment.traceFrameCount()) {
            throw new IllegalArgumentException(
                    "Segment " + segmentIndex
                            + " row count mismatch: manifest="
                            + segment.traceFrameCount() + ", parsed="
                            + rowCount);
        }
    }

    private record LoadedSegmentPayload(
            TraceData trace,
            TraceRunSpecialStageRows specialStageRows) {
    }

    /**
     * True when {@code observedBk2Frame} falls inside the bounded observation
     * window around {@code recordedEdge}. The longer leading window accommodates
     * fade/card setup that begins before the recorder sees a mode change; the
     * shorter trailing grace accommodates equivalent engine transitions whose
     * request becomes visible shortly after that edge.
     */
    public static boolean withinBoundaryWindow(int observedBk2Frame, int recordedEdge) {
        return observedBk2Frame <= recordedEdge + LATE_BOUNDARY_GRACE_FRAMES
            && observedBk2Frame >= recordedEdge - BOUNDARY_WINDOW_FRAMES;
    }

    /**
     * The single {@link PlaybackDebugManager.PlaybackFrameObserver} the chain
     * installs. Delegates BOTH interface methods to an attached delegate
     * (typically a {@link com.openggf.trace.live.LiveTraceComparator} at
     * integration time, or a lightweight stub in unit tests — the delegate
     * type is exactly {@link PlaybackDebugManager.PlaybackFrameObserver},
     * which already exposes only the two methods that need forwarding):
     *
     * <ul>
     *   <li>{@link #shouldSkipGameplayTick} forwards to the delegate's ROM-lag
     *       gating result, or {@code false} when detached (no delegate
     *       attached — e.g. mid-transition).</li>
     *   <li>{@link #afterFrameAdvanced} forwards to the delegate first, then,
     *       when armed with a transient-kind {@link TraceRunManifest.Transition}
     *       ({@code starpost_bonus}, {@code giant_ring}, {@code starpost_special}),
     *       evaluates the boundary predicate DURING the callback — the only
     *       point the underlying request peek is visible — and latches a
     *       {@link BoundaryObservation} on first match within the window.</li>
     * </ul>
     *
     * <p><b>TRANSIENT-PEEK REALITY:</b> the entry request is raised during the
     * gameplay tick and consumed later in the same {@code loop.step()}; by the
     * time {@code step()} returns, the peek is already null. Post-step polling
     * can never observe an entry — only the observer callback can.
     *
     * <p>{@code stage_exit} is PERSISTENT ({@code currentMode() == GameMode.LEVEL})
     * and is instead evaluated lazily, live from the injected {@link EngineHooks},
     * whenever {@link #latched()} is queried — no observer callback fires during
     * a frozen return transition, so {@link #awaitBoundary} (which holds only the
     * probe) observes it by polling {@link #latched()} after each step.
     */
    public static final class BoundaryProbe implements PlaybackDebugManager.PlaybackFrameObserver {
        private final EngineHooks hooks;
        private PlaybackDebugManager.PlaybackFrameObserver delegate;
        private PlaybackDebugManager.PlaybackFrameObserver preparedDelegate;
        private TraceRunManifest.Transition armed;
        private BoundaryObservation latchedObservation;
        private int pendingSpecialStageRequestFrame = -1;
        private Bk2FrameInput preparedFrame;
        private boolean framePrepared;
        private Consumer<Bk2FrameInput> beforeFrameObserver = frame -> {
        };

        public BoundaryProbe(EngineHooks hooks) {
            this.hooks = hooks;
        }

        /** Attaches (or, with {@code null}, detaches) the delegate observer. */
        public void setDelegate(PlaybackDebugManager.PlaybackFrameObserver delegate) {
            this.delegate = delegate;
            // A handoff may be observed after prepareFrame() but before the
            // represented row advances. Keep that row pinned to the delegate
            // which prepared it; afterFrameAdvanced() clears the pin. When no
            // row is prepared there is no cached ownership to retain.
            if (!framePrepared) {
                preparedFrame = null;
                preparedDelegate = null;
                return;
            }
            // A row prepared while DETACHED has no preparer to pin to. Without
            // this, the pin above hands that row to a null delegate: a segment
            // boundary that settles mid-row (the special-stage return latches
            // GameMode.LEVEL on a step that never reaches afterFrameAdvanced,
            // leaving framePrepared set with preparedDelegate == null) would
            // then run its FIRST gameplay frame unobserved. The engine executes
            // that frame -- it reproduces the return segment's recorded frame 0
            // exactly -- but the comparator never sees it, so recorded row 0 is
            // compared against post-frame-1 state and every physics field is
            // reported one frame ahead. Adopting the row keeps the comparator's
            // cursor and the executed frame in lockstep; a row prepared by a
            // real delegate still stays pinned to it.
            if (preparedDelegate == null && delegate != null) {
                preparedDelegate = delegate;
                delegate.prepareFrame(preparedFrame);
            }
        }

        /**
         * Releases every observer alias when the represented segment lease
         * closes. Unlike {@link #setDelegate}, no prepared row may remain
         * pinned across this ownership boundary.
         */
        public void detachDelegate() {
            delegate = null;
            preparedDelegate = null;
            preparedFrame = null;
            framePrepared = false;
        }

        /**
         * Installs a row hook that runs before lag classification or any
         * gameplay service boundary represented by the row.
         */
        public void setBeforeFrameObserver(Consumer<Bk2FrameInput> observer) {
            beforeFrameObserver = observer != null ? observer : frame -> {
            };
        }

        /** Arms the probe for a new boundary, clearing any prior latch. */
        public void arm(TraceRunManifest.Transition boundary) {
            this.armed = boundary;
            this.latchedObservation = null;
            this.pendingSpecialStageRequestFrame = -1;
        }

        /**
         * True once a boundary observation has latched. For the persistent
         * {@code stage_exit} kind this lazily evaluates {@link EngineHooks#currentMode()}
         * on first call after the condition becomes true, caching the frame
         * at which it was first noticed so repeated queries stay stable.
         */
        public boolean latched() {
            if (latchedObservation == null && armed != null) {
                BoundaryEntryMode entryMode = boundaryEntryMode(armed.entryKind());
                boolean persistentHit = entryMode == BoundaryEntryMode.LEVEL_MODE
                    && hooks.currentMode() == GameMode.LEVEL;
                boolean deferredSpecialStageHit = entryMode == BoundaryEntryMode.SPECIAL_STAGE_REQUEST
                    && pendingSpecialStageRequestFrame >= 0;
                if (persistentHit || deferredSpecialStageHit) {
                    int observedFrame = deferredSpecialStageHit
                        ? pendingSpecialStageRequestFrame
                        : hooks.currentBk2Frame();
                    if (withinBoundaryWindow(observedFrame, armed.modeChangeBk2Frame())) {
                        latchedObservation = new BoundaryObservation(true, observedFrame);
                    }
                }
            }
            return latchedObservation != null;
        }

        /** The latched observation, or a not-observed sentinel if {@link #latched()} is false. */
        public BoundaryObservation observation() {
            return latchedObservation != null ? latchedObservation : BoundaryObservation.NOT_OBSERVED;
        }

        /** For {@link #awaitBoundary}'s fail-closed edge check. */
        int currentBk2Frame() {
            return hooks.currentBk2Frame();
        }

        @Override
        public void prepareFrame(Bk2FrameInput frame) {
            if (framePrepared && Objects.equals(preparedFrame, frame)) {
                return;
            }
            preparedFrame = frame;
            framePrepared = true;
            preparedDelegate = delegate;
            beforeFrameObserver.accept(frame);
            if (preparedDelegate != null) {
                preparedDelegate.prepareFrame(frame);
            }
        }

        @Override
        public int appliedInputOffset(Bk2FrameInput frame) {
            prepareFrame(frame);
            return preparedDelegate != null
                    ? preparedDelegate.appliedInputOffset(frame) : 0;
        }

        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            prepareFrame(frame);
            return preparedDelegate != null
                    && preparedDelegate.shouldSkipGameplayTick(frame);
        }

        @Override
        public boolean shouldAdvanceVblankOnSkippedTick(Bk2FrameInput frame) {
            prepareFrame(frame);
            return preparedDelegate == null
                    || preparedDelegate.shouldAdvanceVblankOnSkippedTick(frame);
        }

        @Override
        public int vblankAdvanceCountOnSkippedTick(Bk2FrameInput frame) {
            prepareFrame(frame);
            return preparedDelegate == null
                    ? 1 : preparedDelegate.vblankAdvanceCountOnSkippedTick(frame);
        }

        @Override
        public boolean hasUnconsumedRecordedRows() {
            // Row ownership is the row delegate's to answer; the probe adds
            // only boundary latching. No prepareFrame() here -- this query is
            // read on frozen frames that never prepared a row.
            PlaybackDebugManager.PlaybackFrameObserver rowDelegate =
                    framePrepared ? preparedDelegate : delegate;
            return rowDelegate != null && rowDelegate.hasUnconsumedRecordedRows();
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
            PlaybackDebugManager.PlaybackFrameObserver rowDelegate =
                    framePrepared ? preparedDelegate : delegate;
            if (rowDelegate != null) {
                rowDelegate.afterFrameAdvanced(frame, wasSkipped);
            }
            preparedFrame = null;
            framePrepared = false;
            preparedDelegate = null;
            if (armed == null || latchedObservation != null) {
                return;
            }
            boolean transientHit = switch (boundaryEntryMode(armed.entryKind())) {
                case BONUS_REQUEST -> hooks.peekBonusRequest() != null;
                case SPECIAL_STAGE_REQUEST -> hooks.isSpecialStageRequested();
                case LEVEL_MODE -> false; // stage_exit is persistent; evaluated in latched().
                case LEVEL_LOAD -> false; // supplied by the production level-load seam.
            };
            if (transientHit) {
                int observedFrame = hooks.currentBk2Frame();
                if (withinBoundaryWindow(observedFrame, armed.modeChangeBk2Frame())) {
                    latchedObservation = new BoundaryObservation(true, observedFrame);
                }
            }
        }

        @Override
        public void onSpecialStageRequestRaised() {
            PlaybackDebugManager.PlaybackFrameObserver rowDelegate =
                    framePrepared ? preparedDelegate : delegate;
            if (rowDelegate != null) {
                rowDelegate.onSpecialStageRequestRaised();
            }
            if (armed == null || latchedObservation != null
                || boundaryEntryMode(armed.entryKind()) != BoundaryEntryMode.SPECIAL_STAGE_REQUEST) {
                return;
            }
            // Do not latch here: when the ordinary afterFrameAdvanced callback
            // still follows this request in the same step, its post-advance
            // frame is the established clock anchor. Retain only a fallback for
            // requests raised after that callback and consumed during a context
            // swap before the next frame observer can run.
            pendingSpecialStageRequestFrame = hooks.currentBk2Frame();
        }

        /**
         * Latches a value-only semantic signal emitted outside the ordinary
         * playback callback, notably level advances and death restarts.
         */
        public void observeSignal(RunBoundarySignal signal) {
            Objects.requireNonNull(signal, "signal");
            if (armed == null || latchedObservation != null
                    || !withinBoundaryWindow(
                            signal.physicalBk2Frame(), armed.modeChangeBk2Frame())
                    || !matchesArmedSignal(signal)) {
                return;
            }
            latchedObservation = new BoundaryObservation(
                    true, signal.physicalBk2Frame());
        }

        private boolean matchesArmedSignal(RunBoundarySignal signal) {
            return switch (armed.entryKind()) {
                case "starpost_bonus" ->
                        signal instanceof RunBoundarySignal.BonusRequest;
                case "giant_ring", "starpost_special" ->
                        signal instanceof RunBoundarySignal.SpecialStageRequest;
                case "stage_exit" -> signal instanceof RunBoundarySignal.StageExit;
                case "level_advance" -> signal instanceof RunBoundarySignal.LevelLoaded loaded
                        && loaded.cause() == RunLevelLoadCause.LEVEL_ADVANCE;
                case "death_restart" -> signal instanceof RunBoundarySignal.LevelLoaded loaded
                        && loaded.cause() == RunLevelLoadCause.DEATH_RESTART;
                default -> false;
            };
        }
    }

    /**
     * Arms {@code probe} for {@code boundary}, then repeatedly invokes
     * {@code stepOneFrame} until the probe latches an observation or the
     * cursor passes {@code boundary.modeChangeBk2Frame()}. Two distinct
     * non-hanging outcomes:
     *
     * <ul>
     *   <li>Cursor advances PAST {@code modeChangeBk2Frame()} without the
     *       boundary latching — a missed entry peek — returns
     *       {@link BoundaryObservation#NOT_OBSERVED} (the caller asserts
     *       {@code observed()} and reports it).</li>
     *   <li>{@code maxSteps} {@code stepOneFrame} calls elapse without a latch —
     *       a FROZEN cursor (the fade/title-card return where the BK2 cursor
     *       never advances past the edge and the mode never settles) — throws
     *       {@link BoundaryStepCapExceededException} with a diagnostic instead
     *       of looping forever. Derive {@code maxSteps} from
     *       {@link #interSegmentStepCap(TraceRunManifest)}.</li>
     * </ul>
     */
    public static BoundaryObservation awaitBoundary(
            BoundaryProbe probe, TraceRunManifest.Transition boundary,
            int maxSteps, Runnable stepOneFrame) {
        probe.arm(boundary);
        int steps = 0;
        while (true) {
            stepOneFrame.run();
            steps++;
            if (probe.latched()) {
                return probe.observation();
            }
            if (probe.currentBk2Frame()
                    > boundary.modeChangeBk2Frame() + LATE_BOUNDARY_GRACE_FRAMES) {
                return BoundaryObservation.NOT_OBSERVED;
            }
            if (steps >= maxSteps) {
                throw new BoundaryStepCapExceededException(
                    "awaitBoundary exceeded step cap " + maxSteps + " for entry_kind '"
                    + boundary.entryKind() + "' (mode_change_bk2_frame="
                    + boundary.modeChangeBk2Frame() + ", last observed bk2 frame="
                    + probe.currentBk2Frame()
                    + "): cursor frozen or mode never settled to the boundary.");
            }
        }
    }
}
