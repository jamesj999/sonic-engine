package com.openggf.trace;

import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.timing.HardwareTimingJob;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.trace.timing.HardwareCompletionEdge;

import java.util.List;
import java.util.Objects;

/** Comparison-only projection for native queue heartbeats sampled between loop-tail calls. */
public record LoadQueueComparisonProjection(
        List<TraceEvent.LoadQueueState> expected,
        List<QueueDiagnosticSnapshot> actual) {

    private static final String DIRECT_KIND = "s3k_kos_direct";

    public LoadQueueComparisonProjection {
        expected = List.copyOf(Objects.requireNonNull(expected, "expected"));
        actual = List.copyOf(Objects.requireNonNull(actual, "actual"));
    }

    public static LoadQueueComparisonProjection project(
            TraceData trace,
            int frame,
            List<TraceEvent.LoadQueueState> expected,
            List<QueueDiagnosticSnapshot> actual,
            HardwareTimingSnapshot timing) {
        LoadQueueComparisonProjection unchanged =
                new LoadQueueComparisonProjection(expected, actual);
        if (timing == null) {
            return unchanged;
        }
        QueueDiagnosticSnapshot direct = unchanged.actual().stream()
                .filter(state -> DIRECT_KIND.equals(state.kind().wireName()))
                .findFirst()
                .orElse(null);
        if (direct == null || !direct.busy()) {
            return unchanged;
        }
        if (direct.prepared()) {
            return excuseBoundaryGranularInProgressBit(trace, frame, unchanged, direct, timing);
        }
        HardwareCompletionEdge edge =
                trace.unobservedDirectChildForComparisonFrame(frame);
        if (edge == null) {
            return unchanged;
        }
        HardwareTimingJob.Snapshot job = matchingDirectJob(timing, direct, edge);
        if (job == null) {
            return unchanged;
        }
        return new LoadQueueComparisonProjection(
                unchanged.expected().stream()
                        .filter(state -> !DIRECT_KIND.equals(state.kind()))
                        .toList(),
                unchanged.actual().stream()
                        .filter(state -> !DIRECT_KIND.equals(state.kind().wireName()))
                        .toList());
    }

    /**
     * Excuses the one polarity the engine's model of the direct queue's
     * "prepared" flag can produce spuriously.
     *
     * <p>The recorder projects this field from bit 15 of {@code Kos_decomp_queue_count}
     * (see {@code LoadQueueStateProjector}), which is the ROM's
     * decompression-<em>in-progress</em> sign bit: {@code Process_Kos_Queue_Main}
     * (skdisasm {@code sonic3k.asm:2845-2846}) sets it with
     * {@code ori.w #$8000} on entry to the decompression loop, and
     * {@code Process_Kos_Queue_EndReached} ({@code sonic3k.asm:2938-2941})
     * clears it with {@code andi.w #$7FFF}. A recorded sample reads set only when that
     * frame's V-int landed <em>inside</em> the loop — a sub-frame 68000 cycle
     * position that frame-granularity state cannot reconstruct.
     *
     * <p>The engine's flag is a boundary-granular over-approximation of the same
     * bit: {@code S3kKosDecompressionQueue.afterTimingService} arms the serviced
     * head at each {@code PRE_MAIN_LOOP} boundary and disarms it only on the
     * recorded completion edge. It can therefore read true one or more boundaries
     * before the ROM's V-int first landed in the loop, but it can never read false
     * while the ROM is mid-loop. Accordingly only actual=true/expected=false is
     * excused, and only while the head's own recorded completion still lies
     * strictly in the future; actual=false/expected=true stays a hard error,
     * because that would mean a completion landed early — a real timing defect.
     */
    private static LoadQueueComparisonProjection excuseBoundaryGranularInProgressBit(
            TraceData trace,
            int frame,
            LoadQueueComparisonProjection unchanged,
            QueueDiagnosticSnapshot direct,
            HardwareTimingSnapshot timing) {
        TraceEvent.LoadQueueState expectedDirect = unchanged.expected().stream()
                .filter(state -> DIRECT_KIND.equals(state.kind()))
                .findFirst()
                .orElse(null);
        if (expectedDirect == null || !expectedDirect.busy() || expectedDirect.prepared()) {
            return unchanged;
        }
        HardwareTimingJob.Snapshot head = unclaimedDirectHead(timing, direct);
        if (head == null || !hasFutureRecordedCompletion(trace, frame, head.handle())) {
            return unchanged;
        }
        return new LoadQueueComparisonProjection(
                unchanged.expected().stream()
                        .map(state -> state == expectedDirect
                                ? withPrepared(state)
                                : state)
                        .toList(),
                unchanged.actual());
    }

    private static TraceEvent.LoadQueueState withPrepared(TraceEvent.LoadQueueState state) {
        return new TraceEvent.LoadQueueState(
                state.frame(), state.kind(), state.busy(), true,
                state.activeSource(), state.activeDestination(),
                state.totalWork(), state.remainingWork(),
                state.queuedFingerprints(), state.serviceObservations());
    }

    /** The single live direct job the diagnostics head can name, or null if ambiguous. */
    private static HardwareTimingJob.Snapshot unclaimedDirectHead(
            HardwareTimingSnapshot timing, QueueDiagnosticSnapshot direct) {
        List<HardwareTimingJob.Snapshot> matches = timing.jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .filter(job -> !job.claimed() && !job.physicallyRetired())
                .filter(job -> job.romSourceAddress() == direct.activeSource())
                .filter(job -> job.destinationAddress() == direct.activeDestination())
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean hasFutureRecordedCompletion(
            TraceData trace, int frame, HardwareWorkHandle handle) {
        return trace.hardwareTimingSchedule().edges().stream()
                .anyMatch(edge -> edge.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE
                        && edge.rawFrame() > frame
                        && edge.ordinal() == handle.ordinal()
                        && Objects.equals(
                                edge.submissionFingerprint(),
                                handle.submissionFingerprint()));
    }

    private static HardwareTimingJob.Snapshot matchingDirectJob(
            HardwareTimingSnapshot timing,
            QueueDiagnosticSnapshot direct,
            HardwareCompletionEdge edge) {
        HardwareWorkHandle expectedHandle = new HardwareWorkHandle(
                edge.kind(), edge.ordinal(), edge.submissionFingerprint());
        List<HardwareTimingJob.Snapshot> matches = timing.jobs().stream()
                .filter(job -> job.kind() == HardwareWorkKind.KOS_DECOMPRESSION_QUEUE)
                .filter(job -> !job.claimed() && !job.physicallyRetired())
                .filter(job -> job.romSourceAddress() == direct.activeSource())
                .filter(job -> job.destinationAddress() == direct.activeDestination())
                .filter(job -> job.handle().equals(expectedHandle))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }
}
