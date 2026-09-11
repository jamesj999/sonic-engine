package com.openggf.trace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Rebinds recorder submission edges that spilled past a frame boundary back to
 * the object pass that produced them.
 *
 * <p>One ROM {@code RunObjects} pass submits every changed player DPLC in one
 * burst, but the recorder timestamps each submission with the wall-clock frame
 * it actually crossed: when the pass overruns the frame (a lag row), the later
 * objects' submissions carry the lag row as {@code logical_frame} and surface
 * on the following observation as {@code publication_frame} (stage-1 fixture
 * row 181: {@code ss-sonic} submitted logical 181, while {@code ss-tails} /
 * {@code ss-tails-tails} from the same pass carry logical 182 — the lag row —
 * published 183). Which objects spill depends on sub-frame 68K execution time
 * inside the pass; the ROM has no semantic notion of the split, and the engine
 * publishes each pass atomically. Like the recorder's power-on-epoch delivery
 * identities (see {@link DynamicArtIdEpoch}), the spilled edges' frame stamps
 * are observation artifacts, not ROM state, so they are compared per pass
 * rather than per publication row.
 *
 * <p>Everything else stays absolute: per-pass edge cardinality, in-pass
 * ordering (edge ordinals), owner, phase, mapping frame, and every request
 * field. A genuinely missing, duplicated, or wrongly-attributed submission
 * still fails. Completion edges are never moved: their retirement rows are
 * V-blank facts both sides model.
 */
public final class DynamicArtSpillNormalization {

    private DynamicArtSpillNormalization() {
    }

    /**
     * Returns per-row expected transfer states with spilled submission edges
     * moved to their pass row: the latest non-lag row at or before the edge's
     * recorded {@code logical_frame}. The moved edge's {@code logical_frame}
     * and {@code publication_frame} are rewritten to that pass row; unmoved
     * edges are byte-identical to the recording.
     *
     * @param states   recorded per-row transfer states, keyed by row
     * @param rowCount total observation rows
     * @param lagRow   whether a row is a recorded hardware lag row
     */
    public static Map<Integer, TraceEvent.DynamicArtTransferState> rebindSubmissionSpills(
            Map<Integer, TraceEvent.DynamicArtTransferState> states,
            int rowCount,
            IntPredicate lagRow) {
        return rebindSubmissionSpills(states, rowCount, rowCount, lagRow, r -> false);
    }

    /**
     * As {@link #rebindSubmissionSpills(Map, int, IntPredicate)}, but only
     * rows before {@code rebindEndExclusive} are rebound. Once the recorded
     * {@code run_objects_end} pass bindings pace the replay (from
     * {@code SpecialStage_Started}), each pass already executes against its
     * bound observation and spilled rows need no normalization.
     */
    public static Map<Integer, TraceEvent.DynamicArtTransferState> rebindSubmissionSpills(
            Map<Integer, TraceEvent.DynamicArtTransferState> states,
            int rowCount,
            int rebindEndExclusive,
            IntPredicate lagRow) {
        return rebindSubmissionSpills(states, rowCount, rebindEndExclusive, lagRow, r -> false);
    }

    /**
     * As above, additionally normalizing the wall-clock crossing stamps of
     * spilled submissions on rows in {@code cursorPrecededRow}: observations
     * whose bound ROM pass finished before the observation's own V-int
     * (recorded {@code completion_cursor_frame} earlier than the bound row).
     * There the ROM queued during the preceding lag frame while the engine,
     * paced to the bound observation, queues on the row itself — the stamp
     * difference is the same sub-frame artifact, so it is compared as
     * published-on-this-row. Rows whose passes the engine paces with matching
     * crossing times (everything else) keep their stamps absolute.
     */
    public static Map<Integer, TraceEvent.DynamicArtTransferState> rebindSubmissionSpills(
            Map<Integer, TraceEvent.DynamicArtTransferState> states,
            int rowCount,
            int rebindEndExclusive,
            IntPredicate lagRow,
            IntPredicate cursorPrecededRow) {
        return rebindSubmissionSpills(
                states, rowCount, rebindEndExclusive, lagRow, cursorPrecededRow, List.of());
    }

    /** One recorded ROM object pass: its completion cursor and bound observation row. */
    public record PassBinding(int completionCursorFrame, int boundRow) {
    }

    /**
     * Full form: in the paced region every submission edge is bound to the
     * observation of the earliest pass (in sequence order) whose recorded
     * completion cursor is at or after the edge's wall-clock crossing frame —
     * pass identity, not publication row. The recorder publishes each edge at
     * the first observation after its crossing, which can precede the pass's
     * bound row (stage-1 fixture: pass 8's ss-sonic submission crosses 439 and
     * publishes there while the pass is bound to 441) or trail it. The engine
     * publishes each pass atomically on its bound observation.
     */
    public static Map<Integer, TraceEvent.DynamicArtTransferState> rebindSubmissionSpills(
            Map<Integer, TraceEvent.DynamicArtTransferState> states,
            int rowCount,
            int rebindEndExclusive,
            IntPredicate lagRow,
            IntPredicate cursorPrecededRow,
            List<PassBinding> passBindings) {
        Objects.requireNonNull(states, "states");
        Objects.requireNonNull(lagRow, "lagRow");

        Map<Integer, List<DynamicArtTransfer.SegmentEdge>> edgesByRow = new LinkedHashMap<>();
        java.util.Set<Integer> modifiedRows = new java.util.HashSet<>();
        // A moved submission is outstanding from its pass row up to (not
        // including) the row the recorder originally published it on.
        Map<Integer, List<Long>> earlyOutstandingByRow = new LinkedHashMap<>();
        // Symmetrically, an edge moved FORWARD to its pass's bound row is not
        // outstanding until that row.
        Map<Integer, List<Long>> notYetOutstandingByRow = new LinkedHashMap<>();
        for (Map.Entry<Integer, TraceEvent.DynamicArtTransferState> entry : states.entrySet()) {
            int row = entry.getKey();
            for (DynamicArtTransfer.SegmentEdge edge : entry.getValue().edges()) {
                int target = row;
                if (row >= rebindEndExclusive && "submitted".equals(edge.phase())) {
                    Integer boundRow = boundRowFor(edge.logicalFrame(), passBindings);
                    if (boundRow != null && boundRow != row) {
                        // Edge published away from its pass's bound observation.
                        target = boundRow;
                        for (int r = row; r < boundRow; r++) {
                            notYetOutstandingByRow
                                    .computeIfAbsent(r, x -> new ArrayList<>())
                                    .add(edge.transferId());
                        }
                        for (int r = boundRow; r < row; r++) {
                            earlyOutstandingByRow
                                    .computeIfAbsent(r, x -> new ArrayList<>())
                                    .add(edge.transferId());
                        }
                        edge = withPassRow(edge, boundRow);
                        modifiedRows.add(boundRow);
                        modifiedRows.add(row);
                    } else if (cursorPrecededRow.test(row)
                            && edge.logicalFrame() != edge.publicationFrame()) {
                        // On its bound row, but the crossing stamp records the
                        // ROM queuing during the preceding overrun frame.
                        edge = withPassRow(edge, row);
                        modifiedRows.add(row);
                    }
                } else if (row < rebindEndExclusive
                        && "submitted".equals(edge.phase())
                        && edge.logicalFrame() != edge.publicationFrame()) {
                    target = passRowFor(edge.logicalFrame(), lagRow);
                    for (int r = target; r < row; r++) {
                        earlyOutstandingByRow
                                .computeIfAbsent(r, x -> new ArrayList<>())
                                .add(edge.transferId());
                    }
                    edge = withPassRow(edge, target);
                    modifiedRows.add(target);
                    modifiedRows.add(row);
                }
                edgesByRow.computeIfAbsent(target, r -> new ArrayList<>()).add(edge);
            }
        }

        Map<Integer, TraceEvent.DynamicArtTransferState> normalized = new LinkedHashMap<>();
        for (int row = 0; row < rowCount; row++) {
            TraceEvent.DynamicArtTransferState original = states.get(row);
            List<DynamicArtTransfer.SegmentEdge> edges = edgesByRow.get(row);
            if (original == null && edges == null) {
                continue;
            }
            List<DynamicArtTransfer.SegmentEdge> merged =
                    edges == null ? List.of() : new ArrayList<>(edges);
            if (edges != null) {
                // Recorder ordinals are allocated in submission order; sorting
                // restores the in-pass order for edges merged from spill rows.
                ArrayList<DynamicArtTransfer.SegmentEdge> sorted =
                        (ArrayList<DynamicArtTransfer.SegmentEdge>) merged;
                sorted.sort(Comparator.comparingLong(
                        DynamicArtTransfer.SegmentEdge::edgeOrdinal));
                if (modifiedRows.contains(row)) {
                    // logical_edge_index is the recorder's per-row position;
                    // rows whose membership changed get positions recomputed.
                    for (int i = 0; i < sorted.size(); i++) {
                        DynamicArtTransfer.SegmentEdge e = sorted.get(i);
                        if (e.logicalEdgeIndex() != i) {
                            sorted.set(i, withLogicalEdgeIndex(e, i));
                        }
                    }
                }
            }
            // PRECONDITION: the outstanding-id merge below assumes the target
            // row has a RECORDED heartbeat whose outstanding list already
            // carries every id in flight at that row. The S2 special-stage
            // schema satisfies this by construction (one
            // dynamic_art_transfer_state per stored row); a sparse-heartbeat
            // trace would reach the empty-list branch here and present as a
            // silently empty expected outstanding list at a moved edge's
            // target row — an expectation defect, not an engine divergence.
            List<Long> outstanding = original != null
                    ? new ArrayList<>(original.outstandingTransferIds())
                    : new ArrayList<>();
            for (long early : earlyOutstandingByRow.getOrDefault(row, List.of())) {
                if (!outstanding.contains(early)) {
                    outstanding.add(early);
                }
            }
            outstanding.removeAll(notYetOutstandingByRow.getOrDefault(row, List.of()));
            normalized.put(row, new TraceEvent.DynamicArtTransferState(
                    row, merged, outstanding));
        }
        return normalized;
    }

    private static Integer boundRowFor(int logicalFrame, List<PassBinding> bindings) {
        for (PassBinding binding : bindings) {
            if (binding.completionCursorFrame() >= logicalFrame) {
                return binding.boundRow();
            }
        }
        return null;
    }

    private static int passRowFor(int logicalFrame, IntPredicate lagRow) {
        int row = logicalFrame;
        while (row > 0 && lagRow.test(row)) {
            row--;
        }
        return row;
    }

    private static DynamicArtTransfer.SegmentEdge withLogicalEdgeIndex(
            DynamicArtTransfer.SegmentEdge edge, int index) {
        return new DynamicArtTransfer.SegmentEdge(
                edge.edgeOrdinal(),
                edge.transferId(),
                edge.phase(),
                edge.owner(),
                edge.submissionOrigin(),
                edge.mappingFrame(),
                edge.logicalFrame(),
                index,
                edge.publicationFrame(),
                edge.terminalForwarded(),
                edge.romCallbackPc(),
                edge.requests());
    }

    /**
     * Convenience form for a recorded S2 special-stage segment: derives the
     * pass-pacing start, the pass bindings and the cursor-preceded rows from
     * the trace's own {@code run_objects_end} stream and applies
     * {@link #rebindSubmissionSpills(Map, int, int, IntPredicate, IntPredicate,
     * List)}.
     *
     * <p>This is the single owner of that derivation. Both S2 special-stage
     * comparison lanes use it -- the standalone {@code
     * TestS2SpecialStageTraceReplay} and the complete-run chain's
     * special-stage interior -- so a segment compares identically however it
     * was reached.
     *
     * @return an empty map when the trace advertises no per-frame DPLC state
     */
    public static Map<Integer, TraceEvent.DynamicArtTransferState> forSpecialStage(
            SpecialStageTraceData trace) {
        Objects.requireNonNull(trace, "trace");
        if (!trace.metadata().hasPerFrameDynamicArtTransferState()) {
            return Map.of();
        }
        Map<Integer, TraceEvent.DynamicArtTransferState> states = new LinkedHashMap<>();
        for (int frame = 0; frame < trace.frameCount(); frame++) {
            TraceEvent.DynamicArtTransferState state =
                    trace.dynamicArtTransferStateForFrame(frame);
            if (state != null) {
                states.put(frame, state);
            }
        }
        List<TraceEvent.StateSnapshot> passes = trace.runObjectsEndSnapshots().stream()
                .sorted(Comparator.comparingInt(
                        snapshot -> passField(snapshot, "pass_sequence")))
                .toList();
        // Before SpecialStage_Started rises the ROM copies the pad words ahead
        // of WaitForVint and the recurring object pass is not yet the pacing
        // authority (docs/s2disasm/s2.asm:6674-6688), so replay is frame-paced
        // there and every row is eligible for spill rebinding. Absent such a
        // pass the whole segment is pre-start.
        int pacingStart = passes.stream()
                .filter(snapshot -> passField(snapshot, "started_at_input_sample") != 0)
                .mapToInt(TraceEvent.StateSnapshot::frame)
                .findFirst()
                .orElse(trace.frameCount());
        java.util.Set<Integer> cursorPrecededRows = new java.util.HashSet<>();
        List<PassBinding> bindings = new ArrayList<>();
        for (TraceEvent.StateSnapshot snapshot : trace.runObjectsEndSnapshots()) {
            Object cursor = snapshot.fields().get("completion_cursor_frame");
            if (cursor == null) {
                continue;
            }
            int cursorFrame = Integer.parseInt(String.valueOf(cursor));
            int boundRow = snapshot.frame();
            bindings.add(new PassBinding(cursorFrame, boundRow));
            if (cursorFrame < boundRow) {
                cursorPrecededRows.add(boundRow);
            }
        }
        return rebindSubmissionSpills(
                states, trace.frameCount(), pacingStart,
                frame -> trace.getFrame(frame).lag(),
                cursorPrecededRows::contains,
                bindings);
    }

    private static int passField(TraceEvent.StateSnapshot snapshot, String name) {
        Object raw = snapshot.fields().get(name);
        if (raw == null) {
            throw new IllegalStateException(
                    "run_objects_end is missing " + name + " at frame "
                            + snapshot.frame());
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(raw);
        return text.startsWith("0x") || text.startsWith("0X")
                ? Integer.parseInt(text.substring(2), 16)
                : Integer.parseInt(text);
    }

    private static DynamicArtTransfer.SegmentEdge withPassRow(
            DynamicArtTransfer.SegmentEdge edge, int passRow) {
        return new DynamicArtTransfer.SegmentEdge(
                edge.edgeOrdinal(),
                edge.transferId(),
                edge.phase(),
                edge.owner(),
                edge.submissionOrigin(),
                edge.mappingFrame(),
                passRow,
                edge.logicalEdgeIndex(),
                passRow,
                edge.terminalForwarded(),
                edge.romCallbackPc(),
                edge.requests());
    }
}
