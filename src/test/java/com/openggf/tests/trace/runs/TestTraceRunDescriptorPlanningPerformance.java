package com.openggf.tests.trace.runs;

import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.replay.runs.TraceRunSpecialStageRows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in retained-heap evidence for compact run-segment planning.
 *
 * <p>The real 67-segment run exceeds the default one-GiB Surefire heap when
 * planned eagerly. Run this class through {@code -Ptrace-replay}, whose
 * project-owned three-GiB fork setting is also used by the complete-run
 * replay suite.</p>
 */
@EnabledIfSystemProperty(
        named = "openggf.trace.segmentDescriptorBenchmark", matches = "true")
class TestTraceRunDescriptorPlanningPerformance {
    private static final String RUN_ID =
            "s3k-knuckles-complete-superemeralds";
    private static final int EXPECTED_SEGMENTS = 67;
    private static final long MAX_DESCRIPTOR_RETAINED_BYTES = 16_777_216;
    private static final long FIXED_WARMED_EAGER_BASELINE_BYTES =
            1_087_200_800L;

    @Test
    void compactDescriptorsRetainLessHeapWithoutOwningEagerPayloads()
            throws Exception {
        Path projectRoot = Path.of(System.getProperty("project.basedir", "."))
                .toAbsolutePath().normalize();
        TraceEntry entry = TraceCatalog.scan(
                        projectRoot.resolve("src/test/resources/traces"))
                .stream()
                .filter(candidate -> candidate.isRun()
                        && RUN_ID.equals(candidate.runManifest().runId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Trace catalog did not discover run " + RUN_ID));
        TraceRunManifest manifest = entry.runManifest();

        assertEquals(EXPECTED_SEGMENTS, manifest.segments().size(),
                "benchmark must keep using the measured 67-segment fixture");
        assertDescriptorComponentShape();
        warmAndReleasePlannerState(manifest, entry.runDir());

        EagerMeasurement eager = measureEager(manifest, entry.runDir());
        DescriptorMeasurement descriptor =
                measureDescriptors(manifest, entry.runDir());

        assertEquals(eager.segmentCount(), descriptor.segmentCount());
        assertEquals(eager.rowCount(), descriptor.rowCount());
        assertEquals(descriptor.rowCount(), descriptor.rawFrameCount());
        assertTrue(descriptor.retainedBytes() < FIXED_WARMED_EAGER_BASELINE_BYTES,
                () -> "descriptor retained heap " + descriptor.retainedBytes()
                        + " must be below fixed warmed eager retained heap "
                        + FIXED_WARMED_EAGER_BASELINE_BYTES);
        assertTrue(descriptor.retainedBytes() <= MAX_DESCRIPTOR_RETAINED_BYTES,
                () -> "descriptor retained heap " + descriptor.retainedBytes()
                        + " must not exceed "
                        + MAX_DESCRIPTOR_RETAINED_BYTES);

        long reductionBytes = FIXED_WARMED_EAGER_BASELINE_BYTES
                - descriptor.retainedBytes();
        long reductionPercent = Math.round(reductionBytes * 100.0
                / FIXED_WARMED_EAGER_BASELINE_BYTES);
        System.out.printf(
                "TRACE_SEGMENT_DESCRIPTOR_BENCH segments=%d "
                        + "eager_retained_bytes=%d descriptor_retained_bytes=%d "
                        + "reduction_bytes=%d reduction_percent=%d "
                        + "descriptor_raw_frames=%d%n",
                descriptor.segmentCount(), FIXED_WARMED_EAGER_BASELINE_BYTES,
                descriptor.retainedBytes(), reductionBytes,
                reductionPercent, descriptor.rawFrameCount());
    }

    private static void warmAndReleasePlannerState(
            TraceRunManifest manifest, Path runDir) throws IOException {
        warmEagerPlanner(manifest, runDir);
        forcedGcHeapBytes();
        warmDescriptorPlanner(manifest, runDir);
        forcedGcHeapBytes();
    }

    private static void warmEagerPlanner(
            TraceRunManifest manifest, Path runDir) throws IOException {
        List<TraceRunReplayWalker.SegmentPlan> plans =
                TraceRunReplayWalker.plan(manifest, runDir);
        assertEquals(EXPECTED_SEGMENTS, plans.size());
        Reference.reachabilityFence(plans);
    }

    private static void warmDescriptorPlanner(
            TraceRunManifest manifest, Path runDir) throws IOException {
        List<TraceRunSegmentDescriptor> descriptors =
                TraceRunReplayWalker.planDescriptors(manifest, runDir);
        assertEquals(EXPECTED_SEGMENTS, descriptors.size());
        Reference.reachabilityFence(descriptors);
    }

    private static EagerMeasurement measureEager(
            TraceRunManifest manifest, Path runDir) throws IOException {
        long baselineBytes = forcedGcHeapBytes();
        List<TraceRunReplayWalker.SegmentPlan> plans =
                TraceRunReplayWalker.plan(manifest, runDir);
        long retainedBytes = forcedGcHeapBytes() - baselineBytes;
        int rowCount = plans.stream().mapToInt(plan -> {
            TraceRunSpecialStageRows specialRows = plan.specialStageRows();
            return specialRows != null
                    ? specialRows.rowCount() : plan.trace().frameCount();
        }).sum();
        EagerMeasurement result = new EagerMeasurement(
                plans.size(), rowCount, retainedBytes);
        Reference.reachabilityFence(plans);
        return result;
    }

    private static DescriptorMeasurement measureDescriptors(
            TraceRunManifest manifest, Path runDir) throws IOException {
        long baselineBytes = forcedGcHeapBytes();
        List<TraceRunSegmentDescriptor> descriptors =
                TraceRunReplayWalker.planDescriptors(manifest, runDir);
        long retainedBytes = forcedGcHeapBytes() - baselineBytes;
        int rowCount = descriptors.stream()
                .mapToInt(TraceRunSegmentDescriptor::rowCount)
                .sum();
        int rawFrameCount = descriptors.stream()
                .mapToInt(descriptor -> descriptor.rawFrames().size())
                .sum();
        DescriptorMeasurement result = new DescriptorMeasurement(
                descriptors.size(), rowCount, rawFrameCount, retainedBytes);
        Reference.reachabilityFence(descriptors);
        return result;
    }

    private static void assertDescriptorComponentShape() {
        List<String> approvedComponents = List.of(
                "segment:com.openggf.trace.TraceRunManifest$Segment",
                "segmentDirectory:java.nio.file.Path",
                "metadata:com.openggf.trace.TraceMetadata",
                "rowCount:int",
                "openingFrame:com.openggf.trace.TraceFrame",
                "rawFrames:java.util.List<java.lang.Integer>",
                "laggedRows:java.util.BitSet",
                "hardwareTimingSchedule:com.openggf.trace.timing.HardwareTimingSchedule",
                "terminalDynamicArtLedger:java.util.List<com.openggf.trace.DynamicArtTransfer$Descriptor>",
                "entryBoundary:com.openggf.trace.TraceRunManifest$Transition",
                "exitBoundary:com.openggf.trace.TraceRunManifest$Transition",
                "levelLoopRowCount:int",
                "executionPolicy:com.openggf.trace.replay.runs.TraceRunReplayWalker$SegmentExecutionPolicy");
        List<String> actualComponents = java.util.Arrays.stream(
                        TraceRunSegmentDescriptor.class.getRecordComponents())
                .map(component -> component.getName() + ":"
                        + component.getGenericType().getTypeName())
                .toList();

        assertEquals(approvedComponents, actualComponents,
                "descriptor API must remain within the approved payload-independent shape");
    }

    private static long forcedGcHeapBytes() {
        System.gc();
        System.gc();
        return ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage().getUsed();
    }

    private record EagerMeasurement(
            int segmentCount, int rowCount, long retainedBytes) {
    }

    private record DescriptorMeasurement(
            int segmentCount,
            int rowCount,
            int rawFrameCount,
            long retainedBytes) {
    }
}
