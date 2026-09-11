package com.openggf.trace.replay.runs;

import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceMetadata;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.timing.HardwareTimingSchedule;

import java.nio.file.Path;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, payload-independent summary produced while scanning one trace-run
 * segment. The summary is comparison-only and owns no eager trace rows or
 * auxiliary-event payload.
 */
public record TraceRunSegmentDescriptor(
        TraceRunManifest.Segment segment,
        Path segmentDirectory,
        TraceMetadata metadata,
        int rowCount,
        TraceFrame openingFrame,
        List<Integer> rawFrames,
        BitSet laggedRows,
        HardwareTimingSchedule hardwareTimingSchedule,
        List<DynamicArtTransfer.Descriptor> terminalDynamicArtLedger,
        TraceRunManifest.Transition entryBoundary,
        TraceRunManifest.Transition exitBoundary,
        int levelLoopRowCount,
        TraceRunReplayWalker.SegmentExecutionPolicy executionPolicy) {

    public TraceRunSegmentDescriptor {
        segment = Objects.requireNonNull(segment, "segment");
        Path directory = Objects.requireNonNull(
                segmentDirectory, "segmentDirectory");
        segmentDirectory = directory.getFileSystem().getPath(directory.toString());
        metadata = Objects.requireNonNull(metadata, "metadata");
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must be non-negative");
        }
        rawFrames = List.copyOf(Objects.requireNonNull(rawFrames, "rawFrames"));
        if (rawFrames.size() != rowCount) {
            throw new IllegalArgumentException(
                    "rawFrames size must equal rowCount");
        }
        if (levelLoopRowCount < 0 || levelLoopRowCount > rowCount) {
            throw new IllegalArgumentException(
                    "levelLoopRowCount must be within the segment row range");
        }
        boolean ordinaryRowsParsed = !"special_stage".equals(segment.kind())
                && rowCount > 0;
        if ((openingFrame != null) != ordinaryRowsParsed) {
            throw new IllegalArgumentException(
                    "openingFrame must be present exactly when ordinary rows are parsed");
        }
        laggedRows = (BitSet) Objects.requireNonNull(
                laggedRows, "laggedRows").clone();
        hardwareTimingSchedule = Objects.requireNonNull(
                hardwareTimingSchedule, "hardwareTimingSchedule");
        terminalDynamicArtLedger = List.copyOf(Objects.requireNonNull(
                terminalDynamicArtLedger, "terminalDynamicArtLedger"));
        executionPolicy = Objects.requireNonNull(
                executionPolicy, "executionPolicy");
    }

    @Override
    public BitSet laggedRows() {
        return (BitSet) laggedRows.clone();
    }
}
