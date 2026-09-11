package com.openggf.trace.replay.runs;

import com.openggf.trace.BootstrapDivergence;
import com.openggf.trace.FieldComparison;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.TraceHudModel;
import com.openggf.trace.live.MismatchEntry;

import java.util.List;
import java.util.Objects;

/** Run-scoped HUD diagnostics for comparisons outside a segment owner. */
public final class TraceRunExternalDiagnostics implements TraceHudModel {
    private static final int RING_CAPACITY = 5;

    private final Runnable firstErrorCallback;
    private final com.openggf.trace.live.MismatchRingBuffer mismatches =
            new com.openggf.trace.live.MismatchRingBuffer(RING_CAPACITY);
    private int errorCount;
    private int warningCount;
    private boolean firstErrorPublished;

    public TraceRunExternalDiagnostics(Runnable firstErrorCallback) {
        this.firstErrorCallback = firstErrorCallback;
    }

    public void accept(FrameComparison comparison) {
        absorb(comparison, true);
    }

    /** Records a comparison whose owning comparator publishes first-error UX. */
    public void acceptDisplayed(FrameComparison comparison) {
        absorb(comparison, false);
    }

    /** Seeds exact bootstrap totals produced outside the frame observer. */
    public void acceptBootstrap(List<BootstrapDivergence> bootstrap) {
        for (BootstrapDivergence divergence : Objects.requireNonNull(
                bootstrap, "bootstrap")) {
            Severity severity = divergence.severity()
                    == BootstrapDivergence.Severity.ERROR
                    ? Severity.ERROR : Severity.WARNING;
            if (severity == Severity.ERROR) {
                errorCount++;
                firstErrorPublished = true;
            } else {
                warningCount++;
            }
            mismatches.push(new MismatchEntry(
                    0, divergence.field(), divergence.expected(),
                    divergence.actual(), divergence.context(), severity, 1));
        }
    }

    private void absorb(FrameComparison comparison, boolean publishFirstError) {
        FrameComparison result = Objects.requireNonNull(
                comparison, "comparison");
        boolean rowHasError = false;
        for (FieldComparison field : result.fields().values()) {
            if (field.severity() == Severity.ERROR) {
                errorCount++;
                rowHasError = true;
            } else if (field.severity() == Severity.WARNING) {
                warningCount++;
            }
            if (field.isDivergent()) {
                mismatches.push(new MismatchEntry(
                        result.frame(), field.fieldName(), field.expected(),
                        field.actual(), String.valueOf(field.delta()),
                        field.severity(), 1));
            }
        }
        if (rowHasError && !firstErrorPublished) {
            firstErrorPublished = true;
            if (publishFirstError && firstErrorCallback != null) {
                firstErrorCallback.run();
            }
        }
    }

    @Override public int errorCount() { return errorCount; }
    @Override public int warningCount() { return warningCount; }
    @Override public int laggedFrames() { return 0; }
    @Override public int recentActionMask() { return 0; }
    @Override public int recentInputMask() { return 0; }
    @Override public boolean recentStartPressed() { return false; }
    @Override public List<MismatchEntry> recentMismatches() {
        return mismatches.recent();
    }
    @Override public boolean hasRecordingDesync() { return errorCount > 0; }
    @Override public boolean isComplete() { return false; }
}
