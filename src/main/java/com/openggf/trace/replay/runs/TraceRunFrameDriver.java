package com.openggf.trace.replay.runs;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.replay.TraceReplayRowPolicy;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.SegmentExecutionPolicy;

import java.util.Objects;

/**
 * Shared physical-row sequencer for visual and headless whole-run replay.
 * The driver owns ordering only; adapters supply production and diagnostic
 * operations without giving recorded comparison values to gameplay owners.
 */
public final class TraceRunFrameDriver {
    private Step currentStep;

    public enum Disposition {
        GAMEPLAY_SHARED(true),
        SPECIAL_LOCAL(true),
        PRESENTATION_VBLANK(true),
        PRESENTATION_SUPPRESSED_CLOSURE(true),
        PRESENTATION_ADVANCE_ONLY(false),
        SHARED_GAP(true),
        OFFSET_HANDOFF(false),
        TERMINAL_TAIL(true);

        private final boolean productionLifecycle;

        Disposition(boolean productionLifecycle) {
            this.productionLifecycle = productionLifecycle;
        }

        public boolean runsProductionLifecycle() {
            return productionLifecycle;
        }
    }

    public record Step(
            Disposition disposition,
            int movieRow,
            boolean terminalSegmentRow,
            boolean deferBoundaryCommitAfterRow,
            boolean commitDeferredBoundaryAfterClosure,
            boolean observedVblankCounterAdvance) {
        public Step(
                Disposition disposition,
                int movieRow,
                boolean terminalSegmentRow) {
            this(disposition, movieRow, terminalSegmentRow,
                    false, false, true);
        }

        public Step(
                Disposition disposition,
                int movieRow,
                boolean terminalSegmentRow,
                boolean deferBoundaryCommitAfterRow) {
            this(disposition, movieRow, terminalSegmentRow,
                    deferBoundaryCommitAfterRow, false, true);
        }

        public Step {
            Objects.requireNonNull(disposition, "disposition");
            if (movieRow < 0) {
                throw new IllegalArgumentException("movieRow must be non-negative");
            }
        }
    }

    public interface Hooks<S> {
        void preparePhysicalRow(Step step);

        void prepareHardwareTiming(Step step);

        S captureBefore(Step step);

        void runProductionLifecycle(Step step);

        /**
         * Allows an adapter to retain the current physical row when
         * production crosses a destination boundary. The next admitted
         * destination row owns that row and advances the shared clock.
         */
        default boolean shouldAdvancePhysicalRow(Step step) {
            return true;
        }

        void advancePhysicalRow(Step step);

        S captureAfter(Step step);

        void compare(Step step, S before, S after);

        void afterStep(Step step);
    }

    public <S> void execute(Step step, Hooks<S> hooks) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(hooks, "hooks");
        if (step.disposition() == Disposition.OFFSET_HANDOFF) {
            throw new IllegalStateException(
                    "OFFSET_HANDOFF cannot consume a host step");
        }
        if (currentStep != null) {
            throw new IllegalStateException("trace run frame driver is already executing");
        }
        currentStep = step;
        Throwable primary = null;
        try {
            hooks.preparePhysicalRow(step);
            hooks.prepareHardwareTiming(step);
            S before = hooks.captureBefore(step);
            if (step.disposition().runsProductionLifecycle()) {
                hooks.runProductionLifecycle(step);
            }
            if (hooks.shouldAdvancePhysicalRow(step)) {
                hooks.advancePhysicalRow(step);
            }
            S after = hooks.captureAfter(step);
            hooks.compare(step, before, after);
        } catch (RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            try {
                hooks.afterStep(step);
            } catch (RuntimeException | Error cleanupFailure) {
                if (primary != null) {
                    primary.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            } finally {
                currentStep = null;
            }
        }
    }

    public Disposition currentDisposition() {
        return currentStep != null ? currentStep.disposition() : null;
    }

    public boolean defersBoundaryCommitAfterCurrentRow() {
        return currentStep != null
                && currentStep.deferBoundaryCommitAfterRow();
    }

    /**
     * Keeps a completed presentation owner in place across a recorded span
     * where the ROM did not advance its VBlank counter. The row immediately
     * before the span is included because committing at its end would expose
     * destination work one physical row too early.
     */
    public static boolean shouldDeferBoundaryCommit(
            boolean currentObservedVblankAdvance,
            boolean nextObservedVblankAdvance) {
        return !currentObservedVblankAdvance
                || !nextObservedVblankAdvance;
    }

    /** True on the first represented closure after a no-VBlank span. */
    public static boolean shouldCommitDeferredBoundaryAfterClosure(
            boolean previousObservedVblankAdvance,
            boolean currentObservedVblankAdvance) {
        return !previousObservedVblankAdvance
                && currentObservedVblankAdvance;
    }

    public static Disposition selectDisposition(
            TraceRunPlaybackCoordinator.Phase coordinatorPhase,
            SegmentExecutionPolicy executionPolicy,
            TraceExecutionPhase rowPhase) {
        return selectDisposition(
                coordinatorPhase, executionPolicy, rowPhase,
                true, true, false);
    }

    public static Disposition selectDisposition(
            TraceRunPlaybackCoordinator.Phase coordinatorPhase,
            SegmentExecutionPolicy executionPolicy,
            TraceExecutionPhase rowPhase,
            boolean observedVblankCounterAdvance) {
        return selectDisposition(
                coordinatorPhase, executionPolicy, rowPhase,
                observedVblankCounterAdvance, true, false);
    }

    public static Disposition selectDisposition(
            TraceRunPlaybackCoordinator.Phase coordinatorPhase,
            SegmentExecutionPolicy executionPolicy,
            TraceExecutionPhase rowPhase,
            boolean observedVblankCounterAdvance,
            boolean previousObservedVblankCounterAdvance) {
        return selectDisposition(
                coordinatorPhase, executionPolicy, rowPhase,
                observedVblankCounterAdvance,
                previousObservedVblankCounterAdvance, false);
    }

    /**
     * True when the represented presentation row owns no mode V-int and must
     * run as a lag closure rather than a real V-blank.
     *
     * <p>Two recorded shapes qualify, and both are ROM structure rather than
     * fixture measurement:
     * <ul>
     *   <li>The first row whose counter advances after a span with no advance
     *       at all. The CPU was inside a {@code disable_ints} span rebuilding
     *       the screen (S1 special-stage results:
     *       docs/s1disasm/sonic.asm:3368-3379), and {@code enable_ints} is
     *       reached while {@code v_vblank_routine} is still 0 -- so the first
     *       {@code V_Int} back branches to {@code VBlank_Lag}
     *       (sonic.asm:709) and runs no mode handler. Only the following
     *       iteration, after {@code SS_NormalExit} stores
     *       {@code id_VBlank_TitleCards} and waits (sonic.asm:3403-3405), owns
     *       a real V-int.</li>
     *   <li>A row whose own counter did not advance and whose successor did not
     *       consume a deferred tick. No V-blank elapsed for it either.</li>
     * </ul>
     *
     * <p>The row deliberately excluded is the isolated starved row whose
     * successor consumed two ticks: there the iteration merely overran the
     * sample, its V-blank did run the active handler -- including
     * {@code ProcessPLC_9Tiles} (sonic.asm:1430-1440) -- and only the counter
     * store landed after the sample. Suppressing it costs the row its
     * nine-tile decode, which is what
     * {@code TraceRunPresentationClosure} already documents it must keep.
     */
    static boolean presentationRowIsCarriedLagClosure(
            boolean observedVblankCounterAdvance,
            boolean previousObservedVblankCounterAdvance,
            boolean nextRowCarriesDeferredVblank) {
        return !previousObservedVblankCounterAdvance
                || (!observedVblankCounterAdvance
                        && !nextRowCarriesDeferredVblank);
    }

    public static Disposition selectDisposition(
            TraceRunPlaybackCoordinator.Phase coordinatorPhase,
            SegmentExecutionPolicy executionPolicy,
            TraceExecutionPhase rowPhase,
            boolean observedVblankCounterAdvance,
            boolean previousObservedVblankCounterAdvance,
            boolean destinationGameplayModeReached) {
        return selectDisposition(
                coordinatorPhase, executionPolicy, rowPhase,
                observedVblankCounterAdvance,
                previousObservedVblankCounterAdvance,
                destinationGameplayModeReached,
                false);
    }

    public static Disposition selectDisposition(
            TraceRunPlaybackCoordinator.Phase coordinatorPhase,
            SegmentExecutionPolicy executionPolicy,
            TraceExecutionPhase rowPhase,
            boolean observedVblankCounterAdvance,
            boolean previousObservedVblankCounterAdvance,
            boolean destinationGameplayModeReached,
            boolean nextRowCarriesDeferredVblank) {
        return selectDisposition(
                coordinatorPhase, executionPolicy, rowPhase,
                observedVblankCounterAdvance,
                previousObservedVblankCounterAdvance,
                destinationGameplayModeReached,
                nextRowCarriesDeferredVblank,
                false);
    }

    /**
     * Resolves one presentation-bridge row into the {@link Step} that drives
     * it. This is the single implementation of the bridge-stepping contract:
     * the visual launcher and the headless chain harness both call it, having
     * previously each carried their own copy.
     *
     * <p>Everything the contract derives from the recorded row is derived
     * here -- row phase, this row's and the previous row's observed V-blank
     * counter advance, whether the next row carries a deferred V-blank,
     * whether the row is a recorded lag V-int, and the two boundary-commit
     * flags. {@code terminalSegmentRow} stays a caller argument on purpose:
     * the launcher takes it from the manifest's segment row count and the
     * chain from the loaded fixture's row count. Those agree on all 327
     * segments currently in the tree, but they are not the same expression,
     * so folding them together here would be a behaviour change smuggled into
     * a refactor.
     *
     * @param movieRow the physical movie cursor for {@code localRow}; the
     *                 neighbouring rows are resolved against
     *                 {@code movieRow - 1} and {@code movieRow + 1} to match
     *                 the row-policy contract.
     */
    public static Step presentationBridgeStep(
            TraceData trace,
            int localRow,
            int movieRow,
            SegmentExecutionPolicy executionPolicy,
            boolean destinationGameplayModeReached,
            boolean terminalSegmentRow) {
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        if (localRow < 0 || localRow >= trace.frameCount()) {
            throw new IllegalArgumentException(
                    "presentation row " + localRow + " outside trace");
        }
        TraceReplayRowPolicy rowPolicy =
                TraceReplayRowPolicy.resolve(trace, localRow, movieRow);
        boolean observedVblankCounterAdvance =
                rowPolicy.observedVblankCounterAdvance();
        boolean previousObservedVblankCounterAdvance = localRow == 0
                || TraceReplayRowPolicy
                        .resolve(trace, localRow - 1, movieRow - 1)
                        .observedVblankCounterAdvance();
        boolean hasNextRow = localRow + 1 < trace.frameCount();
        boolean nextRowCarriesDeferredVblank = hasNextRow
                && TraceReplayRowPolicy.carriesDeferredVblank(
                        trace, localRow + 1);
        boolean deferBoundaryCommit = hasNextRow
                && shouldDeferBoundaryCommit(
                        observedVblankCounterAdvance,
                        TraceReplayRowPolicy
                                .resolve(trace, localRow + 1, movieRow + 1)
                                .observedVblankCounterAdvance());
        Disposition disposition = selectDisposition(
                TraceRunPlaybackCoordinator.Phase.CURRENT_SEGMENT,
                executionPolicy,
                rowPolicy.phase(),
                observedVblankCounterAdvance,
                previousObservedVblankCounterAdvance,
                destinationGameplayModeReached,
                nextRowCarriesDeferredVblank,
                recordedRowIsLagVint(trace, localRow));
        return new Step(
                disposition,
                movieRow,
                terminalSegmentRow,
                deferBoundaryCommit,
                shouldCommitDeferredBoundaryAfterClosure(
                        previousObservedVblankCounterAdvance,
                        observedVblankCounterAdvance),
                observedVblankCounterAdvance);
    }

    /**
     * The emulator's own per-frame lag observation for a recorded row. A
     * lagged row's V-int took {@code VBlank_Lag}
     * (docs/s1disasm/sonic.asm:656-657) and reached no {@code ProcessPLC}
     * call (:711-746), so the row owns a V-int closure with no mode handler.
     *
     * <p>Read from the recorded flag and never inferred from counter shape:
     * inside a presentation bridge the two disagree on nearly every row,
     * because bridge gameplay is structurally frozen regardless of lag.
     */
    private static boolean recordedRowIsLagVint(TraceData trace, int localRow) {
        TraceEvent.LagState state =
                trace.lagStateForFrame(trace.getFrame(localRow).frame());
        return state != null && state.lagged();
    }

    /**
     * Adds the recorded per-frame lag observation as a second, independent
     * reason to suppress a presentation row's mode handler.
     *
     * <p>{@code presentationRowIsCarriedLagClosure} models <em>no V-int
     * elapsed for this row</em>, derived from counter shape. This models the
     * other ROM case: a V-int <em>did</em> run and took the {@code VBlank_Lag}
     * branch (docs/s1disasm/sonic.asm:656-657), which reaches no
     * {@code ProcessPLC} call (:712-746) yet still advances
     * {@code v_vblank_count} through {@code VBlank_Exit} (:684-687). Both
     * produce a row that services no patterns, so both select the suppressed
     * closure; neither subsumes the other.
     *
     * <p>The recorded flag is the authority and counter shape must never be
     * used in its place. Inside a presentation bridge the two diverge sharply
     * -- {@code syz2} holds 802 of 811 rows with a frozen gameplay counter
     * while only 9 are lagged -- because bridge gameplay is structurally
     * frozen regardless of lag.
     */
    public static Disposition selectDisposition(
            TraceRunPlaybackCoordinator.Phase coordinatorPhase,
            SegmentExecutionPolicy executionPolicy,
            TraceExecutionPhase rowPhase,
            boolean observedVblankCounterAdvance,
            boolean previousObservedVblankCounterAdvance,
            boolean destinationGameplayModeReached,
            boolean nextRowCarriesDeferredVblank,
            boolean recordedRowIsLagVint) {
        Objects.requireNonNull(coordinatorPhase, "coordinatorPhase");
        Objects.requireNonNull(executionPolicy, "executionPolicy");
        Objects.requireNonNull(rowPhase, "rowPhase");
        return switch (coordinatorPhase) {
            case TRANSITION_GAP -> Disposition.SHARED_GAP;
            case TERMINAL_TAIL -> Disposition.TERMINAL_TAIL;
            case CURRENT_SEGMENT -> switch (executionPolicy) {
                case GAMEPLAY -> Disposition.GAMEPLAY_SHARED;
                case SPECIAL_LOCAL -> Disposition.SPECIAL_LOCAL;
                case LEVEL_PRESENTATION_BRIDGE -> destinationGameplayModeReached
                        ? Disposition.PRESENTATION_SUPPRESSED_CLOSURE
                        : switch (rowPhase) {
                    // Recorder row zero carries a full-state bootstrap shape,
                    // but the segment policy has already proven that the
                    // destination is native presentation rather than gameplay.
                    case FULL_LEVEL_FRAME -> Disposition.PRESENTATION_VBLANK;
                    case VBLANK_ONLY -> recordedRowIsLagVint
                            || presentationRowIsCarriedLagClosure(
                            observedVblankCounterAdvance,
                            previousObservedVblankCounterAdvance,
                            nextRowCarriesDeferredVblank)
                            ? Disposition.PRESENTATION_SUPPRESSED_CLOSURE
                            : Disposition.PRESENTATION_VBLANK;
                    case ADVANCE_ONLY -> Disposition.PRESENTATION_ADVANCE_ONLY;
                    default -> throw new IllegalStateException(
                            "presentation bridge cannot execute " + rowPhase);
                };
            };
            case DESTINATION_READY -> Disposition.OFFSET_HANDOFF;
            case COMPLETE, FAILED -> throw new IllegalStateException(
                    "completed run cannot consume another physical row");
        };
    }
}
