package com.openggf.trace.replay.runs;

import com.openggf.game.resources.DynamicArtGapTransition;
import com.openggf.game.resources.DynamicArtDiagnosticsProvider;
import com.openggf.game.resources.DynamicArtGapDiagnosticsSnapshot;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.TraceRunManifest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only adapter that turns production lifecycle evidence into the shared
 * complete-run gap comparison. It observes work submitted by the engine and
 * never creates, completes, or releases a transfer.
 */
public final class TraceRunDynamicArtGapJournal {
    private final TraceRunManifest manifest;
    private final DynamicArtDiagnosticsProvider diagnostics;
    private long structuralOrdinal;
    private long sourceClosedOrdinal;
    private long gapOpenedOrdinal;
    private int sourceSegmentIndex = -1;
    private int transitionCountAtGapStart;
    private int gapStartMovieLogicalFrame;
    private List<DynamicArtTransfer.Descriptor> openingLedger = List.of();
    /**
     * Lifecycle state observed the instant the source segment closed, before
     * the lifecycle entered the gap.
     *
     * <p>Closing a comparison segment flushes work the segment submitted whose
     * completion falls after the segment's last compared row, and the close
     * itself appends those edges to the gap ledger. A snapshot taken after the
     * close starts counting past them, so each one is dropped from the gap's
     * compared slice and its transfer is already gone from the opening ledger
     * that resolves an edge's submission origin. A gap begins where its source
     * ended, so the opening state is taken there.
     */
    private DynamicArtGapDiagnosticsSnapshot sourceClosedSnapshot;

    public TraceRunDynamicArtGapJournal(
            TraceRunManifest manifest,
            DynamicArtDiagnosticsProvider diagnostics) {
        this.manifest = Objects.requireNonNull(manifest, "manifest");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public void sourceClosed(int segmentIndex) {
        if (sourceSegmentIndex >= 0) {
            if (sourceSegmentIndex != segmentIndex) {
                throw new IllegalStateException(
                        "dynamic-art close changed source segment");
            }
            return;
        }
        sourceSegmentIndex = segmentIndex;
        sourceClosedOrdinal = ++structuralOrdinal;
        sourceClosedSnapshot = diagnostics.gapOpeningSnapshot();
    }

    public void gapOpened(int segmentIndex) {
        if (sourceSegmentIndex != segmentIndex || sourceClosedOrdinal <= 0) {
            throw new IllegalStateException(
                    "dynamic-art gap opened before its source close");
        }
        if (gapOpenedOrdinal > sourceClosedOrdinal) {
            return;
        }
        DynamicArtGapDiagnosticsSnapshot state = sourceClosedSnapshot != null
                ? sourceClosedSnapshot : diagnostics.gapSnapshot();
        gapOpenedOrdinal = ++structuralOrdinal;
        gapStartMovieLogicalFrame = state.movieLogicalFrame();
        transitionCountAtGapStart = state.transitions().size();
        openingLedger = state.ledger().stream()
                .map(TraceRunDynamicArtGapJournal::toTraceDescriptor)
                .toList();
    }

    /**
     * Closes the gap and compares it, at the instant the destination segment
     * opens.
     *
     * @param destinationRowsConsumed the destination's own recorded rows the
     *                                engine has already run when admission is
     *                                polled; see
     *                                {@link #lastMovieRowRun(int, int)}
     */
    public FrameComparison destinationOpened(
            int destinationSegmentIndex, int destinationRowsConsumed) {
        if (sourceSegmentIndex < 0
                || destinationSegmentIndex != sourceSegmentIndex + 1) {
            throw new IllegalStateException(
                    "dynamic-art destination opened without its adjacent gap");
        }
        DynamicArtGapDiagnosticsSnapshot admission = diagnostics.gapSnapshot();
        List<DynamicArtGapTransition> transitions = admission.transitions();
        List<DynamicArtGapTransition> added =
                transitions.size() >= transitionCountAtGapStart
                        ? transitions.subList(transitionCountAtGapStart,
                                transitions.size())
                        : List.of();
        added = rowsCountedBackFromAdmission(
                added, admission.movieLogicalFrame(),
                admission.unannouncedRows());

        TraceRunManifest.Segment source = manifest.segments().get(sourceSegmentIndex);
        TraceRunManifest.Segment destination =
                manifest.segments().get(destinationSegmentIndex);
        FrameComparison comparison = TraceRunDynamicArtGapComparator.compare(
                gapStartMovieLogicalFrame, manifest, sourceSegmentIndex,
                new TraceRunDynamicArtGapComparator.RuntimeGap(
                        source.dir(), destination.dir(),
                        new TraceRunDynamicArtGapComparator.StructuralOrder(
                                sourceClosedOrdinal, gapOpenedOrdinal,
                                ++structuralOrdinal),
                        openingLedger, added));
        sourceSegmentIndex = -1;
        sourceClosedOrdinal = 0;
        gapOpenedOrdinal = 0;
        openingLedger = List.of();
        sourceClosedSnapshot = null;
        return comparison;
    }

    /** Closes and compares the final segment-to-movie-end gap. */
    public FrameComparison terminalTailClosed(int movieFrameCount) {
        if (sourceSegmentIndex < 0
                || sourceSegmentIndex != manifest.segments().size() - 1
                || gapOpenedOrdinal <= sourceClosedOrdinal) {
            throw new IllegalStateException(
                    "dynamic-art terminal tail closed without its final source gap");
        }
        List<DynamicArtGapTransition> transitions =
                diagnostics.gapSnapshot().transitions();
        List<DynamicArtGapTransition> added =
                transitions.size() >= transitionCountAtGapStart
                        ? transitions.subList(transitionCountAtGapStart,
                                transitions.size())
                        : List.of();
        TraceRunManifest.Segment source =
                manifest.segments().get(sourceSegmentIndex);
        FrameComparison comparison =
                TraceRunDynamicArtGapComparator.compareTerminalTail(
                        gapStartMovieLogicalFrame, manifest, sourceSegmentIndex,
                        movieFrameCount,
                        new TraceRunDynamicArtGapComparator.RuntimeTerminalTail(
                                source.dir(), sourceClosedOrdinal,
                                gapOpenedOrdinal, ++structuralOrdinal,
                                openingLedger, added));
        sourceSegmentIndex = -1;
        sourceClosedOrdinal = 0;
        gapOpenedOrdinal = 0;
        openingLedger = List.of();
        sourceClosedSnapshot = null;
        return comparison;
    }

    /**
     * Recovers each gap edge's movie row by subtracting the rows that passed
     * unannounced after it.
     *
     * <p>An edge is stamped with the row the shared cursor was announcing when
     * it was emitted. That stamp is right whenever rows are being announced,
     * and stale for the whole of a span that is driven with none — a harness
     * that pre-seeks the cursor for an uncompared interior re-announces one
     * frozen row until the destination is admitted, so every edge in the
     * transition gap after it carries that same row. The engine counts those
     * unannounced rows as it passes them (see
     * {@code DynamicArtLifecycleService#unannouncedRows}), so an edge followed
     * by {@code n} of them before admission sits {@code n} rows before its own
     * stamp.
     *
     * <p>This is an identity where the raw stamp was already right, and the
     * only available answer where it was not. The count is taken at admission
     * because that is where the gap ends; running it forward from the gap's
     * start instead would place every edge by the difference between the
     * engine's gap length and the recorded one, which no ROM rule predicts.
     *
     * <p>Both inputs are engine-produced — the counts are the engine's own and
     * the stamp is the shared cursor's own position. No recorded edge row is
     * read and no engine state is written; the re-stamped edges are comparison
     * copies.
     *
     * <p>Recovery only applies to a stamp that is actually stale. Staleness is
     * visible without any recorded row: a frozen cursor re-announces the row
     * the admission itself reports, so a stale edge carries
     * {@code admissionMovieLogicalFrame} exactly, and an edge carrying any
     * other row was stamped while the cursor was still announcing — it is
     * already the row it happened on and has nothing to count back. Not every
     * gap freezes the cursor; where the harness keeps announcing across the
     * span, the unannounced counter still advances for the frames the engine
     * spends off-cursor, and counting those back off a live stamp would move a
     * correct row backwards.
     *
     * <p>{@code gapEdgeIndex} is renumbered against the recovered rows for the
     * same reason the recorder numbers it per frame: two edges sharing a row
     * are 0 and 1, and a later row restarts at 0.
     */
    public static List<DynamicArtGapTransition> rowsCountedBackFromAdmission(
            List<DynamicArtGapTransition> added,
            int admissionMovieLogicalFrame,
            int admissionUnannouncedRows) {
        Map<Integer, Integer> nextIndexByRow = new HashMap<>();
        List<DynamicArtGapTransition> rowed = new ArrayList<>(added.size());
        for (DynamicArtGapTransition transition : added) {
            DynamicArtGapTransition.GapEdge edge = transition.edge();
            int row = edge.movieLogicalFrame() == admissionMovieLogicalFrame
                    ? rowCountedBackFromAdmission(edge.movieLogicalFrame(),
                            admissionUnannouncedRows,
                            edge.unannouncedRowsAtEmit())
                    : edge.movieLogicalFrame();
            int index = nextIndexByRow.merge(row, 1, Integer::sum) - 1;
            rowed.add(new DynamicArtGapTransition(
                    new DynamicArtGapTransition.GapEdge(
                            edge.edgeOrdinal(), edge.transferId(), edge.phase(),
                            edge.owner(), edge.mappingFrame(), row, index,
                            edge.unannouncedRowsAtEmit(), edge.requests()),
                    transition.beforeOutstandingTransferIds(),
                    transition.afterOutstandingTransferIds()));
        }
        return List.copyOf(rowed);
    }

    /**
     * Row a stamp actually belongs to, given the unannounced-row counts at the
     * stamp and at the gap's end. A stamp taken in the same row as the end has
     * nothing to subtract.
     *
     * <p>Every stamp counts back from itself. A stale stamp — one carrying the
     * admission's own row, which is what a frozen cursor re-announces — is
     * already that row, because the admission stands on the destination's first
     * row whether or not that row has run yet. This used to count back from a
     * separate {@code lastMovieRowRun}, derived as
     * {@code bk2FrameOffset + rowsConsumed - 1}: a comparator fact standing in
     * for a clock fact, equal to the admission row only while every return
     * consumed the destination's row zero. Where a return consumes none it was
     * one low, and every recovered row in that gap came out one row early.
     */
    public static int rowCountedBackFromAdmission(
            int stampedRow,
            int admissionUnannouncedRows,
            int unannouncedRowsAtStamp) {
        return Math.toIntExact(Math.subtractExact(stampedRow,
                Math.max(0, admissionUnannouncedRows - unannouncedRowsAtStamp)));
    }

    private static DynamicArtTransfer.Descriptor toTraceDescriptor(
            DynamicArtGapDiagnosticsSnapshot.Descriptor descriptor) {
        return new DynamicArtTransfer.Descriptor(
                descriptor.transferId(), descriptor.owner(),
                descriptor.mappingFrame(), descriptor.submissionOrigin(),
                descriptor.requests().stream()
                        .map(request -> new DynamicArtTransfer.Request(
                                request.romSourceAddress(),
                                request.sourceTileIndex(),
                                request.ramSourceAddress(),
                                request.vramDestination(),
                                request.byteLength()))
                        .toList(), null);
    }
}
