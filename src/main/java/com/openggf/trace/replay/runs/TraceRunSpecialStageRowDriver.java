package com.openggf.trace.replay.runs;

import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.TraceData;
import com.openggf.trace.live.MismatchEntry;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.List;

/**
 * Atomic represented-row owner shared by live and headless run adapters.
 */
public final class TraceRunSpecialStageRowDriver {
    public record AdmittedRow(
            int row,
            TraceRunSpecialStageRows.SpecialStageRowAdmission policy) {
        public AdmittedRow {
            if (row < 0) {
                throw new IllegalArgumentException("row must be nonnegative");
            }
            policy = Objects.requireNonNull(policy, "policy");
        }
    }

    private final TraceRunSpecialStageRows rows;
    private final TraceRunReplayWalker.DynamicArtSegmentComparison comparison;
    private int cursor;
    private DynamicArtDiagnosticsSnapshot admissionBaseline;
    private int laggedFrames;
    private int errorCount;
    private int warningCount;
    private final ArrayDeque<MismatchEntry> recentMismatches = new ArrayDeque<>();

    public TraceRunSpecialStageRowDriver(
            TraceRunSpecialStageRows rows, TraceData trace) {
        this.rows = Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(trace, "trace");
        // Recorder spill normalization is a property of the segment's own
        // pass stream, so the rows view owns it; an empty map compares the raw
        // recorded rows. See TraceRunSpecialStageRows#normalizedDynamicArtRows.
        comparison = new TraceRunReplayWalker.DynamicArtSegmentComparison(
                trace, rows.rowCount(), rows.normalizedDynamicArtRows());
    }

    public static void requireFreshAdmission(int rowsConsumed) {
        if (rowsConsumed != 0) {
            throw new IllegalArgumentException(
                    "special-local destination must be admitted before row zero production");
        }
    }

    public int cursor() {
        return cursor;
    }

    public int rowCount() {
        return rows.rowCount();
    }

    public boolean isComplete() {
        return cursor == rows.rowCount() && admissionBaseline == null;
    }

    public boolean hasPendingRow() {
        return admissionBaseline != null;
    }

    public TraceRunSpecialStageRows.SpecialStageRowAdmission currentPolicy() {
        requireCurrentRow();
        return rows.admission(cursor);
    }

    public AdmittedRow admitCurrentRow(DynamicArtDiagnosticsSnapshot before) {
        if (admissionBaseline != null) {
            throw new IllegalStateException(
                    "special-stage row " + cursor + " is already admitted");
        }
        requireCurrentRow();
        admissionBaseline = Objects.requireNonNull(before, "before");
        return new AdmittedRow(cursor, rows.admission(cursor));
    }

    public Optional<FrameComparison> publishAdmittedRow(
            DynamicArtDiagnosticsSnapshot after) {
        DynamicArtDiagnosticsSnapshot before = admissionBaseline;
        if (before == null) {
            throw new IllegalStateException(
                    "special-stage publication has no admitted row");
        }
        Objects.requireNonNull(after, "after");
        if (comparison.isAdvertised()) {
            if (after.deliverySerial() <= before.deliverySerial()
                    || !after.published()
                    || after.segmentGeneration() != before.segmentGeneration()
                    || after.frame() != cursor) {
                throw new IllegalStateException(
                        "advertised special-stage row " + cursor
                                + " was not published atomically for generation "
                                + before.segmentGeneration());
            }
        }
        FrameComparison result = comparison.compareRow(cursor, after);
        if (!rows.admission(cursor).executeGameplay()) {
            laggedFrames++;
        }
        if (result != null) {
            for (FieldComparison field : result.fields().values()) {
                if (field.severity() == Severity.ERROR) {
                    errorCount++;
                } else if (field.severity() == Severity.WARNING) {
                    warningCount++;
                }
                if (field.isDivergent()) {
                    if (recentMismatches.size() == 5) {
                        recentMismatches.removeFirst();
                    }
                    recentMismatches.addLast(new MismatchEntry(
                            cursor, field.fieldName(), field.expected(),
                            field.actual(), String.valueOf(field.delta()),
                            field.severity(), 1));
                }
            }
        }
        admissionBaseline = null;
        cursor++;
        return Optional.ofNullable(result);
    }

    public void verifyComplete() {
        if (admissionBaseline != null) {
            throw new IllegalStateException(
                    "special-stage row " + cursor + " is admitted but unpublished");
        }
        comparison.verifyComplete();
        if (cursor != rows.rowCount()) {
            throw new IllegalStateException(
                    "special-stage segment expected " + rows.rowCount()
                            + " rows but committed " + cursor);
        }
    }

    public List<FrameComparison> comparisons() {
        return comparison.comparisons();
    }

    public int laggedFrames() {
        return laggedFrames;
    }

    public int errorCount() {
        return errorCount;
    }

    public int warningCount() {
        return warningCount;
    }

    public List<MismatchEntry> recentMismatches() {
        return List.copyOf(recentMismatches);
    }

    private void requireCurrentRow() {
        if (cursor >= rows.rowCount()) {
            throw new IllegalStateException("special-stage segment is complete");
        }
    }
}
